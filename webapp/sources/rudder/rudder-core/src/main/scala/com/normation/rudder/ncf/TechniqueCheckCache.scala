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

import com.normation.cfclerk.domain.TechniqueName
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

  extension (self: EditorTechniqueCheckResult) {
    def toError(status: TechniqueActiveStatus): Option[EditorTechniqueError] = self match {
      case p: EditorTechniqueParsingError      => Some(EditorTechniqueError(id, version, status, name, p.errorMsg))
      case c: EditorTechniqueCompilationResult =>
        c.result match {
          case CompilationResult.Error(errorMsg) => Some(EditorTechniqueError(id, version, status, name, errorMsg))
          case CompilationResult.Success         => None
        }
    }

    def id: BundleName = self match {
      case p: EditorTechniqueParsingError      => p.path.id
      case c: EditorTechniqueCompilationResult => c.id
    }

    def version: Version = self match {
      case p: EditorTechniqueParsingError      => p.path.version
      case c: EditorTechniqueCompilationResult => c.version
    }

    private def name: String = self match {
      case p: EditorTechniqueParsingError      => p.path.id.value // there is no known name
      case c: EditorTechniqueCompilationResult => c.name
    }
  }
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

case class EditorTechniqueError(
    id:           BundleName,
    version:      Version,
    status:       TechniqueActiveStatus,
    name:         String,
    errorMessage: String
)

/**
 * The state of editor techniques, where some can be syntactically invalid (parsing as EditorTechnique fails),
 * and for those which are valid, the YAML compilation may fail with some output
 */
sealed trait EditorTechniqueStatus

object EditorTechniqueStatus {
  case object AllSuccess                                         extends EditorTechniqueStatus
  case class Errors(errors: NonEmptyChunk[EditorTechniqueError]) extends EditorTechniqueStatus {
    def size: Int = errors.size
    def ++(that: Errors): Errors = Errors(this.errors ++ that.errors)
  }

  def fromErrors(errors: Iterable[EditorTechniqueError]): EditorTechniqueStatus = {
    NonEmptyChunk.fromIterableOption(errors) match {
      case None      => AllSuccess
      case Some(err) => Errors(err)
    }
  }

  def ignoreDisabledTechniques(status: EditorTechniqueStatus): EditorTechniqueStatus = {
    status match {
      case AllSuccess     => AllSuccess
      case Errors(errors) => fromErrors(errors.filterNot(_.status == TechniqueActiveStatus.Disabled))
    }
  }
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

  /**
   * Get the latest active status from the technique
   * Not found technique should mean that it is disabled/deleted.
   */
  def getActiveStatusOrDisabled(id: BundleName): IOResult[TechniqueActiveStatus] = {
    getActiveStatus(id)
      .map(_.getOrElse(TechniqueActiveStatus.Disabled))
  }
}

trait TechniqueCheckSyncService {
  def unsyncOne(id: (BundleName, Version)): IOResult[Unit]

  /**
   * The whole process that lookup for check status and update them for sync with the UI.
   */
  def checkSyncAll(): IOResult[Unit]

  /*
   * Given the identifier of technique, only update its known status and sync with the UI.
   * Status applies to part of the id : only BundleName, so any version could be updated
   */
  def syncTechniqueActiveStatus(bundleName: BundleName): IOResult[Unit]

}

/**
 * Update technique compilation status from technique compilation output and technique info
 */
trait TechniqueCompilationSyncService {
  /*
   * Given new editor technique compilation results, update current status and sync it with UI
   */
  def syncOneCompilation(result: EditorTechniqueCompilationResult): IOResult[EditorTechniqueStatus]

