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
import com.normation.errors.*
import com.normation.stringtemplate.language.NormationAmpersandTemplateLexer
import com.normation.zio.*
import org.antlr.stringtemplate.StringTemplate
import org.apache.commons.lang3.Strings
import scala.collection.immutable.ArraySeq
import zio.*
import zio.syntax.*

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
    name:       String,
    mayBeEmpty: Boolean,
    values:     ArraySeq[Any],
    isSystem:   Boolean
)

/*
 * Timers accumulator for Fill Template and semaphore waiting
 */
final case class FillTemplateTimer(
    fill:      Ref[Long],
    stringify: Ref[Long],
    waitFill:  Ref[Long],
    get:       Ref[Long],
    waitGet:   Ref[Long]
)

object FillTemplateTimer {
  def make(): ZIO[Any, Nothing, FillTemplateTimer] = for {
    a <- Ref.make(0L)
    b <- Ref.make(0L)
    c <- Ref.make(0L)
    d <- Ref.make(0L)
    e <- Ref.make(0L)
  } yield FillTemplateTimer(a, b, c, d, e)
}

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
  def fill(templateName: String, variables: Seq[STVariable], timer: FillTemplateTimer): IOResult[String] = {
    localTemplate match {
      case Right(sourceTemplate) =>
        for {
          t <- (for {
                 // we need to work on an instance of the template
                 ///// BE CAREFULL: HERE, against all odds, the returned instance is NOT threadsafe. /////
                 /*
                  * Here, we are using bestEffort to try to test a maxum of false values,
                  * but the StringTemplate thing is mutable, so we don't have the intersting
                  * content in case of success.
                  */
                 // return (variable name, variable value, error message)
                 t0     <- currentTimeNanos
                 vars   <- variables.accumulate { variable =>
                             // Only System Variables have nullable entries
                             if (
                               variable.isSystem && variable.mayBeEmpty &&
                               ((variable.values.size == 0) || (variable.values.size == 1 && variable.values.head == ""))
                             ) {
                               List(
                                 (variable.name, null, s"Error when trying to replace variable '${variable.name}' with values []")
                               ).succeed
                             } else if (!variable.mayBeEmpty && variable.values.size == 0) {
                               Unexpected(s"Mandatory variable ${variable.name} is empty, can not write ${templateName}").fail
                             } else {
                               StringTemplateLogger.trace(
                                 s"Adding in ${templateName} variable '${variable.name}' with values [${variable.values.mkString(",")}]"
                               ) *>
                               variable.values.accumulate { value =>
                                 (
                                   variable.name,
                                   value,
                                   s"Error when trying to replace variable '${variable.name}' with values [${variable.values.mkString(",")}]"
                                 ).succeed
                               }
                             }
                           }
                 t1     <- currentTimeNanos
                 _      <- timer.fill.update(_ + t1 - t0)
                 result <-
                   semaphore.withPermit(
                     for {
                       t0_in    <- currentTimeNanos
                       template <- IOResult.attempt(sourceTemplate.getInstanceOf())
                       _        <- vars.flatten.accumulate {
                                     case (name, value, errorMsg) => IOResult.attempt(errorMsg)(template.setAttribute(name, value))
                                   }
                       // return the actual template with replaced variable in case of success
                       result   <- IOResult.attempt("An error occured when converting template to string")(template.toString())
                       t1_in    <- currentTimeNanos
                     } yield (result, t1_in - t0_in)
                   )
                 t2     <- currentTimeNanos
                 delta   = t2 - t1
                 _      <- timer.waitFill.update(_ + delta - result._2)
                 _      <- timer.stringify.update(_ + delta)
               } yield {
                 result._1
               }).chainError(s"Error with template '${templateName}'")
        } yield t

      case Left(_) =>
        val err = Unexpected(s"Error with template '${templateName}' - the template was not correctly parsed")
        StringTemplateLogger.error(err.fullMsg) *> err.fail
    }
  }

}

/*
 * A performance optimized version of fill template which assumes that thread safety is managed for it.
 * Main changes:
 * - IT IS NOT THREAD SAFE.
 * - set variable's values directly as list and not one by one
 * - unwrap STVariable array seq to avoid the huge conversion cost.
 */
