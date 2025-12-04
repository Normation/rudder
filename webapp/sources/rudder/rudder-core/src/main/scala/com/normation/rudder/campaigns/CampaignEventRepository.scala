/*
 *************************************************************************************
 * Copyright 2022 Normation SAS
 *************************************************************************************
 *
 * This file is part of Rudder.
 *
 * Rudder is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * In accordance with the terms of section 7 (7. Additional Terms.) of
 * the GNU General Public License version 3, the copyright holders add
 * the following Additional permissions:
 * Notwithstanding to the terms of section 5 (5. Conveying Modified Source
 * Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
 * Public License version 3, when you create a Related Module, this
 * Related Module is not considered as a part of the work and may be
 * distributed under the license agreement of your choice.
 * A "Related Module" means a set of sources files including their
 * documentation that, without modification of the Source Code, enables
 * supplementary functions or services in addition to those offered by
 * the Software.
 *
 * Rudder is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

 *
 *************************************************************************************
 */

package com.normation.rudder.campaigns

import _root_.cats.implicits.*
import com.normation.errors.*
import com.normation.rudder.campaigns.CampaignEventStateType.ScheduledType
import com.normation.rudder.db.Doobie
import com.normation.rudder.db.json.implicits.*
import com.normation.utils.DateFormaterService
import com.normation.utils.DateFormaterService.toJavaInstant
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.scalaland.chimney.*
import io.scalaland.chimney.syntax.*

import java.time.Instant
import org.joda.time.DateTime
import zio.*
import zio.interop.catz.*
import zio.json.*
import zio.json.internal.Write
import zio.syntax.*

trait CampaignEventRepository {

  def get(campaignEventId: CampaignEventId): IOResult[Option[CampaignEvent]]

  /*
   * Save an event for a campaign. Given the event type, additional details
   * can be needed.
   */
  def saveCampaignEvent(event: CampaignEvent): IOResult[Unit]

  def numberOfEventsByCampaign(campaignId: CampaignId): IOResult[Int]

  /*
   * A function that delete events based on the query parameters. I
   * Implementation should check that the semantic for all parameter being empty
   * is nothing is deleted.
   */
  def deleteEvent(
      id:           Option[CampaignEventId] = None,
      states:       List[CampaignEventStateType] = Nil,
      campaignType: Option[CampaignType] = None,
      campaignId:   Option[CampaignId] = None,
      afterDate:    Option[DateTime] = None,
      beforeDate:   Option[DateTime] = None
  ): IOResult[Unit]

  /*
   * Semantic is:
   * - if Nil or None, clause is ignored
   * - if a value is provided, then it is use to filter things accordingly
   */
  def getWithCriteria(
      states:       List[CampaignEventStateType] = Nil,
      campaignType: List[CampaignType] = Nil,
      campaignId:   Option[CampaignId] = None,
      limit:        Option[Int] = None,
      offset:       Option[Int] = None,
      afterDate:    Option[DateTime] = None,
      beforeDate:   Option[DateTime] = None,
      order:        Option[CampaignSortOrder] = None,
      asc:          Option[CampaignSortDirection] = None
  ): IOResult[List[CampaignEvent]]
}

class CampaignEventRepositoryImpl(doobie: Doobie, campaignSerializer: CampaignSerializer) extends CampaignEventRepository {

  import com.normation.rudder.db.Doobie.DateTimeMeta
  import doobie.*

  implicit val campaignEventStateMeta: Meta[CampaignEventStateType] =
    pgEnumStringOpt("campaignEventState", CampaignEventStateType.withNameInsensitiveOption, _.entryName)

  implicit val campaignIdMeta: Meta[CampaignId] = Meta[String].tiemap(CampaignId.parse)(_.serialize)

  /*
   * We need some specific case classes to map from SQL to business objects
   */
  // the simple jsonb data - no sealed hierarchy else zio-json absolutely wants to add one more field
  private case class ReasonData(reason: String) derives JsonCodec                                               {
    def toSkipped = CampaignEventState.Skipped(reason)
    def toDeleted = CampaignEventState.Deleted(reason)
  }
  private case class FailureData(cause: String, message: String) derives JsonCodec                              {
    def toFailure = CampaignEventState.Failure(cause, message)
  }
  private case class PreHooksData(hooks: Seq[HookResult]) derives JsonCodec                                     {
    def toPreHooks = CampaignEventState.PreHooks(HookResults(hooks))
  }
  private case class PostHooksData(nextState: CampaignEventStateType, hooks: Seq[HookResult]) derives JsonCodec {
    def toPostHooks = CampaignEventState.PostHooks(nextState, HookResults(hooks))
  }

