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
import com.normation.box._
import com.normation.eventlog._
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.RestUtils._
import com.normation.rudder.web.components.DateFormaterService
import com.normation.rudder.web.services._
import net.liftweb.common._
import net.liftweb.http.rest.RestHelper
import net.liftweb.http.{JsonResponse, LiftResponse, Req, S}
import net.liftweb.json.JValue
import net.liftweb.json.JsonDSL._

class EventLogAPI (
    repos: EventLogRepository
  , restExtractor : RestExtractorService
  , eventLogDetail : EventListDisplayer
) extends  RestHelper with  Loggable {

  def serialize(event: EventLog): JValue = {
    import net.liftweb.json.JsonDSL._

    ( ("id"          -> (event.id.map(_.toString).getOrElse("Unknown"): String))
    ~ ("date"        -> DateFormaterService.getFormatedDate(event.creationDate))
    ~ ("actor"       -> event.principal.name)
    ~ ("type"        -> S.?("rudder.log.eventType.names." + event.eventType.serialize))
    ~ ("description" -> eventLogDetail.displayDescription(event).toString)
    ~ ("hasDetails"  -> (if(event.details != <entry></entry>)  true  else  false))
    )
  }

  def responseFormater(draw: Int, totalRecord: Long, totalFiltered: Long, logs: Vector[EventLog], errorMsg: Option[String] = None): JValue = {
    errorMsg match {
      case Some(msg) =>
        ( ("draw"            -> draw)
        ~ ("recordsTotal"    -> totalRecord)
        ~ ("recordsFiltered" -> totalFiltered)
        ~ ("data"            -> logs.map(serialize))
        ~ ("error"           -> msg)
        )
      case _ =>
        ( ("draw"            -> draw)
        ~ ("recordsTotal"    -> totalRecord)
        ~ ("recordsFiltered" -> totalFiltered)
        ~ ("data"            -> logs.map(serialize))
        )
    }
  }

  def getEventLogBySlice(start: Int,  nbelement: Int,  criteria : Option[String], optLimit:Option[Int] = None, orderBy:Option[String]): Box[(Int,Vector[EventLog])] = {
    repos.getEventLogByCriteria(criteria,  optLimit, orderBy).toBox match  {
      case Full(events) => Full((events.size, events.slice(start,start+nbelement)))
      case eb: EmptyBox  => eb ?~! s"Error when trying fetch eventlogs from database for page ${(start/nbelement)+1}"
    }
  }

  def requestDispatch: PartialFunction[Req, () => Box[LiftResponse]] = {
    case Get(Nil, req) =>

      val draw = req.params.get("draw") match {
        case Some(value :: Nil) => Full(value.toInt)
        case _ => Failure("Missing 'draw' field from datatable's request")
      }
      val start = req.params.get("start") match {
        case Some(value :: Nil) => Full(value)
        case _ => Failure("Missing 'start' field from datatable's request")
      }
      val length = req.params.get("length") match {
        case Some(value :: Nil) => Full(value)
        case _ => Failure("Missing 'length' field from datatable's request")
      }

      val response = (draw, start, length) match {
        case (Full(d), Full(s), Full(l)) =>
          repos.getEventLogCount.toBox  match  {
            case Full(totalRecord) =>
              getEventLogBySlice(s.toInt, l.toInt, None,  None,  Some("creationdate DESC" )) match {
                case Full((totalFiltered, events)) =>
                  responseFormater(d, totalRecord,  totalFiltered.toLong, events)
                case eb:  EmptyBox                 =>
                  val fail = eb  ?~! "Failed to get eventlogs"
                  responseFormater(d, totalRecord,  0, Vector.empty, Some(fail.messageChain))
              }
            case eb: EmptyBox      =>
              val fail = eb ?~! "Failed to get event log's count"
              responseFormater(d, 0,  0, Vector.empty, Some(fail.messageChain))
          }

        case (ebDraw: EmptyBox,_, _)      =>
          val fail = ebDraw ?~! "Missing parameter in request"
          responseFormater(0, 0, 0, Vector.empty, Some(fail.messageChain))

        case (_,ebStart: EmptyBox, _)     =>
          val fail = ebStart ?~! "Missing parameter in request"
          responseFormater(0, 0, 0, Vector.empty, Some(fail.messageChain))

        case (_,_, ebLength: EmptyBox)    =>
          val fail = ebLength ?~! "Missing parameter in request"
          responseFormater(0, 0, 0, Vector.empty, Some(fail.messageChain))
      }
      JsonResponse(response, Nil, Nil, 200)

    case Get(id :: "details" :: Nil, _) =>
      repos.getEventLogByCriteria(Some(s"id = $id")).toBox match {
        case Full(e) =>
          e.headOption match {
            case Some(eventLog) =>
              val crid = eventLog.id.flatMap(repos.getEventLogWithChangeRequest(_).toBox match {
                case Full(Some((_, crId))) => crId
                case _                     => None
              })
              val htmlDetails = eventLogDetail.displayDetails(eventLog, crid)
              toJsonResponse(None, "content" -> htmlDetails.toString())("eventdetails", prettify = false)
            case None =>
              toJsonError(None, s"EventLog $id not found")("eventdetails", prettify = false)
          }
        case eb: EmptyBox =>
          val e = eb ?~! s"Error when trying to retrieve eventlog : $id"
          toJsonError(None, e.messageChain)("eventdetails", prettify = false)
      }
  }
  serve("secure" / "api" / "eventlog" prefix requestDispatch)
}
