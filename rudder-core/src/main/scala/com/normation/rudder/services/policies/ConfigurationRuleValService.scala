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
import com.normation.rudder.domain.policies.RuleVal
import net.liftweb.common._
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.rudder.repository.DirectiveRepository
import com.normation.rudder.repository.RuleRepository
import com.normation.rudder.domain.policies.DirectiveVal
import com.normation.cfclerk.domain._
import com.normation.cfclerk.exceptions._
import com.normation.utils.Control.sequence

trait RuleValService {
  def findRuleVal(ruleId:RuleId) : Box[RuleVal]
  
}


class RuleValServiceImpl (
  val ruleRepo : RuleRepository,
  val directiveRepo : DirectiveRepository,
  val techniqueRepository : TechniqueRepository,
  val variableBuilderService: VariableBuilderService
) extends RuleValService with Loggable {
 
  def findRuleVal(ruleId:RuleId) : Box[RuleVal] = {
    for {
      rule <- ruleRepo.get(ruleId)
      target <- Box(rule.target) ?~! "Can not fetch configuration rule values for configuration rule with id %s. The reference target is not defined and I can not build a RuleVal for a not fully defined configuration rule".format(ruleId)
      pisId = rule.directiveIds.toSeq
      containers <- sequence(pisId) { directiveId => {
        for {
        directive <- directiveRepo.getDirective(directiveId) ?~! "Can not fetch policy instance %s for configuration rule with id %s. ".format(directiveId.value, ruleId)
        activeTechnique <- directiveRepo.getActiveTechnique(directiveId)  ?~! "Can not fetch policy template %s for configuration rule with id %s. ".format(directiveId.value, ruleId)
        policyPackage <- techniqueRepository.get(TechniqueId(activeTechnique.techniqueName,directive.techniqueVersion))
        varSpecs = policyPackage.rootSection.getAllVariables ++ policyPackage.systemVariableSpecs :+ policyPackage.trackerVariableSpec
        vared <- variableBuilderService.buildVariables(varSpecs, directive.parameters)
        trackerVariable <- vared.get(policyPackage.trackerVariableSpec.name)
        otherVars = vared - policyPackage.trackerVariableSpec.name
        } yield {
          logger.debug("Creating a DirectiveVal %s from the ruleId %s".format(activeTechnique.techniqueName, ruleId))
        
          DirectiveVal(
              policyPackage.id,
              activeTechnique.id,
              directive.id,
              directive.priority,
              policyPackage.trackerVariableSpec.toVariable(trackerVariable.values),
              otherVars
          )
        }
      } }
    } yield {
      RuleVal(
        rule.id,
        target,
        containers,
        rule.serial
      )
    }
  } 
}
