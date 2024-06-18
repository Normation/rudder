/*
 *************************************************************************************
 * Copyright 2012 Normation SAS
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

package com.normation.rudder.web.snippet.administration

import bootstrap.liftweb.RudderConfig
import com.normation.inventory.domain.MemorySize
import com.normation.rudder.domain.reports.*
import com.normation.utils.DateFormaterService
import net.liftweb.common.*
import net.liftweb.http.*
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.JsRaw
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.Helpers.*
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import scala.xml.*

class DatabaseManagement extends DispatchSnippet with Loggable {

  private val databaseManager = RudderConfig.databaseManager
  private val dbCleaner       = RudderConfig.automaticReportsCleaning

  private var from:   String            = ""
  val deleteAction:   DeleteAction      = DeleteAction(databaseManager, dbCleaner)
  private var action: CleanReportAction = deleteAction

  val DATETIME_FORMAT = "yyyy-MM-dd"
  val DATETIME_PARSER: DateTimeFormatter = DateTimeFormat.forPattern(DATETIME_FORMAT)

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = { case "display" => display }

  def display(xml: NodeSeq): NodeSeq = {

    ("#modeSelector" #> <ul>{
      SHtml
        .radio(
          Seq("Archive", "Delete"),
          Full("Archive"),
          { (value: String) =>
            action = value match {
              case "Delete" => deleteAction
            }
          },
          ("class", "radio")
        )
        .flatMap(e => <li>
              <label>{e.xhtml} <span class="radioTextLabel">{e.key.toString}</span></label>
            </li>)
    }
        </ul> &
    "#deleteReports" #> SHtml.ajaxSubmit("Clean reports", process _, ("class", "btn btn-default")) &
    "#reportFromDate" #> SHtml.text(from, x => from = x))(xml) ++ Script(
      OnLoad(JsRaw("""initReportDatepickler("#reportFromDate");""") & updateValue)
    )
  }

  def process(): JsCmd = {
    S.clearCurrentNotices

    (for {
      fromDate <- tryo(DATETIME_PARSER.parseDateTime(from)) ?~! "Bad date format for 'Archive all reports older than' field"
    } yield {
      fromDate
    }) match {
      case eb: EmptyBox =>
        val e = eb ?~! "An error occured"
        logger.info(e.failureChain.map(_.msg).mkString("", ": ", ""))
        S.error(e.failureChain.map(_.msg).mkString("", ": ", ""))
        Noop
      case Full(date) =>
        S.error("")
        showConfirmationDialog(date, action)
    }
  }

  def updateValue: JsCmd = {
    val reportsInterval = databaseManager.getReportsInterval()

    val inProgress = !deleteAction.actorIsIdle

    def displayInProgress(lastValue: Box[Option[DateTime]]): NodeSeq = {
      val date = displayDate(lastValue)
      if (inProgress) {
        <span>Archiving is in progress, please wait (last known value: "{date}")</span>
      } else {
        date
      }
    }

    SetHtml("oldestEntry", displayInProgress(reportsInterval.map(_._1))) &
    SetHtml("newestEntry", displayInProgress(reportsInterval.map(_._2))) &
    SetHtml(
      "databaseSize",
      databaseManager
        .getDatabaseSize()
        .map(x => Text(MemorySize(x).toStringMo))
        .openOr(Text("Could not compute the size of the database"))
    ) &
    SetHtml("deleteProgress", Text(deleteAction.progress)) &
    updateAutomaticCleaner
  }

  def updateAutomaticCleaner: JsCmd = {
    SetHtml("autoArchiveStatus", if (dbCleaner.archivettl > 0) Text("Enabled") else Text("Disabled")) & {
      if (dbCleaner.archivettl > 1)
        SetHtml("autoArchiveDays", Text("%d".format(dbCleaner.archivettl)))
      else
        JsRaw(""" $('#autoArchiveDetails').hide(); """)
    } &
    SetHtml("autoDeleteStatus", if (dbCleaner.deletettl > 0) Text("Enabled") else Text("Disabled")) & {
      if (dbCleaner.deletettl > 1)
        SetHtml("autoDeleteDays", Text("%d".format(dbCleaner.deletettl)))
      else
        JsRaw(""" $('#autoDeleteDetails').hide(); """)
    } & {
      if (dbCleaner.deletettl > 1 || dbCleaner.archivettl > 1) {
        SetHtml("cleanFrequency", Text(dbCleaner.freq.toString())) &
        SetHtml("nextRun", displayDate(Full(Option(dbCleaner.freq.next))))
      } else {
        JsRaw(""" $('#automaticCleanDetails').hide(); """)
      }
    }
  }

  private def showConfirmationDialog(date: DateTime, action: CleanReportAction): JsCmd = {
    val cancel: JsCmd = {
      SetHtml("confirm", NodeSeq.Empty) &
      JsRaw(""" $('#deleteReports').show();
                $('#cleanParam').show(); """)
    }
    val btnClass = if (action.name == "archive") { "btn-primary" }
    else { "btn-danger" }
    val dialog   = {
      <div class="callout-fade callout-info">
        <div class="marker"><span class="fa fa-exclamation-triangle"></span></div>
        Are you sure you want to
        <b>{action.name}</b>
        reports older than
        <span class="text-bold">
        {DateFormaterService.getDisplayDate(date)}
        </span>
        ?
        <div class="actions">
          {
        SHtml.ajaxButton("Cancel", () => { cancel & updateValue }, ("class", "btn btn-default"))
      }
          {
        SHtml.ajaxButton(
          "%s reports".format(action.name.capitalize),
          { () =>
            val askResult = action.ask(date)
            JsRaw("""$('#cleanResultText').html('%s, you can see more details in the webapp log file (<span class="text-bold">/var/log/rudder/core/rudder-webapp.log</span>)');
                 $('#cleanResult').show();""".format(askResult)) & cancel & updateValue
          },
          ("class", ("btn " ++ btnClass))
        )
      }
        </div>
      </div>
    }

    def showDialog: JsCmd = {
      SetHtml("confirm", dialog) &
      JsRaw(""" $('#deleteReports').hide();
                $('#cleanParam').hide();
                $('#cleanResult').hide();
                $('#confirm').stop(true, true).slideDown(1000); """)
    }

    showDialog
  }

  private def displayDate(entry: Box[Option[DateTime]]): NodeSeq = {
    entry match {
      case Full(dateOption) =>
        dateOption match {
          case Some(date) => <span>{DateFormaterService.getDisplayDate(date)}</span>
          case None       => <span>There is no reports in the table yet</span>
        }
      case _: EmptyBox => <span>There's been an error with the database, could not fetch the value</span>
    }
  }
}
