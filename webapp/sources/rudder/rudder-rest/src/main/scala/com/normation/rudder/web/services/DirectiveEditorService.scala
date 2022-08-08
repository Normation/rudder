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

package com.normation.rudder.web.services

import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.cfclerk.domain.Variable
import com.normation.cfclerk.domain.VariableSpec
import com.normation.rudder.web.model.DirectiveEditor
import net.liftweb.common._
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.PredefinedValuesVariableSpec
import com.normation.rudder.configuration.ConfigurationRepository
import com.normation.box._

trait DirectiveEditorService {

  /**
   * Retrieve a policyEditor given the Directive name,
   * if such Directive is known in the system
   */
  def get(
    techniqueId: TechniqueId,
    directiveId: DirectiveUid,
    //withExecutionPlanning:Option[TemporalVariableVal] = None,
    withVars: Map[String, Seq[String]] = Map()): Box[DirectiveEditor]

}

class DirectiveEditorServiceImpl(
  techniqueRepository: ConfigurationRepository,
  section2FieldService: Section2FieldService) extends DirectiveEditorService {

  /**
   * Retrieve vars for the given Directive.
   * First, we try to retrieve default vars
   * from the techniqueRepository.
   * Then, we look in the parameter vars to
   * search for vars with the same name.
   * For each found, we change the default
   * var value by the parameter one.
   */

  /*
   * We exactly set the variable values to varValues,
   * so a missing variable key actually set the value
   * to Seq()
   */
  private def getVars(allVars: Seq[VariableSpec], vars: Map[String, Seq[String]]): Seq[Variable] = {
    allVars.map { varSpec => varSpec match {
      case spec : PredefinedValuesVariableSpec =>
         // variables values are already builtin
        spec.toVariable()
      case _ =>
         // variable can be modified
        varSpec.toVariable(vars.getOrElse(varSpec.name, Seq()))
      }
    }
  }

  override def get(
    techniqueId: TechniqueId,
    directiveId: DirectiveUid,
    withVarValues: Map[String, Seq[String]] = Map()): Box[DirectiveEditor] = {

    for {
      //start by checking Directive existence
      pol <- techniqueRepository.getTechnique(techniqueId).notOptional(s"Error when looking for technique with ID '${techniqueId.debugString}'. Check technique name and version").toBox
      allVars = pol._2.rootSection.copyWithoutSystemVars.getAllVariables
      vars = getVars(allVars, withVarValues)
      pe <- section2FieldService.initDirectiveEditor(pol._2, directiveId, vars)
    } yield pe
  }

}
