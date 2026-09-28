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

package bootstrap.liftweb.checks.endconfig.action

import bootstrap.liftweb.BootstrapChecks
import bootstrap.liftweb.BootstrapLogger
import com.normation.rudder.services.policies.TechniqueLibraryTenantSync
import com.normation.rudder.tenants.ChangeContext
import com.normation.zio.*

/**
 * Make the tenants of the user library follow the reference library at start up.
 *
 * During a normal life, that alignment is done after each technique library update (see
 * `TechniqueAcceptationUpdater`). It is done here too because a server upgraded from a version where
 * techniques had no tenant tag has a user library that predates the whole notion: without that pass, its
 * active techniques would stay admin-only until their technique happens to change in git.
 *
 * It only ever makes a tag grow, and never fails the boot: an error here means the library keeps the
 * tenants it has.
 */
class CheckTechniqueLibraryTenants(
    tenantSync: TechniqueLibraryTenantSync
) extends BootstrapChecks {

  override val description = "Align the tenants of the user library with the technique library"

  override def checks(): Unit = {
    tenantSync
      .syncAll()(using ChangeContext.newForRudder(Some("Align user library tenants with the technique library at start up")))
      .catchAll(err => BootstrapLogger.error(s"Error when aligning the tenants of the user library: ${err.fullMsg}"))
      .runNow
  }
}
