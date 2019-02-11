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

package com.normation.rudder.web.components.popup

import bootstrap.liftweb.RudderConfig
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.domain.servers.Srv
import com.normation.rudder.web.ChooseTemplate
import com.normation.rudder.web.components.DisplayColumn
import com.normation.rudder.web.components.RuleGrid
import net.liftweb.common._
import net.liftweb.http.DispatchSnippet
import net.liftweb.util.ClearClearable
import net.liftweb.util.Helpers._

import scala.xml.NodeSeq
import scala.xml.Text

import com.normation.box._

object ExpectedPolicyPopup {

  def expectedTechnique = ChooseTemplate(
      List("templates-hidden", "Popup", "expected_policy_popup")
    , "expectedpolicypopup-template"
  )

  def jsVarNameForId(tableId:String) = "oTable" + tableId

}

class ExpectedPolicyPopup(
  htmlId_popup: String,
  nodeSrv     : Srv
) extends DispatchSnippet with Loggable {
  import ExpectedPolicyPopup._

  private[this] val ruleRepository  = RudderConfig.roRuleRepository
  private[this] val dynGroupService = RudderConfig.dynGroupService
  private[this] val checkDynGroup   = RudderConfig.pendingNodeCheckGroup

  def dispatch = {
    case "display" => {_ => display }
  }

  def display : NodeSeq = {
    //find the list of dyn groups on which that server would be and from that, the Rules
    val rulesGrid : NodeSeq = getDependantRulesForNode match {
      case Full(seq) =>
        val noDisplay = DisplayColumn.Force(false)
        (new RuleGrid("dependentRulesGrid", None, false, None, noDisplay, noDisplay)).rulesGridWithUpdatedInfo(Some(seq), false, true)
      case e:EmptyBox =>
        val msg = "Error when trying to find dependencies for that group"
        logger.error(msg, e)
        <div class="error">{msg}</div>
    }

    (
        ClearClearable
      & "#dependentRulesGrid" #> rulesGrid
      & "expectedpolicypopup-node" #> displayNode(nodeSrv)
      & "expectedpolicypopup-os" #> displayNodeOs(nodeSrv)
      & "expectedpolicypopup:close" #> <button onClick="$.modal.close(); return false;">Close</button>
    )(expectedTechnique)
  }

  private[this] val getDependantRulesForNode:Box[Seq[Rule]] = {
    for {
      allDynGroups <- dynGroupService.getAllDynGroups()
      dynGroups    <- checkDynGroup.findDynGroups(Set(nodeSrv.id), allDynGroups.toList) ?~! "Error when building the map of dynamic group to update by node"
      groupTargets =  dynGroups.getOrElse(nodeSrv.id, Seq())
      rules        <- ruleRepository.getAll(includeSytem = false).toBox
    } yield {
      val allNodes = Map( (nodeSrv.id , (nodeSrv.isPolicyServer, nodeSrv.serverRoles)) )
      val groups = groupTargets.map { x => (x, Set(nodeSrv.id)) }.toMap

      rules.filter { r =>
        RuleTarget.getNodeIds(r.targets, allNodes, groups).nonEmpty
      }
    }
  }

  private[this] def displayNode(srv : Srv) : NodeSeq = {
    Text(srv.hostname)
  }
  private[this] def displayNodeOs(srv : Srv) : NodeSeq = {
    Text(srv.osFullName)
  }
}
