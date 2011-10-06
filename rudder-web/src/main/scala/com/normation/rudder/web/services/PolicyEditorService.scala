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

package com.normation.rudder.web.services

import com.normation.rudder.domain.policies.PolicyInstanceId
import com.normation.rudder.domain.policies.ConfigurationRuleVal
import com.normation.cfclerk.domain.Variable
import com.normation.cfclerk.domain.VariableSpec
import com.normation.rudder.web.model.{ PolicyEditor }
import com.normation.cfclerk.services.PolicyPackageService
import net.liftweb.common._
import Box._
import com.normation.cfclerk.domain.{ PolicyPackageId, PolicyPackage }

import org.joda.time.{ LocalDate, LocalTime, Duration, DateTime }

trait PolicyEditorService {

  /**
   * Retrieve a policyEditor given the policy name,
   * if such policy is known in the system
   */
  def get(
    policyTemplateId: PolicyPackageId,
    policyInstanceId: PolicyInstanceId,
    //withExecutionPlanning:Option[TemporalVariableVal] = None,
    withVars: Map[String, Seq[String]] = Map()): Box[PolicyEditor]

}

class PolicyEditorServiceImpl(
  policyPackageService: PolicyPackageService,
  section2FieldService: Section2FieldService) extends PolicyEditorService {

  /**
   * Retrieve vars for the given policy.
   * First, we try to retrieve default vars
   * from the policyPackageService.
   * Then, we look in the parameter vars to
   * search for vars with the same name.
   * For each found, we change the default
   * var value by the parameter one.
   */
  import scala.util.control.Breaks._

  /*
   * We exactly set the variable values to varValues, 
   * so a missing variable key actually set the value
   * to Seq()
   */
  private def getVars(allVars: Seq[VariableSpec], vars: Map[String, Seq[String]]): Seq[Variable] = {
    allVars.map { varSpec =>
      varSpec.toVariable(vars.getOrElse(varSpec.name, Seq()))
    }
  }

  override def get(
    policyName: PolicyPackageId,
    policyInstanceId: PolicyInstanceId,
    withVarValues: Map[String, Seq[String]] = Map()): Box[PolicyEditor] = {

    for {
      //start by checking policy existence
      pol <- policyPackageService.getPolicy(policyName) ?~! ("Error when retrieving policy details for " + policyName)
      val allVars = pol.rootSection.getAllVariables
      val vars = getVars(allVars, withVarValues)
      pe <- section2FieldService.initPolicyEditor(pol, policyInstanceId, vars)
    } yield pe
  }

}