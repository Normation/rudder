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

import com.normation.cfclerk.domain.{Variable, VariableSpec}
import com.normation.cfclerk.exceptions.VariableException
import net.liftweb.common._


/**
 * A service that try to build  variables from a list of VariableSpec and
 * a context (list of values to try to put in variable). 
 * 
 * Different implementation may be more or less strict about how they handle
 * mismatch between context and variable specs. 
 */
trait VariableBuilderService {
  /**
   * From a list of VariableSpec and parameters, build Variables
   * Some sanity check are performed to ensure that not extra value are set, nor that
   * any wrong value are setted
   */
  def buildVariables(
      variableSpecs : Seq[VariableSpec]
    , context:Map[String, Seq[String]]
  ) : Box[Map[String, Variable]]
  
}

/**
 * A default implementation that not strict at all. 
 * It try to do it's best, defaulting to empty values
 * when no parameter are found in the context for a given
 * variableSpec. 
 */
class VariableBuilderServiceImpl extends VariableBuilderService with Loggable {

  override def buildVariables(
      variableSpecs : Seq[VariableSpec]
    , context:Map[String, Seq[String]]
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
}

// For memories: here come the historical version which was much more strict about what to allow
//  /**
//   * From a list of VariableSpec and parameters, build Variables
//   * Some sanity check are performed to ensure that not extra value are set, nor that
//   * any wrong value are setted
//   * TODO : check why the system var are not setted
//   */
//  override def buildVariables(allVariables : Set[VariableSpec],
//        parameters:Map[String, Seq[String]]) : Box[Map[String, Variable]] = {
//      val invalidVariable = scala.collection.mutable.Map[String, Seq[String]]()
//      
//      val variableResult =  scala.collection.mutable.Map[String, Variable]()
//      
//      //check if parameters has more variables than expected by the policy
//      val overflowVariables = (parameters.keySet -- allVariables.map(_.name).toSet)
//      if(overflowVariables.nonEmpty) {
//         return ParamFailure[Map[String,Seq[String]]]("Found configured variable(s) in directive that are not expected", Empty, Empty, overflowVariables.map(n => (n -> parameters(n))).toMap)
//      }
//          
//      for (variableSpec <- allVariables) {
//         // TODO : IS THAT TRUE ?
//         if (variableSpec.systemVar) { // a system var doesn't have value in the PI ?
//            variableResult += (variableSpec.name -> variableSpec.toVariable())
//         } else {
//            variableSpec match {  
//              case x : VariableSpec => 
//                parameters.get(variableSpec.name) match {
//                  case None =>  invalidVariable += (variableSpec.name -> Seq[String]()) // this is an expected variable that is not present
//                  case Some(setValues) => 
//                    try {
//                        val newVar = x.toVariable(setValues)
//                        assert(setValues.toSet == newVar.values.toSet)
//                        variableResult += (variableSpec.name -> newVar)
//                    } catch {
//                      case ex: VariableException => invalidVariable += (variableSpec.name -> setValues)
//                    }
//                }   
//            }
//          }
//      }
//          
//          
//    if (invalidVariable.size > 0) {
//      ParamFailure[scala.collection.Map[String, Seq[String]]]("Some variables are not set/correct", Full(new VariableException("Some variables are not set/correct")), Empty, invalidVariable)
//    } else {
//      Full(variableResult.toMap)
//    }
//
//  }

