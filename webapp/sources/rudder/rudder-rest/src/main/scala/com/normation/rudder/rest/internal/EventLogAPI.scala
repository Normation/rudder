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
import cats.syntax.all.*
import com.normation.box.*
import com.normation.eventlog.*
import com.normation.rudder.domain.policies.SimpleDiff
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.repository.json.DataExtractor.CompleteJson
import com.normation.rudder.rest.OldInternalApiAuthz
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.RestUtils.*
import com.normation.rudder.services.user.PersonIdentService
import com.normation.rudder.users.CurrentUser
import com.normation.rudder.web.services.*
import com.normation.utils.DateFormaterService
import doobie.*
import doobie.implicits.*
import doobie.implicits.javasql.*
import net.liftweb.common.*
import net.liftweb.http.JsonResponse
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.http.S
import net.liftweb.http.rest.RestHelper
import net.liftweb.json.JsonAST
import net.liftweb.json.JsonDSL.*
import net.liftweb.json.JValue
import net.liftweb.util.Helpers.tryo
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

class EventLogAPI(
    repos:              EventLogRepository,
    restExtractor:      RestExtractorService,
    eventLogDetail:     EventLogDetailsGenerator,
    personIdentService: PersonIdentService
) extends RestHelper with Loggable {
  implicit class ToSqlTimestamp(d: DateTime) {
    def toSql = new java.sql.Timestamp(d.getMillis)
  }

  def serialize(event: EventLog): JValue = {
    import net.liftweb.json.JsonDSL.*

    (("id"           -> event.id)
    ~ ("date"        -> DateFormaterService.getDisplayDate(event.creationDate))
    ~ ("actor"       -> event.principal.name)
    ~ ("type"        -> S.?("rudder.log.eventType.names." + event.eventType.serialize))
    ~ ("description" -> eventLogDetail.displayDescription(event).toString)
    ~ ("hasDetails"  -> (if (event.details != <entry></entry>) true else false)))
  }

  def errorFormatter(errorMessage: String):                                                     JsonAST.JObject = {
    (("draw"             -> 0)
    ~ ("recordsTotal"    -> 0)
    ~ ("recordsFiltered" -> 0)
    ~ ("data"            -> "")
    ~ ("error"           -> errorMessage))
  }
  def responseFormater(draw: Int, totalRecord: Long, totalFiltered: Long, logs: Seq[EventLog]): JValue          = {
    (("draw"             -> draw)
    ~ ("recordsTotal"    -> totalRecord)
    ~ ("recordsFiltered" -> totalFiltered)
    ~ ("data"            -> logs.map(serialize)))
  }

  def getEventLogBySlice(
      start:    Int,
      criteria: Option[Fragment],
      optLimit: Option[Int],
      orderBy:  List[Fragment],
      filter:   Option[Fragment]
  ): Box[Seq[EventLog]] = {
    val c = criteria.getOrElse(Fragment.const(" 1 = 1 ")) ++ fr" order by ${orderBy.intercalate(fr",")} offset ${start}"
    repos
      .getEventLogByCriteria(Some(c), optLimit, Nil, filter)
      .toBox match {
      case Full(events) => Full(events)
      case eb: EmptyBox =>
        eb ?~! s"Error when trying fetch eventlogs from database for page ${(start / optLimit.getOrElse(1)) + 1}"
    }
  }

  def requestDispatch: PartialFunction[Req, () => Box[LiftResponse]] = {
    case Post(Nil, req) =>
      def extractFilterOpt(json: JValue) = {
        for {
          value <- CompleteJson.extractJsonString(json, "value")
        } yield {
          if (value == "") {
            None
          } else {
            val v = "%" + value + "%"
            Some(fr"(temp1.filter ilike ${v} OR temp1.eventtype ilike ${v})")
          }
        }
      }

      def extractDateTimeOpt(i: String) = {
        val format = DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ss")

        if (i == "") {
          Full(None)
        } else {
          tryo(Some(DateTime.parse(i, format)))
        }
      }

      implicit val prettify: Boolean = restExtractor
        .extractBoolean("prettify")(req)(identity)
        .getOrElse(Some(false))
        .getOrElse(
          false
        )
      implicit val action:   String  = "eventFilterDetails"
      OldInternalApiAuthz.withWriteAdmin((for {
        json   <- req.json
        draw   <- CompleteJson.extractJsonInt(json, "draw")
        start  <- CompleteJson.extractJsonInt(json, "start")
        length <- CompleteJson.extractJsonInt(json, "length")

        filter       <- CompleteJson.extractJsonObj(json, "search", extractFilterOpt)
        optStartDate <- CompleteJson.extractJsonString(json, "startDate", extractDateTimeOpt)
        optEndDate   <- CompleteJson.extractJsonString(json, "endDate", extractDateTimeOpt)

        order <- CompleteJson.extractJsonArray(json, "order") { json =>
                   for {
                     colId <- CompleteJson.extractJsonInt(json, "column")
                     col   <- colId match {

                                case 0 => Full("id")
                                case 1 => Full("creationdate")
                                case 2 => Full("principal")
                                case 3 => Full("eventtype")
                                case _ => Failure("not a valid column")

                              }
                     dir   <- CompleteJson.extractJsonString(json, "dir")
                     order <- dir.toLowerCase match {
                                case "desc" => Full("DESC")
                                case "asc"  => Full("ASC")
                                case x      => Failure(s"not a valid sorting order: ${x}")
                              }
                   } yield {
                     Fragment.const(s"${col} ${order}")
                   }
                 }

        dateCriteria = (optStartDate, optEndDate) match {
                         case (None, None)                                        => None
                         case (Some(start_), None)                                => Some(fr" creationDate >= ${start_.toSql}")
                         case (None, Some(end_))                                  => Some(fr" creationDate <= ${end_.toSql}")
                         case (Some(start_), Some(end_)) if end_.isBefore(start_) =>
                           Some(
                             fr" creationDate >= ${end_.toSql} and creationDate <= ${start_.toSql}"
                           )
                         case (Some(start_), Some(end_))                          =>
                           Some(
                             fr" creationDate >= ${start_.toSql} and creationDate <= ${end_.toSql}"
                           )
                       }

        (criteria, from) = (filter, dateCriteria) match {
                             case (None, res)              => (res, None)
                             case (Some(value), None)      =>
                               (
                                 Some(value),
                                 Some(
                                   fr" from ( select eventtype, id, modificationid, principal, creationdate, causeid, severity, reason, data, UNNEST(xpath('string(//entry)',data))::text as filter from eventlog) as temp1 "
                                 )
                               )
                             case (Some(value), Some(res)) =>
                               (
                                 Some(fr"${value} and ${res}"),
                                 Some(
                                   fr" from ( select eventtype, id, modificationid, principal, creationdate, causeid, severity, reason, data, UNNEST(xpath('string(//entry)',data))::text as filter from eventlog) as temp1 "
                                 )
                               )
                           }

        events      <- getEventLogBySlice(start, criteria, Some(length), order, from)
        totalRecord <- repos.getEventLogCount(None).toBox
        totalFilter <- repos.getEventLogCount(criteria, from).toBox
      } yield {
        responseFormater(draw, totalRecord, totalFilter, events)
      }) match {
        case Full(resp) =>
          JsonResponse(resp)
        case eb: EmptyBox =>
          val fail = eb ?~! "Error when fetching event logs"
          logger.error(fail.messageChain)
          JsonResponse(errorFormatter(fail.msg), 500) // we don't want to let the user know about SQL error
      })

    case Get(id :: "details" :: Nil, req) =>
      implicit val prettify: Boolean =
        restExtractor.extractBoolean("prettify")(req)(identity).getOrElse(Some(false)).getOrElse(false)
      implicit val action:   String  = "eventDetails"
      OldInternalApiAuthz.withReadAdmin((for {
        realId            <- Box.tryo(id.toLong)
        event             <- repos.getEventLogById(realId).toBox
        crId               = event.id.flatMap(repos.getEventLogWithChangeRequest(_).toBox match {
                               case Full(Some((_, crId))) => crId
                               case _                     => None
                             })
        htmlDetails        = eventLogDetail.displayDetails(event, crId)(CurrentUser.queryContext)
        nodePropertiesDiff = eventLogDetail.nodePropertiesDiff(event)
      } yield {
        val response = {
          (("id"                  -> id)
          ~ ("content"            -> htmlDetails.toString())
          ~ ("nodePropertiesDiff" -> nodePropertiesDiff.map { case SimpleDiff(from, to) => (("from" -> from) ~ ("to" -> to)) })
          ~ ("canRollback"        -> event.canRollBack))
        }
        toJsonResponse(None, response)
      }) match {
        case Full(resp) => resp
        case eb: EmptyBox =>
          val fail = eb ?~! s"Error when getting event log with id '${id}' details"
          logger.error(fail.messageChain)
          toJsonError(None, fail.msg) // we don't want to let the user know about SQL error
      })

    case Post(id :: "details" :: "rollback" :: Nil, req) =>
      implicit val prettify: Boolean =
        restExtractor.extractBoolean("prettify")(req)(identity).getOrElse(Some(false)).getOrElse(false)
      implicit val action:   String  = "eventRollback"

      OldInternalApiAuthz.withWriteAdmin((for {
        reqParam     <- req.params.get("action") match {
                          case Some(actions) if (actions.size == 1) => Full(actions.head)
                          case Some(actions)                        =>
                            Failure(s"Only one action is excepted, ${actions.size} found in request : ${actions.mkString(",")}")
                          case None                                 => Failure("Empty action")
                        }
        rollbackReq  <- if (reqParam == "after") Full(eventLogDetail.RollbackTo)
                        else if (reqParam == "before") Full(eventLogDetail.RollbackBefore)
                        else Failure(s"Unknown rollback's action : ${reqParam}")
        idLong       <- Box.tryo(id.toLong)
        event        <- repos.getEventLogById(idLong).toBox
        committer    <- personIdentService.getPersonIdentOrDefault(CurrentUser.actor.name).toBox
        rollbackExec <- rollbackReq.action(event, committer, Seq(event), event)
      } yield {
        val r = {
          (("action" -> reqParam)
          ~ ("id"    -> id))
        }
        toJsonResponse(None, r)
      }) match {
        case Full(resp) => resp
        case eb: EmptyBox =>
          val fail = eb ?~! s"Error when performing eventlog's rollback with id '${id}'"
          toJsonError(None, fail.messageChain)
      })
  }
  serve("secure" / "api" / "eventlog" prefix requestDispatch)
}
