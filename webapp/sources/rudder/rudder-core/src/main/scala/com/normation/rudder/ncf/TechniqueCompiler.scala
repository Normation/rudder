/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

package com.normation.rudder.ncf

import better.files.File
import cats.implicits._
import com.normation.box._
import com.normation.errors._
import com.normation.errors.IOResult
import com.normation.errors.RudderError
import com.normation.inventory.domain.AgentType
import com.normation.rudder.domain.logger.RuddercLogger
import com.normation.rudder.domain.logger.TechniqueWriterLoggerPure
import com.normation.rudder.domain.logger.TimingDebugLoggerPure
import com.normation.rudder.hooks.Cmd
import com.normation.rudder.hooks.CmdResult
import com.normation.rudder.hooks.RunNuCommand
import com.normation.rudder.ncf.ParameterType.ParameterTypeService
import com.normation.rudder.ncf.migration.MigrateJsonTechniquesService
import com.normation.rudder.repository.xml.RudderPrettyPrinter
import com.normation.rudder.repository.xml.TechniqueFiles
import com.normation.rudder.services.policies.InterpolatedValueCompiler
import com.normation.utils.Control
import com.normation.zio.currentTimeMillis
import com.normation.zio.currentTimeNanos
import java.nio.charset.StandardCharsets
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import scala.xml.{Node => XmlNode}
import scala.xml.NodeSeq
import zio._
import zio.json._
import zio.json.yaml._
import zio.syntax._

/*
 * This file deals with the technique compilation by rudderc and the fallback logic (services and data types
 * (error type, etc)).
 * The webapp writing part is still in TechniqueWriter
 */
trait TechniqueCompiler {

  /*
   * Compile given technique based on editor descriptor.
   *
   * Note: until we get ride of webapp generation, we must keep `EditorTechnique` as the main parameter of the
   * compilation service. This is because the likely main case where we will need to fallback to webapp generation
   * is for technique from editor, and in that case we have more chance to be able to fall back if we use
   * directly the data structure of the editor than if we follow a chain of translation from editor technique to yaml to
   * something back that the fallback compiler can understand.
   */
  def compileTechnique(technique: EditorTechnique): IOResult[TechniqueCompilationOutput]

  // compile based on absolute path of techniqueId/1.0 directory. If the technique is not yaml, it's an error.
  // If you have a json technique, you need to migrate it first.
  def compileAtPath(techniqueBaseDirectory: File): IOResult[TechniqueCompilationOutput] = {
    import com.normation.rudder.ncf.yaml.YamlTechniqueSerializer._
    val yamlFile = techniqueBaseDirectory / TechniqueFiles.yaml
    for {
      yaml <- IOResult.attempt(s"Error when reading technique metadata '${yamlFile}'") {
                yamlFile.contentAsString(StandardCharsets.UTF_8)
              }
      t    <- yaml.fromYaml[EditorTechnique].toIO
      _    <- EditorTechnique.checkTechniqueIdConsistency(techniqueBaseDirectory, t)
      res  <- compileTechnique(t)
    } yield res
  }

  /*
   * check if the technique is an old JSON technique (try to migrate) or a yaml technique
   * without or with old generated files.
   */
  def migrateCompileIfNeeded(techniquePath: File): IOResult[TechniqueCompilationOutput] = {
    val yamlFile    = techniquePath / TechniqueFiles.yaml
    val metadata    = techniquePath / TechniqueFiles.Generated.metadata
    val compileYaml = compileAtPath(techniquePath)
    val success     = TechniqueCompilationOutput(
      TechniqueCompilerApp.Rudderc,
      false,
      0,
      "no compilation needed: artifact are up-to-date",
      "",
      ""
    ).succeed

    for {
      _ <- MigrateJsonTechniquesService.migrateJson(techniquePath)
      x <- IOResult.attemptZIO {
             if (yamlFile.exists) {
               if (metadata.exists) {
                 if (yamlFile.lastModifiedTime.isAfter(metadata.lastModifiedTime)) {
                   compileYaml
                 } else success
               } else compileYaml
             } else success
           }
    } yield x
  }

  def getCompilationOutputFile(technique: EditorTechnique): File

  def getCompilationConfigFile(technique: EditorTechnique): File
}

/*
 * The action of actually writing the technique is either done by `rudderc`
 * (new default behavior) or by the `webapp` (old fashion).
 * We have a third option, `rudderc-unix-only`, which relies on the webapp
 * to still generate non-unix files (like ps1).
 */
sealed trait TechniqueCompilerApp { def name: String }
object TechniqueCompilerApp       {
  case object Webapp  extends TechniqueCompilerApp { val name = "webapp"  }
  case object Rudderc extends TechniqueCompilerApp { val name = "rudderc" }

  def values = ca.mrvisser.sealerate.values[TechniqueCompilerApp]

  def parse(value: String): Option[TechniqueCompilerApp] = {
    values.find(_.name == value.toLowerCase())
  }
}

/*
 * Information about the last compilation.
 * We store compiler (info, errors) messages so that they can be used in the technique UI.
 * At some point, it will be nice to have structured message in place of string.
 *
 * If the webapp fallbacked more than what was asked (ie, if the compiler asked for, either by
 * default or specifically with a local override was not used but an other was used), then the
 * fallback flag will be set.
 */
final case class TechniqueCompilationOutput(
    compiler:   TechniqueCompilerApp,
    fallbacked: Boolean,
    resultCode: Int,
    msg:        String,
    stdout:     String,
    stderr:     String
)

final case class TechniqueCompilationConfig(
    compiler: Option[TechniqueCompilerApp]
    // other overrides ? Verbosity ?
)

object TechniqueCompilationIO {

  implicit val codecTechniqueCompilerApp: JsonCodec[TechniqueCompilerApp] = JsonCodec(
    JsonEncoder.string.contramap(_.name),
    JsonDecoder.string.mapOrFail(s => {
      TechniqueCompilerApp
        .parse(s)
        .toRight(
          s"Error when parsing '${s}' as a technique compiler. Choices are: '${TechniqueCompilerApp.values.toList.map(_.name).sorted.mkString("', '")}''"
        )
    })
  )

