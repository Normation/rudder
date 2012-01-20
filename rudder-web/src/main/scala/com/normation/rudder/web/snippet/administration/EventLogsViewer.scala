/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

import scala.xml._
import net.liftweb.http.{DispatchSnippet,S}
import net.liftweb.common._
import net.liftweb.util._
import net.liftweb.util.Helpers._
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import com.normation.rudder.repository.EventLogRepository
import com.normation.eventlog.EventLog
import com.normation.rudder.domain.log._
import com.normation.rudder.web.components.DateFormaterService
import net.liftweb.http.js._
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.SHtml
import com.normation.rudder.services.log.EventLogDetailsService
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.cfclerk.domain.PolicyPackageName
import com.normation.rudder.domain.queries.Query
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.log.InventoryLogDetails
import com.normation.rudder.web.model.JsInitContextLinkUtil._
import com.normation.rudder.web.services.EventListDisplayer

class EventLogsViewer extends DispatchSnippet with Loggable {
  private[this] val repos = inject[EventLogRepository]
  private[this] val logDetailsService = inject[EventLogDetailsService]
  private[this] val eventList = inject[EventListDisplayer]
  private[this] val xmlPretty = new scala.xml.PrettyPrinter(80, 2)
  
  private[this] val gridName = "eventLogsGrid"
  
  def getLastEvents : Box[Seq[EventLog]] = {
    repos.getEventLogByCriteria(None, Some(1000), Some("id DESC")) 
  }
    
  def dispatch = { 
    case "display" => xml => getLastEvents match {
      case Full(seq) => eventList.display(seq,gridName) ++ Script(eventList.initJs(gridName))
      case Empty => eventList.display(Seq(), gridName) ++ Script(eventList.initJs(gridName))
      case f:Failure => 
        <div class="error">Error when trying to get last event logs. Error message was: {f.msg}</div>
    } 
  } 

}
