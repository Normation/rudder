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

package com.normation.rudder.web.model

import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.workflows.ChangeRequestId
import net.liftweb.http.S
import net.liftweb.common.Full
import net.liftweb.common.EmptyBox
import net.liftweb.common.Loggable
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.rudder.domain.policies.RuleTarget
import net.liftweb.http.js.JsCmds.RedirectTo
import net.liftweb.http.js.JsCmd
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.services.nodes.NodeInfoService

/**
 * That class helps user to create valide JS initialisation context
 * links for pages that support them (search, Directive,
 * Rule).
 * Link are constructed with a private definition, which is the actual base path to the page (starting by /)
 * and then we have two functions, the link, with the context, and the JsCmd to redirect to this page
 * without the Context, as the JS already embed the context in its redirect
 */
class LinkUtil (
    roRuleRepository      : RoRuleRepository
  , roGroupRepository     : RoNodeGroupRepository
  , roDirectiveRepository : RoDirectiveRepository
  , nodeInfoService       : NodeInfoService
) extends Loggable {
  def baseGroupLink(id:NodeGroupId) =
    s"""/secure/nodeManager/groups#{"groupId":"${id.value}"}"""

  def baseTargetLink(target: RuleTarget) =
    s"""/secure/nodeManager/groups#{"target":"${target.target}"}"""

  def groupLink(id:NodeGroupId) =
    s"""${S.contextPath}${baseGroupLink(id)}"""

  def targetLink(target: RuleTarget) =
    s"""${S.contextPath}${baseTargetLink(target)}"""

  def redirectToGroupLink(id:NodeGroupId) : JsCmd=
    RedirectTo(baseGroupLink(id))

  def redirectToTargteLink(target:RuleTarget) : JsCmd=
    RedirectTo(baseTargetLink(target))

  def baseRuleLink(id:RuleId) =
    s"""/secure/configurationManager/ruleManagement#{"ruleId":"${id.value}"}"""

  def ruleLink(id:RuleId) =
    s"""${S.contextPath}${baseRuleLink(id)}"""

  def redirectToRuleLink(id:RuleId) : JsCmd =
    RedirectTo(baseRuleLink(id))

  def baseDirectiveLink(id:DirectiveId) =
    s"""/secure/configurationManager/directiveManagement#{"directiveId":"${id.value}"}"""

  def directiveLink(id:DirectiveId) =
    s"""${S.contextPath}${baseDirectiveLink(id)}"""

  def redirectToDirectiveLink(id:DirectiveId) : JsCmd =
    RedirectTo(baseDirectiveLink(id))

  def baseNodeLink(id:NodeId) =
    s"""/secure/nodeManager/searchNodes#{"nodeId":"${id.value}"}"""

  def nodeLink(id:NodeId) =
    s"""${S.contextPath}${baseNodeLink(id)}"""

  def redirectToNodeLink(id:NodeId) : JsCmd =
     RedirectTo(baseNodeLink(id))

  def baseGlobalParameterLink(name:ParameterName) =
    s"/secure/configurationManager/parameterManagement"

  def globalParameterLink(name:ParameterName) =
    s"${S.contextPath}${baseGlobalParameterLink(name)}"

  def redirectToGlobalParameterLink(name:ParameterName) : JsCmd =
     RedirectTo(baseGlobalParameterLink(name))

  def baseChangeRequestLink(id:ChangeRequestId) =
    s"/secure/plugins/changes/changeRequest/${id}"

  def changeRequestLink(id:ChangeRequestId) =
    s"${S.contextPath}${baseChangeRequestLink(id)}"

  def redirectToChangeRequestLink(id:ChangeRequestId) : JsCmd =
     RedirectTo(baseChangeRequestLink(id))

  def createRuleLink(id:RuleId) = {
    roRuleRepository.get(id) match {
      case Full(rule) => <span> <a href={baseRuleLink(id)}>{rule.name}</a> (Rudder ID: {id.value})</span>
      case eb:EmptyBox => val fail = eb ?~! s"Could not find Rule with Id ${id.value}"
        logger.error(fail.msg)
        <span> {id.value} </span>
    }
  }

  def createGroupLink(id:NodeGroupId) = {
    roGroupRepository.getNodeGroup(id) match {
      case Full((group,_)) => <span> <a href={baseGroupLink(id)}>{group.name}</a> (Rudder ID: {id.value})</span>
      case eb:EmptyBox => val fail = eb ?~! s"Could not find NodeGroup with Id ${id.value}"
        logger.error(fail.msg)
        <span> {id.value} </span>
    }
  }

  def createDirectiveLink(id:DirectiveId) = {
    roDirectiveRepository.getDirective(id) match {
      case Full(directive) => <span> <a href={baseDirectiveLink(id)}>{directive.name}</a> (Rudder ID: {id.value})</span>
      case eb:EmptyBox => val fail = eb ?~! s"Could not find Directive with Id ${id.value}"
        logger.error(fail.msg)
        <span> {id.value} </span>
    }
  }

  def createNodeLink(id: NodeId) = {
    nodeInfoService.getNodeInfo(id) match {
      case Full(Some(node)) =>
        <span>Node <a href={baseNodeLink(id)}>{node.hostname}</a> (Rudder ID: {id.value})</span>
      case _ =>
        <span>Node {id.value}</span>
    }
  }
  // Naive implementation that redirect simply to all Global Parameter page
  def createGlobalParameterLink(name:ParameterName) = {
      <span> <a href={baseGlobalParameterLink(name)}>{name}</a></span>
  }
}
