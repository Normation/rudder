/*
*************************************************************************************
* Copyright 2011 Normation SAS
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
package com.normation.rudder.web.services

import com.normation.box._
import com.normation.eventlog.EventLog
import com.normation.rudder.repository._
import com.normation.rudder.rule.category.RoRuleCategoryRepository
import com.normation.rudder.services.eventlog.EventLogDetailsService
import com.normation.rudder.services.modification.ModificationService
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.user.PersonIdentService
import com.normation.rudder.web.components.DateFormaterService
import com.normation.rudder.web.model.LinkUtil
import net.liftweb.common._
import net.liftweb.http.{S, SHtml}
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.js._
import net.liftweb.json._
import net.liftweb.util.Helpers._
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

import scala.xml._

/**
 * Used to display the event list, in the pending modification (AsyncDeployment),
 * or in the administration EventLogsViewer
 */
class EventListDisplayer(
      logDetailsService   : EventLogDetailsService
    , repos               : EventLogRepository
    , nodeGroupRepository : RoNodeGroupRepository
    , directiveRepository : RoDirectiveRepository
    , nodeInfoService     : NodeInfoService
    , ruleCatRepository   : RoRuleCategoryRepository
    , modificationService : ModificationService
    , personIdentService  : PersonIdentService
    , linkUtil            : LinkUtil
) extends Loggable {

  private[this] val gridName = "eventLogsGrid"

  def display(refreshEvents:() => Box[Seq[EventLog]]) : NodeSeq  = {
    val limit: Int = 500
    //common part between last events and interval
    def displayEvents(events: Box[Seq[EventLog]]) :JsCmd = {
      events match {
        case Full(events) =>
          val lines = {
            val el = events.map(EventLogLine(_)).toList.sortWith(_.event.creationDate.getMillis > _.event.creationDate.getMillis)
            if(el.size > limit) JsTableData(el.take(limit)) else JsTableData(el)
          }
          JsRaw(s"refreshTable('${gridName}',${lines.json.toJsCmd})")
        case eb : EmptyBox =>
          val fail = eb ?~! "Could not get latest event logs"
          logger.error(fail.messageChain)
          val xml = <div class="error">Error when trying to get last event logs. Error message was: {fail.messageChain}</div>
          SetHtml("eventLogsError",xml)
      }
    }

    def getLastEvents : JsCmd = {
      displayEvents(refreshEvents())
    }

    def getEventsInterval(jsonInterval: String): JsCmd = {
      import java.sql.Timestamp
      val format = DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ss")

      displayEvents(for {
        parsed   <- tryo(parse(jsonInterval)) ?~! s"Error when trying to parse '${jsonInterval}' as a JSON datastructure with fields 'start' and 'end'"
        startStr <- parsed \ "start" match {
                      case JString(startStr) if startStr.nonEmpty =>
                        val date = tryo(DateTime.parse(startStr, format)) ?~! s"Error when trying to parse start date '${startStr}"
                        date match {
                          case Full(d) => Full(Some(new Timestamp(d.getMillis)))
                          case eb: EmptyBox =>
                            eb ?~! s"Invalid start date"
                        }
                      case _ => Full(None)
                    }
        endStr   <- parsed \ "end" match {
                      case JString(endStr) if endStr.nonEmpty =>
                        val date = tryo(DateTime.parse(endStr, format)) ?~! s"Error when trying to parse end date '${endStr}"
                        date match {
                          case Full(d) => Full(Some(new Timestamp(d.getMillis)))
                          case eb: EmptyBox =>
                             eb ?~! s"Invalid end date"
                        }
                      case _ => Full(None)
                    }
        whereStatement = (startStr, endStr) match {
          case (None, None) => None
          case (Some(start), None) => Some(s" creationdate > '$start'")
          case (None, Some(end)) => Some(s" creationdate < '$end'")
          case (Some(start), Some(end)) =>
            val orderedDate = if(start.after(end)) (end, start) else (start, end)
            Some(s" creationdate > '${orderedDate._1}' and creationdate < '${orderedDate._2}'")
        }
        logs     <- repos.getEventLogByCriteria(whereStatement, None, Some("id DESC")).toBox
      } yield {
        logs
      })
    }

    val refresh = AnonFunc(SHtml.ajaxInvoke( () => getLastEvents))

    Script(OnLoad(JsRaw(s"""
     var pickEventLogsInInterval = ${AnonFunc(SHtml.ajaxCall(JsRaw(
       """'{"start":"'+$(".pickStartInput").val()+'", "end":"'+$(".pickEndInput").val()+'"}'"""
     ), getEventsInterval)._2).toJsCmd}
     var refreshEventLogs = ${refresh.toJsCmd};
     createEventLogTable('${gridName}',[], '${S.contextPath}', refreshEventLogs, pickEventLogsInInterval)
     refreshEventLogs();
    """)))
  }

  /*
   *   Javascript object containing all data to create a line in event logs table
   *   { "id" : Event log id [Int]
   *   , "date": date the event log was produced [Date/String]
   *   , "actor": Name of the actor making the event [String]
   *   , "type" : Type of the event log [String]
   *   , "hasDetails" : do our event needs to display details (do we need to be able to open the row [Boolean]
   *   }
   */
  case class EventLogLine(event : EventLog) extends JsTableLine {
    val json = {
      JsObj(
          "id" -> (event.id.map(_.toString).getOrElse("Unknown"): String)
        , "date" -> DateFormaterService.getFormatedDate(event.creationDate)
        , "actor" -> event.principal.name
        , "type" -> S.?("rudder.log.eventType.names." + event.eventType.serialize)
        , "hasDetails" -> boolToJsExp(event.details != <entry></entry>)
      )
    }
  }
}
