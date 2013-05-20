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

trait RuleValService {
  def buildRuleVal(rule: Rule, directiveLib: FullActiveTechniqueCategory) : Box[RuleVal]
}


class RuleValServiceImpl (
    val variableBuilderService: VariableBuilderService
) extends RuleValService with Loggable {

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
          policyPackage <- Box(fullActiveTechnique.techniques.get(directive.techniqueVersion)) ?~! "The required version of technique is not available for directive"
          varSpecs = policyPackage.rootSection.getAllVariables ++ policyPackage.systemVariableSpecs :+ policyPackage.trackerVariableSpec
          vared <- variableBuilderService.buildVariables(varSpecs, directive.parameters)
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
        } yield {
            logger.debug("Creating a DirectiveVal %s from the ruleId %s".format(fullActiveTechnique.techniqueName, ruleId.value))

            Some(DirectiveVal(
                policyPackage,
                directive.id,
                directive.priority,
                policyPackage.trackerVariableSpec.toVariable(trackerVariable.values),
                otherVars,
                vared
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
        rule.serial
      )
    }
  }
}