  implicit val codecTechniqueCompilationOutput: JsonCodec[TechniqueCompilationOutput] = DeriveJsonCodec.gen
  implicit val codecTechniqueCompilationConfig: JsonCodec[TechniqueCompilationConfig] = DeriveJsonCodec.gen
}

sealed trait RuddercResult {
  def code:   Int
  def msg:    String // human formatted message / error
  def stdout: String // capture of stdout
  def stderr: String // capture of stderr
}
sealed trait RuddercError extends RuddercResult
object RuddercResult       {
  // success - capture stdout for debug etc
  final case class Ok(msg: String, stdout: String, stderr: String) extends RuddercResult { val code = 0 }

  // an error that should be displayed to user
  final case class UserError(code: Int, msg: String, stdout: String, stderr: String) extends RuddercError
  // a rudderc error that is not recoverable by the user and should lead to a fallback
  final case class Fail(code: Int, msg: String, stdout: String, stderr: String)      extends RuddercError

  // biased it toward system error for now, it can change when rudderc is mature enough
  def fromCmdResult(code: Int, userMsg: String, stdout: String, stderr: String): RuddercResult = {
    // todo: parse a rudderc output and get an error from it
    if (code == 0) {
      Ok(userMsg, stdout, stderr)
    } else if (code == USER_ERROR_CODE) {
      UserError(code, userMsg, stdout, stderr)
    } else {
      // returns 1 on internal error
      Fail(code, userMsg, stdout, stderr)
    }
  }

  val USER_ERROR_CODE = 2
}

/*
 * Option for rudder, like verbosity, etc
 */
final case class RuddercOptions(
    verbose: Boolean
)

sealed trait NcfError extends RudderError {
  def message:   String
  def exception: Option[Throwable]
  def msg = message
}

final case class IOError(message: String, exception: Option[Throwable])              extends NcfError
final case class TechniqueUpdateError(message: String, exception: Option[Throwable]) extends NcfError
final case class MethodNotFound(message: String, exception: Option[Throwable])       extends NcfError

/*
 * A trait that call Rudderc and translate it's output to things understandable by Rudder.
 * Can be mocked for tests
 */
trait RuddercService {

  // techniqueDir: absolute path to technique main directory (techniqueId/version)
  def compile(techniqueDir: File, options: RuddercOptions): IOResult[RuddercResult]
}

/*
 * notice: https://issues.rudder.io/issues/23053 => mv from target to parent at the end of compilation
 */
class RuddercServiceImpl(
    val ruddercCmd: String,
    killTimeout:    Duration
) extends RuddercService {

  def compilationOutputDir(techniqueDir: File): File = techniqueDir / "target"

  override def compile(techniqueDir: File, options: RuddercOptions): IOResult[RuddercResult] = {
    val cmd = buildCmdLine(techniqueDir, options)

    for {
      _        <- RuddercLogger.debug(s"Run rudderc: ${cmd.display}")
      time_0   <- currentTimeNanos
      p        <- RunNuCommand.run(cmd) // this can't fail, errors are captured in return code
      r        <- p.await.timeout(killTimeout).flatMap {
                    case Some(ok) =>
                      ok.succeed
                    case None     =>
                      val error = s"Rudderc ${cmd.display} timed out after ${killTimeout.render}"
                      RuddercLogger.error(error) *> CmdResult(1, "", error).succeed
                  }
      c         = translateReturnCode(techniqueDir.pathAsString, r)
      _        <- logReturnCode(c)
      // in all case, whatever the return code, move everything from target subdir if it exists to parent and delete it
      outputDir = compilationOutputDir(techniqueDir)
      _        <- ZIO.whenZIO(IOResult.attempt(outputDir.exists)) {
                    ZIO.foreach(outputDir.children.toList)(f => {
                      IOResult.attempt {
                        val dest = techniqueDir / f.name
                        if (dest.exists()) dest.delete()
                        f.moveTo(techniqueDir / f.name)(Seq[CopyOption](StandardCopyOption.REPLACE_EXISTING))
                      }
                    }) *>
                    IOResult.attempt(outputDir.delete())
                  }
      time_1   <- currentTimeNanos
      duration  = time_1 - time_0
      _        <- RuddercLogger.debug(
                    s"Done in ${duration / 1000} us: ${cmd.display}"
                  )
    } yield {
      c
    }
  }

  def buildCmdLine(techniquePath: File, options: RuddercOptions): Cmd = {
    val params = {
      (if (options.verbose) List("-v") else Nil) :::
      ("--directory" :: techniquePath.pathAsString :: "build" :: Nil)
    }

    Cmd(ruddercCmd, params, Map("NO_COLOR" -> "1"))
  }

  def logReturnCode(result: RuddercResult): IOResult[Unit] = {
    for {
      _ <- ZIO.when(RuddercLogger.logEffect.isTraceEnabled()) {
             RuddercLogger.trace(s"  -> results: ${result.stdout}") *>
             RuddercLogger.trace(s"  -> stdout : ${result.stdout}") *>
             RuddercLogger.trace(s"  -> stderr : ${result.stderr}")
           }
      _ <- ZIO.when(result.code >= 1 && result.code != RuddercResult.USER_ERROR_CODE) { // log at warning level rudderc own errors
             for {
               _ <- RuddercLogger.warn(result.stdout)
               _ <- ZIO.when(result.stdout.size > 0)(RuddercLogger.warn(s"  -> stdout : ${result.stdout}"))
               _ <- ZIO.when(result.stderr.size > 0)(RuddercLogger.warn(s"  -> stderr : ${result.stderr}"))
             } yield ()
           }
    } yield ()
  }

  def translateReturnCode(techniquePath: String, result: CmdResult): RuddercResult = {
    lazy val msg = {
      val specialCode = if (result.code == Int.MinValue) { // this is most commonly file not found or bad rights
        " (check that file exists and is executable)"
      } else ""
      s"Exit code=${result.code}${specialCode} for technique: '${techniquePath}'."
    }
    RuddercResult.fromCmdResult(result.code, msg, result.stdout, result.stderr)
  }
}

/*
 * The main compiler service, which is able to choose between rudderc and webapp based on
 * default & local config, and can fallback from rudder to webapp when needed.
 */
