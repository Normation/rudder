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

/**
  * A class to synchronize the usage of template
  * Race conditions can occurs (see https://issues.rudder.io/issues/14322 ), causing two
  * different thread trying to insert vars in the same template
  * Encapsuling the template in a class allow to synchronize on the class itself, so
  * ensuring that two different thread can touch the same template (but can touch different
  * templates)
  */
class SynchronizedFileTemplate(templateName: String,content: String)  extends Loggable {
  val localTemplate = {
    try {
      Full(new StringTemplate(content, classOf[NormationAmpersandTemplateLexer]))
    } catch {
      case ex: Exception =>
        val m = s"Error when trying to parse template '${templateName}': ${ex.getMessage}"
        logger.error(m, ex)
        Failure(m)
    }
  }

  /**
    * Replace all occurences of parameters in the 'content' string with
    * their value(s) defined in the given set of values.
    *
    * If there is no error, returns the resulting content
    */
  def fill(templateName: String, content: String, variables: Seq[STVariable]): Box[(String, Long, Long, Long)] = localTemplate.synchronized {
    localTemplate match {
      case Full(sourceTemplate) =>
        val t0 = System.nanoTime()
        // we need to work on an instance of the template
        val template = sourceTemplate.getInstanceOf()
        val t1 = System.nanoTime()
        val getInstanceTime = t1 - t0
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
              case ex: Exception =>
                Failure(s"Error when trying to replace variable '${variable.name}' with values [${variable.values.mkString(",")}]: ${ex.getMessage}", Full(ex), Empty)
            }
          } else if (!variable.mayBeEmpty && variable.values.size == 0) {
            Failure(s"Mandatory variable ${variable.name} is empty, can not write ${templateName}")
          } else {
            logger.trace(s"Adding in ${templateName} variable '${variable.name}' with values [${variable.values.mkString(",")}]")
// here we could try to pass the list as one: STAttributeList, or simply a java.util.list to lower pressure on gc,
            // and hopefully gain a bit of speed

     /*       bestEffort(variable.values) { value =>
              try {
                Full(template.setAttribute(variable.name, value))
              } catch {
                case ex: Exception => Failure(s"Error when trying to replace variable '${variable.name}' with values [${variable.values.mkString(",")}]: ${ex.getMessage}", Full(ex), Empty)
              }*/
            try {
            variable.values match {
              case seq if seq.size>1 => Full(template.setAttribute(variable.name, scala.collection.JavaConverters.seqAsJavaList(seq)))
              case seq if seq.size == 1 =>   Full(template.setAttribute(variable.name, seq.head))
                case _ => Full(template.setAttribute(variable.name, null))
            } } catch {
                case ex: Exception => Failure(s"Error when trying to replace variable '${variable.name}' with values [${variable.values.mkString(",")}]: ${ex.getMessage}", Full(ex), Empty)
              }

          }
        }
        val t2 = System.nanoTime()
        val replaceTime = t2 - t1
        //return the actual template with replaced variable in case of success
        replaced match {
          case Full(_) =>
            val result = template.toString()
            val t3 = System.nanoTime()
            val toStringTime = t3 - t2
            Full((result, getInstanceTime, replaceTime, toStringTime))
          case Empty => //should not happen, but well, that the price of not using Either
            Failure(s"An unknown error happen when trying to fill template '${templateName}'")
          case f: Failure => //build a new failure with all the failure message concatenated and the templateName as context
            Failure(s"Error with template '${templateName}': ${f.failureChain.map { _.msg }.mkString("; ") }")
        }
      case _ =>
        val msg = s"Error with template '${templateName}' - the template was not correctly parsed"
        logger.error(msg)
        Failure(msg)
    }
  }

}

class FillTemplatesService extends Loggable {

  /*
   * The cache is managed template by template
   * If a content is not in the cache, it will create the StringTemplate instance, and return it
   *
   */
  private[this] var cache = Map[String,SynchronizedFileTemplate]()


  def clearCache(): Unit = this.synchronized {
    cache = Map()
  }

  def getTemplateFromContent(templateName: String,content: String): Box[SynchronizedFileTemplate] = this.synchronized {
    cache.get(content) match {
      case Some(template) => Full(template)
      case _ =>
        try {
          val template = new SynchronizedFileTemplate(templateName, content)
          cache = cache + ((content, template))
          Full(template)
        } catch {
          case ex: Exception =>
            val m = s"Error when trying to parse template '${templateName}': ${ex.getMessage}"
            logger.error(m, ex)
            Failure(m)
        }
    }
  }

  /**
    * From a templateName, content and variable, find the template, and call method to fill it
    */
  def fill(templateName: String, content: String, variables: Seq[STVariable]): Box[(String, Long, Long, Long)] = {
    for {
      template <- getTemplateFromContent(templateName, content)

      (filled, getInstanceTime, varTime, toStringTime) <- template.fill(templateName, content, variables)

    } yield {
      (filled, getInstanceTime, varTime, toStringTime)
    }
  }
}
