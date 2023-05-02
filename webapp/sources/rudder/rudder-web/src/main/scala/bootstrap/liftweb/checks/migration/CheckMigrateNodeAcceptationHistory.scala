/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

package bootstrap.liftweb.checks.migration

import bootstrap.liftweb.BootstrapChecks

/*
 * This class handles the migration of the format of node serialization that are accepted or refused.
 * Before 8.0, when a node was accepted of refused, and LDIF dump of the node inventory part was saved
 * under /var/rudder/inventories/historical
 * The layout was:
 * /var/rudder/inventories/historical/c1bab9fc-bcf6-4d59-a397-84c8e2fc06c0/2019-10-10_10:27.55.437
 * were the UUID is a directory for that node, and the date part is a file in LDIF format.
 *
 * We will keep the same layout to simplify other part of Rudder.
 *
 * To mark the end of migration, we will create a metadata file format under
 * /var/rudder/inventories/historical/info.properties with the key/value fileFormat=nodeFactV2
 */
class CheckMigrateNodeAcceptationHistory(
) extends BootstrapChecks {

  override def description: String = "Change history format for node accepted or refused inventories"

  override def checks(): Unit = {
    ??? // TODO
  }

}
