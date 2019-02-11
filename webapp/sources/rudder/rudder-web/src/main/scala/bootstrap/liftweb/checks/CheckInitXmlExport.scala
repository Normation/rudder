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

import com.normation.rudder.repository.ItemArchiveManager
import com.normation.rudder.services.user.PersonIdentService
import com.normation.rudder.domain.eventlog.RudderEventActor
import net.liftweb.common._
import com.normation.utils.StringUuidGenerator
import com.normation.eventlog.ModificationId

import com.normation.box._
import scalaz.zio._

/**
 *
 * When the application is first initialized, we want to set the
 * content of configuration-repository file to a consistant state,
 * especially for directives/rules/groups, where we want to have
 * all system categories and entities saved (else, we are going
 * to have some surprise on the first import).
 *
 * So, if a full export wasn't done until know, just do one.
 *
 */
class CheckInitXmlExport(
    itemArchiveManager: ItemArchiveManager
  , personIdentService: PersonIdentService
  , uuidGen           : StringUuidGenerator
) extends BootstrapChecks {

  override val description = "Check existence of at least one archive of the configuration"

  override def checks() : Unit = {
    (for {
      tagMap <- itemArchiveManager.getFullArchiveTags
      ident  <- personIdentService.getPersonIdentOrDefault(RudderEventActor.name)
      res    <- if(tagMap.isEmpty) {
                  BootraspLogger.info("No full archive of configuration-repository items seems to have been done, initialising the system with one") *>
                  itemArchiveManager.exportAll(ident, ModificationId(uuidGen.newUuid), RudderEventActor, Some("Initialising configuration-repository sub-system"), false)
                } else {
                  BootraspLogger.trace("At least a full archive of configuration items done, no need for further initialisation") *>
                  UIO.unit
                }

    } yield {
      res
    }).toBox match {
      case eb:EmptyBox =>
        val fail = eb ?~! "Error when trying to initialise to configuration-repository sub-system with a first full archive"
        BootraspLogger.logEffect.error(fail)
        fail.rootExceptionCause.foreach { t =>
          BootraspLogger.logEffect.error("Root exception was:", t)
        }

      case Full(_) =>
        BootraspLogger.logEffect.info("First full archive of configuration-repository items done")
    }
  }
}
