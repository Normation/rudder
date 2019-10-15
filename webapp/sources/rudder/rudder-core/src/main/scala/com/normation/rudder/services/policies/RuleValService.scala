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

import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.Rule
import net.liftweb.common._
import com.normation.cfclerk.domain._
import com.normation.utils.Control.bestEffort
import com.normation.rudder.repository.FullActiveTechniqueCategory
import org.joda.time.DateTime
import com.normation.inventory.domain.NodeId
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.domain.nodes.NodeInfo

import zio.syntax._
import com.normation.errors._

trait RuleValService {
  def buildRuleVal(rule: Rule, directiveLib: FullActiveTechniqueCategory, groupLib: FullNodeGroupCategory, allNodeInfos: Map[NodeId, NodeInfo]) : Box[RuleVal]

  def lookupNodeParameterization(variables:Seq[Variable]): InterpolationContext => IOResult[Map[String, Variable]]
}


class RuleValServiceImpl(
    interpolatedValueCompiler: InterpolatedValueCompiler
) extends RuleValService with Loggable {

  private[this] def buildVariables(
      variableSpecs: Seq[VariableSpec]
    , context      : Map[String, Seq[String]]
  ) : Box[Map[String, Variable]] = {

    Full(
      variableSpecs.map { spec =>
        context.get(spec.name) match {
          case None => (spec.name, spec.toVariable())
          case Some(seqValues) =>
            val newVar = spec.toVariable(seqValues)
            assert(seqValues.toSet == newVar.values.toSet)
            (spec.name -> newVar)
        }
      }.toMap
    )
  }



  /*
   * From a sequence of variable, look at the variable's value (because it's where
   * interpolation is) and build the function that, given an interpolation context,
   * give (on success) the string with expansion done.
   *
   * We must exclude variable from ncf technique, that are processed differently, because
   * the provided value is not managed by rudder (it is on a cfengine file elsewhere).
   */
  override def lookupNodeParameterization(variables:Seq[Variable]): InterpolationContext => IOResult[Map[String, Variable]] = {
    (context:InterpolationContext) =>
      variables.accumulate ( variable => variable.spec match {
        //do not touch ncf variables
        case _: PredefinedValuesVariableSpec => variable.succeed
        case _                               =>
          (variable.values.accumulate { value =>
            for {
              parsed <- interpolatedValueCompiler.compile(value)
              //can lead to stack overflow, no ?
              applied <- parsed(context)
            } yield {
              applied
            }
          }).foldM(
              err => Chained(s"On variable '${variable.spec.name}':", err).fail
            , seq => Variable.matchCopy(variable, seq).succeed
          )
      } ).map(seqVar => seqVar.map(v => (v.spec.name, v)).toMap)
  }


  def getParsedPolicyDraft(piId : DirectiveId, ruleId:RuleId, ruleOrder: BundleOrder, ruleName: String, directiveLib: FullActiveTechniqueCategory) : Box[Option[ParsedPolicyDraft]]= {
    directiveLib.allDirectives.get(piId) match {
      case None => Failure("Cannot find Directive with id %s when building Rule %s".format(piId.value, ruleId.value))
      case Some((_, directive) ) if !(directive.isEnabled) =>
        logger.debug("The Directive with id %s is disabled and we don't generate a ParsedPolicyDraft for Rule %s".format(piId.value, ruleId.value))
        Full(None)
      case Some((fullActiveDirective, _) ) if !(fullActiveDirective.isEnabled) =>
        logger.debug(s"The Active Technique with id ${fullActiveDirective.id.value} is disabled and we don't generate a ParsedPolicyDraft for Rule ${ruleId.value}")
        Full(None)
      case Some((fullActiveTechnique, directive)) =>
        for {
          technique <- Box(fullActiveTechnique.techniques.get(directive.techniqueVersion)) ?~! s"Version '${directive.techniqueVersion}' of technique '${fullActiveTechnique.techniqueName}' is not available for directive '${directive.name}' [${directive.id.value}]"
          varSpecs = technique.rootSection.getAllVariables ++ technique.systemVariableSpecs :+ technique.trackerVariableSpec
          vared <- buildVariables(varSpecs, directive.parameters)
          exists <- {
            if (vared.isDefinedAt(technique.trackerVariableSpec.name)) {
              Full("OK")
            } else {
              logger.error("Cannot find key %s in Directive %s when building Rule %s".format(technique.trackerVariableSpec.name, piId.value, ruleId.value))
              Failure("Cannot find key %s in Directibe %s when building Rule %s".format(technique.trackerVariableSpec.name, piId.value, ruleId.value))
            }
          }
          trackerVariable <- vared.get(technique.trackerVariableSpec.name)
          otherVars = vared - technique.trackerVariableSpec.name
          //only normal vars can be interpolated
        } yield {
            logger.trace("Creating a ParsedPolicyDraft %s from the ruleId %s".format(fullActiveTechnique.techniqueName, ruleId.value))

            Some(ParsedPolicyDraft(
                PolicyId(ruleId, piId, technique.id.version)
              , ruleName
              , directive.name
              , technique
                // if the technique don't have an acceptation date time, this is bad. Use "now",
                // which mean that it will be considered as new every time.
              , fullActiveTechnique.acceptationDatetimes.get(technique.id.version).getOrElse(DateTime.now)
              , directive.priority
              , directive.isSystem
              , directive.policyMode
              , technique.trackerVariableSpec.toVariable(trackerVariable.values)
              , lookupNodeParameterization(otherVars.values.toSeq)
              , vared
              , ruleOrder
              , BundleOrder(directive.name)
            ))
        }
    }
  }

  def getTargetedNodes(rule: Rule, groupLib: FullNodeGroupCategory, allNodeInfos: Map[NodeId, NodeInfo]): Set[NodeId] = {
    val wantedNodeIds = groupLib.getNodeIds(rule.targets, allNodeInfos)
    val nodeIds = wantedNodeIds.intersect(allNodeInfos.keySet)
    if(nodeIds.size != wantedNodeIds.size) {
      logger.error(s"Some nodes are in the target of rule '${rule.name}' (${rule.id.value}) but are not present " +
          s"in the system. It looks like an inconsistency error. Ignored nodes: ${(wantedNodeIds -- nodeIds).map( _.value).mkString(", ")}")
    }
    nodeIds
  }

  override def buildRuleVal(rule: Rule, directiveLib: FullActiveTechniqueCategory, groupLib: FullNodeGroupCategory, allNodeInfos: Map[NodeId, NodeInfo]) : Box[RuleVal] = {
    val nodeIds = getTargetedNodes(rule, groupLib, allNodeInfos)

    for {
      drafts <- bestEffort(rule.directiveIds.toSeq) { getParsedPolicyDraft(_, rule.id, BundleOrder(rule.name), rule.name, directiveLib) }
    } yield {
      RuleVal(rule.id, nodeIds, drafts.flatten)
    }
  }
}
