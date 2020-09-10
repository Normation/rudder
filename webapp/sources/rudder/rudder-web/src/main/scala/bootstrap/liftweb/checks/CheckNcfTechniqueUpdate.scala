/*
*************************************************************************************
* Copyright 2017 Normation SAS
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

package bootstrap.liftweb
package checks

import better.files.File
import com.normation.eventlog.ModificationId
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.ncf.TechniqueWriter
import com.normation.cfclerk.services.UpdateTechniqueLibrary
import com.normation.eventlog.EventActor
import com.normation.rudder.api.ApiAccount
import com.normation.errors.RudderError
import com.normation.rudder.ncf.ResourceFileService
import com.normation.rudder.ncf.ResourceFileState.Untouched
import com.normation.rudder.ncf.TechniqueReader
import zio._
import com.normation.zio._

sealed trait NcfTechniqueUpgradeError extends RudderError{
  def msg : String
  def exception : Option[Throwable]
}
object NcfTechniqueUpgradeError {
final case class NcfApiAuthFailed    (msg: String, exception: Option[Throwable]) extends NcfTechniqueUpgradeError
final case class NcfApiRequestFailed (msg: String, exception: Option[Throwable]) extends NcfTechniqueUpgradeError
final case class JsonExtractionError (msg: String, exception: Option[Throwable]) extends NcfTechniqueUpgradeError
final case class TechniqueUpdateError(msg: String, exception: Option[Throwable]) extends NcfTechniqueUpgradeError
final case class WriteTechniqueError (msg: String, exception: Option[Throwable]) extends NcfTechniqueUpgradeError
final case class FlagFileError       (msg: String, exception: Option[Throwable]) extends NcfTechniqueUpgradeError

  type Result[T] = Either[NcfTechniqueUpgradeError, T]

}

/**
 * Check at webapp startup if ncf Technique needs to be rewritten by Rudder
 * Controlled by presence of flag file /opt/rudder/etc/force_ncf_technique_update
 */
class CheckNcfTechniqueUpdate(
    restExtractor  : RestExtractorService
  , techniqueWrite : TechniqueWriter
  , systemApiToken : ApiAccount
  , uuidGen        : StringUuidGenerator
  , techLibUpdate  : UpdateTechniqueLibrary
  , techniqueReader: TechniqueReader
  , resourceFileService: ResourceFileService
) extends BootstrapChecks {

  override val description = "Regenerate all ncf techniques"

  override def checks() : Unit = {

    val ncfTechniqueUpdateFlag = File("/opt/rudder/etc/force_ncf_technique_update")

    import com.normation.errors._

    def updateNcfTechniques  = {
      for {
        _          <- techniqueReader.updateMethodsMetadataFile
        _          <- techniqueReader.updateTechniquesMetadataFile

        methods    <- techniqueReader.readMethodsMetadataFile
        techniques <- techniqueReader.readTechniquesMetadataFile
        techniquesWithResources <-
          ZIO.foreach(techniques) {
            technique =>
              resourceFileService.getResources(technique).map(r => technique.copy(ressources = r.filter(_.state == Untouched)))
          }
        // Actually write techniques
        written    <- ZIO.foreach(techniquesWithResources)( t =>
                        techniqueWrite.writeTechnique(t, methods, ModificationId(uuidGen.newUuid), EventActor(systemApiToken.name.value)).chainError(s"An error occured while writing technique ${t.bundleName.value}")
                      )
                                                        // Update technique library once all technique are updated
        libUpdate   <- techLibUpdate.update(ModificationId(uuidGen.newUuid), EventActor(systemApiToken.name.value), Some(s"Update Technique library after updating all techniques at start up")).toIO.chainError( s"An error occured during techniques update after update of all techniques from the editor")

        flagDeleted <- IOResult.effect( ncfTechniqueUpdateFlag.delete() )
      } yield {
         techniques
      }
    }

    val prog = (for {
      flagExists <- IOResult.effect(s"An error occurred while accessing flag file '${ncfTechniqueUpdateFlag}'")(ncfTechniqueUpdateFlag.exists)
      _ <- if (flagExists) updateNcfTechniques else BootstrapLogger.info(s"Flag file '${ncfTechniqueUpdateFlag}' does not exist, do not regenerate ncf Techniques")
    } yield ())

    ZioRuntime.runNowLogError(err => BootstrapLogger.error(s"An error occurred while updating techniques based on ncf; error message is: ${err.fullMsg}"))(prog)
  }
}
