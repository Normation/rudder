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

import java.io.File

import com.normation.eventlog.ModificationId
import com.normation.rudder.batch.{AsyncDeploymentActor, AutomaticStartDeployment}
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.utils.StringUuidGenerator

/**
 * Inconditionaly starts a policy generation at the start of the webapp
 * Necessary for any changes that may happen (system variable change like dav password
 * for instance)
 * A policy generation only write files if something changed, so it's pretty safe
 * This needs to be achieved after all tasks that could modify configuration
 */
class TriggerPolicyUpdate(
    asyncGeneration : AsyncDeploymentActor
  , uuidGen         : StringUuidGenerator
) extends BootstrapChecks {

  override val description = "Trigger policy update automatically at start"

  override def checks() : Unit = {
    val filePath = asyncGeneration.triggerPolicyUpdateFlagPath
    // Check if the flag file is present, and purge it if so
    val file =  new File(filePath)
    try {
      if (file.exists) {
        // File exists, update policies
        BootstrapLogger.logEffect.info(s"Deprecated flag file '${filePath}' found, removing it")
        if (file.delete) {
          // Deleted, come back to normal
          BootstrapLogger.logEffect.info(s"Flag file '${file.getPath}' successfully removed")
        } else {
          // File could not be deleted, seek for reason
          if (!file.exists()) {
            BootstrapLogger.logEffect.info(s"Flag file '${file.getPath}' has been removed")
          } else {
            BootstrapLogger.logEffect.error(s"Flag file '${file.getPath}' could not be removed, you may have to remove it manually, cause is: Permission denied or someone is actually editing the file")
          }
        }
      }
    } catch {
      // Exception while checking the file existence
      case e : Exception =>
        BootstrapLogger.logEffect.error(s"An error occurred while accessing flag file '${filePath}', cause is: ${e.getMessage}")
    }

    BootstrapLogger.logEffect.debug(s"Unconditionally starts a new policy update")
    asyncGeneration ! AutomaticStartDeployment(ModificationId(uuidGen.newUuid), RudderEventActor)

  }

}
