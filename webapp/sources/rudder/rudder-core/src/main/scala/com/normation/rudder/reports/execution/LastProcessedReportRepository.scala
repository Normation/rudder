/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

package com.normation.rudder.reports.execution

import doobie._, doobie.implicits._
import cats.implicits._


import com.normation.rudder.db.DB

import org.joda.time.DateTime
import net.liftweb.common._
import com.normation.rudder.db.Doobie
import com.normation.rudder.db.Doobie._


/**
 * Manage the status of the fetching of execution date per node
 */
trait LastProcessedReportRepository {

  /*
   * BE CAREFUL : these methods will report wrong values
   * if two instances of Rudder are using the same
   * database.
   */

  def getExecutionStatus : Box[Option[(Long,DateTime)]]

  def setExecutionStatus (newId : Long, reportsDate : DateTime): Box[DB.StatusUpdate]

}


class LastProcessedReportRepositoryImpl (
    db: Doobie
) extends LastProcessedReportRepository with Loggable {
  import db._

  val PROP_EXECUTION_STATUS = "executionStatus"

  def getExecutionStatus : Box[Option[(Long,DateTime)]] = {
    val sql = sql"""
        select lastid, date from statusupdate where key=${PROP_EXECUTION_STATUS}
      """.query[(Long, DateTime)].option

    transactRun(xa => sql.transact(xa).attempt) match {
      case Right(x)  => Full(x)
      case Left(ex) => Failure(s"Error when retrieving '${PROP_EXECUTION_STATUS}' from db: ${ex.getMessage}", Full(ex), Empty)
    }
  }

  def setExecutionStatus(newId : Long, reportsDate : DateTime) : Box[DB.StatusUpdate] = {

    //upsert of the poor :)
    val insert = sql"""
      insert into statusupdate (key, lastid, date)
      values (${PROP_EXECUTION_STATUS}, ${newId}, ${reportsDate})
    """.update

    val update = sql"""
      update statusupdate set lastid=${newId}, date=${reportsDate}
      where key=${PROP_EXECUTION_STATUS}
    """.update

    transactRun(xa => (for {
      rowAffected <- update.run
      entry       <- rowAffected match {
                       case 0 => insert.run
                       case 1 => 1.pure[ConnectionIO]
                       case n => throw new RuntimeException(s"Error when updating the table statusupdate properties ${PROP_EXECUTION_STATUS}")
                     }
    } yield {
      entry
    }).transact(xa).attempt.unsafeRunSync match {
      case Right(x) => Full(DB.StatusUpdate(PROP_EXECUTION_STATUS, newId, reportsDate))
      case Left(ex) => Failure(s"Error when retrieving '${PROP_EXECUTION_STATUS}' from db: ${ex.getMessage}", Full(ex), Empty)
    }
  }

}

