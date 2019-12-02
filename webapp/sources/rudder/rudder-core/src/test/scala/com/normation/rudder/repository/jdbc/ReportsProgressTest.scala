/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

import com.normation.BoxSpecMatcher
import com.normation.rudder.db.DB.StatusUpdate
import com.normation.rudder.db.DBCommon
import com.normation.rudder.reports.execution.LastProcessedReportRepositoryImpl

import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner

import doobie.implicits._
import zio.interop.catz._


/**
 *
 * Test on database.
 *
 */
@RunWith(classOf[JUnitRunner])
class ReportsProgressTest extends DBCommon with BoxSpecMatcher {

  //clean data base
  def cleanTables() = {
    transacRun(xa => sql"DELETE FROM ReportsExecution;".update.run.transact(xa))
  }


  lazy val lastlogged    = new RudderPropertiesRepositoryImpl(doobie)
  lazy val lastprocessed = new LastProcessedReportRepositoryImpl(doobie)


  sequential


  "Last processed id" should {

    "correctly insert at start" in {
      val now = DateTime.now
      (lastprocessed.getExecutionStatus mustFullEq(None)) and
      (lastprocessed.setExecutionStatus(43, now) mustFullEq(StatusUpdate(lastprocessed.PROP_EXECUTION_STATUS, 43, now)) ) and
      (lastprocessed.getExecutionStatus mustFullEq(Some((43, now))))
    }

    "correctly update after" in {
      val now = DateTime.now
      (lastprocessed.setExecutionStatus(88, now) mustFullEq(StatusUpdate(lastprocessed.PROP_EXECUTION_STATUS, 88, now)) ) and
      (lastprocessed.getExecutionStatus mustFullEq(Some((88, now))))
    }
  }

  "Last logged id" should {

    "correctly insert at start" in {
      (lastlogged.getReportLoggerLastId mustEmpty) and
      (lastlogged.updateReportLoggerLastId(43) mustFullEq(43) ) and
      (lastlogged.getReportLoggerLastId mustFullEq(43))
    }

    "correctly update after" in {
      (lastlogged.updateReportLoggerLastId(88) mustFullEq(88) ) and
      (lastlogged.getReportLoggerLastId mustFullEq(88))
    }
  }
}
