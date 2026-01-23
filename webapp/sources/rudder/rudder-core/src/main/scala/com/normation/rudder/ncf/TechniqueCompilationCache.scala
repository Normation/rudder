/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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
import cats.syntax.functor.*
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.errors.IOResult
import com.normation.inventory.domain.Version
import com.normation.rudder.batch.UpdateTechniqueStatus
import com.normation.rudder.domain.logger.StatusLoggerPure
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.rudder.repository.RoDirectiveRepository
import net.liftweb.common.SimpleActor
import zio.*
import zio.syntax.*

sealed trait CompilationResult
object CompilationResult {
  case object Success                extends CompilationResult
  case class Error(errorMsg: String) extends CompilationResult
}

case class EditorTechniqueCompilationResult(
    id:      BundleName,
    version: Version,
    name:    String,
    result:  CompilationResult
)

opaque type EditorTechniqueCheckResult = EditorTechniqueParsingError | EditorTechniqueCompilationResult
object EditorTechniqueCheckResult {
  def apply(res: EditorTechniqueCompilationResult): EditorTechniqueCheckResult = res
  def apply(res: EditorTechniqueParsingError):      EditorTechniqueCheckResult = res
}

object EditorTechniqueCompilationResult {
  def from(technique: EditorTechnique, output: TechniqueCompilationOutput): EditorTechniqueCompilationResult = {
    if (output.isError) {
      EditorTechniqueCompilationResult(technique.id, technique.version, technique.name, CompilationResult.Error(output.stderr))
    } else {
      EditorTechniqueCompilationResult(technique.id, technique.version, technique.name, CompilationResult.Success)
    }
  }
}

//TODO: this is "compilation error" ?
case class EditorTechniqueError(
    id:           BundleName,
    version:      Version,
    status:       TechniqueActiveStatus,
    name:         String,
    errorMessage: String
)

case class EditorTechniqueParsingError(
    id:           TechniqueId,
    status:       TechniqueActiveStatus,
    name:         String,
    errorMessage: String
) {
  def version: TechniqueVersion = id.version
}
object EditorTechniqueParsingError      {
  def from(technique: Technique, status: TechniqueActiveStatus, errorMsg: String): EditorTechniqueParsingError = {
    EditorTechniqueParsingError(
      technique.id,
      status,
      technique.name,
      errorMsg
    )
  }
}

/**
 * The state of editor techniques, where some can be syntactically invalid (parsing as EditorTechnique fails),
 * and for those which are valid, the YAML compilation may fail with some output
 */
sealed trait EditorTechniqueStatus

object EditorTechniqueStatus {
  case object AllSuccess                                              extends EditorTechniqueStatus
  case class Errors(parsingErrors: Chunk[EditorTechniqueParsingError], compilationErrors: Chunk[EditorTechniqueError]) extends EditorTechniqueStatus

  def fromErrors(errors: Chunk[EditorTechniqueError | EditorTechniqueParsingError]): EditorTechniqueStatus = {
    NonEmptyChunk.fromChunk(errors) match {
      case None        => AllSuccess
      case Some(err) => Errors(err.collect { case e: EditorTechniqueParsingError => e }, err.collect { case e: EditorTechniqueError => e })
    }
  }

  /*
  def ignoreDisabledTechniques(status: CompilationStatus): CompilationStatus = {
    status match {
      case CompilationStatusErrors(techniquesInError) =>
        fromErrors(techniquesInError.collect { case e @ EditorTechniqueError(_, _, TechniqueActiveStatus.Enabled, _, _) => e })
      case CompilationStatusAllSuccess                =>
        CompilationStatusAllSuccess
    }
  }*/
}

/**
  * Get latest global techniques check results
  */
trait ReadEditorTechniqueCheckResult {

  def get(): IOResult[List[EditorTechniqueCheckResult]]

}

sealed trait TechniqueActiveStatus
object TechniqueActiveStatus {
  case object Enabled  extends TechniqueActiveStatus
  case object Disabled extends TechniqueActiveStatus
}

/**
  * Get attribute from the configuration techniques
  */
trait ReadEditorTechniqueActiveStatus {

  def getActiveStatus(id: BundleName): IOResult[Option[TechniqueActiveStatus]]

  def getActiveStatuses(): IOResult[Map[BundleName, TechniqueActiveStatus]]

}

/**
  * Update technique compilation status from technique compilation output and technique info
  */
trait TechniqueCompilationStatusSyncService {
  /*
   * Given new editor technique compilation results, update current status and sync it with UI
   */
  def syncOne(result: EditorTechniqueCompilationResult): IOResult[Unit]

  /*
   * Given the identifier of technique, only update its known status and sync with the UI.
   * Status applies to part of the id : only BundleName, so any version could be updated
   */
  def syncTechniqueActiveStatus(bundleName: BundleName): IOResult[Unit]

  def unsyncOne(id: (BundleName, Version)): IOResult[Unit]

