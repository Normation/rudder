/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

import com.normation.box._
import com.normation.errors.IOResult
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.repository.ItemArchiveManager
import com.normation.rudder.services.user.PersonIdentService
import com.normation.utils.StringUuidGenerator
import net.liftweb.common._
import scalaz.zio._

/**
 *
 * Check that the rules archive directory in
 * configuration-repository exsits.
 *
 */
class CheckRootRuleCategoryExport(
    itemArchiveManager : ItemArchiveManager
  , categoryDirectory  : File
  , personIdentService : PersonIdentService
  , uuidGen            : StringUuidGenerator
) extends BootstrapChecks {

  override val description = "Check rules archive directory in configuration-repository"

  override def checks() : Unit = {
    (for {
      exists <- IOResult.effect(categoryDirectory.exists).chainError(s"Error when checking '${categoryDirectory}' directory existence")
      ident  <- personIdentService.getPersonIdentOrDefault(RudderEventActor.name)
      res    <- if(!exists) {
                  BootraspLogger.info(s"Directory '${categoryDirectory.getAbsolutePath()}' is missing, initialize it by exporting Rules") *>
                  itemArchiveManager.exportRules(ident, ModificationId(uuidGen.newUuid), RudderEventActor, Some("Initialising configuration-repository Rule categories directory"), false)
                } else {
                  BootraspLogger.trace(s"Directory '${categoryDirectory.getAbsolutePath()}' exists") *>
                  UIO.unit
                }
    } yield {
    }).toBox match {
      case eb:EmptyBox =>
        val fail = eb ?~! "Initialising configuration-repository Rule categories directory with a Rule archive"
        BootraspLogger.logEffect.error(fail.msg)
        fail.rootExceptionCause.foreach { t =>
          BootraspLogger.logEffect.error("Root exception was:", t)
        }

      case Full(_) =>
        BootraspLogger.logEffect.info(s"Creating directory '${categoryDirectory.getAbsolutePath()}' exists, done")
    }
  }
}
