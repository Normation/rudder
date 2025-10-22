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

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.RoRuleRepository
import com.normation.zio.*
import net.liftweb.common.Loggable
import net.liftweb.http.S
import net.liftweb.http.js.JsCmd
import net.liftweb.http.js.JsCmds.RedirectTo
import net.liftweb.util.StringHelpers.encJs
import scala.xml.Elem

/**
 * That class helps user to create valide JS initialisation context
 * links for pages that support them (search, Directive,
 * Rule).
 * Link are constructed with a private definition, which is the actual base path to the page (starting by /)
 * and then we have two functions, the link, with the context, and the JsCmd to redirect to this page
 * without the Context, as the JS already embed the context in its redirect
 */
class LinkUtil(
    roRuleRepository:      RoRuleRepository,
    roGroupRepository:     RoNodeGroupRepository,
    roDirectiveRepository: RoDirectiveRepository,
    nodeFactRepo:          NodeFactRepository
) extends Loggable {
  def baseGroupLink(id: NodeGroupId): String =
    s"""/secure/nodeManager/groups#{"groupId":"${id.serialize}"}"""

  def baseTargetLink(target: RuleTarget): String =
    s"""/secure/nodeManager/groups#{"target":"${target.target}"}"""

  def groupLink(id: NodeGroupId): String =
    s"""${S.contextPath}${baseGroupLink(id)}"""

  def targetLink(target: RuleTarget): String =
    s"""${S.contextPath}${baseTargetLink(target)}"""

  def redirectToGroupLink(id: NodeGroupId): JsCmd =
    RedirectTo(baseGroupLink(id))

  def redirectToTargteLink(target: RuleTarget): JsCmd =
    RedirectTo(baseTargetLink(target))

  def baseRuleLink(id: RuleId): String =
    s"""/secure/configurationManager/ruleManagement/rule/${id.serialize}"""

  def ruleLink(id: RuleId): String =
    s"""${S.contextPath}${baseRuleLink(id)}"""

  def redirectToRuleLink(id: RuleId): JsCmd =
    RedirectTo(baseRuleLink(id))

  def baseDirectiveLink(id: DirectiveUid): String =
    s"""/secure/configurationManager/directiveManagement#{"directiveId":"${id.value}"}"""

  def directiveLink(id: DirectiveUid): String =
    s"""${S.contextPath}${baseDirectiveLink(id)}"""

  def baseTechniqueLink(id: String): String =
    s"""/secure/configurationManager/techniqueEditor/technique/${id}"""

  def techniqueLink(id: String): String =
    s"""${S.contextPath}${baseTechniqueLink(id)}"""

  def redirectToDirectiveLink(id: DirectiveUid): JsCmd =
    RedirectTo(baseDirectiveLink(id))

  def baseNodeLink(id: NodeId): String =
    s"""/secure/nodeManager/node/${id.value}"""

  def nodeLink(id: NodeId): String =
    s"""${S.contextPath}${baseNodeLink(id)}"""

  def redirectToNodeLink(id: NodeId): JsCmd =
    RedirectTo(baseNodeLink(id))

  def baseGlobalParameterLink(name: String): String =
    s"/secure/configurationManager/parameterManagement"

  def globalParameterLink(name: String): String =
    s"${S.contextPath}${baseGlobalParameterLink(name)}"

  def redirectToGlobalParameterLink(name: String): JsCmd =
    RedirectTo(baseGlobalParameterLink(name))

  def baseChangeRequestLink(id: ChangeRequestId): String =
    s"/secure/configurationManager/changes/changeRequest/${id.value}"

  def changeRequestLink(id: ChangeRequestId, contextPath: String = S.contextPath): String =
    s"${contextPath}${baseChangeRequestLink(id)}"

  def redirectToChangeRequestLink(id: ChangeRequestId, contextPath: String = S.contextPath): JsCmd = {
    RedirectToFullPath(changeRequestLink(id, contextPath))
  }

  case class RedirectToFullPath(where: String) extends JsCmd {
    override def toJsCmd: String = s"window.location = ${encJs(where)} ;"
  }

  def createRuleLink(id: RuleId): Elem = {
    roRuleRepository.get(id).either.runNow match {
      case Right(rule) => <span> <a href={baseRuleLink(id)}>{rule.name}</a> (Rudder ID: {id.serialize})</span>
      case Left(err)   =>
        logger.error(s"Could not find Rule with Id ${id.serialize}. Error was: ${err.fullMsg}")
        <span> {id.serialize} </span>
    }
  }

  def createGroupLink(id: NodeGroupId)(implicit qc: QueryContext): Elem = {
    roGroupRepository.getNodeGroup(id).either.runNow match {
      case Right((group, _)) => <span> <a href={baseGroupLink(id)}>{group.name}</a> (Rudder ID: {id.serialize})</span>
      case Left(err)         =>
        logger.error(s"Could not find NodeGroup with Id ${id.serialize}. Error was: ${err.fullMsg}")
        <span> {id.serialize} </span>
    }
  }

  def createDirectiveLink(id: DirectiveUid): Elem = {
    roDirectiveRepository.getDirective(id).either.runNow match {
      case Right(Some(directive)) => <span> <a href={baseDirectiveLink(id)}>{directive.name}</a> (Rudder ID: {id.value})</span>
      case Right(None)            =>
        <span> {id.value} </span>
      case Left(err)              =>
        logger.error(s"Could not find Directive with Id ${id.value}. Error was: ${err.fullMsg}")
        <span> {id.value} </span>
    }
  }

  def createNodeLink(id: NodeId)(implicit qc: QueryContext): Elem = {
    nodeFactRepo.get(id).either.runNow match {
      case Right(Some(node)) =>
        <span>Node <a href={baseNodeLink(id)}>{node.fqdn}</a> (Rudder ID: {id.value})</span>
      case _                 =>
        <span>Node {id.value}</span>
    }
  }
  // Naive implementation that redirect simply to all Global Parameter page
  def createGlobalParameterLink(name: String):               Elem = {
    <span> <a href={baseGlobalParameterLink(name)}>{name}</a></span>
  }
}
