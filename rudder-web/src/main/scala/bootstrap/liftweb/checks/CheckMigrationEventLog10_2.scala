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

import net.liftweb.common._
import com.normation.rudder.migration._
import com.normation.rudder.domain.logger.MigrationLogger
import com.normation.rudder.domain.logger.MigrationLogger

/**
 * That class add all the available reference template in 
 * the default user library 
 * if it wasn't already initialized.
 */
class CheckMigrationEventLog10_2(
  manageEventLogsMigration: ControlEventLogsMigration_10_2
) extends BootstrapChecks {

  override def checks() : Unit = {
    manageEventLogsMigration.migrate() match {
      case Full(_) => //ok, and logging should already be done
      case eb:EmptyBox =>
        val e = eb ?~! "Error when migrating EventLogs' datas from format 1 to 2 in database"
        MigrationLogger(2).error(e.messageChain)
        e.rootExceptionCause.foreach { ex =>
          MigrationLogger(2).error("Exception was:", ex)
        }
    }
  }
}

class CheckMigrationEventLog2_3(
  manageEventLogsMigration: ControlEventLogsMigration_2_3
) extends BootstrapChecks {

  override def checks() : Unit = {
    manageEventLogsMigration.migrate() match {
      case Full(_) => //ok, and logging should already be done
      case eb:EmptyBox =>
        val e = eb ?~! "Error when migrating EventLogs' datas from format 1 to 2 in database"
        MigrationLogger(3).error(e.messageChain)
        e.rootExceptionCause.foreach { ex =>
          MigrationLogger(3).error("Exception was:", ex)
        }
    }
  }
}
