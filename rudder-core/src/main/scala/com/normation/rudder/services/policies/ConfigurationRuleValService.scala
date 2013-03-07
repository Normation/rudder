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

import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleVal
import net.liftweb.common._
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.domain.policies.DirectiveVal
import com.normation.cfclerk.domain._
import com.normation.cfclerk.exceptions._
import com.normation.utils.Control.sequence
import com.normation.utils.Control.sequenceEmptyable

trait RuleValService {
  def findRuleVal(ruleId:RuleId) : Box[RuleVal]
}


class RuleValServiceImpl (
  val ruleRepo              : RoRuleRepository,
  val directiveRepo         : RoDirectiveRepository,
  val techniqueRepository   : TechniqueRepository,
  val variableBuilderService: VariableBuilderService
) extends RuleValService with Loggable {

  private[this] def getContainer(piId : DirectiveId, ruleId:RuleId) : Box[Option[DirectiveVal]]= {
    directiveRepo.getDirective(piId) match {
      case e:Failure => e
      case Empty => Failure("Cannot find Directive with id %s when building Rule %s".format(piId.value, ruleId.value))
      case Full(pi) if !(pi.isEnabled) =>
        logger.debug("The Directive with id %s is disabled and we don't generate a DirectiveVal for Rule %s".format(piId.value, ruleId.value))
        Full(None)
      case Full(pi) if (pi.isEnabled) =>
        directiveRepo.getActiveTechnique(piId) match {
          case e:Failure => e
          case Empty => Failure("Cannot find the active Technique on which Directive with id %s is based when building Rule %s".format(piId.value, ruleId.value))
          case Full(upt) if !(upt.isEnabled) =>
             Failure("We are trying to apply the Directive with id %s which is based on disabled Technique %s".format(piId.value, upt.techniqueName))
          case Full(upt) if upt.isEnabled =>
            for {
              policyPackage <- techniqueRepository.get(TechniqueId(upt.techniqueName, pi.techniqueVersion))
              varSpecs = policyPackage.rootSection.getAllVariables ++ policyPackage.systemVariableSpecs :+ policyPackage.trackerVariableSpec
              vared <- variableBuilderService.buildVariables(varSpecs, pi.parameters)
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
                logger.debug("Creating a DirectiveVal %s from the ruleId %s".format(upt.techniqueName, ruleId.value))

                Some(DirectiveVal(
                    policyPackage.id,
                    upt.id,
                    pi.id,
                    pi.priority,
                    policyPackage.trackerVariableSpec.toVariable(trackerVariable.values),
                    otherVars
                ))
              }
        }
    }
  }

  override def findRuleVal(ruleId:RuleId) : Box[RuleVal] = {
    for {
      rule         <- ruleRepo.get(ruleId)
      targets      = rule.targets
      directiveIds = rule.directiveIds.toSeq
      containers   <- sequence(directiveIds) { getContainer(_, ruleId) }
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
