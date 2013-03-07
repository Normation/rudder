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

import com.normation.rudder.domain.policies.ConfigurationRuleId
import com.normation.rudder.domain.policies.PolicyInstanceId
import com.normation.rudder.domain.policies.ConfigurationRuleVal
import net.liftweb.common._
import com.normation.cfclerk.services.PolicyPackageService
import com.normation.rudder.repository.PolicyInstanceRepository
import com.normation.rudder.repository.ConfigurationRuleRepository
import com.normation.rudder.domain.policies.PolicyInstanceContainer
import com.normation.cfclerk.domain._
import com.normation.cfclerk.exceptions._
import com.normation.utils.Control.sequence
import com.normation.utils.Control.sequenceEmptyable

trait ConfigurationRuleValService {
  def findConfigurationRuleVal(configurationRuleId:ConfigurationRuleId) : Box[ConfigurationRuleVal]

}


class ConfigurationRuleValServiceImpl (
  val configurationRuleRepo : ConfigurationRuleRepository,
  val policyInstanceRepo : PolicyInstanceRepository,
  val policyPackageService : PolicyPackageService,
  val variableBuilderService: VariableBuilderService
) extends ConfigurationRuleValService with Loggable {

  private[this] def getContainer(piId : PolicyInstanceId, configurationRuleId:ConfigurationRuleId) : Box[Option[PolicyInstanceContainer]]= {
    policyInstanceRepo.getPolicyInstance(piId) match {
      case e:Failure => e
      case Empty => Failure("Cannot find policy instance with id %s when building Configuration Rule %s".format(piId.value, configurationRuleId.value))
      case Full(pi) if !(pi.isActivated) =>
        logger.debug("The Policy Instance with id %s is disabled and we don't generate a container for Configuration Rule %s".format(piId.value, configurationRuleId.value))
        Full(None)
      case Full(pi) if (pi.isActivated) =>
        policyInstanceRepo.getUserPolicyTemplate(piId) match {
          case e:Failure => e
          case Empty => Failure("Cannot find the PT on which Policy Instance with id %s is based when building Configuration Rule %s".format(piId.value, configurationRuleId.value))
          case Full(upt) if !(upt.isActivated) =>
            Failure("We are trying to apply the Policy Instance with id %s which is based on disabled Policy Template %s".format(piId.value, upt.referencePolicyTemplateName))
          case Full(upt) if upt.isActivated =>
            for {
              policyPackage <- Box(policyPackageService.getPolicy(PolicyPackageId(upt.referencePolicyTemplateName,pi.policyTemplateVersion))) ?~! "Cannot find the Policy Template %s on which Policy Instance with id %s is based when building Configuration Rule %s".format(upt.referencePolicyTemplateName, piId.value, configurationRuleId.value)
              varSpecs = policyPackage.rootSection.getAllVariables ++ policyPackage.systemVariableSpecs :+ policyPackage.trackerVariableSpec
              vared <- variableBuilderService.buildVariables(varSpecs, pi.parameters)
              exists <- {
                if (vared.isDefinedAt(policyPackage.trackerVariableSpec.name)) {
                  Full("OK")
                } else {
                  logger.error("Cannot find key %s in Policy Instance %s when building Configration Rule %s".format(policyPackage.trackerVariableSpec.name, piId.value, configurationRuleId.value))
                  Failure("Cannot find key %s in Policy Instance %s when building rule %s".format(policyPackage.trackerVariableSpec.name, piId.value, configurationRuleId.value))
                }
              }
              trackerVariable <- vared.get(policyPackage.trackerVariableSpec.name)
              otherVars = vared - policyPackage.trackerVariableSpec.name
              } yield {
                logger.debug("Creating a PolicyInstanceContainer %s from the configurationRuleId %s".format(upt.referencePolicyTemplateName, configurationRuleId.value))

                Some(PolicyInstanceContainer(
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

  def findConfigurationRuleVal(configurationRuleId:ConfigurationRuleId) : Box[ConfigurationRuleVal] = {
    for {
      cr <- configurationRuleRepo.get(configurationRuleId)
      target <- Box(cr.target) ?~! "Can not fetch configuration rule values for configuration rule with id %s. The reference target is not defined and I can not build a ConfigurationRuleVal for a not fully defined configuration rule".format(configurationRuleId.value)
      pisId = cr.policyInstanceIds.toSeq
      containers <- sequence(pisId) { piId => getContainer(piId, configurationRuleId) }
    } yield {
      ConfigurationRuleVal(
        cr.id,
        target,
        containers.flatten,
        cr.serial
      )
    }
  }
}
