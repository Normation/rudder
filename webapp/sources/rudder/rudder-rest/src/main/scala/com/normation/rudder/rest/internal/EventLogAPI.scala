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

import com.normation.errors.*
import com.normation.eventlog.*
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.domain.logger.EventLogsLoggerPure
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.rest.*
import com.normation.rudder.rest.RudderJsonRequest.*
import com.normation.rudder.rest.data.*
import com.normation.rudder.rest.data.RestEventLogRollback.Action.After
import com.normation.rudder.rest.data.RestEventLogRollback.Action.Before
import com.normation.rudder.rest.lift.*
import com.normation.rudder.rest.syntax.*
import com.normation.rudder.services.user.PersonIdentService
import com.normation.rudder.tenants.QueryContext
import com.normation.rudder.web.services.*
import com.normation.zio.UnsafeRun
import io.scalaland.chimney.syntax.*
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import zio.ZIO
import zio.syntax.*

class EventLogAPI(
    service:            EventLogService,
    coreService:        com.normation.rudder.services.eventlog.EventLogService,
    eventLogDetail:     EventLogDetailsGenerator,
    translateEventType: EventLogType => String
) extends LiftApiModuleProvider[EventLogApi] {
  import com.normation.rudder.rest.internal.EventLogService.*

  implicit lazy val translateEventLogType: EventLogType => String   = translateEventType
  implicit lazy val eventLogDetailsGen:    EventLogDetailsGenerator = eventLogDetail

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
        restFilter  <- req.fromJson[RestEventLogFilter].toIO
        filter       = restFilter.toEventLogRequest
        totalRecord <- coreService.getEventLogCount(filter = None)
        totalFilter <- coreService.getEventLogCount(filter = Some(filter))
        events      <- coreService.getUserEventLogs(filter = Some(filter))
        res          = EventLogSlice(events, totalRecord, totalFilter)
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

  def getEventLogDetails(id: Long)(implicit qc: QueryContext): IOResult[RestEventLogDetails] = {

    (for {
      event <- repo.getEventLogById(id)
      crId  <- ZIO.foreach(event.id)(repo.getEventLogWithChangeRequest(_).notOptional("").map(_._2).catchAll(_ => None.succeed))

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
}
