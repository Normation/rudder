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
import cats.implicits.*
import com.normation.errors.*
import com.normation.errors.IOResult
import com.normation.errors.RudderError
import com.normation.rudder.domain.logger.RuddercLogger
import com.normation.rudder.hooks.Cmd
import com.normation.rudder.hooks.CmdResult
import com.normation.rudder.hooks.RunNuCommand
import com.normation.rudder.ncf.migration.MigrateJsonTechniquesService
import com.normation.rudder.repository.xml.TechniqueFiles
import com.normation.zio.currentTimeNanos
import enumeratum.*
import java.nio.charset.StandardCharsets
import java.nio.file.CopyOption
import java.nio.file.StandardCopyOption
import zio.*
import zio.json.*
import zio.json.yaml.*
import zio.syntax.*

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
    import com.normation.rudder.ncf.yaml.YamlTechniqueSerializer.*
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
      resultCode = 0,
      fileStatus = Chunk.empty,
      msg = "no compilation needed: artifact are up-to-date",
      stdout = "",
      stderr = ""
    ).succeed

    for {
      _ <- RuddercLogger.debug(s"Migrate/recompile '${techniquePath.pathAsString}' if needed'")
      _ <- MigrateJsonTechniquesService.migrateJson(techniquePath)
      x <- IOResult.attemptZIO {
             if (yamlFile.exists) {
               if (metadata.exists) {
                 if (yamlFile.lastModifiedTime.isAfter(metadata.lastModifiedTime)) {
                   RuddercLogger.debug(
                     s"'${techniquePath.pathAsString}': YAML descriptor is more recent than XML metadata, recompiling"
                   ) *> compileYaml
                 } else success
               } else {
                 RuddercLogger.debug(
                   s"'${techniquePath.pathAsString}': XML metadata missing, recompiling"
                 ) *> compileYaml
               }
             } else {
               RuddercLogger.debug(
                 s"'${techniquePath.pathAsString}': YAML descriptor doesn't exist: assuming it's an old technique format, ignore"
               ) *> success
             }
           }
    } yield x
  }

  def getCompilationOutputFile(technique: EditorTechnique): File

  def getCompilationConfigFile(technique: EditorTechnique): File

  def getCompilationOutput(technique: EditorTechnique): IOResult[Option[TechniqueCompilationOutput]] = {
    import TechniqueCompilationIO.codecTechniqueCompilationOutput
    val file = getCompilationOutputFile(technique)
    (for {
      f       <- IOResult.attempt(Option.when(file.exists)(file))
      content <-
        ZIO.foreach(f)(c => IOResult.attempt(c.contentAsString(StandardCharsets.UTF_8)))
      out     <- ZIO.foreach(content)(_.fromYaml[TechniqueCompilationOutput].toIO)
    } yield {
      out
    }).chainError(s"error when reading compilation output of technique ${technique.id.value}/${technique.version.value}")
  }
}

/*
 * The action of actually writing the technique is either done by `rudderc`.
 * We previously had the option to do it using the webapp itself, but it is now outdated.
 * We have a third option, `rudderc-unix-only`, which relies on the webapp
 * to still generate non-unix files (like ps1).
 */
sealed trait TechniqueCompilerApp extends EnumEntry {
  def name: String
}

object TechniqueCompilerApp extends Enum[TechniqueCompilerApp] {
  case object Rudderc extends TechniqueCompilerApp { val name = "rudderc" }

  val values: IndexedSeq[TechniqueCompilerApp] = findValues

  def parse(value: String): Option[TechniqueCompilerApp] = {
    values.find(_.name == value.toLowerCase())
  }
}

/*
 * Information about the last compilation.
 * We store compiler (info, errors) messages so that they can be used in the technique UI.
 * At some point, it will be nice to have structured message in place of string.
 */
final case class TechniqueCompilationOutput(
    compiler:   TechniqueCompilerApp,
    resultCode: Int,
    fileStatus: Chunk[ResourceFile],
    msg:        String,
    stdout:     String,
    stderr:     String
) {
  def isError: Boolean = resultCode != 0
}

