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

import com.normation.errors.IOResult
import com.normation.inventory.domain.Version
import com.normation.rudder.batch.UpdateCompilationStatus
import com.normation.rudder.domain.logger.StatusLoggerPure
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

object EditorTechniqueCompilationResult {
  def from(technique: EditorTechnique, output: TechniqueCompilationOutput) = {
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
    name:         String,
    errorMessage: String
)

sealed trait CompilationStatus
case object CompilationStatusAllSuccess                                                    extends CompilationStatus
case class CompilationStatusErrors(techniquesInError: NonEmptyChunk[EditorTechniqueError]) extends CompilationStatus {
  def ++(other: CompilationStatusErrors): CompilationStatusErrors = CompilationStatusErrors(
    this.techniquesInError ++ other.techniquesInError
  )
}

/**
  * Get latest global techniques compilation results
  */
trait ReadEditorTechniqueCompilationResult {

  def get(): IOResult[List[EditorTechniqueCompilationResult]]

}

/**
  * Update technique compilation status from technique compilation output and technique info
  */
trait TechniqueCompilationStatusSyncService {
  /*
   * Given new editor technique compilation results, update current status and sync it with UI
   */
  def syncOne(result: EditorTechniqueCompilationResult): IOResult[Unit]

  def unsyncOne(id: (BundleName, Version)): IOResult[Unit]

  /**
   * The whole process that lookup for compilation status and update everything.
   * @param results if none all results are looked up, if some only consider these ones
   */
  def getUpdateAndSync(results: Option[List[EditorTechniqueCompilationResult]] = None): IOResult[Unit]
}

/**
  * Service to read the latest technique compilation results using the editor techniques 
  * and the compiler which has access to the output from the editor techniques 
  */
class TechniqueCompilationStatusService(
    readTechniques:    EditorTechniqueReader,
    techniqueCompiler: TechniqueCompiler
) extends ReadEditorTechniqueCompilationResult {

  override def get(): IOResult[List[EditorTechniqueCompilationResult]] = {
    readTechniques.readTechniquesMetadataFile.flatMap {
      // _._3 errors are not compilation errors but error on techniques, we ignore them for status
      case (techniques, _, _) => {

        val outputs = ZIO
          .foreach(techniques) { technique =>
            techniqueCompiler
              .getCompilationOutput(technique)
              .map {
                case None         => // we don't have output only in case of successful compilation
                  EditorTechniqueCompilationResult(technique.id, technique.version, technique.name, CompilationResult.Success)
                case Some(output) =>
                  EditorTechniqueCompilationResult.from(technique, output)
              }
          }

        outputs <* StatusLoggerPure.Techniques.trace(
          s"Get compilation status : read ${techniques.size} editor techniques to update compilation status with"
        )

      }
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
  */
class TechniqueCompilationErrorsActorSync(
    actor:     SimpleActor[UpdateCompilationStatus],
    reader:    ReadEditorTechniqueCompilationResult,
    errorBase: Ref[Map[(BundleName, Version), EditorTechniqueError]]
) extends TechniqueCompilationStatusSyncService {

  /*
   * Given new editor technique compilation results, update current status and sync it with UI
   */
  def syncOne(result: EditorTechniqueCompilationResult): IOResult[Unit] = {
    for {
      status <- updateOneStatus(result)
      _      <- syncStatusWithUi(status)
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
   * The whole process that lookup for compilation status and update everything
   */
  def getUpdateAndSync(results: Option[List[EditorTechniqueCompilationResult]]): IOResult[Unit] = {
    (for {
      results <- results.map(_.succeed).getOrElse(reader.get())
      status  <- updateStatus(results)
      _       <- syncStatusWithUi(status)
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
    IOResult.attempt(actor ! UpdateCompilationStatus(status))
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
  private[ncf] def updateStatus(results: List[EditorTechniqueCompilationResult]): UIO[CompilationStatus] = {
    errorBase.updateAndGet { m =>
      results.collect {
        case r @ EditorTechniqueCompilationResult(id, version, name, CompilationResult.Error(error)) =>
          (getKey(r) -> EditorTechniqueError(id, version, name, error))
      }.toMap
    }.map(m => getStatus(m.values))
  }

  /**
    * Only take a single result to update the cached technique if it is there, else do nothing 
    */
  private[ncf] def updateOneStatus(result: EditorTechniqueCompilationResult): UIO[CompilationStatus] = {
    // only replace when current one is an error, when present or absent we should set the value
    val replacement: Option[EditorTechniqueError] => Option[EditorTechniqueError] = result match {
      case EditorTechniqueCompilationResult(id, version, name, CompilationResult.Error(error)) =>
        _ => Some(EditorTechniqueError(id, version, name, error))
      case _                                                                                   =>
        _ => None
    }
    errorBase
      .updateAndGet(_.updatedWith(getKey(result))(replacement(_)))
      .map(m => getStatus(m.values))
  }

  private def getKey(result: EditorTechniqueCompilationResult): (BundleName, Version) = {
    result.id -> result.version
  }
}

object TechniqueCompilationErrorsActorSync {
  def make(
      actor:  SimpleActor[UpdateCompilationStatus],
      reader: ReadEditorTechniqueCompilationResult
  ): UIO[TechniqueCompilationErrorsActorSync] = {
    Ref
      .make(Map.empty[(BundleName, Version), EditorTechniqueError])
      .map(new TechniqueCompilationErrorsActorSync(actor, reader, _))
  }
}
