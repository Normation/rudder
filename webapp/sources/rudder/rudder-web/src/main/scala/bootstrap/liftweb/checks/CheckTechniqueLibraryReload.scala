/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

import net.liftweb.common._
import java.io.File
import com.normation.cfclerk.services.UpdateTechniqueLibrary
import com.normation.rudder.batch.AsyncDeploymentActor
import com.normation.eventlog.ModificationId
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.domain.eventlog.RudderEventActor

/**
 * Check at webapp startup if Rudder Technique library has to be reloaded
 * If flag file is present then reload technique library
 * This needs to be achieved after all tasks that could modify configuration
 */
class CheckTechniqueLibraryReload(
    techniqueLibUpdater  : UpdateTechniqueLibrary
  , asyncDeploymentAgent : AsyncDeploymentActor
  , uuidGen              : StringUuidGenerator
) extends BootstrapChecks {

  override val description = "Check for force reload of Techniques library"

  override def checks() : Unit = {

    val forceReloadFlagPath = "/opt/rudder/etc/force_technique_reload"

    // Check if the force technique reload file is present, and reload technique library if needed
    val file =  new File(forceReloadFlagPath)
    try {
      if (file.exists) {
        // File exists, reload
        BootraspLogger.logEffect.info(s"Flag file '${forceReloadFlagPath}' found, reload Technique library now")
        techniqueLibUpdater.update(
            ModificationId(uuidGen.newUuid)
          , RudderEventActor
          , Some(s"Reload Technique library at start up")
        ) match {
          case Full(_) =>
            // Success! now try deleting the flag
            BootraspLogger.logEffect.info(s"Successfully reloaded Technique library on start up. now deleting flag file '${forceReloadFlagPath}'")
            try {
              if (file.delete) {
                // Deleted, come back to normal
                BootraspLogger.logEffect.info(s"Flag file '${forceReloadFlagPath}' successfully removed")
              } else {
                // File could not be deleted, seek for reason
                if(!file.exists()) {
                  BootraspLogger.logEffect.warn(s"Flag file '${forceReloadFlagPath}' could not be removed as it does not exist anymore")
                } else {
                  BootraspLogger.logEffect.error(s"Flag file '${forceReloadFlagPath}' could not be removed, you may have to remove it manually, cause is: Permission denied or someone is actually editing the file")
                }
              }
            } catch {
              // Exception while deleting the file
              case e : Exception =>
                BootraspLogger.logEffect.error(s"An error occurred while checking flag file '${forceReloadFlagPath}' after removal attempt, cause is: ${e.getMessage}")
            }
          case eb:EmptyBox =>
            val msg = (eb ?~! ("An error occured while updating")).messageChain
            BootraspLogger.logEffect.error(s"Flag file '${forceReloadFlagPath}' but Techniques library reload failed, cause is: ${msg}")
        }
      } else {
        BootraspLogger.logEffect.info(s"Flag file '${forceReloadFlagPath}' does not exist, do not Technique library will not be reloaded")
      }
    } catch {
      // Exception while checking the file existence
      case e : Exception =>
        BootraspLogger.logEffect.error(s"An error occurred while accessing flag file '${forceReloadFlagPath}', cause is: ${e.getMessage}")
    }
  }

}
