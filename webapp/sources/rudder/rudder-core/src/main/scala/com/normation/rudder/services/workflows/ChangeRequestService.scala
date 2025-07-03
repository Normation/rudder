/*
 *************************************************************************************
 * Copyright 2011-2013 Normation SAS
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

package com.normation.rudder.services.workflows

import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.TechniqueName
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.domain.nodes.ChangeRequestNodeGroupDiff
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.policies.ChangeRequestDirectiveDiff
import com.normation.rudder.domain.policies.ChangeRequestRuleDiff
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.ModifyToRuleDiff
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.properties.ChangeRequestGlobalParameterDiff
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.workflows.*
import org.joda.time.DateTime

/**
 * A service that handle all the logic about how
 * a change request is created from basic parts.
 *
 * It does nothing
 */
object ChangeRequestService {

  def createChangeRequestFromDirective(
      changeRequestName: String,
      changeRequestDesc: String,
      techniqueName:     TechniqueName,
      rootSection:       Option[SectionSpec],
      directiveId:       DirectiveId,
      originalDirective: Option[Directive],
      diff:              ChangeRequestDirectiveDiff,
      actor:             EventActor,
      reason:            Option[String]
  ): ConfigurationChangeRequest = {

    val initialState  = originalDirective match {
      case None    => None
      case Some(x) => Some((techniqueName, x, rootSection))
    }
    val change        = DirectiveChange(
      initialState = initialState,
      firstChange = DirectiveChangeItem(actor, DateTime.now, reason, diff),
      Seq()
    )
    val changeRequest = ConfigurationChangeRequest(
      ChangeRequestId(0),
      None,
      ChangeRequestInfo(
        changeRequestName,
        changeRequestDesc
      ),
      Map(directiveId -> DirectiveChanges(change, Seq())),
      Map(),
      Map(),
      Map()
    )
    changeRequest
  }

  private def rulechange(
      baseRule:  Rule,
      finalRule: Rule,
      actor:     EventActor,
      reason:    Option[String]
  ) = {
    val change = RuleChanges(
      RuleChange(
        Some(baseRule),
        RuleChangeItem(actor, DateTime.now, reason, ModifyToRuleDiff(finalRule)),
        Seq()
      ),
      Seq()
    )
    baseRule.id -> change
  }

  def createChangeRequestFromDirectiveAndRules(
      changeRequestName: String,
      changeRequestDesc: String,
      techniqueName:     TechniqueName,
      rootSection:       Option[SectionSpec],
      directiveId:       DirectiveId,
      originalDirective: Option[Directive],
      diff:              ChangeRequestDirectiveDiff,
      actor:             EventActor,
      reason:            Option[String],
      rulesToUpdate:     List[Rule],
      updatedRules:      List[Rule]
  ): ChangeRequest = {

    val initialState = originalDirective match {
      case None    => None
      case Some(x) => Some((techniqueName, x, rootSection))
    }

    val change        = DirectiveChange(
      initialState = initialState,
      firstChange = DirectiveChangeItem(actor, DateTime.now, reason, diff),
      Seq()
    )
    val rulesChanges  = (rulesToUpdate zip updatedRules).map(r => rulechange(r._1, r._2, actor, reason)).toMap
    val changeRequest = ConfigurationChangeRequest(
      ChangeRequestId(0),
      None,
      ChangeRequestInfo(
        changeRequestName,
        changeRequestDesc
      ),
      Map(directiveId -> DirectiveChanges(change, Seq())),
      Map(),
      rulesChanges,
      Map()
    )
    ApplicationLogger.trace(s"New directive and rule change request: ${changeRequest}")
    changeRequest
  }

  def createChangeRequestFromRules(
      changeRequestName: String,
      changeRequestDesc: String,
      actor:             EventActor,
      reason:            Option[String],
      rulesToUpdate:     List[Rule],
      updatedRules:      List[Rule]
  ): ChangeRequest = {
    val rulesChanges  = (rulesToUpdate zip updatedRules).map(r => rulechange(r._1, r._2, actor, reason)).toMap
    val changeRequest = ConfigurationChangeRequest(
      ChangeRequestId(0),
      None,
      ChangeRequestInfo(
        changeRequestName,
        changeRequestDesc
      ),
      Map(),
      Map(),
      rulesChanges,
      Map()
    )
    ApplicationLogger.trace(s"New rules change request: ${changeRequest}")
    changeRequest
  }

  def createChangeRequestFromRule(
      changeRequestName: String,
      changeRequestDesc: String,
      rule:              Rule,
      originalRule:      Option[Rule],
      diff:              ChangeRequestRuleDiff,
      actor:             EventActor,
      reason:            Option[String]
  ): ChangeRequest = {
    val change        = RuleChange(
      initialState = originalRule,
      firstChange = RuleChangeItem(actor, DateTime.now, reason, diff),
      Seq()
    )
    val changeRequest = ConfigurationChangeRequest(
      ChangeRequestId(0),
      None,
      ChangeRequestInfo(
        changeRequestName,
        changeRequestDesc
      ),
      Map(),
      Map(),
      Map(rule.id -> RuleChanges(change, Seq())),
      Map()
    )
    ApplicationLogger.trace(s"New rule change request: ${changeRequest}")
    changeRequest
  }

  def createChangeRequestFromNodeGroup(
      changeRequestName: String,
      changeRequestDesc: String,
      nodeGroup:         NodeGroup,
      originalNodeGroup: Option[NodeGroup],
      diff:              ChangeRequestNodeGroupDiff,
      actor:             EventActor,
      reason:            Option[String]
  ): ChangeRequest = {

    val change        = NodeGroupChange(
      initialState = originalNodeGroup,
      firstChange = NodeGroupChangeItem(actor, DateTime.now, reason, diff),
      Seq()
    )
    val changeRequest = ConfigurationChangeRequest(
      ChangeRequestId(0),
      None,
      ChangeRequestInfo(
        changeRequestName,
        changeRequestDesc
      ),
      Map(),
      Map(nodeGroup.id -> NodeGroupChanges(change, Seq())),
      Map(),
      Map()
    )
    ApplicationLogger.trace(s"New node group change request: ${changeRequest}")
    changeRequest
  }

  def createChangeRequestFromGlobalParameter(
      changeRequestName:   String,
      changeRequestDesc:   String,
      globalParam:         GlobalParameter,
      originalGlobalParam: Option[GlobalParameter],
      diff:                ChangeRequestGlobalParameterDiff,
      actor:               EventActor,
      reason:              Option[String]
  ): ChangeRequest = {
    val change        = GlobalParameterChange(
      initialState = originalGlobalParam,
      firstChange = GlobalParameterChangeItem(actor, DateTime.now, reason, diff),
      Seq()
    )
    val changeRequest = ConfigurationChangeRequest(
      ChangeRequestId(0),
      None,
      ChangeRequestInfo(
        changeRequestName,
        changeRequestDesc
      ),
      Map(),
      Map(),
      Map(),
      Map(globalParam.name -> GlobalParameterChanges(change, Seq()))
    )
    ApplicationLogger.trace(s"New global parameter change request: ${changeRequest}")
    changeRequest
  }
}
