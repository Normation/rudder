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

import com.normation.errors.IOResult
import com.normation.rudder.db.Doobie
import doobie.Fragments
import doobie.Meta
import doobie.Read
import doobie.Write
import doobie.implicits.*
import org.joda.time.DateTime
import zio.interop.catz.*

trait CampaignEventRepository {
  def get(campaignEventId:                 CampaignEventId): IOResult[Option[CampaignEvent]]
  def saveCampaignEvent(c:                 CampaignEvent):   IOResult[CampaignEvent]
  def numberOfEventsByCampaign(campaignId: CampaignId):      IOResult[Int]
  def deleteEvent(
      id:           Option[CampaignEventId] = None,
      states:       List[CampaignEventState] = Nil,
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
      states:       List[CampaignEventState] = Nil,
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

  import com.normation.rudder.campaigns.CampaignSerializer.*
  import com.normation.rudder.db.Doobie.DateTimeMeta
  import com.normation.rudder.db.json.implicits.*
  import doobie.*

  implicit val stateWrite: Meta[CampaignEventState] = new Meta(pgDecoderGet, pgEncoderPut)

  implicit val eventWrite: Write[CampaignEvent] = {
    Write[(String, String, String, CampaignEventState, DateTime, DateTime, String)].contramap {
      case event =>
        (event.id.value, event.campaignId.value, event.name, event.state, event.start, event.end, event.campaignType.value)
    }
  }

  implicit val eventRead: Read[CampaignEvent] = {
    Read[(String, String, String, CampaignEventState, DateTime, DateTime, String)].map {
      (d: (String, String, String, CampaignEventState, DateTime, DateTime, String)) =>
        CampaignEvent(
          CampaignEventId(d._1),
          CampaignId(d._2),
          d._3,
          d._4,
          d._5,
          d._6,
          campaignSerializer.campaignType(d._7)
        )
    }
  }

  def get(id: CampaignEventId): IOResult[Option[CampaignEvent]] = {
    val q =
      sql"select eventId, campaignId, name, state, startDate, endDate, campaignType from  CampaignEvents where eventId = ${id.value}"
    transactIOResult(s"error when getting campaign event with id ${id.value}")(xa => q.query[CampaignEvent].option.transact(xa))
  }

  def getWithCriteria(
      states:       List[CampaignEventState] = Nil,
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

    import cats.syntax.list.*
    val campaignIdQuery   = campaignId.map(c => fr"campaignId = ${c.value}")
    val campaignTypeQuery = campaignType.toNel.map(c => Fragments.in(fr"campaignType", c))
    val stateQuery        = states.toNel.map(s => Fragments.in(fr"state->>'value'", s.map(_.value)))
    val afterQuery        = afterDate.map(d => fr"endDate >= ${new java.sql.Timestamp(d.getMillis)}")
    val beforeQuery       = beforeDate.map(d => fr"startDate <= ${new java.sql.Timestamp(d.getMillis)}")
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

    val q =
      sql"select eventId, campaignId, name, state, startDate, endDate, campaignType from  CampaignEvents " ++ where ++ orderBy ++ limitQuery ++ offsetQuery

    transactIOResult(s"error when getting campaign events")(xa => q.query[CampaignEvent].to[List].transact(xa))
  }

  def numberOfEventsByCampaign(campaignId: CampaignId): IOResult[Int] = {
    val q = sql"select count(*) from  CampaignEvents where campaignId = ${campaignId.value}"

    transactIOResult(s"error when getting campaign events")(xa => q.query[Int].unique.transact(xa))
  }

  def saveCampaignEvent(c: CampaignEvent): IOResult[CampaignEvent] = {
    import doobie.*
    val query = {
      sql"""insert into CampaignEvents  (eventId, campaignId, name, state, startDate, endDate, campaignType) values (${c})
           |  ON CONFLICT (eventId) DO UPDATE
           |  SET state = ${c.state}, name = ${c.name}, startDate = ${c.start}, endDate = ${c.end} ; """.stripMargin
    }

    transactIOResult(s"error when inserting event with id ${c.id.value}")(xa => query.update.run.transact(xa)).map(_ => c)
  }

  def deleteEvent(
      id:           Option[CampaignEventId] = None,
      states:       List[CampaignEventState] = Nil,
      campaignType: Option[CampaignType] = None,
      campaignId:   Option[CampaignId] = None,
      afterDate:    Option[DateTime] = None,
      beforeDate:   Option[DateTime] = None
  ): IOResult[Unit] = {

    import cats.syntax.list.*
    val eventIdQuery      = id.map(c => fr"eventId = ${c.value}")
    val campaignIdQuery   = campaignId.map(c => fr"campaignId = ${c.value}")
    val campaignTypeQuery = campaignType.map(c => fr"campaignType = ${c.value}")
    val stateQuery        = states.toNel.map(s => Fragments.in(fr"state->>'value'", s.map(_.value)))
    val afterQuery        = afterDate.map(d => fr"endDate >= ${new java.sql.Timestamp(d.getMillis)}")
    val beforeQuery       = beforeDate.map(d => fr"startDate <= ${new java.sql.Timestamp(d.getMillis)}")
    val where             = Fragments.whereAndOpt(eventIdQuery, campaignIdQuery, campaignTypeQuery, stateQuery, afterQuery, beforeQuery)
    val query             = sql"""delete from campaignEvents """ ++ where
    transactIOResult(s"error when deleting campaign event")(xa => query.update.run.transact(xa).unit)
  }
}