class TechniqueCompilerWithFallback(
    fallbackCompiler:         TechniqueCompiler,
    ruddercService:           RuddercService,
    defaultCompiler:          TechniqueCompilerApp,
    getTechniqueRelativePath: EditorTechnique => String, // get the technique path relative to git root.
    val baseConfigRepoPath:   String                     // root of config repos
) extends TechniqueCompiler {

  // root of technique repository
  val gitDir = File(baseConfigRepoPath)

  val compilationConfigFilename = "compilation-config.yml"
  val compilationOutputFilename = "compilation-output.yml"

  def getCompilationOutputFile(technique: EditorTechnique) =
    gitDir / getTechniqueRelativePath(technique) / compilationOutputFilename
  def getCompilationConfigFile(technique: EditorTechnique) =
    gitDir / getTechniqueRelativePath(technique) / compilationConfigFilename

  /*
   * This method read compilation file, compile accordingly, and write if needed the new
   * compilation file.
   */
  override def compileTechnique(technique: EditorTechnique): IOResult[TechniqueCompilationOutput] = {
    for {
      config    <- readCompilationConfigFile(technique)
      outPutFile = getCompilationOutputFile(technique)
      _         <- ZIO.whenZIO(IOResult.attempt(outPutFile.exists)) {
                     IOResult.attempt(outPutFile.delete()) // clean-up previous output
                   }
      // if compiler app is defined, recover is forbidden
      app        = config.compiler
      // clean-up generated files
      _         <- ZIO.foreach(TechniqueFiles.Generated.all) { name =>
                     IOResult.attempt((gitDir / getTechniqueRelativePath(technique) / name).delete(true))
                   }
      res       <- compileTechniqueInternal(technique, app)
      _         <- ZIO.when(res.fallbacked == true || res.resultCode != 0) {
                     writeCompilationOutputFile(technique, res)
                   }
    } yield res
  }

  /*
   * This method compile the technique using the given compiler.
   * If the compiler is rudderc or rudder-unix-only, a fallback is authorized in case
   * of error to webapp.
   * In case of error or fallback, the compilation.yml file is updated.
   */
  def compileTechniqueInternal(
      technique: EditorTechnique,
      // if app is given, then recover is forbidden
      app:       Option[TechniqueCompilerApp]
  ): IOResult[TechniqueCompilationOutput] = {

    val verbose        = false
    val ruddercOptions = RuddercOptions(verbose)

    val ruddercAll = ruddercService.compile(gitDir / getTechniqueRelativePath(technique), ruddercOptions)

    // recover from rudderc if result says so
    def recoverIfNeeded(app: TechniqueCompilerApp, r: RuddercResult): IOResult[TechniqueCompilationOutput] = {
      val ltc = TechniqueCompilationOutput(app, false, r.code, r.msg, r.stdout, r.stderr)
      r match {
        case _: RuddercResult.Fail =>
          // fallback but keep rudderc error for logs
          fallbackCompiler
            .compileTechnique(technique) *> ltc.copy(compiler = TechniqueCompilerApp.Webapp, fallbacked = true).succeed
        case _ => ltc.succeed
      }
    }

    app match {
      case None                               =>
        ruddercAll.flatMap(res => recoverIfNeeded(defaultCompiler, res))
      case Some(TechniqueCompilerApp.Webapp)  => // in that case, we can't fallback even more, so the result is final
        fallbackCompiler.compileTechnique(technique)
      case Some(TechniqueCompilerApp.Rudderc) =>
        ruddercAll.map(r => TechniqueCompilationOutput(TechniqueCompilerApp.Rudderc, false, r.code, r.msg, r.stdout, r.stderr))
    }
  }

  /*
   * read the compilation.yml file to see if user asked for webapp
   */
  def readCompilationConfigFile(technique: EditorTechnique): IOResult[TechniqueCompilationConfig] = {
    import TechniqueCompilationIO._

    for {
      content <- IOResult.attempt(s"Error when writing compilation file for technique '${getTechniqueRelativePath(technique)}'") {
                   val config = getCompilationConfigFile(technique)
                   if (config.exists) { // this is optional
                     Some(config.contentAsString(StandardCharsets.UTF_8))
                   } else {
                     None
                   }
                 }
      res     <- content match {
                   case None    => TechniqueCompilationConfig(None).succeed // default
                   case Some(x) => x.fromYaml[TechniqueCompilationConfig].toIO
                 }
    } yield res
  }

  /*
   * If compilation had error, or if we fallbacked, we need to write the compilation file.
   */
  def writeCompilationOutputFile(technique: EditorTechnique, comp: TechniqueCompilationOutput): IOResult[Unit] = {
    import TechniqueCompilationIO._
    for {
      value <- comp.toYaml().toIO
      _     <- IOResult.attempt(getCompilationOutputFile(technique).write(value))
    } yield ()
  }
}

/*
 * This class implements the old webapp-based compiler used in Rudder 7.x.
 * It is now use as a fallback for when rudderc fails or if configured.
 */
