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

package com.normation.rudder.web.components.popup

import com.normation.rudder.services.servers.ServerSummaryService
import com.normation.rudder.domain.policies._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.services.queries.DynGroupService
import com.normation.rudder.services.policies._
import com.normation.utils.Control.sequence
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.exceptions.TechnicalException

//lift std import
import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.util._
import Helpers._
import net.liftweb.http.js._
import JsCmds._ // For implicits
import JE._
import net.liftweb.http.SHtml._

import org.joda.time.DateTime

import org.slf4j.LoggerFactory

import com.normation.rudder.domain.RudderDit
import com.normation.rudder.web.services.ServerGrid
import com.normation.rudder.web.components.ConfigurationRuleGrid
import bootstrap.liftweb.LiftSpringApplicationContext.inject


object ExpectedPolicyPopup {
   
  def expectedPolicyTemplatePath = List("templates-hidden", "Popup", "expected_policy_popup")
  def template() =  Templates(expectedPolicyTemplatePath) match {
    case Empty | Failure(_,_,_) => 
      throw new TechnicalException("Template for server grid not found. I was looking for %s.html".format(expectedPolicyTemplatePath.mkString("/")))
    case Full(n) => n
  }
  
  def expectedPolicyTemplate = chooseTemplate("expectedPolicyPopup","template",template)
  
  def jsVarNameForId(tableId:String) = "oTable" + tableId 
  
}

class ExpectedPolicyPopup(
  htmlId_popup:String, 
  serverId : NodeId
) extends DispatchSnippet with Loggable {
  import ExpectedPolicyPopup._
  
  private[this] val serverSummaryService = inject[ServerSummaryService]
  private[this] val dependenciesServices = inject[DependencyAndDeletionService]
  private[this] val dynGroupService = inject[DynGroupService]
  private[this] val acceptedServersDit = inject[InventoryDit]("acceptedServersDit")



  def dispatch = {
    case "display" => {_ => display }
  }


  def display : NodeSeq = {
    
    //find the list of dyn groups on which that server would be and from that, the configuration rules
    val rulesGrid : NodeSeq = configurationRules match {
      case Full(seq) => 
        (new ConfigurationRuleGrid("dependentRulesGrid", seq, None, false)).configurationRulesGrid(false)
      case e:EmptyBox => 
        val msg = "Error when trying to find dependencies for that group"
        logger.error(msg, e)
        <div class="error">{msg}</div>
    }
        
    (
        ClearClearable & 
        "#dependentRulesGrid" #> rulesGrid 
    )(bind("expectedPolicyPopup",expectedPolicyTemplate, 
      "server" -> displayServer(serverId), 
      "close" -> <button onClick="$.modal.close(); return false;">Close</button>
    ) ) 
  }
  
  
  private[this] val configurationRules:Box[Seq[ConfigurationRule]] = {
    for {
      groupMap <- dynGroupService.findDynGroups(Seq(serverId)) ?~! "Error when building the map of dynamic group to update by node"
      seqNodeGroupId = groupMap.get(serverId).getOrElse(Seq())
      seqTargetDeps <- sequence(seqNodeGroupId) { groupId => 
        dependenciesServices.targetDependencies(GroupTarget(groupId)) ?~! "Error when building the list of configuration rules depending on group %s".format(groupId)
      }
    } yield {
      seqTargetDeps.flatMap { case TargetDependencies(target, configurationRules) => configurationRules }.distinct
    }
  }
    
  
  private[this] def displayServer(serverId : NodeId) : NodeSeq = {
    serverSummaryService.find(acceptedServersDit,serverId) match {
      case Full(srv) => 
        srv.toList match {
          case Nil => <div>No server found</div>
          case head::Nil => Text(head.hostname + " - " +head.osName)
          case _ => logger.error("Too many nodes returned while searching server %s".format(serverId.value)) 
                  <p class="error">ERROR - Too many nodes</p>
        }
      case _ => <div>No node found</div>
    }
    
  }
  
  
}
