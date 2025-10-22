/*
 *************************************************************************************
 * Copyright 2012 Normation SAS
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

package com.normation.rudder.services.system

import com.normation.rudder.repository.ReportsRepository
import com.normation.rudder.repository.UpdateExpectedReportsRepository
import com.normation.utils.Control
import net.liftweb.common.*
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import scala.concurrent.duration.Duration

sealed trait DeleteCommand {
  def date: DateTime
}

object DeleteCommand {
  final case class Reports(date: DateTime)         extends DeleteCommand
  final case class ComplianceLevel(date: DateTime) extends DeleteCommand
}

trait DatabaseManager {

  /**
   * Get the older entry in the report database, and the newest
   */
  def getReportsInterval(): Box[(Option[DateTime], Option[DateTime])]

  /**
   * Return the reports database size
   */
  def getDatabaseSize(): Box[Long]

  /**
   * Delete reports older than target date both in archived reports and reports database
   */
  def deleteEntries(reports: DeleteCommand.Reports, complianceLevels: Option[DeleteCommand.ComplianceLevel]): Box[Int]

  def deleteLogReports(olderThan: Duration): Box[Int]
}

class DatabaseManagerImpl(
    reportsRepository:   ReportsRepository,
    expectedReportsRepo: UpdateExpectedReportsRepository
) extends DatabaseManager with Loggable {

  def getReportsInterval(): Box[(Option[DateTime], Option[DateTime])] = {
    reportsRepository.getReportsInterval()
  }

  def getDatabaseSize(): Box[Long] = {
    reportsRepository.getDatabaseSize(reportsRepository.reports)
  }

  def deleteEntries(reports: DeleteCommand.Reports, complianceLevels: Option[DeleteCommand.ComplianceLevel]): Box[Int] = {
    val nodeReports       = reportsRepository.deleteEntries(reports.date) ?~! "An error occurred while deleting reports"
    val nodeConfigs       =
      expectedReportsRepo.deleteNodeConfigIdInfo(reports.date) ?~! "An error occurred while deleting old node configuration IDs"
    val deleteNodeConfigs =
      expectedReportsRepo.deleteNodeConfigurations(reports.date) ?~! "An error occurred while deleting Node Configurations"
    // Accumulate errors, them sum values
    (Control
      .bestEffort(Seq(nodeReports, nodeConfigs, deleteNodeConfigs))(identity))
      .map(_.sum)
  }

  override def deleteLogReports(since: Duration): Box[Int] = {
    val date = DateTime.now(DateTimeZone.UTC).minus(since.toMillis)
    reportsRepository.deleteLogReports(date) ?~! "An error occurred while deleting log reports"
  }
}
