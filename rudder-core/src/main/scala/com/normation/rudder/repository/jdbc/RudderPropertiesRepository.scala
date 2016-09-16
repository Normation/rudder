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

package com.normation.rudder.repository.jdbc

import scalaz.{Failure => _, _}, Scalaz._
import doobie.imports._
import scalaz.concurrent.Task

import com.normation.rudder.db.DB

import net.liftweb.common._
import com.normation.rudder.repository.ReportsRepository
import com.normation.rudder.repository.RudderPropertiesRepository
import com.normation.rudder.db.Doobie

class RudderPropertiesRepositoryImpl(
     db: Doobie
) extends RudderPropertiesRepository with Loggable {

  val PROP_REPORT_LAST_ID = "reportLoggerLastId"

  import db._

  /**
   * Get the last report id processed by the non compliant report Logger.
   * If there was no id processed, get the last report id in reports database
   * and add it to the database.
   */
  def getReportLoggerLastId: Box[Long] = {

    val sql = sql"select value from rudderproperties where name=${PROP_REPORT_LAST_ID}".query[Long].option
    sql.attempt.transact(xa).run match {
      case \/-(None) =>
          Failure(s"The property '${PROP_REPORT_LAST_ID}' was not found in table 'rudderproperties'")
      case \/-(Some(x)) =>
        try {
          Full(x.toLong)
        } catch {
          case ex: Exception => Failure(s"Error when parsing property '${PROP_REPORT_LAST_ID}' with value '${x}' as long", Full(ex), Empty)
        }
      case -\/(ex) => Failure(s"Error when parsing property '${PROP_REPORT_LAST_ID}'", Full(ex), Empty)
    }
  }

  /**
   * Update the last id processed by the non compliant report logger
   * If not present insert it
   */
  def updateReportLoggerLastId(newId: Long) : Box[Long] = {

    val insert = sql"""
      insert into rudderproperties(name, value)
      values (${PROP_REPORT_LAST_ID}, ${newId})
    """.update
    val update = sql"""
      update rudderproperties
      set value=${newId.toString}
      where name=${PROP_REPORT_LAST_ID}
    """.update

    val sql = for {
      rowsAffected <- update.run
      result       <- rowsAffected match {
                        case 0 =>
                          logger.warn("last id not present in database, create it with value %d".format(newId))
                          insert.run
                        case 1 => 1.point[ConnectionIO]
                        case n => throw new RuntimeException(s"Expected 0 or 1 change, not ${n} for ${PROP_REPORT_LAST_ID}")
                      }
    } yield {
      result
    }


    sql.attempt.transact(xa).run match {
      case \/-(x)  => Full(newId)
      case -\/(ex) => Failure(s"could not update lastId from database, cause is: ${ex.getMessage}", Full(ex), Empty)
    }
  }

}