  /**
   * The whole process that lookup for compilation status and update them for sync with the UI.
   */
  def syncCompilation(results: List[EditorTechniqueCompilationResult]): IOResult[EditorTechniqueStatus]
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
          }
          .map(parsingErrors.map(EditorTechniqueCheckResult(_)) ++ _)

        outputs <* StatusLoggerPure.Techniques.trace(
          s"Get status of editor techniques : read ${techniques.size} editor techniques to update status with : ${techniques.map(_.id).mkString(",")}\n"
          + s"Get status of editor techniques : parsing errors ${parsingErrors.size} at ${parsingErrors.mkString(",")}"
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
    directiveRepo
      .getFullDirectiveLibrary()
      .map(_.allActiveTechniques.map((_, t) => techniqueId(t) -> techniqueActiveStatus(t)).toMap)
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

class TechniqueCheckActorSync(
    actor:            SimpleActor[UpdateTechniqueStatus],
    reader:           ReadEditorTechniqueCheckResult,
    attributesReader: ReadEditorTechniqueActiveStatus,
    errorBase:        Ref[Map[(BundleName, Version), EditorTechniqueError]]
) extends TechniqueCheckSyncService with TechniqueCompilationSyncService {
  override def syncTechniqueActiveStatus(bundleName: BundleName): IOResult[Unit] = {
    for {
      activeStatus <- attributesReader.getActiveStatusOrDisabled(bundleName)
      _            <-
        /**
         * Only update the active status of the technique : replace the active status if the technique exists in cache
         */
        errorBase
          .update(_.map {
            case (id @ (`bundleName`, version), err) => id -> err.copy(status = activeStatus)
            case o                                   => o
          })

      _ <- syncActorFromErrorBase()
    } yield ()
  }

  /**
   * Drop a value from the error base if it exists, sync the status to forget the specified one
   */
  override def unsyncOne(id: (BundleName, Version)): IOResult[Unit] = {
    for {
      _ <- errorBase.update(_ - id)
      _ <- syncActorFromErrorBase()
    } yield ()
  }

  def syncOneCompilation(result: EditorTechniqueCompilationResult): IOResult[EditorTechniqueStatus] = {
    syncOne(EditorTechniqueCheckResult(result))
  }

  /*
   * Given new editor technique check results, update current status and sync it with UI
   */
  def syncOne(result: EditorTechniqueCheckResult): IOResult[EditorTechniqueStatus] = {
    for {
      activeStatus <- attributesReader.getActiveStatusOrDisabled(result.id)
      _            <- {

        /**
         * Only take a single result to update the cached technique if it is there, else do nothing 
         */
        // only replace when current one is an error, when present or absent we should set the value
        val replacement: Option[EditorTechniqueError] => Option[EditorTechniqueError] = _ => result.toError(activeStatus)
        errorBase
          .update(_.updatedWith(getKey(result))(replacement(_)))
      }

      status <- syncActorFromErrorBase()
    } yield {
      status
    }
  }

  override def checkSyncAll(): IOResult[Unit] = {
    syncAll(None).unit
  }

  override def syncCompilation(results: List[EditorTechniqueCompilationResult]): IOResult[EditorTechniqueStatus] = {
    syncAll(Some(results.map(EditorTechniqueCheckResult(_))))
  }

  /*
   * The whole process that lookup for check status and update everything.
   * Sync always looks up for the latest status of techniques to filter out disabled ones.
   */
  private[ncf] def syncAll(results: Option[List[EditorTechniqueCheckResult]]): IOResult[EditorTechniqueStatus] = {
    (for {
      res            <- results.map(_.succeed).getOrElse(reader.get())
      activeStatuses <- attributesReader.getActiveStatuses()
      // ones without status are filtered out (if the status is unknown, they are ignored)
      resWithStatus   = res.flatMap(r => activeStatuses.get(r.id).map(r -> _))
      _              <- errorBase.set {
                          resWithStatus.collect {
                            case (r, activeStatus) =>
                              r.toError(activeStatus).map(getKey(r) -> _)
                          }.flatten.toMap
                        }
      status         <- syncActorFromErrorBase()
    } yield status).tap {
      case EditorTechniqueStatus.AllSuccess =>
        StatusLoggerPure.Techniques.info("All techniques have success parsing and compilation result")
      case e: EditorTechniqueStatus.Errors =>
        val errors = e.errors.map(t => s"${t.id.value}(v${t.version.value})").toList.mkString(",")
        StatusLoggerPure.Techniques.warn(
          s"Found ${e.size} techniques with parsing/compilation errors: ${errors}"
        )
    }
  }

  private def getKey(result: EditorTechniqueCheckResult): (BundleName, Version) = {
    result.id -> result.version
  }

  /*
   * Sync the error base with the comet actor
   */
  private[ncf] def syncActorFromErrorBase(): IOResult[EditorTechniqueStatus] = {
    for {
      errors <- errorBase.get
      status  = EditorTechniqueStatus.fromErrors(errors.values)
      _       = actor ! UpdateTechniqueStatus(status)
    } yield {
      status
    }
  }

}

/**
 * Technique check output needs to be saved in a cache (frequent reads, we don't want to get files on the FS every time).
 *
 * This cache is a simple in-memory one which only saves errors,
 * so it saves only compilation output stderr message in case the compilation failed.
 * It notifies the lift actor on update of the global status of checks.
 *
 * It is used mainly to get errors in the Rudder UI, and is updated when API requests to update techniques are made,
 * when technique library is reloaded.
 *
 * It needs to share the Ref of errors with the shared cache of errors for all technique checks.
 */

object TechniqueCheckActorSync {
  def make(
      actor:            SimpleActor[UpdateTechniqueStatus],
      reader:           ReadEditorTechniqueCheckResult,
      attributesReader: ReadEditorTechniqueActiveStatus
  ): UIO[TechniqueCheckActorSync] = {
    Ref
      .make(Map.empty[(BundleName, Version), EditorTechniqueError])
      .map(new TechniqueCheckActorSync(actor, reader, attributesReader, _))
  }
}