  private type DATA = ReasonData | FailureData | PostHooksData | PreHooksData

  // some boilerplate for the union encoder, because not supported automatically.
  implicit private val encoderDATA: JsonEncoder[DATA] = (a: DATA, indent: Option[RuntimeFlags], out: Write) => {
    a match {
      case x: ReasonData    => JsonEncoder[ReasonData].unsafeEncode(x, indent, out)
      case x: FailureData   => JsonEncoder[FailureData].unsafeEncode(x, indent, out)
      case x: PreHooksData  => JsonEncoder[PreHooksData].unsafeEncode(x, indent, out)
      case x: PostHooksData => JsonEncoder[PostHooksData].unsafeEncode(x, indent, out)
    }
  }

  // some boilerplate for the union decoder, because not supported automatically.
  // Note: it test sequentially each decoder, which is inefficient, so perhaps we
  // should keep the type as a member after all.
  implicit private val decoderDATA: JsonDecoder[DATA] = {
    JsonDecoder[ReasonData].widen[DATA] <>
    JsonDecoder[PostHooksData].widen[DATA] <>
    JsonDecoder[PreHooksData].widen[DATA] <>
    JsonDecoder[FailureData].widen[DATA]
  }

  implicit private val putDATA: Put[DATA] = pgEncoderPut
  implicit private val getDATA: Get[DATA] = pgDecoderGet

  /*
   * Recompose a state object from the state type and additional data
   */
  private def getState(s: CampaignEventStateType, data: Option[DATA]): Either[String, CampaignEventState] = {
    import com.normation.rudder.campaigns.CampaignEventStateType.*

    data match {
      case None                   => Right(CampaignEventState.getDefault(s))
      case Some(x: ReasonData)    =>
        s match {
          case SkippedType => Right(x.toSkipped)
          case DeletedType => Right(x.toDeleted)
          case r           => Left(s"Error: data of type 'reason' is incompatible with state '${r.entryName}'")
        }
      case Some(x: FailureData)   => Right(x.toFailure)
      case Some(x: PreHooksData)  => Right(x.toPreHooks)
      case Some(x: PostHooksData) => Right(x.toPostHooks)
    }
  }

  // Direct mapping of the CampaignEvents table - for insert/update
  private case class CampaignEventInsert(
      id:           CampaignEventId,
      campaignId:   CampaignId,
      name:         String,
      state:        CampaignEventStateType,
      start:        DateTime,
      end:          DateTime,
      campaignType: CampaignType
  )

  private object CampaignEventInsert {
    implicit val transformCampaignEvent: Transformer[CampaignEvent, CampaignEventInsert] = {
      Transformer
        .define[CampaignEvent, CampaignEventInsert]
        .withFieldComputed(_.state, _.state.value)
        .buildTransformer
    }

    def from(e: CampaignEvent): CampaignEventInsert = e.transformInto
  }

  // Mapping of the CampaignEvents JOIN History.data table - for select
  private case class CampaignEventSelect(
      id:           CampaignEventId,
      campaignId:   CampaignId,
      name:         String,
      state:        CampaignEventStateType,
      start:        DateTime,
      end:          DateTime,
      campaignType: CampaignType,
      data:         Option[DATA]
  ) {
    def toCampaignEvent: PureResult[CampaignEvent] = {
      getState(state, data)
        .map(s => CampaignEvent(id, campaignId, name, s, start, end, campaignType))
        .left
        .map(err => Inconsistency(s"Cannot decode campaign event state '${id.value}': ${err}"))
    }
  }

  // Direct mapping of the CampaignEventStateHistory table - for insert/update
  private case class CampaignEventHistoryInsert(
      id:    CampaignEventId,
      state: CampaignEventStateType,
      start: Instant,
      end:   Option[Instant],
      data:  Option[DATA]
  ) {
    def toCampaignEvent: PureResult[CampaignEventHistory] = {
      getState(state, data)
        .map(s => CampaignEventHistory(id, s, start, end))
        .left
        .map(err => Inconsistency(s"Cannot decode campaign event history state '${id.value}': ${err}"))
    }
  }

