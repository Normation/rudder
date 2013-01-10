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
  

  val DATETIME_FORMAT = "yyyy-MM-dd"
  val DATETIME_PARSER = DateTimeFormat.forPattern(DATETIME_FORMAT)
  
  def dispatch = {
    case "display" => display
  }
  
  def display(xml:NodeSeq) : NodeSeq = {
    val reportsInterval = databaseManager.getReportsInterval()
    val archivedReportsInterval = databaseManager.getArchivedReportsInterval()
    (  
      "#oldestEntry" #> displayDate(reportsInterval.map( x => x._1 )) &
      "#newestEntry" #> displayDate(reportsInterval.map( x => x._2 )) &
      "#databaseSize" #> databaseManager.getDatabaseSize().map(x => Text(MemorySize(x).toStringMo())).openOr(Text("Could not compute the size of the database")) &
      "#oldestArchivedEntry" #> displayDate(archivedReportsInterval.map( x => x._1 )) &
      "#newestArchivedEntry" #> displayDate(archivedReportsInterval.map( x => x._2 )) &
      "#archiveReports" #> SHtml.ajaxSubmit("Archive Report", process _) &
      "#reportFromDate" #> SHtml.text(from, {x => from = x } ) 
      
    )(xml) ++ Script(OnLoad(JsRaw("""initReportDatepickler("#reportFromDate"); correctButtons(); """)))
  }
  
  def process(): JsCmd = {
    S.clearCurrentNotices
    
    (for {
      fromDate <- tryo { DATETIME_PARSER.parseDateTime(from) } ?~! "Bad date format for 'Archive all reports older than' field"
    } yield {
      fromDate
    }) match {
        case eb:EmptyBox =>
          val e = eb ?~! "An error occured"
          logger.info(e.failureChain.map( _.msg ).mkString("", ": ", ""))
          S.error(e.failureChain.map( _.msg ).mkString("", ": ", "")  )
          Noop
        case Full(date) =>
          logger.info("Archiving all reports before %s".format(date))
          try {
            val result = databaseManager.archiveEntries(date)
            Alert("Correctly archived %d reports".format(result))
          } catch {
            case e: Exception => logger.error("Could not archive reports", e)
                                 Alert("An error occured while archiving reports")
          }
          
    }
  }
  
  private[this] def displayDate( entry : Box[Option[DateTime]]) : NodeSeq= {
    entry match {
      case Full(dateOption) => dateOption match {
        case Some(date) =>  <span>{DateFormaterService.getFormatedDate(date)}</span>
        case None => <span>There is no reports in the table yet</span>
      }
      case _:EmptyBox => <span>There's been an error with the database, could not fetch the value</span>
    }
  }
}