class WebappTechniqueCompiler(
    translater:               InterpolatedValueCompiler,
    xmlPrettyPrinter:         RudderPrettyPrinter,
    parameterTypeService:     ParameterTypeService,
    editorTechniqueReader:    EditorTechniqueReader,
    getTechniqueRelativePath: EditorTechnique => String, // get the technique path relative to git root.
    val baseConfigRepoPath:   String                     // root of config repos
) extends TechniqueCompiler {
  private[this] val cfengineTechniqueWriter =
    new ClassicTechniqueWriter(baseConfigRepoPath, parameterTypeService, getTechniqueRelativePath)
  private[this] val dscTechniqueWriter      =
    new DSCTechniqueWriter(baseConfigRepoPath, translater, parameterTypeService, getTechniqueRelativePath)
  private[this] val agentSpecific           = cfengineTechniqueWriter :: dscTechniqueWriter :: Nil

  override def compileTechnique(technique: EditorTechnique): IOResult[TechniqueCompilationOutput] = {
    for {
      methods <- editorTechniqueReader.getMethodsMetadata
      _       <- writeAgentFiles(technique, methods, onlyPS1 = false)
      time_1  <- currentTimeMillis
      _       <- writeMetadata(technique, methods)
      time_2  <- currentTimeMillis
      _       <- TimingDebugLoggerPure.trace(
                   s"writeTechnique: generating metadata for technique '${technique.name}' took ${time_2 - time_1}ms"
                 )
    } yield {
      TechniqueCompilationOutput(
        TechniqueCompilerApp.Webapp,
        false,
        0,
        s"Technique '${getTechniqueRelativePath(technique)}' written by webapp",
        "",
        ""
      )
    }
  }

  def writeAgentFiles(
      technique: EditorTechnique,
      methods:   Map[BundleName, GenericMethod],
      onlyPS1:   Boolean
  ): IOResult[Seq[String]] = {
    val agents = if (onlyPS1) dscTechniqueWriter :: Nil else agentSpecific

    for {
      time_1 <- currentTimeMillis
      // Create/update agent files, filter None by flattening to list
      files  <- ZIO.foreach(agents)(_.writeAgentFiles(technique, methods)).map(_.flatten)
      _      <- TechniqueWriterLoggerPure.debug(s"writeAgentFiles for technique ${technique.name} is ${files.mkString("\n")}")
      time_2 <- currentTimeMillis
      _      <- TimingDebugLoggerPure.trace(
                  s"writeTechnique: writing agent files for technique '${technique.name}' took ${time_2 - time_1}ms"
                )
    } yield {
      files
    }
  }

  def writeMetadata(technique: EditorTechnique, methods: Map[BundleName, GenericMethod]): IOResult[String] = {

    val metadataPath = s"${getTechniqueRelativePath(technique)}/metadata.xml"

    val path = s"${baseConfigRepoPath}/${metadataPath}"
    for {
      content <- techniqueMetadataContent(technique, methods).map(n => xmlPrettyPrinter.format(n)).toIO
      _       <- IOResult.attempt(s"An error occurred while creating metadata file for Technique '${technique.name}'") {
                   implicit val charSet = StandardCharsets.UTF_8
                   val file             = File(path).createFileIfNotExists(true)
                   file.write(content)
                 }
    } yield {
      metadataPath
    }
  }

  def techniqueMetadataContent(technique: EditorTechnique, methods: Map[BundleName, GenericMethod]): PureResult[XmlNode] = {

    def reportingValuePerBlock(block: MethodBlock): PureResult[NodeSeq] = {

      for {
        childs <- reportingSections(block.calls)
      } yield {
        if (childs.isEmpty) {
          NodeSeq.Empty
        } else {
          val reportingLogic = block.reportingLogic.value
          <SECTION component="true" multivalued="true" name={block.component} reporting={reportingLogic} id={block.id}>
            {childs}
          </SECTION>
        }
      }
    }

    def reportingValuePerMethod(call: MethodCall): PureResult[Seq[XmlNode]] = {
      for {
        method      <- methods.get(call.method) match {
                         case None    =>
                           Left(
                             MethodNotFound(
                               s"Cannot find method ${call.method.value} when writing a method call of Technique '${technique.id.value}'",
                               None
                             )
                           )
                         case Some(m) => Right(m)
                       }
        class_param <- call.parameters.find(_._1 == method.classParameter) match {
                         case None    =>
                           Left(
                             MethodNotFound(
                               s"Cannot find call parameter of ${call.method.value} when writing a method call of Technique '${technique.id.value}'",
                               None
                             )
                           )
                         case Some(m) => Right(m._2)
                       }

      } yield {
        val name = if (call.component.isEmpty) {
          method.name
        } else {
          call.component
        }
        <SECTION component="true" multivalued="true" id={call.id} name={name}>
          <REPORTKEYS>
            <VALUE id={call.id}>{class_param}</VALUE>
          </REPORTKEYS>
        </SECTION>

      }
    }

    def reportingSections(sections: List[MethodElem]) = {
      val expectedReportingMethodsWithValues      =
        sections.collect { case m: MethodCall => m }.filterNot(m => m.disabledReporting || m.method.value.startsWith("_"))
      val expectedGroupReportingMethodsWithValues = sections.collect { case m: MethodBlock => m }

      for {
        uniqueSection <- expectedReportingMethodsWithValues.traverse(reportingValuePerMethod)
        groupSection  <- expectedGroupReportingMethodsWithValues.traverse(reportingValuePerBlock)
      } yield {
        groupSection ::: uniqueSection
      }
    }

    def parameterSection(parameter: TechniqueParameter): Seq[XmlNode] = {
      // Here we translate technique parameters into Rudder variables
      // ncf technique parameters ( having an id and a name, which is used inside the technique) were translated into Rudder variables spec
      // (having a name, which acts as an id, and allow to do templating on techniques, and a description which is presented to users) with the following Rule
      //  ncf Parameter | Rudder variable
      //      id        |      name
      //     name       |   description
      <INPUT>
        <NAME>{parameter.id.value.toUpperCase()}</NAME>
        <DESCRIPTION>{parameter.description}</DESCRIPTION>
        <LONGDESCRIPTION>{parameter.documentation.getOrElse("")}</LONGDESCRIPTION>
        <CONSTRAINT>
          <TYPE>textarea</TYPE>
          <MAYBEEMPTY>{parameter.mayBeEmpty}</MAYBEEMPTY>
        </CONSTRAINT>
      </INPUT>
    }
    // Regroup method calls from which we expect a reporting
    // We filter those starting by _, which are internal methods

    for {
      reportingSection     <- reportingSections(technique.calls.toList)
      agentSpecificSection <- agentSpecific.traverse(_.agentMetadata(technique, methods))
    } yield {
      <TECHNIQUE name={technique.name}>
        {
        if (technique.parameters.nonEmpty) {
          <POLICYGENERATION>separated-with-parameters</POLICYGENERATION>
          <MULTIINSTANCE>true</MULTIINSTANCE>
        } else NodeSeq.Empty
      }<DESCRIPTION>{technique.description}</DESCRIPTION>
       <USEMETHODREPORTING>true</USEMETHODREPORTING>{agentSpecificSection}<SECTIONS>
        {reportingSection}{
        if (technique.parameters.nonEmpty) {
          <SECTION name="Technique parameters">
            {technique.parameters.map(parameterSection)}
          </SECTION>
        } else NodeSeq.Empty
      }
      </SECTIONS>
      </TECHNIQUE>
    }
  }

  // root of technique repository
  val gitDir = File(baseConfigRepoPath)

  val compilationConfigFilename = "compilation-config.yml"
  val compilationOutputFilename = "compilation-output.yml"

  def getCompilationOutputFile(technique: EditorTechnique) =
    gitDir / getTechniqueRelativePath(technique) / compilationOutputFilename

  def getCompilationConfigFile(technique: EditorTechnique) =
    gitDir / getTechniqueRelativePath(technique) / compilationConfigFilename

}