  private object CampaignEventHistoryInsert {
    // more boilerplate
    implicit val transformCampaignEventSql: Transformer[CampaignEvent, CampaignEventHistoryInsert] = {
      import com.normation.rudder.campaigns.CampaignEventState.*
      Transformer
        .define[CampaignEvent, CampaignEventHistoryInsert]
        .withFieldComputed(_.start, x => x.start.toJavaInstant)
        .withFieldComputed(_.end, x => Some(x.end.toJavaInstant))
        .withFieldComputed(_.state, _.state.value)
        .withFieldComputed(
          _.data,
          _.state match {
            case Scheduled | Running | Finished => None
            case Skipped(reason)                => Some(ReasonData(reason))
            case Deleted(reason)                => Some(ReasonData(reason))
            case PreHooks(hooks)                => Some(PreHooksData(hooks.results))
            case PostHooks(state, hooks)        => Some(PostHooksData(state, hooks.results))
            case Failure(cause, message)        => Some(FailureData(cause, message))
          }
        )
        .buildTransformer
    }

    def from(e: CampaignEvent): CampaignEventHistoryInsert = e.transformInto
  }

  override def get(id: CampaignEventId): IOResult[Option[CampaignEvent]] = {
    val q = {
      sql"""SELECT e.eventId, e.campaignId, e.name, e.state, e.startDate, e.endDate, e.campaignType, h.data
            FROM CampaignEvents AS e LEFT JOIN CampaignEventsStateHistory AS h
            ON e.eventId = h.eventId AND e.state = h.state
            WHERE e.eventId = ${id.value}"""
    }

    for {
      opt   <- transactIOResult(s"error when getting campaign event with id ${id.value}")(xa =>
                 q.query[CampaignEventSelect].option.transact(xa)
               )
      event <- opt match {
                 case None    => None.succeed
                 case Some(x) => x.toCampaignEvent.map(Some.apply).toIO
               }
    } yield event
  }

  override def getWithCriteria(
      states:       List[CampaignEventStateType] = Nil,
      campaignType: List[CampaignType] = Nil,
      campaignId:   Option[CampaignId] = None,
      limit:        Option[Int] = None,
      offset:       Option[Int] = None,
      afterDate:    Option[DateTime] = None,
      beforeDate:   Option[DateTime] = None,
      order:        Option[CampaignSortOrder],
      asc:          Option[CampaignSortDirection]
  ): IOResult[List[CampaignEvent]] = {

    import com.normation.rudder.campaigns.CampaignSortDirection.*
    import com.normation.rudder.campaigns.CampaignSortOrder.*

    import _root_.cats.syntax.list.*

    val campaignIdQuery   = campaignId.map(c => fr"campaignId = ${c.value}")
    val campaignTypeQuery = campaignType.toNel.map(c => Fragments.in(fr"campaignType", c))
    val stateQuery        = states.toNel.map(s => Fragments.in(fr"e.state", s))
    val afterQuery        = afterDate.map(d => fr"e.endDate >= ${new java.sql.Timestamp(d.getMillis)}")
    val beforeQuery       = beforeDate.map(d => fr"e.startDate <= ${new java.sql.Timestamp(d.getMillis)}")
    val where             = Fragments.whereAndOpt(campaignIdQuery, campaignTypeQuery, stateQuery, afterQuery, beforeQuery)

    val limitQuery  = limit.map(i => fr" limit $i").getOrElse(fr"")
    val offsetQuery = offset.map(i => fr" offset $i").getOrElse(fr"")

    val orderBy = (order, asc.getOrElse(Asc)) match {
      case (Some(StartDate), Asc)  => fr" order by startDate asc"
      case (Some(StartDate), Desc) => fr" order by startDate desc"
      case (Some(EndDate), Asc)    => fr" order by endDate asc"
      case (Some(EndDate), Desc)   => fr" order by endDate desc"
      // default mapping, when no sort specified
      case _                       => fr" order by startDate desc"
    }

    val q = {
      sql"""SELECT e.eventId, e.campaignId, e.name, e.state, e.startDate, e.endDate, e.campaignType, h.data
          FROM CampaignEvents AS e LEFT JOIN CampaignEventsStateHistory AS h
          ON e.eventId = h.eventId AND e.state = h.state
         """ ++ where ++ orderBy ++ limitQuery ++ offsetQuery
    }

    for {
      list <- transactIOResult(s"error when getting campaign events")(xa => q.query[CampaignEventSelect].to[List].transact(xa))
      // perhaps it's not a foreach but more a collect ignore errors
      res  <- ZIO.foreach(list)(_.toCampaignEvent.toIO)
    } yield res
  }