// additional param to give to rudderc
final case class TechniqueCompilationConfig(
    args: Option[List[String]]
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

  implicit val codecResourceFileState: JsonCodec[ResourceFileState] = {
    new JsonCodec(
      JsonEncoder.string.contramap(_.value),
      JsonDecoder.string.mapOrFail(ResourceFileState.parse(_).left.map(_.fullMsg))
    )
  }
  implicit val codecResourceFile:      JsonCodec[ResourceFile]      = DeriveJsonCodec.gen

  implicit val codecTechniqueCompilationOutput: JsonCodec[TechniqueCompilationOutput] = DeriveJsonCodec.gen
  implicit val codecTechniqueCompilationConfig: JsonCodec[TechniqueCompilationConfig] = DeriveJsonCodec.gen
}

sealed trait RuddercResult {
  def code:       Int
  def fileStatus: Chunk[ResourceFile]
  def msg:        String // human formatted message / error
  def stdout:     String // capture of stdout
  def stderr:     String // capture of stderr
}
sealed trait RuddercError extends RuddercResult
object RuddercResult       {
  // success - capture stdout for debug etc
  final case class Ok(fileStatus: Chunk[ResourceFile], msg: String, stdout: String, stderr: String) extends RuddercResult {
    val code = 0
  }

  // an error that should be displayed to user
  final case class UserError(code: Int, fileStatus: Chunk[ResourceFile], msg: String, stdout: String, stderr: String)
      extends RuddercError
  // a rudderc error that is not recoverable by the user and should lead to a fallback
  final case class Fail(code: Int, fileStatus: Chunk[ResourceFile], msg: String, stdout: String, stderr: String)
      extends RuddercError

  // biased it toward system error for now, it can change when rudderc is mature enough
  def fromCmdResult(code: Int, files: Chunk[ResourceFile], userMsg: String, stdout: String, stderr: String): RuddercResult = {
    // todo: parse a rudderc output and get an error from it
    if (code == 0) {
      Ok(files, userMsg, stdout, stderr)
    } else if (code == USER_ERROR_CODE) {
      UserError(code, files, userMsg, stdout, stderr)
    } else {
      // returns 1 on internal error
      Fail(code, files, userMsg, stdout, stderr)
    }
  }

  val USER_ERROR_CODE = 2
}

/*
 * Option for rudder, like verbosity, etc
 */
