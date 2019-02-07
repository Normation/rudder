/*
*************************************************************************************
* Copyright 2016 Normation SAS
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

package com.normation.templates


/*
 * This file define the service responsible to replace variables markers in a string
 * to corresponding values.
 */

import com.normation.stringtemplate.language.NormationAmpersandTemplateLexer
import com.normation.utils.Control.bestEffort

import org.antlr.stringtemplate.StringTemplate


import net.liftweb.common._

/**
 * A "string template variable" is a variable destinated to be
 * used by String Template so that it can be replaced correctly
 * in templates.
 *
 * A STVariable is composed of:
 * - a name : the tag in the template that string template
 *   will look for and replace)
 * - a list of values of type Any which string template will handle
 *   accordingly to its formatters
 * - a "mayBeEmpty" flag that allows string template to know how to
 *   handle empty list of values
 * - a "isSystem" flag that describe if the variable is system or not
 */
final case class STVariable(
    name      :String
  , mayBeEmpty:Boolean
  , values    :Seq[Any]
  , isSystem  :Boolean
)

class FillTemplatesService extends Loggable {

  /**
   * Replace all occurences of parameters in the 'content' string with
   * their value(s) defined in the given set of values.
   *
   * If there is no error, returns the resulting template.
   *
   */
  def fill(templateName: String, content: String, variables: Seq[STVariable]): Box[String] = {
    for {
      template <- try {
                    //string template does not allows "." in path name, so we are force to use a templateGroup by policy template (versions have . in them)
                    val template = new StringTemplate(content, classOf[NormationAmpersandTemplateLexer])
                    Full(template)
                  } catch {
                    case ex: Exception =>
                      val m = s"Error when trying to parse template '${templateName}': ${ex.getMessage}"
                      logger.error(m, ex)
                      Failure(m)
                  }
       filled  <- {
                    /*
                     * Here, we are using bestEffort to try to test a maxum of false values,
                     * but the StringTemplate thing is mutable, so we don't have the intersting
                     * content in case of success.
                     */
                    val replaced = bestEffort(variables) { case variable =>
                      // Only System Variables have nullable entries
                      if ( variable.isSystem && variable.mayBeEmpty &&
                          ( (variable.values.size == 0) || (variable.values.size ==1 && variable.values.head == "") )
                      ) {
                        try {
                          Full(template.setAttribute(variable.name, null))
                        } catch {
                          case ex: Exception => Failure(s"Error when trying to replace variable '${variable.name}' with values [${variable.values.mkString(",")}]: ${ex.getMessage}", Full(ex), Empty)
                        }
                      } else if (!variable.mayBeEmpty && variable.values.size == 0) {
                        Failure(s"Mandatory variable ${variable.name} is empty, can not write ${templateName}")
                      } else {
                        logger.trace(s"Adding in ${templateName} variable '${variable.name}' with values [${variable.values.mkString(",")}]")
                        bestEffort(variable.values) { value =>
                          try {
                            Full(template.setAttribute(variable.name, value))
                          } catch {
                            case ex: Exception => Failure(s"Error when trying to replace variable '${variable.name}' with values [${variable.values.mkString(",")}]: ${ex.getMessage}", Full(ex), Empty)
                          }
                        }
                      }
                    }
                    //return the actual template with replaced variable in case of success
                    replaced match {
                      case Full(_) => Full(template.toString())
                      case Empty => //should not happen, but well, that the price of not using \/
                        Failure(s"An unknown error happen when trying to fill template '${templateName}'")
                      case f: Failure => //build a new failure with all the failure message concatenated and the templateName as context
                        Failure(s"Error with template '${templateName}': ${f.failureChain.map { _.msg }.mkString("; ") }")
                    }
                  }
    } yield {
      filled
    }
  }
}
