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

class DatabaseManagement extends DispatchSnippet with Loggable {

  private[this] val databaseManager = inject[DatabaseManager]
  
  def dispatch = {
    case "display" => display
  }
  
  def display(xml:NodeSeq) : NodeSeq = {
    val reportsInterval = databaseManager.getReportsInterval()
    val archivedReportsInterval = databaseManager.getArchivedReportsInterval()
    (  
      "#oldestEntry" #> displayDate(reportsInterval.map( x => x._1 )) &
      "#newestEntry" #> displayDate(reportsInterval.map( x => x._2 )) &
      "#databaseSize" #> databaseManager.getDatabaseSize().map(x => Text(MemorySize(x).toStringMo())).openOr(Text("could not fetch")) &
      "#oldestArchivedEntry" #> displayDate(archivedReportsInterval.map( x => x._1 )) &
      "#newestArchivedEntry" #> displayDate(archivedReportsInterval.map( x => x._2 )) 
    )(xml)
  }
  
  private[this] def displayDate( entry : Box[DateTime]) : NodeSeq= {
    entry.
      map ( x => <span>{DateFormaterService.getFormatedDate(x)}</span> ).
      openOr( <span>Could not fetch the value</span>)
  }
}