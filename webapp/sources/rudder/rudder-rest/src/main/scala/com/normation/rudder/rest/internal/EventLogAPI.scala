/*
 *************************************************************************************
 * Copyright 2019 Normation SAS
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

package com.normation.rudder.rest.internal

import cats.data.NonEmptyList
import com.normation.errors.*
import com.normation.eventlog.*
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.domain.logger.EventLogsLoggerPure
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.rest.ApiModuleProvider
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.EventLogApi
import com.normation.rudder.rest.RudderJsonRequest.*
import com.normation.rudder.rest.RudderJsonResponse
import com.normation.rudder.rest.data.RestEventLog
import com.normation.rudder.rest.data.RestEventLogDetails
import com.normation.rudder.rest.data.RestEventLogError
import com.normation.rudder.rest.data.RestEventLogFilter
import com.normation.rudder.rest.data.RestEventLogFilter.Direction.Asc
import com.normation.rudder.rest.data.RestEventLogFilter.Direction.Desc
import com.normation.rudder.rest.data.RestEventLogRollback
import com.normation.rudder.rest.data.RestEventLogRollback.Action.After
import com.normation.rudder.rest.data.RestEventLogRollback.Action.Before
import com.normation.rudder.rest.data.RestEventLogSuccess
import com.normation.rudder.rest.data.SimpleDiffJson
import com.normation.rudder.rest.implicits.*
import com.normation.rudder.rest.lift.DefaultParams
import com.normation.rudder.rest.lift.LiftApiModule
import com.normation.rudder.rest.lift.LiftApiModule0
import com.normation.rudder.rest.lift.LiftApiModuleProvider
import com.normation.rudder.rest.lift.LiftApiModuleString
import com.normation.rudder.services.user.PersonIdentService
import com.normation.rudder.web.services.*
import com.normation.zio.UnsafeRun
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.fragments
import io.scalaland.chimney.syntax.*
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import zio.ZIO
import zio.syntax.*

class EventLogAPI(
    service:            EventLogService,
    eventLogDetail:     EventLogDetailsGenerator,
    translateEventType: EventLogType => String
) extends LiftApiModuleProvider[EventLogApi] {
  import EventLogService.*

  implicit val translateEventLogType: EventLogType => String   = translateEventType
  implicit val eventLogDetailsGen:    EventLogDetailsGenerator = eventLogDetail

  override def schemas: ApiModuleProvider[EventLogApi] = EventLogApi

  override def getLiftEndpoints(): List[LiftApiModule] = {
    EventLogApi.endpoints.map {
      case EventLogApi.GetEventLogs       => GetEventLogs
      case EventLogApi.GetEventLogDetails => GetEventLogDetails
      case EventLogApi.RollbackEventLog   => RollbackEventLog
    }
  }

  object GetEventLogs extends LiftApiModule0 {
    val schema: EventLogApi.GetEventLogs.type = EventLogApi.GetEventLogs

    def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val prettify: Boolean = params.prettify

      (for {
        restFilter <- req.fromJson[RestEventLogFilter].toIO
        res        <- service.getEventLogSlice(restFilter)
      } yield {
        (restFilter.draw, res)
      }).chainError("Error when fetching event logs")
        .either
        .runNow
        .fold(
          err => RudderJsonResponse.generic.internalError(RestEventLogError(err.fullMsg)),
          {
            case (draw, EventLogSlice(events, totalRecord, totalFilter)) =>
              RudderJsonResponse.LiftJsonResponse(
                RestEventLogSuccess(draw, totalRecord, totalFilter, events.map(_.transformInto[RestEventLog])),
                params.prettify,
                200
              )
          }
        )
    }
  }

  object GetEventLogDetails extends LiftApiModuleString {
    val schema: EventLogApi.GetEventLogDetails.type = EventLogApi.GetEventLogDetails

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      (
        for {
          evId    <- id.toLongOption.notOptional("event log ID is not a long integer : " + id)
          details <- service.getEventLogDetails(evId)
        } yield {
          details
        }
      ).chainError(s"Error when getting event log with id '${id}' details")
        .toLiftResponseOne(
          params,
          schema,
          None
        )
    }
  }
  object RollbackEventLog   extends LiftApiModuleString {
    val schema: EventLogApi.RollbackEventLog.type = EventLogApi.RollbackEventLog

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      (for {
        evId    <- id.toLongOption.notOptional("event log ID is not a long integer : " + id)
        action  <- req.params.get("action") match {
                     case Some(action :: Nil) =>
                       RestEventLogRollback.Action
                         .withNameInsensitiveOption(action)
                         .notOptional(s"Unknown rollback's action : ${action}")
                     case Some(actions)       =>
                       Inconsistency(
                         s"Only one action is excepted, ${actions.size} found in request : ${actions.mkString(",")}"
                       ).fail
                     case None                => Inconsistency("Empty action").fail
                   }
        details <- service.rollback(evId, action)
      } yield {
        RestEventLogRollback(action, id)
      }).chainError(s"Error when performing eventlog's rollback with id '${id}'")
        .toLiftResponseOne(
          params,
          schema,
          None
        )
    }
  }

}

class EventLogService(
    repo:                    EventLogRepository,
    eventLogDetailGenerator: EventLogDetailsGenerator,
    personIdentService:      PersonIdentService
) {
  import EventLogService.*

  def getEventLogSlice(
      restFilter: RestEventLogFilter
  ): IOResult[EventLogSlice] = {
    import restFilter.*

    val dateCriteria =
      fragments.andOpt(startDate.map(start => fr"creationDate >= ${start}"), endDate.map(end => fr"creationDate <= ${end}"))

    val (criteria, from) = (search.flatMap(_.toFragment), dateCriteria) match {
      case (None, res)              => (res, None)
      case (Some(value), None)      =>
        (
          Some(value),
          Some(
            fr"from ( select eventtype, id, modificationid, principal, creationdate, causeid, severity, reason, data, UNNEST(xpath('string(//entry)',data))::text as filter from eventlog) as temp1"
          )
        )
      case (Some(value), Some(res)) =>
        (
          Some(fragments.and(value, res)),
          Some(
            fr"from ( select eventtype, id, modificationid, principal, creationdate, causeid, severity, reason, data, UNNEST(xpath('string(//entry)',data))::text as filter from eventlog) as temp1"
          )
        )
    }
    val orderBy          = NonEmptyList
      .fromList(order.map(_.toFragment).toList)
      .map(fragments.comma(_))
      .map(fr"order by" ++ _)
      .getOrElse(Fragment.empty)
    val offset           = fr"offset ${start}"
    val queryCriteria    = Some(
      // as full criteria the "where" needs a condition
      // we also need the orderBy here because it should come before the offset
      criteria.getOrElse(fr"1=1") ++ orderBy ++ offset
    )

    (for {
      events      <-
        repo
          .getEventLogByCriteria(queryCriteria, Some(length), Nil, from)
          .chainError(s"Error when trying fetch eventlogs from database for page ${(start / length) + 1}")
      totalRecord <- repo.getEventLogCount(None)
      totalFilter <- repo.getEventLogCount(criteria, from)
    } yield {
      EventLogSlice(events, totalRecord, totalFilter)
    }).catchSystemErrors
  }

  def getEventLogDetails(id: Long)(implicit qc: QueryContext): IOResult[RestEventLogDetails] = {

    (for {
      event             <- repo.getEventLogById(id)
      crId              <- ZIO.foreach(event.id)(repo.getEventLogWithChangeRequest(_).notOptional("").map(_._2).catchAll(_ => None.succeed))
      htmlDetails        = eventLogDetailGenerator.displayDetails(event, crId.flatten)
      nodePropertiesDiff = eventLogDetailGenerator.nodePropertiesDiff(event)
    } yield {
      RestEventLogDetails(
        id.toString,
        htmlDetails,
        event.canRollBack,
        nodePropertiesDiff.map(_.transformInto[SimpleDiffJson[List[NodeProperty]]])
      )
    }).catchSystemErrors
  }

  def rollback(id: Long, action: RestEventLogRollback.Action)(implicit qc: QueryContext): IOResult[Unit] = {
    (for {
      event      <- repo.getEventLogById(id).catchSystemErrors
      rollbackReq = action match {
                      case After  => eventLogDetailGenerator.RollbackTo
                      case Before => eventLogDetailGenerator.RollbackBefore
                    }
      committer  <- personIdentService.getPersonIdentOrDefault(qc.actor.name)
      _          <- rollbackReq.action(event, committer, Seq(event), event).toIO
    } yield ())
  }

}

object EventLogService {
  final case class EventLogSlice(events: Seq[EventLog], totalRecord: Long, totalFilter: Long)

  /**
   * Syntax to avoid exposing database query errors in the API response,
   * since we don't want to let the user know about SQL error.
   *
   * There is no strong guarantee that this catches all system errors,
   * especially since errors can be chained !
   */
  implicit private class IOResultSystemError[A](io: IOResult[A]) {
    def catchSystemErrors: IOResult[A] = io.catchSome {
      case err: SystemError =>
        EventLogsLoggerPure.error(err.fullMsg) *>
        Inconsistency("A database error occured").fail

      // in case user had called chainError once on a database call
      case Chained(msg, err: SystemError) =>
        EventLogsLoggerPure.error(err.fullMsg) *>
        Inconsistency(msg).fail
    }
  }

  /**
   * Syntax for converting rest filters objects to fragment, as only the service knows about the table and column names.
   * Some day we would have a repo that expose queries and test the resulting query with type-checking
   */
  implicit class SearchOps(search: RestEventLogFilter.Search) {
    def toFragment: Option[Fragment] = {
      if (search.value.isEmpty) {
        None
      } else {
        val v = "%" + search.value + "%"
        Some(fr"(temp1.filter ilike ${v} OR temp1.eventtype ilike ${v})")
      }
    }
  }

  implicit class OrderOps(order: RestEventLogFilter.Order) {
    // fragment needs to be with constant known column names
    def toFragment: Fragment = Fragment.const(order.column.entryName) ++ directionFragment
    private def directionFragment = order.dir match {
      case Desc => fr"DESC"
      case Asc  => fr"ASC"
    }
  }
}