  /**
   * The whole process that lookup for compilation status and update everything.
   * @param results if none all results are looked up, if some only consider these ones
   */
  def getUpdateAndSync(results: Option[List[EditorTechniqueCompilationResult]] = None): IOResult[Unit]
}

/**
  * Service to read the latest technique parsing or compilation results
  * using the editor techniques and the compiler, which has access to the output from the editor techniques
  */
class TechniqueCheckStatusService(
    readTechniques:    EditorTechniqueReader,
    techniqueCompiler: TechniqueCompiler
) extends ReadEditorTechniqueCheckResult {

  override def get(): IOResult[List[EditorTechniqueCheckResult]] = {
    readTechniques.readTechniquesMetadataFile.flatMap {
      // other errors than parsing and compilation are ignored for status
      case ReadEditorTechnique(techniques, _, parsingErrors, _) => {

        val outputs = ZIO
          .foreach(techniques) { technique =>
            ZIO
              .succeed(
                parsingErrors
                  .find(err => technique.matchesPath(err.file))
                  .map(EditorTechniqueCheckResult(_))
              )
              .someOrElseZIO(
                techniqueCompiler
                  .getCompilationOutput(technique)
                  .map {
                    case None         => // we don't have output only in case of successful compilation
                      EditorTechniqueCheckResult(
                        EditorTechniqueCompilationResult(
                          technique.id,
                          technique.version,
                          technique.name,
                          CompilationResult.Success
                        )
                      )
                    case Some(output) =>
                      EditorTechniqueCheckResult(
                        EditorTechniqueCompilationResult.from(technique, output)
                      )
                  }
              )
          }

        outputs <* StatusLoggerPure.Techniques.trace(
          s"Get status of editor techniques : read ${techniques.size} editor techniques to update status with"
        )

      }
    }
  }

}

/**
  * Service to gather technique attributes using the directive repository : 
  * it knows about active techniques and their status
  */
class TechniqueActiveStatusService(directiveRepo: RoDirectiveRepository) extends ReadEditorTechniqueActiveStatus {

  override def getActiveStatus(id: BundleName): IOResult[Option[TechniqueActiveStatus]]          = {
    directiveRepo.getActiveTechnique(TechniqueName(id.value)).map(_.map(techniqueActiveStatus(_)))
  }
  override def getActiveStatuses():             IOResult[Map[BundleName, TechniqueActiveStatus]] = {
    directiveRepo.getFullDirectiveLibrary().map(_.activeTechniques.map(t => techniqueId(t) -> techniqueActiveStatus(t)).toMap)
  }

  private def techniqueId(technique: FullActiveTechnique): BundleName = BundleName(technique.techniqueName.value)

  private def techniqueActiveStatus(technique: ActiveTechnique):     TechniqueActiveStatus = {
    technique.isEnabled match {
      case true  => TechniqueActiveStatus.Enabled
      case false => TechniqueActiveStatus.Disabled
    }
  }
  private def techniqueActiveStatus(technique: FullActiveTechnique): TechniqueActiveStatus = {
    technique.isEnabled match {
      case true  => TechniqueActiveStatus.Enabled
      case false => TechniqueActiveStatus.Disabled
    }
  }
}

/**
  * Technique compilation output needs to be saved in a cache (frequent reads, we don't want to get files on the FS every time).
  *
  * This cache is a simple in-memory one which only saves errors,
  * so it saves only compilation output stderr message in case the compilation failed.
  * It notifies the lift actor on update of the compilation status.
  *
  * It is used mainly to get errors in the Rudder UI, and is updated when API requests to update techniques are made,
  * when technique library is reloaded
 *
 * TODO: error base is specific to compilation, so how does it translate to general "editorTechniqueStatus" to be shared with the actor ?
  */
