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

class DatabaseManagement extends DispatchSnippet with Loggable {

  private[this] val databaseManager = inject[DatabaseManager]
  private[this] var from : String = ""
  private[this] var action : (DateTime => Box[Int], String) = (databaseManager.archiveEntries , "Archiv" )

  val DATETIME_FORMAT = "yyyy-MM-dd"
  val DATETIME_PARSER = DateTimeFormat.forPattern(DATETIME_FORMAT)
  
  def dispatch = {
    case "display" => display
  }
  
  def display(xml:NodeSeq) : NodeSeq = {

     ("#modeSelector" #> <ul style="float:left">{SHtml.radio(Seq("Archive", "Delete"), Full("Archive")
          , {value:String => action = value match {
            case "Archive" => (databaseManager.archiveEntries , "Archiv" )
            case "Delete"  => (databaseManager.deleteEntries  , "Delet" )
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
          val warning = "Are you sure you want to %se reports older than %s?".format(action._2,DateFormaterService.getFormatedDate(date))
          S.error("")
          showConfirmationDialog(date,cleanReports(action._1,action._2),warning,action._2)
    }
  }

  def cleanReports (cleanAction:(DateTime=>Box[Int]), cleanType:String) (date:DateTime) : JsCmd = {
    logger.info("%sing all reports before %s".format(cleanType,date))
    try {
      cleanAction(date) match {

        case eb:EmptyBox =>
          val e = eb ?~! "An error occured while %sing reports".format(cleanType.toLowerCase)
          val eToPrint = e.failureChain.map( _.msg ).mkString("", ": ", "")
          logger.info(eToPrint)
          Alert(eToPrint)

        case Full(result) =>
          logger.info("Correctly %sed %d reports".format(cleanType.toLowerCase,result))
          Alert("Correctly %sed %d reports".format(cleanType.toLowerCase,result))& updateValue

      }
    } catch {
      case e: Exception => logger.error("Could not %se reports".format(cleanType.toLowerCase), e)
        Alert("An error occured while %sing reports".format(cleanType.toLowerCase))
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
      Text(MemorySize(x).toStringMo())).openOr(Text("Could not compute the size of the database")))
  }

  private[this] def showConfirmationDialog(date:DateTime, action : (DateTime => JsCmd) , warning : String, sentence: String ) : JsCmd = {
    val cancel : JsCmd = {
      SetHtml("confirm", NodeSeq.Empty) &
      JsRaw(""" $('#archiveReports').show();
                $('#cleanParam').show(); """)
    }

    val dialog =
    <span style="margin:5px;">
          <img src="/images/icWarn.png" alt="Warning!" height="25" width="25" class="warnicon"
            style="vertical-align: middle; padding: 0px 0px 2px 0px;"/>
          <b>{warning}</b>

      <span style="margin-left:7px">
          {
            SHtml.ajaxButton("Cancel", { () => { cancel } })
          }
      <span style="margin-left:10px">
          {
            SHtml.ajaxButton("%se reports".format(sentence), { () => action(date) & cancel } )
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