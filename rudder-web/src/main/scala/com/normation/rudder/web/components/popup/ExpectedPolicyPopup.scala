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


import bootstrap.liftweb.RudderConfig
import com.normation.exceptions.TechnicalException
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.servers.Srv
import com.normation.rudder.web.components.RuleGrid
import net.liftweb.http.DispatchSnippet
import net.liftweb.common._
import net.liftweb.http.Templates
import net.liftweb.util.ClearClearable
import net.liftweb.util.Helpers._
import scala.xml.NodeSeq
import scala.xml.Text

object ExpectedPolicyPopup {

  def expectedTechniquePath = List("templates-hidden", "Popup", "expected_policy_popup")
  def template() =  Templates(expectedTechniquePath) match {
    case Empty | Failure(_,_,_) =>
      throw new TechnicalException("Template for server grid not found. I was looking for %s.html".format(expectedTechniquePath.mkString("/")))
    case Full(n) => n
  }

  def expectedTechnique = chooseTemplate("expectedpolicypopup","template",template)

  def jsVarNameForId(tableId:String) = "oTable" + tableId

}

class ExpectedPolicyPopup(
  htmlId_popup: String,
  nodeSrv     : Srv
) extends DispatchSnippet with Loggable {
  import ExpectedPolicyPopup._

  private[this] val ruleRepository  = RudderConfig.roRuleRepository
  private[this] val dynGroupService = RudderConfig.dynGroupService

  def dispatch = {
    case "display" => {_ => display }
  }

  def display : NodeSeq = {

    //find the list of dyn groups on which that server would be and from that, the Rules
    val rulesGrid : NodeSeq = getDependantRulesForNode match {
      case Full(seq) =>
        (new RuleGrid("dependentRulesGrid", None, false)).rulesGridWithUpdatedInfo(Some(seq), false, false)
      case e:EmptyBox =>
        val msg = "Error when trying to find dependencies for that group"
        logger.error(msg, e)
        <div class="error">{msg}</div>
    }

    (
        ClearClearable &
        "#dependentRulesGrid" #> rulesGrid
    )(bind("expectedPolicyPopup",expectedTechnique,
      "node" -> displayNode(nodeSrv),
      "close" -> <button onClick="$.modal.close(); return false;">Close</button>
    ) )
  }

  private[this] val getDependantRulesForNode:Box[Seq[Rule]] = {
    for {
      dynGroup     <- dynGroupService.findDynGroups(Seq(nodeSrv.id)) ?~! "Error when building the map of dynamic group to update by node"
      groupTargets =  dynGroup.getOrElse(nodeSrv.id, Seq())
      rules        <- ruleRepository.getAll(includeSytem = false)
    } yield {
      val allNodes = Map( (nodeSrv.id , (nodeSrv.isPolicyServer, nodeSrv.serverRoles)) )
      val groups = groupTargets.map { x => (x, Set(nodeSrv.id)) }.toMap

      rules.filter { r =>
        RuleTarget.getNodeIds(r.targets, allNodes, groups).nonEmpty
      }
    }
  }

  private[this] def displayNode(srv : Srv) : NodeSeq = {
    Text(srv.hostname + " - " + srv.osFullName)
  }

}
