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

import com.normation.box.*
import com.normation.eventlog.EventLog
import com.normation.rudder.repository.*
import doobie.*
import doobie.implicits.*
import doobie.implicits.javasql.*
import net.liftweb.common.*
import net.liftweb.http.S
import net.liftweb.http.SHtml
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.json.*
import net.liftweb.util.Helpers.*
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import scala.xml.*

/**
 * Used to display the event list, in the pending modification (AsyncDeployment),
 * or in the administration EventLogsViewer
 */
class EventListDisplayer(repos: EventLogRepository) extends Loggable {

  private val gridName = "eventLogsGrid"

  def display(refreshEvents: () => Box[Seq[EventLog]]): NodeSeq = {
    // common part between last events and interval
    def displayEvents(events: Box[Seq[EventLog]]): JsCmd = {
      events match {
        case Full(events) =>
          JsRaw(s"refreshTable('${gridName}',[])")
        case eb: EmptyBox =>
          val fail = eb ?~! "Could not get latest event logs"
          logger.error(fail.messageChain)
          val xml  = <div class="error">Error when trying to get last event logs. Error message was: {
            fail.msg
          }</div> // we don't want to let the user know about SQL error
          SetHtml("eventLogsError", xml)
      }
    }

    def getLastEvents: JsCmd = {
      displayEvents(refreshEvents())
    }

    def getEventsInterval(jsonInterval: String): JsCmd = {
      import java.sql.Timestamp
      val format = DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ss")

      displayEvents(for {
        parsed        <- tryo(
                           parse(jsonInterval)
                         ) ?~! s"Error when trying to parse '${jsonInterval}' as a JSON datastructure with fields 'start' and 'end'"
        startStr      <- parsed \ "start" match {
                           case JString(startStr) if startStr.nonEmpty =>
                             val date =
                               tryo(DateTime.parse(startStr, format)) ?~! s"Error when trying to parse start date '${startStr}"
                             date match {
                               case Full(d) => Full(Some(new Timestamp(d.getMillis)))
                               case eb: EmptyBox =>
                                 eb ?~! s"Invalid start date"
                             }
                           case _                                      => Full(None)
                         }
        endStr        <- parsed \ "end" match {
                           case JString(endStr) if endStr.nonEmpty =>
                             val date = tryo(DateTime.parse(endStr, format)) ?~! s"Error when trying to parse end date '${endStr}"
                             date match {
                               case Full(d) => Full(Some(new Timestamp(d.getMillis)))
                               case eb: EmptyBox =>
                                 eb ?~! s"Invalid end date"
                             }
                           case _                                  => Full(None)
                         }
        whereStatement = (startStr, endStr) match {
                           case (None, None)             => None
                           case (Some(start), None)      => Some(fr" creationdate > ${start}")
                           case (None, Some(end))        => Some(fr" creationdate < ${end}")
                           case (Some(start), Some(end)) =>
                             val orderedDate = if (start.after(end)) (end, start) else (start, end)
                             Some(fr" creationdate > ${orderedDate._1} and creationdate < ${orderedDate._2} ")
                         }
        logs          <- repos.getEventLogByCriteria(whereStatement, None, List(Fragment.const("id DESC"))).toBox
      } yield {
        logs
      })
    }

    val refresh = AnonFunc(SHtml.ajaxInvoke(() => getLastEvents))

    Script(OnLoad(JsRaw(s"""
     var refreshEventLogs = ${refresh.toJsCmd};
     initDatePickers("#filterLogs", ${AnonFunc("param", SHtml.ajaxCall(JsVar("param"), getEventsInterval)._2).toJsCmd});
     createEventLogTable('${gridName}',[], '${S.contextPath}', refreshEventLogs)
     refreshEventLogs();
    """)))
  }

}
