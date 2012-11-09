/*
*************************************************************************************
* Copyright 2012 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.web.snippet.administration

import net.liftweb.common._
import net.liftweb.http.DispatchSnippet
import net.liftweb.util._
import net.liftweb.util.Helpers._
import scala.xml._
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import com.normation.rudder.services.system.DatabaseManager
import com.normation.rudder.web.components.DateFormaterService
import org.joda.time.DateTime
import com.normation.inventory.domain.MemorySize
import net.liftweb.http._
import net.liftweb.http.js._
import net.liftweb.http.js.JsCmds._
import org.joda.time.format.DateTimeFormat
import net.liftweb.http.js.JE.JsRaw
import com.normation.rudder.domain.logger.ReportLogger
import com.normation.rudder.domain.reports._
import com.normation.rudder.batch.AutomaticReportsCleaning

class DatabaseManagement extends DispatchSnippet with Loggable {

  private[this] val databaseManager = inject[DatabaseManager]
  private[this] val dbCleaner = inject[AutomaticReportsCleaning]
  private[this] var from : String = ""
  private[this] var action : CleanReportAction = ArchiveAction(databaseManager)

  val DATETIME_FORMAT = "yyyy-MM-dd"
  val DATETIME_PARSER = DateTimeFormat.forPattern(DATETIME_FORMAT)
  
  def dispatch = {
    case "display" => display
  }
  
  def display(xml:NodeSeq) : NodeSeq = {

     ("#modeSelector" #> <ul style="float:left">{SHtml.radio(Seq("Archive", "Delete"), Full("Archive")
          , {value:String => action = value match {
            case "Archive" => ArchiveAction(databaseManager)
            case "Delete"  => DeleteAction(databaseManager)
          } }
          , ("class", "radio") ).flatMap(e =>
            <li>
              <label>{e.xhtml} <span class="radioTextLabel">{e.key.toString}</span></label>
            </li>) }
        </ul> &
      "#archiveReports" #> SHtml.ajaxSubmit("Clean reports", process _) &
      "#reportFromDate" #> SHtml.text(from, {x => from = x } ) 
      
    )(xml) ++ Script(OnLoad(JsRaw("""initReportDatepickler("#reportFromDate"); correctButtons(); """)& updateValue))
  }
  
  def process(): JsCmd = {
    S.clearCurrentNotices

    (for {
      fromDate <- tryo { DATETIME_PARSER.parseDateTime(from) } ?~! "Bad date format for 'Archive all reports older than' field"
    } yield {
      fromDate
    } ) match {
        case eb:EmptyBox =>
          val e = eb ?~! "An error occured"
          logger.info(e.failureChain.map( _.msg ).mkString("", ": ", ""))
          S.error(e.failureChain.map( _.msg ).mkString("", ": ", "")  )
          Noop
        case Full(date) =>
          S.error("")
          showConfirmationDialog(date,action)
    }
  }



  def updateValue = {
    val reportsInterval = databaseManager.getReportsInterval()
    val archivedReportsInterval = databaseManager.getArchivedReportsInterval()
    SetHtml("oldestEntry", displayDate(reportsInterval.map( x => x._1 ))) &
    SetHtml("newestEntry", displayDate(reportsInterval.map( x => x._2 ))) &
    SetHtml("databaseSize" , databaseManager.getDatabaseSize().map(x =>
      Text(MemorySize(x).toStringMo())).openOr(Text("Could not compute the size of the database"))) &
    SetHtml("oldestArchivedEntry", displayDate(archivedReportsInterval.map( x => x._1 ))) &
    SetHtml("newestArchivedEntry", displayDate(archivedReportsInterval.map( x => x._2 ))) &
    SetHtml("archiveSize", databaseManager.getArchiveSize().map(x =>
      Text(MemorySize(x).toStringMo())).openOr(Text("Could not compute the size of the database"))) &
      updateAutomaticCleaner
  }

  def updateAutomaticCleaner = {
    SetHtml("autoArchiveStatus", if(dbCleaner.archivettl > 0) Text("Enabled") else Text("Disabled") ) &
    { if(dbCleaner.archivettl > 1)
        SetHtml("autoArchiveDays", Text("%d".format(dbCleaner.archivettl)))
      else
        JsRaw(""" $('#autoArchiveDetails').hide(); """) } &
    SetHtml("autoDeleteStatus", if(dbCleaner.deletettl > 0) Text("Enabled") else Text("Disabled") ) &
    { if(dbCleaner.deletettl > 1)
        SetHtml("autoDeleteDays", Text("%d".format(dbCleaner.deletettl)))
      else
        JsRaw(""" $('#autoDeleteDetails').hide(); """) } &
    { if(dbCleaner.deletettl > 1 || dbCleaner.archivettl > 1)
        SetHtml("cleanFrequency",  Text(dbCleaner.freq.toString()) ) &
        SetHtml("nextRun",  displayDate(Full(dbCleaner.freq.next)))
      else
        JsRaw(""" $('#automaticCleanDetails').hide(); """) }
  }

  private[this] def showConfirmationDialog(date:DateTime, action : CleanReportAction ) : JsCmd = {
    val cancel : JsCmd = {
      SetHtml("confirm", NodeSeq.Empty) &
      JsRaw(""" $('#archiveReports').show();
                $('#cleanParam').show(); """)
    }

    val dialog =
    <span style="margin:5px;">
          <img src="/images/icWarn.png" alt="Warning!" height="25" width="25" class="warnicon"
            style="vertical-align: middle; padding: 0px 0px 2px 0px;"/>
          <b>{"Are you sure you want to %s reports older than %s?".format(action.name,DateFormaterService.getFormatedDate(date))}</b>

      <span style="margin-left:7px">
          {
            SHtml.ajaxButton("Cancel", { () => { cancel & updateValue } })
          }
      <span style="margin-left:10px">
          {
            SHtml.ajaxButton("%se reports".format(action.name), { () => action.cleanReports(date) & cancel & updateValue } )
          }
        </span>
      </span>
    </span>

    def showDialog : JsCmd = {
      SetHtml("confirm", dialog) &
      JsRaw(""" $('#archiveReports').hide();
                $('#cleanParam').hide();
                correctButtons();
                $('#confirm').stop(true, true).slideDown(1000); """)
    }

    showDialog
  }
  
  private[this] def displayDate( entry : Box[DateTime]) : NodeSeq= {
    entry.
      map ( x => <span>{DateFormaterService.getFormatedDate(x)}</span> ).
      openOr( <span>There's been an error with the database, could not fetch the value</span>)
  }
}