final case class RuddercOptions(
    args: List[String]
)
object RuddercOptions {
  def fromConfig(config: TechniqueCompilationConfig): RuddercOptions = {
    RuddercOptions(config.args.toList.flatten)
  }
}

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
      // in all case, whatever the return code, move everything from target subdir if it exists to parent and delete it
      outputDir = compilationOutputDir(techniqueDir)
      files    <- ZIO
                    .whenZIO(IOResult.attempt(outputDir.exists)) {
                      ZIO.foreach(Chunk.fromIterator(outputDir.children)) { f =>
                        IOResult.attempt {
                          val dest = techniqueDir / f.name
                          val s    = if (dest.exists()) {
                            dest.delete()
                            ResourceFile(dest.name, ResourceFileState.Modified)
                          } else {
                            ResourceFile(dest.name, ResourceFileState.New)
                          }
                          f.moveTo(techniqueDir / f.name)(Seq[CopyOption](StandardCopyOption.REPLACE_EXISTING))
                          s
                        }
                      } <*
                      IOResult.attempt(outputDir.delete())
                    }
                    .map(_.getOrElse(Chunk.empty))
      c         = translateReturnCode(techniqueDir.pathAsString, r, files)
      _        <- logReturnCode(c)
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
      options.args :::
      ("--directory" :: techniquePath.pathAsString :: "build" :: Nil)
    }

    Cmd(ruddercCmd, params, Map("NO_COLOR" -> "1"), None)
  }

  def logReturnCode(result: RuddercResult): IOResult[Unit] = {
    for {
      _ <- ZIO.when(RuddercLogger.logEffect.isTraceEnabled()) {
             RuddercLogger.trace(s"  -> results: ${result.code.toString}") *>
             RuddercLogger.trace(
               s"  -> modified files: ${result.fileStatus.map(s => s"${s.path}: ${s.state.value}").mkString(" ; ")}"
             ) *>
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

  def translateReturnCode(techniquePath: String, result: CmdResult, fileStates: Chunk[ResourceFile]): RuddercResult = {
    lazy val msg = {
      val specialCode = if (result.code == Int.MinValue) { // this is most commonly file not found or bad rights
        " (check that file exists and is executable)"
      } else ""
      s"Exit code=${result.code}${specialCode} for technique: '${techniquePath}'."
    }
    RuddercResult.fromCmdResult(result.code, fileStates, msg, result.stdout, result.stderr)
  }
}

/*
 * The main compiler service that handles filesystem techniques,
 * and uses the rudderc service to compile techniques and manages the filesystem.
 */
class RuddercTechniqueCompiler(
    ruddercService:           RuddercService,
    getTechniqueRelativePath: EditorTechnique => String, // get the technique path relative to git root.
    val baseConfigRepoPath:   String                     // root of config repos
) extends TechniqueCompiler {

  // root of technique repository
  val gitDir: File = File(baseConfigRepoPath)

  val compilationConfigFilename: String = "compilation-config.yml"
  val compilationOutputFilename: String = "compilation-output.yml"

  def getCompilationOutputFile(technique: EditorTechnique): File =
    gitDir / getTechniqueRelativePath(technique) / compilationOutputFilename
  def getCompilationConfigFile(technique: EditorTechnique): File =
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
      // clean-up generated files
      _         <- ZIO.foreach(TechniqueFiles.Generated.all) { name =>
                     IOResult.attempt((gitDir / getTechniqueRelativePath(technique) / name).delete(true))
                   }
      res       <- compileTechniqueInternal(technique, config)
      _         <- ZIO.when(res.isError) {
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
      config:    TechniqueCompilationConfig
  ): IOResult[TechniqueCompilationOutput] = {

    val ruddercOptions = RuddercOptions.fromConfig(config)

    val ruddercAll = ruddercService.compile(gitDir / getTechniqueRelativePath(technique), ruddercOptions)

    ruddercAll.map { r =>
      TechniqueCompilationOutput(
        TechniqueCompilerApp.Rudderc,
        resultCode = r.code,
        fileStatus = r.fileStatus,
        msg = r.msg,
        stdout = r.stdout,
        stderr = r.stderr
      )
    }
  }

  /*
   * read the compilation.yml file to see if user asked for webapp
   */
  def readCompilationConfigFile(technique: EditorTechnique): IOResult[TechniqueCompilationConfig] = {
    import TechniqueCompilationIO.*

    for {
      content <- IOResult.attempt(s"Error when writing compilation file for technique '${getTechniqueRelativePath(technique)}'") {
                   val config = getCompilationConfigFile(technique)
                   if (config.exists) { // this is optional
                     val s = config.contentAsString(StandardCharsets.UTF_8)
                     if (s.strip().isEmpty) None else Some(s)
                   } else {
                     None
                   }
                 }
      res     <- content match {
                   case None    => TechniqueCompilationConfig(None).succeed // default
                   case Some(x) =>
                     x.fromYaml[TechniqueCompilationConfig]
                       .toIO
                       .chainError(
                         s"Could not read technique compilation configuration YAML file ${getCompilationConfigFile(technique)}"
                       )
                 }
    } yield res
  }

  /*
   * If compilation had error, or if we fallbacked, we need to write the compilation file.
   */
  def writeCompilationOutputFile(technique: EditorTechnique, comp: TechniqueCompilationOutput): IOResult[Unit] = {
    import TechniqueCompilationIO.*
    for {
      value <- comp.toYaml().toIO
      _     <- IOResult.attempt(getCompilationOutputFile(technique).write(value))
    } yield ()
  }
}
