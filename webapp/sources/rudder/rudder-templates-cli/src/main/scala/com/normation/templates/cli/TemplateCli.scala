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

package com.normation.templates.cli

import com.normation.errors.*
import com.normation.templates.FillTemplatesService
import com.normation.templates.FillTemplateTimer
import com.normation.templates.STVariable
import com.normation.zio.*
import java.io.File
import java.nio.charset.StandardCharsets
import net.liftweb.common.*
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import scala.collection.immutable.ArraySeq
import scopt.OptionParser
import zio.{System as _, *}
import zio.json.*
import zio.json.ast.Json
import zio.syntax.*

/**
 * The configuration object for our CLI.
 * The basic process is to take one file in input for the definition of variables, one set of files as template to change.
 *
 * By default, the files are generated with the same name in the current folder.
 *
 * - add the possibility to take directories as input (but a good shell can do it, so not very important)
 */

final case class Config(
    variables:       File = new File("variables.json"),
    templates:       Seq[File] = Seq(),
    outdir:          File = new File("."),
    verbose:         Boolean = false,
    inputExtension:  String = ".st",
    outputExtension: String = "",
    showStackTrace:  Boolean = false,
    outputToStdout:  Boolean = false
)

object Tryor {
  // the lazy param is of course necessary, else the exception is thrown
  // before going to the block, never caught.
  def apply[T](cmd: => T, errorMsg: String): Box[T] = {
    try {
      Full(cmd)
    } catch {
      case ex: Exception => Failure(s"${errorMsg}: ${ex.getMessage}", Full(ex), Empty)
    }
  }
}

object TemplateCli {

  val fillerService = new FillTemplatesService()

  val parser: OptionParser[Config] = new OptionParser[Config]("Rudder template cli") {
    head("rudder-templates-cli", "4.0.x")

    opt[File]("outdir") valueName ("<file>") action { (x, c) =>
      c.copy(outdir = x)
    } text ("output directory for filled template, default is '.'")

    opt[String]("inext").optional() valueName ("<input file extension>") action { (x, c) =>
      c.copy(inputExtension = x)
    } text ("extension of input templates. Default is '.st'")

    opt[String]("outext").optional() valueName ("<output file extension>") action { (x, c) =>
      c.copy(outputExtension = x)
    } text ("extension of templates after processing. Default is '' (no extension added)")

    opt[File]('p', "params").optional() valueName ("<variable.json>") action { (x, c) =>
      c.copy(variables = x)
    } text ("JSON file defining variables. Default is 'variables.json'. See below for format details.")

    opt[Unit]('X', "stackTrace").optional() action { (_, c) => c.copy(showStackTrace = true) } text ("Print stack trace on error")

    opt[Unit]("stdout").optional() action { (_, c) => c.copy(outputToStdout = true) } text ("Print stack trace on error")

    arg[File]("<template.st>...").optional().unbounded() action { (x, c) =>
      c.copy(templates = c.templates :+ x)
    } text ("""list of templates to fill. Only file with the correct extension (by default '.st') will
               | be processed. The extension will be replaced by '.cf' by default, ounce processed.""".stripMargin)

    help("help") text ("prints this usage text")

    note(
      """The expected format for variables.json is a simple key:value file, with value being only string, boolean or Array of string. 'system' and 'optioannal' properties can also be specified:
        | {
        |     "key1": true
        |   , "key2": "some value"
        |   , "key3": "42"
        |   , "key4": [ "some", "more", "values", true, false ]
        |   , "key5": { "value": "k5", "system": true, "optional": false }
        |   , "key6": { "value": [ "a1", "a2", "a3" ], "system": false, "optional": true }
        |   , "key7": ""
        |   , "key8": { "value": [] }
        | }
      """.stripMargin
    )
  }

  def main(args: Array[String]): Unit = {

    // in case of error with args, stop and display usage
    val config = parser.parse(args, Config()).getOrElse {
      parser.displayToOut(parser.usage)
      System.exit(1)
      // just for type inference, never reached
      Config()
    }

    process(config).either.runNow match {
      case Left(err) =>
        System.err.println(err.fullMsg)
        System.exit(1)

      case Right(()) =>
      // ok
      // here, we can't call System.exit(0), because maven.
      // seriously: http://maven.apache.org/surefire/maven-surefire-plugin/faq.html#vm-termination
      // """Surefire does not support tests or any referenced libraries calling System.exit() at any time."""
    }
  }

  /**
   * An utility method so that I can actually test things,
   * because you know, maven doesn't allow to have exit(1)
   * anywhere, so I'm going to be able to test on Full/Failure
   */
  def process(config: Config): IOResult[Unit] = {
    for {
      timer     <- FillTemplateTimer.make()
      variables <- ParseVariables.fromFile(config.variables)
      _         <- if (config.templates.nonEmpty) {
                     val filler = { // if we are writing to stdout, use a different filler and ignore outputExtension
                       if (config.outputToStdout) {
                         fillToStdout(variables.toSeq, config.inputExtension, timer)
                       } else {
                         fill(variables.toSeq, config.outdir, config.inputExtension, config.outputExtension, timer)
                       }
                     }
                     config.templates.accumulate(filler)
                   } else {
                     /*
              * If no templates are given, try to read from stdin.
              * In that case, --stdout is forced.
              */
                     for {
                       content <- readStdin()
                       ok      <- filledAndWriteToStdout(variables.toSeq, content, "stdin", timer)
                     } yield {
                       ok
                     }
                   }
    } yield ()
  }

