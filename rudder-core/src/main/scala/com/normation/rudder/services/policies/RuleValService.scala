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

package com.normation.rudder.services.policies

import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleVal
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.Rule
import net.liftweb.common._
import com.normation.rudder.domain.policies.DirectiveVal
import com.normation.cfclerk.domain._
import com.normation.cfclerk.exceptions._
import com.normation.utils.Control.sequence
import com.normation.utils.Control.sequenceEmptyable
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.domain.policies.InterpolationContext
import com.normation.rudder.domain.policies.InterpolationContext
import com.normation.cfclerk.domain.BundleOrder

trait RuleValService {
  def buildRuleVal(rule: Rule, directiveLib: FullActiveTechniqueCategory) : Box[RuleVal]

  def lookupNodeParameterization(variables:Seq[Variable]): InterpolationContext => Box[Map[String, Variable]]
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
            try {
                val newVar = spec.toVariable(seqValues)
                assert(seqValues.toSet == newVar.values.toSet)
                (spec.name -> newVar)
            } catch {
              case ex: VariableException =>
                logger.error("Error when trying to set values for variable '%s', use a default value for that variable. Erroneous values was: %s".format(spec.name, seqValues.mkString("[", " ; ", "]")))
                (spec.name, spec.toVariable())
            }
        }
      }.toMap
    )
  }



  /*
   * From a sequence of variable, look at the variable's value (because it's where
   * interpolation is) and build the function that, given an interpolation context,
   * give (on success) the string with expansion done.
   */
  def lookupNodeParameterization(variables:Seq[Variable]): InterpolationContext => Box[Map[String, Variable]] = {
    (context:InterpolationContext) =>
      sequence(variables) { variable =>
        (sequence(variable.values) { value =>
          for {
            parsed <- interpolatedValueCompiler.compile(value) ?~! s"Error when parsing variable ${variable.spec.name}"
            //can lead to stack overflow, no ?
            applied <- parsed(context) ?~! s"Error when resolving interpolated variable in directive variable ${variable.spec.name}"
          } yield {
            applied
          }
        }) match {
          case eb: EmptyBox => eb
          case Full(seq) => Full(Variable.matchCopy(variable, seq))
        }
      }.map(seqVar => seqVar.map(v => (v.spec.name, v)).toMap)
  }


  private[this] def getContainer(piId : DirectiveId, ruleId:RuleId, directiveLib: FullActiveTechniqueCategory) : Box[Option[DirectiveVal]]= {
    directiveLib.allDirectives.get(piId) match {
      case None => Failure("Cannot find Directive with id %s when building Rule %s".format(piId.value, ruleId.value))
      case Some((_, directive) ) if !(directive.isEnabled) =>
        logger.debug("The Directive with id %s is disabled and we don't generate a DirectiveVal for Rule %s".format(piId.value, ruleId.value))
        Full(None)
      case Some((fullActiveDirective, _) ) if !(fullActiveDirective.isEnabled) =>
        logger.debug(s"The Active Technique with id ${fullActiveDirective.id.value} is disabled and we don't generate a DirectiveVal for Rule ${ruleId.value}")
        Full(None)
      case Some((fullActiveTechnique, directive)) =>
        for {
          policyPackage <- Box(fullActiveTechnique.techniques.get(directive.techniqueVersion)) ?~! s"The required version of technique is not available for directive ${directive.name}"
          varSpecs = policyPackage.rootSection.getAllVariables ++ policyPackage.systemVariableSpecs :+ policyPackage.trackerVariableSpec
          vared <- buildVariables(varSpecs, directive.parameters)
          exists <- {
            if (vared.isDefinedAt(policyPackage.trackerVariableSpec.name)) {
              Full("OK")
            } else {
              logger.error("Cannot find key %s in Directive %s when building Rule %s".format(policyPackage.trackerVariableSpec.name, piId.value, ruleId.value))
              Failure("Cannot find key %s in Directibe %s when building Rule %s".format(policyPackage.trackerVariableSpec.name, piId.value, ruleId.value))
            }
          }
          trackerVariable <- vared.get(policyPackage.trackerVariableSpec.name)
          otherVars = vared - policyPackage.trackerVariableSpec.name
          //only normal vars can be interpolated
        } yield {
            logger.trace("Creating a DirectiveVal %s from the ruleId %s".format(fullActiveTechnique.techniqueName, ruleId.value))

            Some(DirectiveVal(
                policyPackage
              , directive.id
              , directive.priority
              , policyPackage.trackerVariableSpec.toVariable(trackerVariable.values)
              , lookupNodeParameterization(otherVars.values.toSeq)
              , vared
              , BundleOrder(directive.name)
            ))
        }
    }
  }

  override def buildRuleVal(rule: Rule, directiveLib: FullActiveTechniqueCategory) : Box[RuleVal] = {
    val targets      = rule.targets
    val directiveIds = rule.directiveIds.toSeq

    for {
      containers   <- sequence(directiveIds) { getContainer(_, rule.id, directiveLib) }
    } yield {
      RuleVal(
        rule.id,
        targets,
        containers.flatten,
        rule.serial,
        BundleOrder(rule.name)
      )
    }
  }
}
