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

import cats.implicits._
import com.normation.box.IOToBox
import com.normation.errors.IOResult
import com.normation.rudder.db.Doobie
import com.normation.rudder.repository.RudderPropertiesRepository
import doobie._
import doobie.implicits._
import net.liftweb.common._
import zio.interop.catz._

class RudderPropertiesRepositoryImpl(
     db: Doobie
) extends RudderPropertiesRepository with Loggable {

  val PROP_REPORT_LAST_ID = "reportLoggerLastId"
  val PROP_REPORT_HANDLER_LAST_ID = "reportHandlerLastId"

  import db._


  def getLastId(key: String) : IOResult[Option[Long]] = {
    val sql = sql"select value from rudderproperties where name=${key}"
    transactIOResult(s"Error when parsing property '${key}'")(xa => sql.query[Long].option.transact(xa))
  }

  /**
   * Update the last id processed by the non compliant report logger
   * If not present insert it
   */
  def updateLastId(key : String, newId: Long) : IOResult[Long] = {

    val update = sql"""
      update rudderproperties
      set value=${newId.toString}
      where name=${key}
    """.update

    val sql = for {
      rowsAffected <- update.run
      result       <- rowsAffected match {
        case 0 =>
          logger.warn(s"last id not present in database, create it with value '${newId}'")
          sql"""insert into rudderproperties(name, value) values (${key}, ${newId})""".update.run
        case 1 => 1.pure[ConnectionIO]
        case n => throw new RuntimeException(s"Expected 0 or 1 change, not ${n} for ${key}")
      }
    } yield {
      result
    }

    transactIOResult(s"could not update lastId from database")(xa => sql.transact(xa).map(_ => newId))
  }

  /**
   * Get the last report id processed by the non compliant report Logger.
   * If there was no id processed, returns an Empty box so that the AutomaticReportLogger can update the value
   */
  def getReportLoggerLastId: Box[Option[Long]] = {
    getLastId(PROP_REPORT_LAST_ID).toBox
  }
  /**
   * Update the last id processed by the non compliant report logger
   * If not present insert it
   */
  def updateReportLoggerLastId(newId: Long) : Box[Long] = {
    updateLastId(PROP_REPORT_LAST_ID, newId).toBox

  }

  /**
   * Get the last report id processed by the Report handler.
   */
  def getReportHandlerLastId: IOResult[Option[Long]] = getLastId(PROP_REPORT_HANDLER_LAST_ID)

  /**
   * Update or create (if needed the last id processed by the non compliant report logger
   */
  def updateReportHandlerLastId(newId: Long): IOResult[Long] = updateLastId(PROP_REPORT_HANDLER_LAST_ID, newId)
}
