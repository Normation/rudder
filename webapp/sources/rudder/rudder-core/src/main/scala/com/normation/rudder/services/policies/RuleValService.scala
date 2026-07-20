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

package com.normation.rudder.services.policies

import com.normation.cfclerk.domain.*
import com.normation.errors.*
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.logger.PolicyGenerationLoggerPure
import com.normation.rudder.domain.nodes.NodeAndServerIds
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.tenants.TenantAccessGrant
import java.time.Instant
import zio.syntax.*

trait RuleValService {
  def buildRuleVal(
      rule:         Rule,
      directiveLib: FullActiveTechniqueCategory,
      groupLib:     FullNodeGroupCategory,
      // per-node info (is-policy-server + tenant security tag) used to resolve targets and enforce the
      // tenant boundary at generation time
      nodes:        Map[NodeId, NodeSecurityInfo]
  ): IOResult[RuleVal]

  def lookupNodeParameterization(
      variables: Map[ComponentId, Variable]
  ): InterpolationContext => IOResult[Map[ComponentId, Variable]]
}

class RuleValServiceImpl(
    interpolatedValueCompiler: InterpolatedValueCompiler
) extends RuleValService {

  private val logger = PolicyGenerationLoggerPure

  /*
   * return variable merged when they have the same component name & reportid.
   */
  private def buildVariables(
      variableSpecs: Seq[(List[SectionSpec], VariableSpec)],
      context:       Map[String, Seq[String]]
  ): Map[ComponentId, Variable] = {
    variableSpecs.map {
      case (parents, spec) =>
        // be careful, component id are not defined at the varspec level, but in its parent spec one
        val reportId = spec.id.orElse(parents.headOption.flatMap(_.id))
        context.get(spec.name) match {
          case None            =>
            (ComponentId(spec.name, parents.map(_.name), reportId), spec.toVariable())
          case Some(seqValues) =>
            val newVar = spec.toVariable(seqValues)
            assert(seqValues.toSet == newVar.values.toSet)
            (ComponentId(spec.name, parents.map(_.name), reportId), newVar)
        }
    }.toMap
  }

  /*
   * From a sequence of variable, look at the variable's value (because it's where
   * interpolation is) and build the function that, given an interpolation context,
   * give (on success) the string with expansion done.
   *
   * We must exclude variable from ncf technique, that are processed differently, because
   * the provided value is not managed by rudder (it is on a cfengine file elsewhere).
   */
  override def lookupNodeParameterization(
      variables: Map[ComponentId, Variable]
  ): InterpolationContext => IOResult[Map[ComponentId, Variable]] = { (context: InterpolationContext) =>
    variables.accumulate {
      case (key, variable) =>
        variable.spec match {
          // do not touch ncf variables
          case _: PredefinedValuesVariableSpec => (key, variable).succeed
          case _ =>
            (variable.values.accumulate { value =>
              for {
                parsed  <- interpolatedValueCompiler.compile(value).toIO
                // can lead to stack overflow, no ?
                applied <- parsed(context)
              } yield {
                applied
              }
            }).chainError(s"On variable '${variable.spec.name}':").map(seq => (key, Variable.matchCopy(variable, seq)))
        }
    }.map(_.toMap)
  }

  def getParsedPolicyDraft(
      id:           DirectiveId,
      ruleId:       RuleId,
      ruleOrder:    BundleOrder,
      ruleName:     String,
      directiveLib: FullActiveTechniqueCategory
  ): IOResult[Option[ParsedPolicyDraft]] = {
    directiveLib.allDirectives.get(id) match {
      case None                                                               =>
        Inconsistency(
          s"Cannot find directive with id '${id.debugString}' when building rule '${ruleOrder.value}' (${ruleId.serialize})"
        ).fail
      case Some((_, directive)) if !(directive.isEnabled)                     =>
        logger.debug(
          s"The Directive with id ${id.debugString} is disabled and we don't generate a ParsedPolicyDraft for Rule '${ruleId.serialize}'"
        ) *> None.succeed
      case Some((fullActiveDirective, _)) if !(fullActiveDirective.isEnabled) =>
        logger.debug(
          s"The Active Technique with id ${fullActiveDirective.id.value} is disabled and we don't generate a ParsedPolicyDraft for Rule ${ruleId.serialize}"
        ) *> None.succeed
      case Some((fullActiveTechnique, directive))                             =>
        for {
          technique                    <-
            fullActiveTechnique.techniques
              .get(directive.techniqueVersion)
              .notOptional(
                s"Version '${directive.techniqueVersion.debugString}' of technique '${fullActiveTechnique.techniqueName.value}' is not available for directive '${directive.name}' [${directive.id.uid.value}]"
              )
          varSpecs                      = technique.rootSection.getAllVariablesBySection(Nil) ++ technique.systemVariableSpecs
                                            .map((Nil, _)) :+ ((Nil, technique.trackerVariableSpec))
          vared                         = buildVariables(varSpecs, directive.parameters)
          trackerVariableComponentId    = ComponentId(technique.trackerVariableSpec.name, Nil, technique.trackerVariableSpec.id)
          (trackerVariable, otherVars) <- vared.get(trackerVariableComponentId) match {
                                            case None    =>
                                              logger.error(
                                                "Cannot find key %s in Directive %s when building Rule %s".format(
                                                  technique.trackerVariableSpec.name,
                                                  id.debugString,
                                                  ruleId.serialize
                                                )
                                              ) *> Inconsistency(
                                                s"Cannot find key ${technique.trackerVariableSpec.name} in directive ${id.debugString} when building rule ${ruleId.serialize}"
                                              ).fail
                                            case Some(x) => (x, vared - trackerVariableComponentId).succeed
                                          }
          // only normal vars can be interpolated
          _                            <- logger.trace(
                                            s"Creating a ParsedPolicyDraft '${fullActiveTechnique.techniqueName}' from the ruleId ${ruleId.serialize}"
                                          )
        } yield {
          Some(
            ParsedPolicyDraft(
              PolicyId(ruleId, id, technique.id.version),
              ruleName,
              directive.name,
              technique, // if the technique don't have an acceptation date time, this is bad. Use "now",
              // which mean that it will be considered as new every time.

              fullActiveTechnique.acceptationDatetimes.get(technique.id.version).getOrElse(Instant.now()),
              directive.priority,
              directive.isSystem,
              directive.policyMode,
              directive.scheduleId,
              technique.trackerVariableSpec.toVariable(trackerVariable.values),
              lookupNodeParameterization(otherVars),
              vared,
              ruleOrder,
              BundleOrder(directive.name),
              // if there is a campaign ID, we directly map that ID into the corresponding string for ifvarclass.
              // Escaping will be done in agent specific way when building bundle.
              directive.scheduleId.map(IfVarClass.fromScheduleId)
            )
          )
        }
    }
  }

  def getTargetedNodes(
      rule:     Rule,
      groupLib: FullNodeGroupCategory,
      nodes:    Map[NodeId, NodeSecurityInfo]
  ): Set[NodeId] = {
    // Tenant boundary: the tenants on a rule direct which objects and nodes it actually reaches.
    val ruleScope = TenantAccessGrant.fromSecurityScope(rule.security)

    // (d) target-level filter: drop simple targets the rule can't see - foreign groups, and admin-only
    // special targets (AllTarget, etc: security `None`) which must not be usable by a tenant-scoped rule.
    // Composite targets (union/intersection/exclusion) are not in `allTargets`; they pass through and are
    // contained by the node-level filter below.
    val scopedTargets = rule.targets.filter(t => groupLib.allTargets.get(t).forall(info => ruleScope.canSee(info.security)))

    val nodeAndServerIds = NodeAndServerIds(nodes.keySet, nodes.filter(_._2.isPolicyServer).keySet)

    val wantedNodeIds  = groupLib.getNodeIds(scopedTargets, nodeAndServerIds)
    val presentNodeIds = wantedNodeIds.intersect(nodes.keySet)
    if (presentNodeIds.size != wantedNodeIds.size) {
      // ignored nodes are filtered-out early during generation, so we don't have access to their node info here,
      // they are just missing from allNodeInfos map.
      logger.debug(
        s"Some nodes are in the target of rule '${rule.name}' (${rule.id.serialize}) but are not present " +
        s"in the system. These nodes are likely in state `ignored`: ${(wantedNodeIds -- presentNodeIds).map(_.value).mkString(", ")}"
      )
    }

    // (node level, load-bearing) whatever the targets resolved to, keep only the nodes the rule's tenants
    // can see. This contains composite targets and any node wrongly present in a group's serverList.
    val nodeIds = presentNodeIds.filter(id => ruleScope.canSee(nodes.get(id).flatMap(_.security)))
    if (nodeIds.size != presentNodeIds.size) {
      logger.trace(
        s"Rule '${rule.name}' (${rule.id.serialize}) targeted nodes filtered by tenant: excluded " +
        s"${(presentNodeIds -- nodeIds).map(_.value).mkString(", ")}"
      )
    }
    nodeIds
  }

  override def buildRuleVal(
      rule:         Rule,
      directiveLib: FullActiveTechniqueCategory,
      groupLib:     FullNodeGroupCategory,
      nodes:        Map[NodeId, NodeSecurityInfo]
  ): IOResult[RuleVal] = {
    val ruleScope = TenantAccessGrant.fromSecurityScope(rule.security)
    val nodeIds   = getTargetedNodes(rule, groupLib, nodes)

    // (e) directive-level filter: a tenant-scoped rule only applies directives it shares a tenant with.
    // Foreign or admin-only (`None`) directives referenced by a tenant rule are dropped. Untagged/admin
    // rules (scope `All`) see every directive, so non-tenant setups are unaffected.
    val scopedDirectiveIds = rule.directiveIds.toList.filter { id =>
      directiveLib.allDirectives.get(id).forall { case (_, directive) => ruleScope.canSee(directive.security) }
    }

    for {
      drafts <- scopedDirectiveIds.accumulate {
                  getParsedPolicyDraft(_, rule.id, BundleOrder(rule.name), rule.name, directiveLib)
                }
    } yield {
      RuleVal(rule.id, nodeIds, drafts.flatten)
    }
  }
}
