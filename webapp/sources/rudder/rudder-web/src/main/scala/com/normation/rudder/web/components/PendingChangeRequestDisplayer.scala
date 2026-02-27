/*
 *************************************************************************************
 * Copyright 2013 Normation SAS
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

package com.normation.rudder.web.components

import bootstrap.liftweb.RudderConfig
import com.normation.box.*
import com.normation.errors.IOResult
import com.normation.rudder.AuthorizationType
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.workflows.ChangeRequest
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.zio.UnsafeRun
import net.liftweb.common.Loggable
import net.liftweb.util.Helpers.*
import scala.xml.NodeSeq

/*
 * This object is just a service that check if a given rule/directive/etc has
 * already a change request on it to display a relevant information if it is
 * the case.
 */
object PendingChangeRequestDisplayer extends Loggable {

  private val workflowLevel = RudderConfig.workflowLevelService
  private val linkUtil      = RudderConfig.linkUtil

  private def displayPendingChangeRequest(
      xml:         NodeSeq,
      crs:         IOResult[Seq[ChangeRequest]],
      checkRights: AuthorizationType => Boolean
  )(using qc: QueryContext): NodeSeq = {

    val hasRights   = checkRights(AuthorizationType.Validator.Read) ||
      checkRights(AuthorizationType.Deployer.Read)
    val curUserName = qc.actor.name

    crs.chainError("Error when trying to lookup pending change request").either.runNow match {
      case Left(err)                   =>
        logger.error(err.fullMsg)
        <span class="error">{err.fullMsg}</span>
      case Right(crs) if (crs.isEmpty) => NodeSeq.Empty
      case Right(crs)                  =>
        val pendingChangeRequestLink = crs.foldLeft(NodeSeq.Empty) { (res, cr) =>
          res ++ {
            if (hasRights || cr.owner == curUserName) {
              <li><a href={linkUtil.baseChangeRequestLink(cr.id)}>CR #{cr.id}: {cr.info.name}</a></li>
            } else {
              <li>CR #{cr.id}</li>
            }
          }
        }
        ("#changeRequestList *+" #> pendingChangeRequestLink).apply(xml)
    }
  }

  private type checkFunction[T] = (T, Boolean) => IOResult[Seq[ChangeRequest]]

  private def checkChangeRequest[T](
      xml:         NodeSeq,
      id:          T,
      check:       checkFunction[T],
      checkRights: AuthorizationType => Boolean
  )(using qc: QueryContext): NodeSeq = {
    if (RudderConfig.configService.rudder_workflow_enabled().toBox.getOrElse(false)) {
      val crs = check(id, true)
      displayPendingChangeRequest(xml, crs, checkRights)
    } else {
      // Workflow disabled, nothing to display
      NodeSeq.Empty
    }
  }

  def checkByRule(xml: NodeSeq, ruleId: RuleUid, checkRights: AuthorizationType => Boolean)(using qc: QueryContext): NodeSeq = {
    checkChangeRequest(xml, ruleId, workflowLevel.getByRule, checkRights)
  }

  def checkByGroup(xml: NodeSeq, groupId: NodeGroupId, checkRights: AuthorizationType => Boolean)(using
      qc: QueryContext
  ): NodeSeq = {
    checkChangeRequest(xml, groupId, workflowLevel.getByNodeGroup, checkRights)
  }

  def checkByDirective(xml: NodeSeq, directiveId: DirectiveUid, checkRights: AuthorizationType => Boolean)(using
      qc: QueryContext
  ): NodeSeq = {
    checkChangeRequest(xml, directiveId, workflowLevel.getByDirective, checkRights)
  }
}
