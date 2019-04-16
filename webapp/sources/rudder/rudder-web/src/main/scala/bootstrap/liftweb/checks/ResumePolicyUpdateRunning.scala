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
import com.normation.rudder.batch.AsyncDeploymentActor
import com.normation.eventlog.ModificationId
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.batch.AutomaticStartDeployment

/**
 * Check at webapp startup if a policy update was running when webapp was stopped
 * If flag file is present then start a new policy update
 * This needs to be achieved after all tasks that could modify configuration
 */
class ResumePolicyUpdateRunning(
    asyncGeneration : AsyncDeploymentActor
  , uuidGen         : StringUuidGenerator
) extends BootstrapChecks {

  override val description = "Resume policy update if it was running before shutdown"

  override def checks() : Unit = {

    val filePath = asyncGeneration.policyUpdateRunningFlagPath
    // Check if the flag file is present, and start a new policy update if needed
    val file =  new File(filePath)
    try {
      if (file.exists) {
        // File exists, update policies
        BootraspLogger.logEffect.info(s"Flag file '${filePath}' found, Start a new policy update now")
        asyncGeneration ! AutomaticStartDeployment(ModificationId(uuidGen.newUuid), RudderEventActor)
      } else {
        BootraspLogger.logEffect.info(s"Flag file '${filePath}' does not exist, No need to start a new policy update")
      }
    } catch {
      // Exception while checking the file existence
      case e : Exception =>
        BootraspLogger.logEffect.error(s"An error occurred while accessing flag file '${filePath}', cause is: ${e.getMessage}")
    }
  }

}
