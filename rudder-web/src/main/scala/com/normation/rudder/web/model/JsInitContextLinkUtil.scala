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

package com.normation.rudder.web.model

import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.workflows.ChangeRequestId
import bootstrap.liftweb.RudderConfig
import net.liftweb.http.SHtml
import net.liftweb.http.S
import net.liftweb.common.Full
import scala.xml.Text
import net.liftweb.common.EmptyBox
import net.liftweb.common.Loggable
/**
 * That class helps user to create valide JS initialisation context
 * links for pages that support them (search, Directive,
 * Rule).
 */
object JsInitContextLinkUtil extends Loggable {

  private[this] val roRuleRepository      = RudderConfig.roRuleRepository
  private[this] val roGroupRepository     = RudderConfig.roNodeGroupRepository
  private[this] val roDirectiveRepository = RudderConfig.roDirectiveRepository

  def groupLink(id:NodeGroupId) =
    s"""/secure/nodeManager/groups#{"groupId":"${id.value}"}"""

  def ruleLink(id:RuleId) =
    s"""/secure/configurationManager/ruleManagement#{"ruleId":"${id.value}"}"""

  def directiveLink(id:DirectiveId) =
    s"""/secure/configurationManager/directiveManagement#{"directiveId":"${id.value}"}"""

  def nodeLink(id:NodeId) =
    s"""/secure/nodeManager/searchNodes#{"nodeId":"${id.value}s"}"""

  def changeRequestLink(id:ChangeRequestId) =
    s"/secure/utilities/changeRequest/${id}"

  def createRuleLink(id:RuleId) = {
    roRuleRepository.get(id) match {
      case Full(rule) => <span> {SHtml.a(() => S.redirectTo(ruleLink(id)), Text(rule.name))} (Rudder ID: {id.value.toUpperCase})</span>
      case eb:EmptyBox => val fail = eb ?~! s"Could not find Rule with Id ${id.value}"
        logger.error(fail.msg)
        <span> {id.value.toUpperCase} </span>
    }
  }

  def createGroupLink(id:NodeGroupId) = {
    roGroupRepository.getNodeGroup(id) match {
      case Full((group,_)) => <span> {SHtml.a(() => S.redirectTo(groupLink(id)), Text(group.name))} (Rudder ID: {id.value.toUpperCase})</span>
      case eb:EmptyBox => val fail = eb ?~! s"Could not find NodeGroup with Id ${id.value}"
        logger.error(fail.msg)
        <span> {id.value.toUpperCase} </span>
    }
  }

  def createDirectiveLink(id:DirectiveId) = {
    roDirectiveRepository.getDirective(id) match {
      case Full(directive) => <span> {SHtml.a(() => S.redirectTo(directiveLink(id)), Text(directive.name))} (Rudder ID: {id.value.toUpperCase})</span>
      case eb:EmptyBox => val fail = eb ?~! s"Could not find Directive with Id ${id.value}"
        logger.error(fail.msg)
        <span> {id.value.toUpperCase} </span>
    }
  }
}