  override def numberOfEventsByCampaign(campaignId: CampaignId): IOResult[Int] = {
    val q = sql"select count(*) from  CampaignEvents where campaignId = ${campaignId.value}"

    transactIOResult(s"error when getting campaign events")(xa => q.query[Int].unique.transact(xa))
  }

  /*
   * Save a campaign state, ensuring internal consistency.
   * In particular, if the event is "scheduled", we delete other scheduled even for that campaign, since we want to
   * have only one event at a time for a given campaign.
   */
  override def saveCampaignEvent(c: CampaignEvent): IOResult[Unit] = {
    import doobie.*
    // on insert, we want to update current state (CampaignEvents table) and history table.

    val query1 = {
      sql"""INSERT INTO CampaignEvents (eventId, campaignId, name, state, startDate, endDate, campaignType)
           |VALUES (${CampaignEventInsert.from(c)})
           |ON CONFLICT (eventId) DO UPDATE
           |SET state = ${c.state.value}, name = ${c.name}, startDate = ${c.start}, endDate = ${c.end};""".stripMargin
    }

    // on conflict with an existing history event, we only update endtime
    val query2 = {
      val sqlData = CampaignEventHistoryInsert.from(c)
      sql"""INSERT INTO CampaignEventsStateHistory (eventId, state, startDate, endDate, data)
           |VALUES (${sqlData})
           |ON CONFLICT (eventId, state) DO UPDATE
           |SET endDate = ${sqlData.end}, data = ${sqlData.data};""".stripMargin
    }

    transactIOResult(s"error when inserting event with id ${c.id.value}") { xa =>
      (for {
        _ <- if (c.state.value == ScheduledType) {
               internalDelete(None, ScheduledType :: Nil, None, Some(c.campaignId), None, None)
             } else ().pure[ConnectionIO]
        _ <- query1.update.run
        _ <- query2.update.run
      } yield ()).transact(xa)
    }.unit
  }

  private def internalDelete(
      id:           Option[CampaignEventId],
      states:       List[CampaignEventStateType],
      campaignType: Option[CampaignType],
      campaignId:   Option[CampaignId],
      afterDate:    Option[DateTime],
      beforeDate:   Option[DateTime]
  ): ConnectionIO[RuntimeFlags] = {

    import _root_.cats.syntax.list.*
    val eventIdQuery      = id.map(c => fr"eventId = ${c.value}")
    val campaignIdQuery   = campaignId.map(c => fr"campaignId = ${c.value}")
    val campaignTypeQuery = campaignType.map(c => fr"campaignType = ${c.value}")
    val stateQuery        = states.toNel.map(s => Fragments.in(fr"state", s))
    val afterQuery        = afterDate.map(d => fr"endDate >= ${new java.sql.Timestamp(d.getMillis)}")
    val beforeQuery       = beforeDate.map(d => fr"startDate <= ${new java.sql.Timestamp(d.getMillis)}")
    val where             = Fragments.whereAndOpt(eventIdQuery, campaignIdQuery, campaignTypeQuery, stateQuery, afterQuery, beforeQuery)
    val query             = sql"""delete from campaignEvents """ ++ where

    query.update.run
  }

  override def deleteEvent(
      id:           Option[CampaignEventId] = None,
      states:       List[CampaignEventStateType] = Nil,
      campaignType: Option[CampaignType] = None,
      campaignId:   Option[CampaignId] = None,
      afterDate:    Option[DateTime] = None,
      beforeDate:   Option[DateTime] = None
  ): IOResult[Unit] = {
    if (List[Iterable[Any]](id, states, campaignType, campaignId, afterDate, beforeDate).exists(_.nonEmpty)) {
      transactIOResult(s"error when deleting campaign event")(xa =>
        internalDelete(id, states, campaignType, campaignId, afterDate, beforeDate).transact(xa).unit
      )
    } else ZIO.unit
  }
}
