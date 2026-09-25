/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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

package bootstrap.liftweb.checks.earlyconfig.ldap

import bootstrap.liftweb.BootstrapChecks
import com.normation.rudder.domain.logger.MigrationLoggerPure
import com.normation.rudder.tenants.LibraryObjectsTagMigration
import com.normation.zio.*

/*
 * Tag the root categories and the special targets Rudder provides as library objects (`open-ro`), so that
 * every tenant sees and uses them while only an administrator changes them.
 *
 * It runs before the services are instantiated because from there on, the tag stored on those entries is
 * the one that is read: until Rudder 9.1 the roots were forced open at read time, which is what this
 * replaces. See `LibraryObjectsTagMigration` for what is tagged and why.
 *
 * An error here does not stop the boot: the objects keep the tag they have, which at worst means a tenant
 * user does not see the root categories - visible and annoying, not a security hole.
 */
class CheckLibraryObjectsTag(
    migration: LibraryObjectsTagMigration
) extends BootstrapChecks {

  override val description = "Tag the root categories and the special targets as library objects"

  override def checks(): Unit = {
    migration
      .migrate()
      .flatMap {
        case 0 => MigrationLoggerPure.debug("All library objects are already tagged, nothing to do")
        case n => MigrationLoggerPure.info(s"${n} library objects were tagged as visible to every tenant")
      }
      .catchAll(err => MigrationLoggerPure.error(s"Error when tagging the library objects: ${err.fullMsg}"))
      .runNow
  }
}
