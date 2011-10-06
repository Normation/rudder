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

package com.normation.rudder.domain.policies

import com.normation.cfclerk.domain._

import com.normation.utils.Utils._
import org.joda.time.DateTime

import com.normation.cfclerk.domain.{PolicyPackageId,CFCPolicyInstanceId,CFCPolicyInstance}

import net.liftweb.common._
import Box._

import org.joda.time.{LocalDate,LocalTime,Duration,DateTime}
import com.normation.rudder.domain._


/*
 * Immutable bridge between cfclerk and rudder 
 */

/**
 * This class holds the necessary data for a PolicyInstance. It kind of replace the PolicyInstanceBean
 */
case class PolicyInstanceContainer(
    policyPackageId: PolicyPackageId
  , userPolicyTemplateId: UserPolicyTemplateId
  , policyInstanceId: PolicyInstanceId
  , priority: Int
  , TrackerVariable: TrackerVariable
  , variables: Map[String, Variable]
)



case class ConfigurationRuleVal(
  configurationRuleId:ConfigurationRuleId,
  target : PolicyInstanceTarget,  //list of target for that policy instance (server groups, server ids, etc)
  policies : Seq[PolicyInstanceContainer],
  serial : Int // the generation serial of the CR
) {
  
  def toIdentifiableCFCPI : Seq[IdentifiableCFCPI] = 
    policies.map ( pol => IdentifiableCFCPI(configurationRuleId,
        new CFCPolicyInstance(CFCPolicyInstanceId(configurationRuleId.value + "@@" + pol.policyInstanceId.value),
            pol.policyPackageId, __variableMap = pol.variables, pol.TrackerVariable,
            priority = pol.priority, serial = serial )))
}

/**
 * A composite class, to keep the link between the applied PI and the CR
 */
case class IdentifiableCFCPI (
		configurationRuleId:ConfigurationRuleId,
		policyInstance: CFCPolicyInstance
) 


