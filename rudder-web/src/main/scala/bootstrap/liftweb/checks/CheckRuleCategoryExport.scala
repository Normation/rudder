/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
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
import com.normation.rudder.rule.category.RoRuleCategoryRepository
import java.io.File
import net.liftweb.util.ControlHelpers.tryo



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
class CheckRootRuleCategoryExport(
    itemArchiveManager : ItemArchiveManager
  , categoryDirectory  : File
  , personIdentService : PersonIdentService
  , uuidGen            : StringUuidGenerator
) extends BootstrapChecks with Loggable {

  override def checks() : Unit = {
    (for {
      exists <- tryo{ categoryDirectory.exists() }
      ident  <- personIdentService.getPersonIdentOrDefault(RudderEventActor.name)
    } yield {
      if(!exists) {
        logger.info(s"Directory '${categoryDirectory.getAbsolutePath()}' is missing, initialize it by exporting Rules")
        itemArchiveManager.exportRules(ident, ModificationId(uuidGen.newUuid), RudderEventActor, Some("Initialising configuration-repository Rule categories directory"), false)
      } else {
        logger.trace(s"Directory '${categoryDirectory.getAbsolutePath()}' exists")
        Full("OK")
      }
    }) match {
      case eb: EmptyBox =>
        val fail = eb ?~! s"Error when checking '${categoryDirectory}' directory existence"
        logger.error(fail.msg)
        fail.rootExceptionCause.foreach { t =>
          logger.error("Root exception was:", t)
        }
      case Full(eb:EmptyBox) =>
        val fail = eb ?~! "Initialising configuration-repository Rule categories directory with a Rule archive"
        logger.error(fail.msg)
        fail.rootExceptionCause.foreach { t =>
          logger.error("Root exception was:", t)
        }

      case Full(Full(_)) =>
        logger.info(s"Creating directory '${categoryDirectory.getAbsolutePath()}' exists, done")
    }
  }
}
