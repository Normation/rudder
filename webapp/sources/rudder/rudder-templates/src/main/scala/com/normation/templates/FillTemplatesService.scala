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

import com.normation.NamedZioLogger
import org.antlr.stringtemplate.StringTemplate
import com.normation.errors._
import com.normation.stringtemplate.language.NormationAmpersandTemplateLexer
import com.normation.zio.ZioRuntime
import zio._
import zio.syntax._

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
    name      : String
  , mayBeEmpty: Boolean
  , values    : Seq[Any]
  , isSystem  : Boolean
)


object StringTemplateLogger extends NamedZioLogger {
  override def loggerName: String = "string-template-processor"
}

/**
 * A class to synchronize the usage of template
 * Race conditions can occurs (see https://issues.rudder.io/issues/14322 ), causing two
 * different thread trying to insert vars in the same template
 * Encapsuling the template in a class allow to synchronize on the class itself, so
 * ensuring that two different thread can touch the same template (but can touch different
 * templates)
 *
 */
class SynchronizedFileTemplate(templateName: String, localTemplate: Either[RudderError, StringTemplate]) {

  private val semaphore = ZioRuntime.unsafeRun(Semaphore.make(1))

  /**
    * Replace all occurences of parameters in the 'content' string with
    * their value(s) defined in the given set of values.
    *
    * If there is no error, returns the resulting content
    */
  def fill(templateName: String, content: String, variables: Seq[STVariable]): IOResult[String] = {
    localTemplate match {
      case Right(sourceTemplate) =>
        semaphore.withPermit(for {
          // we need to work on an instance of the template
          template <- IOResult.effect(sourceTemplate.getInstanceOf())
          /*
           * Here, we are using bestEffort to try to test a maxum of false values,
           * but the StringTemplate thing is mutable, so we don't have the intersting
           * content in case of success.
           */
          _        <- variables.accumulate { variable =>
                        // Only System Variables have nullable entries
                        if(variable.isSystem && variable.mayBeEmpty &&
                          ( (variable.values.size == 0) || (variable.values.size ==1 && variable.values.head == "") )
                        ) {
                          IOResult.effect(s"Error when trying to replace variable '${variable.name}' with values [${variable.values.mkString(",")}]") {
                            template.setAttribute(variable.name, null)
                          }
                        } else if (!variable.mayBeEmpty && variable.values.size == 0) {
                          Unexpected(s"Mandatory variable ${variable.name} is empty, can not write ${templateName}").fail
                        } else {
                          StringTemplateLogger.trace(s"Adding in ${templateName} variable '${variable.name}' with values [${variable.values.mkString(",")}]") *>
                          variable.values.accumulate { value =>
                            IOResult.effect(s"Error when trying to replace variable '${variable.name}' with values [${variable.values.mkString(",")}]") {
                              template.setAttribute(variable.name, value)
                            }
                          }
                        }
                      }
          //return the actual template with replaced variable in case of success
          result    <- IOResult.effect("An error occured when converting template to string")(template.toString())
        } yield {
          result
        }).chainError(s"Error with template '${templateName}'")

      case Left(_) =>
        val err = Unexpected(s"Error with template '${templateName}' - the template was not correctly parsed")
        StringTemplateLogger.error(err.fullMsg) *> err.fail
    }
  }

}

class FillTemplatesService {

  val semaphore = ZioRuntime.unsafeRun(Semaphore.make(1))

  /*
   * The cache is managed template by template
   * If a content is not in the cache, it will create the StringTemplate instance, and return it
   *
   */
  private[this] val cache = ZioRuntime.unsafeRun(Ref.make(Map[String, SynchronizedFileTemplate]()))

  def clearCache(): IOResult[Unit] = {
    semaphore.withPermit {
      cache.set(Map())
    }
  }

  def getTemplateFromContent(templateName: String,content: String): IOResult[SynchronizedFileTemplate] = {
    semaphore.withPermit(for {
      c <- cache.get
      x <- c.get(content) match {
             case Some(template) => template.succeed
             case None =>
               for {
                 parsed <- IOResult.effect(s"Error when trying to parse template '${templateName}'") {
                             new StringTemplate(content, classOf[NormationAmpersandTemplateLexer])
                           }.either
                 template = new SynchronizedFileTemplate(templateName, parsed)
                 _        <- cache.update(_ + ((content, template)) )
               } yield {
                 template
               }
        }
    } yield {
      x
    })
  }

  /**
   * From a templateName, content and variable, find the template, and call method to fill it
   */
  def fill(templateName: String, content: String, variables: Seq[STVariable]): IOResult[String] = {
    for {
      template <- getTemplateFromContent(templateName, content)
      result   <- template.fill(templateName, content, variables)
    } yield {
      result
    }
  }
}