trait AgentSpecificTechniqueWriter {

  def writeAgentFiles(technique: EditorTechnique, methods: Map[BundleName, GenericMethod]): IOResult[Seq[String]]

  def agentMetadata(technique: EditorTechnique, methods: Map[BundleName, GenericMethod]): PureResult[NodeSeq]
}

class ClassicTechniqueWriter(
    basePath:                 String,
    parameterTypeService:     ParameterTypeService,
    getTechniqueRelativePath: EditorTechnique => String // get the technique path relative to git root.
) extends AgentSpecificTechniqueWriter {

  // We need to add a reporting bundle for this method to generate a na report for any method with a condition != any/cfengine (which ~= true
  def truthyCondition(condition: String) = condition.isEmpty || condition == "any" || condition == "cfengine-community"
  def methodCallNeedReporting(methods: Map[BundleName, GenericMethod], parentBlock: List[MethodBlock])(
      call:                            MethodCall
  ): Boolean = {
    val condition = formatCondition(call, parentBlock)
    methods
      .get(call.method)
      .map(m => !m.agentSupport.contains(AgentType.CfeCommunity) || !truthyCondition(condition))
      .getOrElse(true)
  }

  def elemNeedReportingBundle(methods: Map[BundleName, GenericMethod], parentBlock: List[MethodBlock])(
      elem:                            MethodElem
  ): Boolean = {
    elem match {
      case c: MethodCall  => methodCallNeedReporting(methods, parentBlock)(c)
      case b: MethodBlock => !truthyCondition(b.condition) || b.calls.exists(elemNeedReportingBundle(methods, b :: parentBlock))
    }
  }

  def formatCondition(methodCall: MethodCall, parentBlock: List[MethodBlock]) = {
    (parentBlock.map(_.condition).filterNot(truthyCondition), truthyCondition(methodCall.condition)) match {
      case (Nil, true)   => "any"
      case (list, true)  => list.mkString("(", ").(", ")")
      case (Nil, false)  => methodCall.condition
      case (list, false) => list.mkString("(", ").(", s".${methodCall.condition})")
    }
  }

  def needReportingBundle(technique: EditorTechnique, methods: Map[BundleName, GenericMethod]) =
    technique.calls.exists(elemNeedReportingBundle(methods, Nil))

  def canonifyCondition(methodCall: MethodCall, parentBlock: List[MethodBlock]) = {
    formatCondition(methodCall, parentBlock).replaceAll("""(\$\{[^\}]*})""", """",canonify("$1"),"""")
  }

  // regex to match double quote characters not preceded by a backslash, and backslash not preceded by backslash or not followed by a backslash or a quote (simple or double)
  def escapeCFEngineString(value: String) = value.replaceAll("""\\""", """\\\\""").replaceAll(""""""", """\\"""")

  def reportingContextInBundle(args: Seq[String]) = {
    s"_method_reporting_context_v4(${convertArgsToBundleCall(args)})"
  }

  def convertArgsToBundleCall(args: Seq[String]): String = {
    args.map(escapeCFEngineString(_)).map(""""${""" + _ + """}"""").mkString(",")
  }

  override def writeAgentFiles(technique: EditorTechnique, methods: Map[BundleName, GenericMethod]): IOResult[Seq[String]] = {

    // increment of the bundle number in the technique, used by createCallingBundle
    var bundleIncrement = 0

    val bundleParams =
      if (technique.parameters.nonEmpty) technique.parameters.map(_.name).mkString("(", ",", ")") else ""

    // generate a bundle which encapsulate the method_reporting_context + the actual method call
    // and the method to call this bundle
    // Params are:
    // * condition when to call this bundle
    // * the methodCall itself from the technique editor
    // * the class parameter _value_
    // * all the parameters already converted to cfengine format
    // * if it's for the NAReports bundle (reverse the condition from if to unless)
    // Returns the bundle agent, and the "promised_" usebundle => bundle_created
    def createCallingBundle(
        condition:           String,
        call:                MethodCall,
        classParameterValue: String,
        params:              Seq[String],
        forNaReport:         Boolean
    ) = {
      val promiser = call.id + "_${report_data.directive_id}"

      val filterOnMethod = forNaReport match {
        case false => "if"
        case true  => "unless"
      }

      val method = methods.get(call.method)

      val name = if (call.component.isEmpty) method.map(_.name).getOrElse(call.method.value) else call.component

      // Reporting argument
      val reportingValues = escapeCFEngineString(name) ::
        escapeCFEngineString(classParameterValue) ::
        call.id :: Nil
      // create the bundle arguments:
      // there are 3 arguments corresponding to the reportingValues, (need to be quoted)
      // the rest is for the methodArgs.
      val allValues       = reportingValues.map(x => s""""${x}"""") ::: params.toList

      // Get all bundle argument names
      val bundleName = (technique.id.value + "_gm_" + bundleIncrement).replaceAll("-", "_")
      bundleIncrement = bundleIncrement + 1

      val reportingArgs = "c_name" :: "c_key" :: "report_id" :: Nil

      val (bundleArgs, bundleNameAndArg) = forNaReport match {
        case false =>
          val args = params.toList.zipWithIndex.map {
            case (_, id) =>
              method.flatMap(_.parameters.get(id.toLong).map(_.id.value)).getOrElse("arg_" + id)
          }
          (args, s"""${call.method.value}(${convertArgsToBundleCall(args)});""")

        case true =>
          // special case for log_na_rudder; args muse be called with @
          val args = "message" :: "class_parameter" :: "unique_prefix" :: "args" :: Nil
          (args, """log_na_rudder("${message}","${class_parameter}","${unique_prefix}",@{args});""")
      }

      val allArgs = reportingArgs ::: bundleArgs

      // The bundle that will effectively act
      val bundleActing = {
        val bundleCall = {
          s"""    "${promiser}" usebundle => ${reportingContextInBundle(reportingArgs)};
             |    "${promiser}" usebundle => ${bundleNameAndArg}""".stripMargin('|')
        }

        val bundleCallWithReportOption = {
          if (call.disabledReporting) {
            s"""    "${promiser}" usebundle => disable_reporting;
               |${bundleCall}
               |    "${promiser}" usebundle => enable_reporting;""".stripMargin('|')
          } else {
            bundleCall
          }
        }

        s"""bundle agent ${bundleName}(${allArgs.mkString(", ")}) {
           |  methods:
           |${bundleCallWithReportOption}
           |}
           |""".stripMargin('|')
      }

      // the call to the bundle
      val callBundle = {
        s"""    "${promiser}" usebundle => ${bundleName}(${allValues.mkString(", ")}),
           |     ${promiser.map(_ => ' ')}         ${filterOnMethod} => concat("${condition}");
           |""".stripMargin('|')

      }
      (bundleActing, callBundle)
    }

    // returns the bundle acting, and the method to call the bundle
    def bundleMethodCall(parentBlocks: List[MethodBlock])(method: MethodElem): List[(String, String)] = {
      method match {
        case call:  MethodCall  =>
          (for {
            method_info              <- methods.get(call.method)
            (_, classParameterValue) <- call.parameters.find(_._1 == method_info.classParameter)

            params <- Control.sequence(method_info.parameters) { p =>
                        for {
                          (_, value) <- Box(call.parameters.find(_._1 == p.id))
                          escaped    <- parameterTypeService.translate(value, p.parameterType, AgentType.CfeCommunity).toBox
                        } yield {
                          escaped
                        }
                      }
          } yield {
            val condition = canonifyCondition(call, parentBlocks)
            createCallingBundle(condition, call, classParameterValue, params, false)
          }).toList
        case block: MethodBlock =>
          block.calls.flatMap(bundleMethodCall(block :: parentBlocks))
      }
    }
    val bundleAndMethodCallsList = technique.calls.flatMap(bundleMethodCall(Nil))

    val bundleActings = bundleAndMethodCallsList.map(_._1).mkString("")
    val methodsCalls  = bundleAndMethodCallsList.map(_._2).mkString("")

    val content = {
      import net.liftweb.json._
      import net.liftweb.json.JsonDSL._

      s"""# @name ${technique.name}
         |# @description ${technique.description.replaceAll("\\R", "\n# ")}
         |# @version ${technique.version.value}
         |${technique.parameters.map { p =>
          val param =
            ("name" -> p.name) ~ ("id" -> p.id.value) ~ ("description" -> p.description.replaceAll("\\R", "£# "))
          // for historical reason, we want to have real \n in the string, and not the char \n (because of how liftweb print them)
          s"""# @parameter ${compactRender(param).replaceAll("£#", "\n#")}"""
        }.mkString("\n")}
         |
         |bundle agent ${technique.id.value}${bundleParams}
         |{
         |  vars:
         |    "resources_dir" string => "$${this.promise_dirname}/resources";
         |  classes:
         |    "pass3" expression => "pass2";
         |    "pass2" expression => "pass1";
         |    "pass1" expression => "any";
         |  methods:
         |${methodsCalls}
         |}
         |
         |${bundleActings}""".stripMargin('|')
    }

    implicit val charset = StandardCharsets.UTF_8
    val techFile         =
      File(basePath) / getTechniqueRelativePath(technique) / "technique.cf"
    val t                = {
      IOResult.attempt(s"Could not write na reporting Technique file '${technique.name}' in path ${techFile.path.toString}") {
        techFile.createFileIfNotExists(true).write(content.stripMargin('|'))
        File(basePath).relativize(techFile.path).toString
      }
    }

    val t2 = if (!needReportingBundle(technique, methods)) {
      ZIO.succeed(Nil)
    } else {

      val bundleParams =
        if (technique.parameters.nonEmpty) technique.parameters.map(_.name).mkString("(", ",", ")") else ""
      val args         = technique.parameters.map(p => s"$${${p.name}}").mkString(", ")

      def bundleMethodCall(parentBlocks: List[MethodBlock])(method: MethodElem): List[(String, String)] = {
        method match {
          case c:     MethodCall  =>
            val call = MethodCall.renameParams(c, methods).copy(method = BundleName("log_na_rudder"))
            (for {
              method_info              <- methods.get(c.method)
              // Skip that method if name starts with _
              if !c.method.value.startsWith("_")
              (_, classParameterValue) <- call.parameters.find(_._1 == method_info.classParameter)

              escapedClassParameterValue = escapeCFEngineString(classParameterValue)
              classPrefix                = s"$${class_prefix}_${method_info.classPrefix}_${escapedClassParameterValue}"

            } yield {
              def naReport(condition: String, message: String) = {

                val params =
                  s""""${message}"""" :: s""""${escapedClassParameterValue}"""" :: s""""${classPrefix}"""" :: "@{args}" :: Nil

                createCallingBundle(condition, call, classParameterValue, params, true)
              }

              // Write report if the method does not support CFEngine ...
              (if (!method_info.agentSupport.contains(AgentType.CfeCommunity)) {
                 val message   = s"""'${method_info.name}' method is not available on Linux Rudder agent, skip"""
                 val condition = "false"
                 Some((condition, message))
               } else {
                 // ... or if the condition needs rudder_reporting
                 if (methodCallNeedReporting(methods, parentBlocks)(c)) {
                   val condition       = formatCondition(c, parentBlocks)
                   val message         =
                     s"""Skipping method '${method_info.name}' with key parameter '${escapedClassParameterValue}' since condition '${condition}' is not reached"""
                   val canon_condition = s"${canonifyCondition(call, parentBlocks)}"
                   Some((canon_condition, message))
                 } else {
                   None
                 }
               }).map((naReport _).tupled)
            }).toList.flatten
          case block: MethodBlock =>
            block.calls.flatMap(bundleMethodCall(block :: parentBlocks))
        }
      }
      val bundleAndMethodCallsList = technique.calls.flatMap(bundleMethodCall(Nil))

      val bundleActings = bundleAndMethodCallsList.map(_._1).mkString("")
      val methodsCalls  = bundleAndMethodCallsList.map(_._2).mkString("")

      val content = {
        s"""bundle agent ${technique.id.value}_rudder_reporting${bundleParams}
           |{
           |  vars:
           |    "args"               slist => { ${args} };
           |    "report_param"      string => join("_", args);
           |    "full_class_prefix" string => canonify("${technique.id.value}_rudder_reporting_$${report_param}");
           |    "class_prefix"      string => string_head("$${full_class_prefix}", "1000");
           |
           |  methods:
           |${methodsCalls}
           |}
           |
           |${bundleActings}"""
      }

      val reportingFile = File(
        basePath
      ) / getTechniqueRelativePath(technique) / TechniqueFiles.Generated.cfengineReporting
      IOResult.attempt(
        s"Could not write na reporting Technique file '${technique.name}' in path ${reportingFile.path.toString}"
      ) {
        reportingFile.createFileIfNotExists(true).write(content.stripMargin('|'))
        Seq(File(basePath).relativize(reportingFile.path).toString)
      }
    }

    for {
      tech <- t
      repo <- t2
    } yield {
      tech +: repo
    }
  }

  override def agentMetadata(technique: EditorTechnique, methods: Map[BundleName, GenericMethod]): PureResult[NodeSeq] = {

    val needReporting = needReportingBundle(technique, methods)
    val xml           = <AGENT type="cfengine-community,cfengine-nova">
      <BUNDLES>
        <NAME>{technique.id.value}</NAME>
        {if (needReporting) <NAME>{technique.id.value}_rudder_reporting</NAME> else NodeSeq.Empty}
      </BUNDLES>
      <FILES>
        <FILE name={
      s"RUDDER_CONFIGURATION_REPOSITORY/${getTechniqueRelativePath(technique)}/technique.cf"
    }>
          <INCLUDED>true</INCLUDED>
        </FILE>
        {
      if (needReporting) {
        <FILE name={
          s"RUDDER_CONFIGURATION_REPOSITORY/${getTechniqueRelativePath(technique)}/${TechniqueFiles.Generated.cfengineReporting}"
        }>
            <INCLUDED>true</INCLUDED>
          </FILE>
      } else NodeSeq.Empty
    }
        {
      for {
        resource <- technique.resources
        if resource.state != ResourceFileState.Deleted
      } yield {
        <FILE name={
          s"RUDDER_CONFIGURATION_REPOSITORY/${getTechniqueRelativePath(technique)}/resources/${resource.path}"
        }>
              <INCLUDED>false</INCLUDED>
              <OUTPATH>{technique.id.value}/{technique.version.value}/resources/{resource.path}</OUTPATH>
            </FILE>
      }
    }
      </FILES>
    </AGENT>
    Right(xml)
  }

}

class DSCTechniqueWriter(
    basePath:                 String,
    translater:               InterpolatedValueCompiler,
    parameterTypeService:     ParameterTypeService,
    getTechniqueRelativePath: EditorTechnique => String // get the technique path relative to git root.

) extends AgentSpecificTechniqueWriter {
  implicit class IndentString(s: String) {
    // indent all lines EXCLUDING THE FIRST by the given number of spaces
    def indentNextLines(spaces: Int) = s.linesIterator.mkString("\n" + " " * spaces)
  }

  // WARNING: this is extremely likely false, it MUST be done in the technique editor or
  // via a full fledge parser of conditions
  def canonifyCondition(methodCall: MethodCall, parentBlocks: List[MethodBlock]) = {
    formatCondition(methodCall, parentBlocks).replaceAll(
      """(\$\{[^\}]*})""",
      """" + ([Rudder.Condition]::canonify($1)) + """"
    )
  }

  def truthyCondition(condition: String) = condition.isEmpty || condition == "any"

  def formatCondition(methodCall: MethodCall, parentBlock: List[MethodBlock]) = {
    (parentBlock.map(_.condition).filterNot(truthyCondition), truthyCondition(methodCall.condition)) match {
      case (Nil, true)   => "any"
      case (list, true)  => list.mkString("(", ").(", ")")
      case (Nil, false)  => methodCall.condition
      case (list, false) => list.mkString("(", ").(", s".${methodCall.condition})")
    }
  }

  // needed for override in tests
  def pathOf(technique: EditorTechnique, filename: String) =
    s"${getTechniqueRelativePath(technique)}}/${filename}"

  def formatDscMethodBlock(techniqueName: String, methods: Map[BundleName, GenericMethod], parentBlocks: List[MethodBlock])(
      method:                             MethodElem
  ): PureResult[List[String]] = {
    method match {
      case c: MethodCall =>
        val call = MethodCall.renameParams(c, methods)
        if (call.method.value.startsWith("_")) {
          Right(Nil)
        } else {

          (for {

            // First translate parameters to Dsc values
            params         <-
              ((call.parameters.toList).traverse {
                case (id, arg) =>
                  translater.translateToAgent(arg, AgentType.Dsc) match {
                    case Full(dscValue) =>
                      parameterTypeService
                        .translate(
                          dscValue,
                          methods
                            .get(call.method)
                            .flatMap(_.parameters.find(_.id == id))
                            .map(_.parameterType)
                            .getOrElse(ParameterType.StringParameter),
                          AgentType.Dsc
                        )
                        .map(dscValue => (id, dscValue))

                    case eb: EmptyBox =>
                      val fail =
                        eb ?~! s"Error when translating parameter '${arg}' of technique of method ${call.method} of technique ${techniqueName}"
                      Left(IOError(fail.messageChain, None))
                  }
              }).map(_.toMap)

            // Translate condition
            condition      <- translater.translateToAgent(canonifyCondition(call, parentBlocks), AgentType.Dsc) match {
                                case Full(c) => Right(c)
                                case eb: EmptyBox =>
                                  val fail =
                                    eb ?~! s"Error when translating condition '${call.condition}' of technique of method ${call.method} of technique ${techniqueName}"
                                  Left(IOError(fail.messageChain, None))
                              }

            // Check if method exists
            method         <- methods.get(call.method) match {
                                case Some(method) =>
                                  Right(method)
                                case None         =>
                                  Left(
                                    MethodNotFound(
                                      s"Method '${call.method.value}' not found when writing dsc Technique '${techniqueName}' methods calls",
                                      None
                                    )
                                  )
                              }
            // Check if class parameter is correctly defined
            classParameter <- params.get(method.classParameter) match {
                                case Some(classParameter) =>
                                  Right(classParameter)
                                case None                 =>
                                  Left(
                                    MethodNotFound(
                                      s"Parameter '${method.classParameter.value}' for method '${method.id.value}' not found when writing dsc Technique '${techniqueName}' methods calls",
                                      None
                                    )
                                  )
                              }

            methodParams  = params.map { case (id, arg) => s"""-${id.validDscName} ${arg}""" }.mkString(" ")
            effectiveCall =
              s"""$$call = ${call.method.validDscName} ${methodParams} -PolicyMode $$policyMode""" // methodParams can be multiline text
            // so we should never indent it
            methodContext = s"""$$methodContext = Compute-Method-Call @reportParams -MethodCall $$call
                               |$$localContext.merge($$methodContext)
                               |""".stripMargin

          } yield {

            val name             = if (call.component.isEmpty) {
              method.name
            } else {
              call.component
            }
            val componentName    = name.replaceAll("\"", "`\"")
            val disableReporting = if (call.disabledReporting) {
              "true"
            } else {
              "false"
            }
            val methodCall       = if (method.agentSupport.contains(AgentType.Dsc)) {
              if (condition == "any") {
                s"""  ${effectiveCall}
                   |  ${methodContext.indentNextLines(2)}""".stripMargin
              } else {
                s"""  $$class = "${condition}"
                   |  if ($$localContext.Evaluate($$class)) {
                   |    ${effectiveCall}
                   |    ${methodContext.indentNextLines(4)}
                   |  } else {
                   |    Rudder-Report-NA @reportParams
                   |  }""".stripMargin('|')
              }
            } else {
              s"  Rudder-Report-NA @reportParams"
            }

            s"""|  $$reportId=$$reportIdBase+"${call.id}"
                |  $$componentKey = ${classParameter}
                |  $$reportParams = @{
                |    ClassPrefix = ([Rudder.Condition]::canonify(("${method.classPrefix}_" + $$componentKey)))
                |    ComponentKey = $$componentKey
                |    ComponentName = "${componentName}"
                |    PolicyMode = $$policyMode
                |    ReportId = $$reportId
                |    DisableReporting = $$${disableReporting}
                |    TechniqueName = $$techniqueName
                |  }
                |${methodCall}""".stripMargin('|') :: Nil

          })
        }

      case block: MethodBlock =>
        block.calls.flatTraverse(formatDscMethodBlock(techniqueName, methods, block :: parentBlocks))
    }
  }

  override def writeAgentFiles(technique: EditorTechnique, methods: Map[BundleName, GenericMethod]): IOResult[Seq[String]] = {

    val parameters = technique.parameters.map { p =>
      val mandatory = if (p.mayBeEmpty) "$false" else "$true"
      s"""      [parameter(Mandatory=${mandatory})]
         |      [string]$$${p.name},"""
    }.mkString("\n").stripMargin('|')

    val techniquePath       = getTechniqueRelativePath(technique) + "/technique.ps1"
    val techniqueParameters =
      technique.parameters.map(p => s"""    "${p.name}" = $$${p.name}""").mkString("\n")

    for {

      calls <- technique.calls.toList.flatTraverse(formatDscMethodBlock(technique.name, methods, Nil)).toIO

      content = {
        s"""|function ${technique.id.validDscName} {
            |  [CmdletBinding()]
            |  param (
            |      [parameter(Mandatory=$$true)]
            |      [string]$$reportId,
            |      [parameter(Mandatory=$$true)]
            |      [string]$$techniqueName,
            |${parameters}
            |      [Rudder.PolicyMode]$$policyMode
            |  )
            |  $$techniqueParams = @{
            |${techniqueParameters}
            |  }
            |  BeginTechniqueCall -Name $$techniqueName -Parameters $$techniqueParams
            |  $$reportIdBase = $$reportId.Substring(0,$$reportId.Length-1)
            |  $$localContext = New-Object -TypeName "Rudder.Context" -ArgumentList @($$techniqueName)
            |  $$localContext.Merge($$system_classes)
            |  $$resources_dir = $$PSScriptRoot + "\\resources"
            |
            |${calls.mkString("\n\n")}
            |  EndTechniqueCall -Name $$techniqueName
            |}""".stripMargin('|')
      }

      path          <- IOResult.attempt(s"Could not find dsc Technique '${technique.name}' in path ${basePath}/${techniquePath}")(
                         Paths.get(s"${basePath}/${techniquePath}")
                       )
      // Powershell files needs to have a BOM added at the beginning of all files when using UTF8 enoding
      // See https://docs.microsoft.com/en-us/windows/desktop/intl/using-byte-order-marks
      // Bom, three bytes: EF BB BF https://en.wikipedia.org/wiki/Byte_order_mark
      contentWithBom = Array(239.toByte, 187.toByte, 191.toByte) ++ content.getBytes(StandardCharsets.UTF_8)

      files <- IOResult.attempt(s"Could not write dsc Technique file '${technique.name}' in path ${basePath}/${techniquePath}") {
                 Files.createDirectories(path.getParent)
                 Files.write(path, contentWithBom.toArray)
               }
    } yield {
      techniquePath :: Nil
    }
  }

  override def agentMetadata(technique: EditorTechnique, methods: Map[BundleName, GenericMethod]) = {
    val xml = <AGENT type="dsc">
      <BUNDLES>
        <NAME>{technique.id.validDscName}</NAME>
      </BUNDLES>
      <FILES>
        <FILE name={s"RUDDER_CONFIGURATION_REPOSITORY/${getTechniqueRelativePath(technique)}/technique.ps1"}>
          <INCLUDED>true</INCLUDED>
        </FILE> {
      for {
        resource <- technique.resources
        if resource.state != ResourceFileState.Deleted
      } yield {
        <FILE name={
          s"RUDDER_CONFIGURATION_REPOSITORY/${getTechniqueRelativePath(technique)}/resources/${resource.path}"
        }>
              <INCLUDED>false</INCLUDED>
              <OUTPATH>{technique.id.value}/{technique.version.value}/resources/{resource.path}</OUTPATH>
            </FILE>
      }
    }
      </FILES>
    </AGENT>
    Right(xml)
  }

}
