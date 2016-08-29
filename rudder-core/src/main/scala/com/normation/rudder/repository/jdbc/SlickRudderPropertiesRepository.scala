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

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{ Failure => TFailure }
import scala.util.{ Success => TSuccess }

import com.normation.rudder.db.DB
import com.normation.rudder.db.SlickSchema

import net.liftweb.common._
import com.normation.rudder.repository.ReportsRepository
import com.normation.rudder.repository.RudderPropertiesRepository

class RudderPropertiesRepositoryImpl(
     slickSchema      : SlickSchema
   , reportsRepository: ReportsRepository
) extends RudderPropertiesRepository with Loggable {

  private[this] val PROP_REPORT_LAST_ID = "reportLoggerLastId"

  import slickSchema.api._

  /**
   * Get the last report id processed by the non compliant report Logger.
   * If there was no id processed, get the last report id in reports database
   * and add it to the database.
   */
  def getReportLoggerLastId: Future[Box[Long]] = {

    val q = for {
      p <- slickSchema.runProperties
           if( p.name === PROP_REPORT_LAST_ID)
    } yield {
      p.value
    }

    slickSchema.db.run(q.result.asTry).map { res => res match {
      case TSuccess(seq) => seq.headOption match { //name is primary key, at most 1
        case None =>
          Failure(s"The property '${PROP_REPORT_LAST_ID}' was not found in table '${slickSchema.runProperties.baseTableRow.tableName}'")
        case Some(x) =>
          try {
            Full(x.toLong)
          } catch {
            case ex: Exception => Failure(s"Error when parsing property '${PROP_REPORT_LAST_ID}' with value '${x}' as long", Full(ex), Empty)
          }
      }

    } }
  }

  /**
   * Update the last id processed by the non compliant report logger
   * If not present insert it
   */
  def updateReportLoggerLastId(newId: Long) : Future[Box[Long]] = {
    val q = for {
      rowsAffected <- slickSchema.runProperties
                        .filter( _.name === PROP_REPORT_LAST_ID )
                        .map(_.value).update(newId.toString)
      result       <- rowsAffected match {
                        case 0 =>
                          logger.warn("last id not present in database, create it with value %d".format(newId))
                          slickSchema.runProperties += DB.RunProperties(PROP_REPORT_LAST_ID, newId.toString) //insert
                        case 1 => DBIO.successful(1)
                        case n => DBIO.failed(new RuntimeException(s"Expected 0 or 1 change, not ${n} for ${PROP_REPORT_LAST_ID}"))
                      }
    } yield {
      result
    }


    slickSchema.db.run(q.asTry).map {
      case TSuccess(x)  => Full(newId)
      case TFailure(ex) => Failure(s"could not update lastId from database, cause is: ${ex.getMessage}", Full(ex), Empty)
    }
  }

}