class TechniqueCompilationErrorsActorSync(
    actor:            SimpleActor[UpdateTechniqueStatus],
    reader:           ReadEditorTechniqueCheckResult,
    attributesReader: ReadEditorTechniqueActiveStatus,
    errorBase:        Ref[Map[(BundleName, Version), EditorTechniqueError]]
) extends TechniqueCompilationStatusSyncService {

  /*
   * Given new editor technique compilation results, update current status and sync it with UI
   */
  def syncOne(result: EditorTechniqueCompilationResult): IOResult[Unit] = {
    for {
      activeStatus <- getActiveStatusOrDisabled(result.id)
      status       <- updateOneStatus(result, activeStatus)
      _            <- syncStatusWithUi(status)
    } yield ()
  }

  def syncTechniqueActiveStatus(bundleName: BundleName): IOResult[Unit] = {
    for {
      activeStatus <- getActiveStatusOrDisabled(bundleName)
      status       <- updateOneActiveStatus(bundleName, activeStatus)
      _            <- syncStatusWithUi(status)
    } yield ()
  }

  /**
     * Drop a value from the error base if it exists, sync the status to forget the specified one
     */
  def unsyncOne(id: (BundleName, Version)): IOResult[Unit] = {
    for {
      base  <-
        errorBase.updateAndGet(_ - id)
      status = getStatus(base.values)

      _ <- syncStatusWithUi(status)
    } yield ()
  }

  /*
   * The whole process that lookup for compilation status and update everything.
   * Sync always looks up for the latest status of techniques to filter out disabled ones.
   */
  def getUpdateAndSync(results: Option[List[EditorTechniqueCompilationResult]]): IOResult[Unit] = {
    (for {
      res            <- results.map(_.succeed).getOrElse {
                          // ignore parsing errors in this class which only synces compilation
                          reader.get().map(_.collect { case c: EditorTechniqueCompilationResult => c })
                        }
      activeStatuses <- attributesReader.getActiveStatuses()
      // ones without status are filtered out (if the status is unknown, they are ignored)
      resWithStatus   = res.flatMap(r => activeStatuses.get(r.id).map(r -> _))
      status         <- updateStatus(resWithStatus)
      _              <- syncStatusWithUi(status)
    } yield status).flatMap {
      case CompilationStatusAllSuccess => StatusLoggerPure.Techniques.info("All techniques have success compilation result")
      case e: CompilationStatusErrors =>
        val techniques = e.techniquesInError.map(t => s"${t.id.value}(v${t.version.value})").toList.mkString(",")
        StatusLoggerPure.Techniques.warn(
          s"Found ${e.techniquesInError.size} techniques with compilation errors : ${techniques}"
        )
    }
  }

  /*
   * Push the changes to comet actor
   */
  private[ncf] def syncStatusWithUi(status: CompilationStatus): IOResult[Unit] = {
    IOResult.attempt(actor ! UpdateTechniqueStatus(status))
  }

  /**
   * Get the latest active status from the technique
   * Not found technique should mean that it is disabled/deleted.
   */
  private def getActiveStatusOrDisabled(id: BundleName): IOResult[TechniqueActiveStatus] = {
    attributesReader
      .getActiveStatus(id)
      .map(_.getOrElse(TechniqueActiveStatus.Disabled))
  }

  private def getStatus(errors: Iterable[EditorTechniqueError]): CompilationStatus = {
    NonEmptyChunk.fromIterableOption(errors) match {
      case None        => CompilationStatusAllSuccess
      case Some(value) => CompilationStatusErrors(value)
    }
  }

  /*
   * Update the internal cache and build a Compilation status
   */
  private[ncf] def updateStatus(
      results: List[(EditorTechniqueCompilationResult, TechniqueActiveStatus)]
  ): UIO[CompilationStatus] = {
    errorBase.updateAndGet { m =>
      results.collect {
        case (
              r @ EditorTechniqueCompilationResult(id, version, name, CompilationResult.Error(error)),
              activeStatus
            ) =>
          (getKey(r) -> EditorTechniqueError(id, version, activeStatus, name, error))
      }.toMap
    }.map(m => getStatus(m.values))
  }

  /**
    * Only take a single result to update the cached technique if it is there, else do nothing 
    */
  private[ncf] def updateOneStatus(
      result:       EditorTechniqueCompilationResult,
      activeStatus: TechniqueActiveStatus
  ): UIO[CompilationStatus] = {
    // only replace when current one is an error, when present or absent we should set the value
    val replacement: Option[EditorTechniqueError] => Option[EditorTechniqueError] = result match {
      case EditorTechniqueCompilationResult(id, version, name, CompilationResult.Error(error)) =>
        _ => Some(EditorTechniqueError(id, version, activeStatus, name, error))
      case _                                                                                   =>
        _ => None
    }
    errorBase
      .updateAndGet(_.updatedWith(getKey(result))(replacement(_)))
      .map(m => getStatus(m.values))
  }

  /**
    * Only update the active status of the technique : replace the active status if the technique exists in cache
    */
  private[ncf] def updateOneActiveStatus(
      bundleName:   BundleName,
      activeStatus: TechniqueActiveStatus
  ): UIO[CompilationStatus] = {
    errorBase
      .updateAndGet(_.map {
        case (id @ (`bundleName`, version), err) => id -> err.copy(status = activeStatus)
        case o                                   => o
      })
      .map(m => getStatus(m.values))
  }

  private def getKey(result: EditorTechniqueCompilationResult): (BundleName, Version) = {
    result.id -> result.version
  }
}

object TechniqueCompilationErrorsActorSync {
  def make(
      actor:            SimpleActor[UpdateTechniqueStatus],
      reader:           ReadEditorTechniqueCheckResult,
      attributesReader: ReadEditorTechniqueActiveStatus
  ): UIO[TechniqueCompilationErrorsActorSync] = {
    Ref
      .make(Map.empty[(BundleName, Version), EditorTechniqueError])
      .map(new TechniqueCompilationErrorsActorSync(actor, reader, attributesReader, _))
  }
}
