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

import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleVal
import com.normation.cfclerk.domain.Variable
import com.normation.cfclerk.domain.VariableSpec
import com.normation.rudder.web.model.{ DirectiveEditor }
import com.normation.cfclerk.services.TechniqueRepository
import net.liftweb.common._
import Box._
import com.normation.cfclerk.domain.{ TechniqueId, Technique }
import org.joda.time.{ LocalDate, LocalTime, Duration, DateTime }
import com.normation.cfclerk.domain.PredefinedValuesVariableSpec

trait DirectiveEditorService {

  /**
   * Retrieve a policyEditor given the Directive name,
   * if such Directive is known in the system
   */
  def get(
    techniqueId: TechniqueId,
    directiveId: DirectiveId,
    //withExecutionPlanning:Option[TemporalVariableVal] = None,
    withVars: Map[String, Seq[String]] = Map()): Box[DirectiveEditor]

}

class DirectiveEditorServiceImpl(
  techniqueRepository: TechniqueRepository,
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
  import scala.util.control.Breaks._

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
    policyName: TechniqueId,
    directiveId: DirectiveId,
    withVarValues: Map[String, Seq[String]] = Map()): Box[DirectiveEditor] = {

    for {
      //start by checking Directive existence
      pol <- techniqueRepository.get(policyName) ?~! ("Error when retrieving Directive details for " + policyName)
      allVars = pol.rootSection.getAllVariables
      vars = getVars(allVars, withVarValues)
      pe <- section2FieldService.initDirectiveEditor(pol, directiveId, vars)
    } yield pe
  }

}