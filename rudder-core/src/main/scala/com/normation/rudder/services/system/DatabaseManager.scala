/*
*************************************************************************************
* Copyright 2012 Normation SAS
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


package com.normation.rudder.services.system

import net.liftweb.common._
import org.joda.time.DateTime
import org.springframework.jdbc.core.JdbcTemplate
import net.liftweb.common.Loggable
import com.normation.rudder.repository.ReportsRepository

trait DatabaseManager {

  /**
   * Get the older entry in the report database, and the newest
   */
  def getReportsInterval() : Box[(Option[DateTime], Option[DateTime])]
  
  /**
   * Get the older entry in the archived report database, and the newest
   */
  def getArchivedReportsInterval() : Box[(Option[DateTime], Option[DateTime])]
  

  /**
   * Return the reports database size
   */
  def getDatabaseSize() : Box[Long]
  
  /**
   * Return the archive reports database size
   */
  def getArchiveSize() : Box[Long]
  
  /**
   * Archive reports older than target date in archived reports database
   * and delete them from reports database
   */
  def archiveEntries(date : DateTime) : Box[Int]

  /**
   * Delete reports older than target date both in archived reports and reports database
   */
  def deleteEntries(date : DateTime) : Box[Int]
}

class DatabaseManagerImpl(
    reportsRepository : ReportsRepository
  )  extends DatabaseManager with  Loggable {
  
  def getReportsInterval() : Box[(Option[DateTime], Option[DateTime])] = {
    try {
      for {
        oldest   <- reportsRepository.getOldestReports()
        youngest <- reportsRepository.getNewestReports()
      } yield {
        (oldest.map(_.executionTimestamp), youngest.map(_.executionTimestamp))
      }
    } catch {
      case e: Exception =>
        logger.error("Could not fetch the reports interval from the database. Reason is : %s".format(e.getMessage()))
        Failure(e.getMessage())
    }
  }
  
  def getArchivedReportsInterval() : Box[(Option[DateTime], Option[DateTime])] = {
    try {
      for {
        oldest   <- reportsRepository.getOldestArchivedReports()
        youngest <- reportsRepository.getNewestArchivedReports()
      } yield {
        (oldest.map(_.executionTimestamp), youngest.map(_.executionTimestamp))
      }
    } catch {
      case e: Exception =>
        logger.error("Could not fetch the archived reports interval from the database. Reason is : %s".format(e.getMessage()))
        Failure(e.getMessage())
    }
  }
  
   def getDatabaseSize() : Box[Long] = {
     reportsRepository.getDatabaseSize(reportsRepository.reportsTable)
   }
   
   def getArchiveSize() : Box[Long] = {
     reportsRepository.getDatabaseSize(reportsRepository.archiveTable)
   }

   def archiveEntries(date : DateTime) =  {
     reportsRepository.archiveEntries(date)
   }

   def deleteEntries(date : DateTime) = {
     reportsRepository.deleteEntries(date)
   }
}