  def readStdin(): IOResult[String] = {
    for {
      in      <- IOResult.attempt("Error when trying to access stdin")(new java.io.InputStreamReader(System.in))
      ready   <-
        if (in.ready) ZIO.unit else Inconsistency("Can not get template content from stdin and no template file given").fail
      content <- IOResult.attempt("Error when trying to read content from stdin")(IOUtils.toString(System.in, "UTF-8"))
      ok      <- if (content.length > 0) {
                   content.succeed
                 } else {
                   Inconsistency("Can not get template content from stdin and no template file given").fail
                 }
    } yield {
      ok
    }
  }

  /**
   * Utility class that handles reading from file / writing to file.
   * It takes variables and outDir as a seperate argument list so that
   * it is easier to reuse the same "filler" context for different templates
   *
   * Only file with inputExtension are processed.
   * inputExtension is replaced by outputExtension.
   */
  def fill(variables: Seq[STVariable], outDir: File, inputExtension: String, outputExtension: String, timer: FillTemplateTimer)(
      template: File
  ): IOResult[String] = {
    for {
      ok      <- if (template.getName.endsWith(inputExtension)) { ZIO.unit }
                 else {
                   Inconsistency(s"Ignoring file ${template.getName} because it does not have extension '${inputExtension}'").fail
                 }
      content <- IOResult.attempt(s"Error when reading variables from ${template.getAbsolutePath}")(
                   FileUtils.readFileToString(template, StandardCharsets.UTF_8)
                 )
      filled  <- fillerService.fill(template.getAbsolutePath, content, variables, timer)
      name     = template.getName
      out      = new File(outDir, name.substring(0, name.size - inputExtension.size) + outputExtension)
      writed  <- IOResult.attempt(s"Error when writting filled template into ${out.getAbsolutePath}")(
                   FileUtils.writeStringToFile(out, filled, StandardCharsets.UTF_8)
                 )
    } yield {
      out.getAbsolutePath
    }
  }

  /**
   * Same as fill, but print everything to stdout
   */
  def fillToStdout(variables: Seq[STVariable], inputExtension: String, timer: FillTemplateTimer)(
      template: File
  ): IOResult[String] = {
    for {
      ok      <- if (template.getName.endsWith(inputExtension)) { ZIO.unit }
                 else {
                   Inconsistency(s"Ignoring file ${template.getName} because it does not have extension '${inputExtension}'").fail
                 }
      content <- IOResult.attempt(s"Error when reading variables from ${template.getAbsolutePath}")(
                   FileUtils.readFileToString(template, StandardCharsets.UTF_8)
                 )
      writed  <- filledAndWriteToStdout(variables, content, template.getName, timer)
    } yield {
      writed
    }
  }

  def filledAndWriteToStdout(
      variables:    Seq[STVariable],
      content:      String,
      templateName: String,
      timer:        FillTemplateTimer
  ): ZIO[Any, RudderError, String] = {
    for {
      filled <- fillerService.fill(templateName, content, variables, timer)
      writed <- IOResult.attempt(s"Error when writting filled template to stdout")(IOUtils.write(filled, System.out, "UTF-8"))
    } yield {
      templateName
    }
  }

}

/**
 * Parse the JSON file for variables.
 * We only uderstand two type of value: string and boolean.
 * The expected format is:
 * {
 *     "key1": true
 *   , "key2": "some value"
 *   , "key3": "42"
 *   , "key4": [ "some", "more", "values", true, false ]
 *   , "key5": { "value": "k5", "system": true, "optional": false }
 *   , "key6": { "value": [ "a1", "a2", "a3" ], "system": false, "optional": true }
 *   , "key7": ""
 *   , "key8": { "value": [] }
 * }
 *
 *
 * Default value for system is false
 * Default value for optional is true
 *
 */
object ParseVariables extends Loggable {

  def fromFile(file: File): IOResult[Set[STVariable]] = {
    for {
      jsonString <-
        IOResult.attempt(s"Error when trying to read file ${file.getAbsolutePath}")(FileUtils.readFileToString(file, "UTF-8"))
      vars       <- fromString(jsonString)
    } yield {
      vars
    }
  }

  def fromString(jsonString: String): IOResult[Set[STVariable]] = {

    def parseAsValue(v: Json): ArraySeq[Any] = {
      v match {
        case Json.Str(value)  => ArraySeq(value)
        case Json.Bool(value) => ArraySeq(value)
        case Json.Arr(arr)    =>
          arr.map { x =>
            x match {
              case Json.Str(value)  => value
              case Json.Bool(value) => value
              // at that level, any other thing, including array, is parsed as a simple string
              case value            => value.toJson
            }
          }.to(ArraySeq)
        case value            => ArraySeq(value.toJson)
      }
    }

    // the whole logic
    for {
      json <- jsonString.fromJson[Json].left.map(_ => "Error when parsing the variable file").toIO
    } yield {
      json match {
        case Json.Obj(fields) =>
          fields.flatMap { x =>
            x match {
              case field @ (name, Json.Obj(values)) => // in that case, only value is mandatory
                val map = values.toMap

                map.get("value") match {
                  case None        =>
                    logger.info(s"Missing mandatory field 'value' in object ${Json.Obj(field).toJson}")
                    None
                  case Some(value) =>
                    val optional = map.get("optional") match {
                      case Some(Json.Bool(b)) => b
                      case _                  => true
                    }
                    val system   = map.get("system") match {
                      case Some(Json.Bool(b)) => b
                      case _                  => false
                    }

                    Some(STVariable(name, optional, parseAsValue(value), system))
                }

              // in any other case, parse as value
              case (name, value)                    => Some(STVariable(name, mayBeEmpty = true, values = parseAsValue(value), isSystem = false))
            }
          }.toSet

        case _ => Set()
      }
    }
  }
}