object FillTemplateThreadUnsafe {
  ////////// Hottest method on whole Rudder //////////
  def fill(
      templateName:   String,
      sourceTemplate: StringTemplate,
      variables:      Seq[STVariable],
      timer:          FillTemplateTimer,
      replaceId:      Option[(String, String)]
  ): IOResult[(String, String)] = {
    (for {
      // we need to work on an instance of the template
      ///// BE CAREFUL: HERE, against all odds, the returned instance is NOT thread safe. /////
      /*
       * Here, we are using bestEffort to try to test a maximum of false values,
       * but the StringTemplate thing is mutable, so we don't have the interesting
       * content in case of success.
       */
      // return (variable name, variable value, error message)
      template <- IOResult.attempt(sourceTemplate.getInstanceOf())
      t0       <- currentTimeNanos
      vars     <- variables.accumulate { variable =>
                    // Only System Variables have nullable entries
                    if (
                      variable.isSystem && variable.mayBeEmpty &&
                      ((variable.values.size == 0) || (variable.values.size == 1 && variable.values.head == ""))
                    ) {
                      List((variable.name, null, s"Error when trying to replace variable '${variable.name}' with values []")).succeed
                    } else if (!variable.mayBeEmpty && variable.values.size == 0) {
                      Unexpected(s"Mandatory variable ${variable.name} is empty, can not write ${templateName}").fail
                    } else {
                      StringTemplateLogger.trace(
                        s"Adding in ${templateName} variable '${variable.name}' with values [${variable.values.mkString(",")}]"
                      ) *>
                      IOResult.attempt(
                        s"Error when trying to replace variable '${variable.name}' with values [${variable.values.mkString(",")}]"
                      ) {
                        // if there is only one value in the value array, only set unique head value or else we encounter https://issues.rudder.io/issues/18205
                        // to sum up, if we pass an array here, StringTemplate will treat it as an array, and not an unique value, hence in the case of 18025,
                        // the Boolean value is used in a condition and string template will only check if the array is empty or not and not look at the boolean value
                        // if we have more than one value, directly set the array of values in place of values one by one (and let ST build back the array, mutating its map for values
                        // each time. Also: ST really need an array, so we use an array seq in STVariable (for immutability) and unwrap it here to
                        // avoid the cost of translation.
                        val value = if (variable.values.size == 1) { variable.values.head }
                        else { variable.values.unsafeArray }
                        template.setAttribute(variable.name, value)
                      }
                    }
                  }
      // set the ST Variable for RudderUniqueID
      _         = replaceId match {
                    case None             => // nothing
                    case Some((from, to)) =>
                      val variable = STVariable(from, mayBeEmpty = true, values = ArraySeq(to), isSystem = true)
                      template.setAttribute(variable.name, variable.values.unsafeArray)
                  }
      t1       <- currentTimeNanos
      _        <- timer.fill.update(_ + t1 - t0)
      // return the actual template with replaced variable in case of success
      policy   <- IOResult.attempt("An error occurred when converting template to string")(template.toString())
      // if the technique is multi-policy, replace rudderTag by reportId
      result    = replaceId match {
                    case None             => (policy, templateName)
                    case Some((from, to)) =>
                      // this is done quite heavely on big instances, with string rather big, and the performance of
                      // StringUtils.replace ix x4 the one of String.replace (no regex), see:
                      // https://stackoverflow.com/questions/16228992/commons-lang-stringutils-replace-performance-vs-string-replace/19163566
                      (policy, Strings.CS.replace(templateName, from, to))
                  }
      t2       <- currentTimeNanos
      delta     = t2 - t1
      _        <- timer.stringify.update(_ + delta)
    } yield {
      result
    }).chainError(s"Error with template '${templateName}'").uninterruptible
  }
}

class FillTemplatesService {

  val semaphore: Semaphore =
    ZioRuntime.unsafeRun(Semaphore.make(1)) // now that we use Promises this semaphore doesn't cost anything

  /*
   * The cache is managed template by template
   * If a content is not in the cache, it will create the StringTemplate instance, and return it
   *
   */
  private val cache = ZioRuntime.unsafeRun(Ref.make(Map[String, Promise[RudderError, SynchronizedFileTemplate]]()))

  def clearCache(): IOResult[Unit] = {
    cache.set(Map())
  }

  def getTemplateFromContent(
      templateName: String,
      content:      String,
      timer:        FillTemplateTimer
  ): IOResult[Promise[RudderError, SynchronizedFileTemplate]] = {
    for {
      t0 <- currentTimeNanos
      s  <- semaphore.withPermit(for {
              t0_in <- currentTimeNanos
              c     <- cache.get
              x     <- c.get(content) match {
                         case Some(promise) =>
                           ZIO.succeed(promise)
                         case None          =>
                           for {
                             p <- Promise.make[RudderError, SynchronizedFileTemplate]
                             _ <- p.complete(for {
                                    parsed  <- IOResult
                                                 .attempt(s"Error when trying to parse template '${templateName}'") {
                                                   new StringTemplate(content, classOf[NormationAmpersandTemplateLexer])
                                                 }
                                                 .either
                                    template = new SynchronizedFileTemplate(templateName, parsed)
                                  } yield {
                                    template
                                  }).forkDaemon
                             _ <- cache.update(_ + (content -> p))
                           } yield p
                       }
              t1_in <- currentTimeNanos
              delta  = t1_in - t0_in
              _     <- timer.get.update(_ + delta)
            } yield {
              (x, delta)
            })
      t1 <- currentTimeNanos
      _  <- timer.waitGet.update(_ + t1 - t0 - s._2)
    } yield s._1
  }

  /**
   * From a templateName, content and variable, find the template, and call method to fill it
   */
  def fill(templateName: String, content: String, variables: Seq[STVariable], timer: FillTemplateTimer): IOResult[String] = {
    for {
      promise  <- getTemplateFromContent(templateName, content, timer)
      template <- promise.await
      result   <- template.fill(templateName, variables, timer)
    } yield {
      result
    }
  }
}
