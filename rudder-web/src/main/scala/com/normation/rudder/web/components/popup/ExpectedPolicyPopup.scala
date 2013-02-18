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

import com.normation.rudder.services.servers.NodeSummaryService
import com.normation.rudder.domain.policies._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.services.queries.DynGroupService
import com.normation.rudder.services.policies._
import com.normation.utils.Control.sequence
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.exceptions.TechnicalException
import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.util._
import Helpers._
import net.liftweb.http.js._
import JsCmds._
import JE._
import net.liftweb.http.SHtml._
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.web.services.NodeGrid
import com.normation.rudder.web.components.RuleGrid
import bootstrap.liftweb.RudderConfig


object ExpectedPolicyPopup {

  def expectedTechniquePath = List("templates-hidden", "Popup", "expected_policy_popup")
  def template() =  Templates(expectedTechniquePath) match {
    case Empty | Failure(_,_,_) =>
      throw new TechnicalException("Template for server grid not found. I was looking for %s.html".format(expectedTechniquePath.mkString("/")))
    case Full(n) => n
  }

  def expectedTechnique = chooseTemplate("expectedPolicyPopup","template",template)

  def jsVarNameForId(tableId:String) = "oTable" + tableId

}

class ExpectedPolicyPopup(
  htmlId_popup:String,
  nodeId : NodeId
) extends DispatchSnippet with Loggable {
  import ExpectedPolicyPopup._

  private[this] val serverSummaryService = RudderConfig.nodeSummaryService
  private[this] val dependenciesServices = RudderConfig.dependencyAndDeletionService
  private[this] val dynGroupService      = RudderConfig.dynGroupService
  private[this] val pendingNodesDit      = RudderConfig.pendingNodesDit



  def dispatch = {
    case "display" => {_ => display }
  }


  def display : NodeSeq = {

    //find the list of dyn groups on which that server would be and from that, the Rules
    val rulesGrid : NodeSeq = rules match {
      case Full(seq) =>
        (new RuleGrid("dependentRulesGrid", seq, None, false)).rulesGrid(popup = true,false)
      case e:EmptyBox =>
        val msg = "Error when trying to find dependencies for that group"
        logger.error(msg, e)
        <div class="error">{msg}</div>
    }

    (
        ClearClearable &
        "#dependentRulesGrid" #> rulesGrid
    )(bind("expectedPolicyPopup",expectedTechnique,
      "node" -> displayNode(nodeId),
      "close" -> <button onClick="$.modal.close(); return false;">Close</button>
    ) )
  }


  private[this] val rules:Box[Seq[Rule]] = {
    for {
      groupMap <- dynGroupService.findDynGroups(Seq(nodeId)) ?~! "Error when building the map of dynamic group to update by node"
      seqNodeGroupId = groupMap.get(nodeId).getOrElse(Seq())
      seqTargetDeps <- sequence(seqNodeGroupId) { groupId =>
        dependenciesServices.targetDependencies(GroupTarget(groupId)) ?~! "Error when building the list of Rules depending on group %s".format(groupId)
      }
    } yield {
      seqTargetDeps.flatMap { case TargetDependencies(target, rules) => rules }.distinct
    }
  }


  private[this] def displayNode(nodeId : NodeId) : NodeSeq = {
    serverSummaryService.find(pendingNodesDit,nodeId) match {
      case Full(srv) =>
        srv.toList match {
          case Nil => <div>Node not found</div>
          case head::Nil => Text(head.hostname + " - " +head.osFullName)
          case _ => logger.error("Too many nodes returned while searching server %s".format(nodeId.value))
                  <p class="error">ERROR - Too many nodes</p>
        }
      case _ => <div>No node found</div>
    }

  }


}
