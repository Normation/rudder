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

import com.normation.{BoxSpecMatcher, errors}
import com.normation.inventory.domain.NodeId
import com.normation.rudder.db.{DB, DBCommon}
import com.normation.rudder.reports.execution.RoReportsExecutionRepositoryImpl
import com.normation.rudder.reports.execution.WoReportsExecutionRepositoryImpl
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import doobie.implicits._
import zio.interop.catz._
import com.normation.box._

/**
 *
 * Test on database.
 *
 */
@RunWith(classOf[JUnitRunner])
class AgentRunsTest extends DBCommon with BoxSpecMatcher {

  //clean data base
  def cleanTables() = {
    transacRun(xa => sql"DELETE FROM ReportsExecution;".update.run.transact(xa))
  }



  lazy val woRunRepo = new WoReportsExecutionRepositoryImpl(doobie)
  lazy val roRunRepo = new RoReportsExecutionRepositoryImpl(doobie,woRunRepo, null,  new PostgresqlInClause(2), 200)

  val nodes = Seq(NodeId("n1"), NodeId("n2"), NodeId("n3"))

  val runBaseline = DateTime.now.minusMinutes(2)

  val insertionTimeBaseline = DateTime.now.minusMinutes(1)

  val runs = Seq(
        DB.UncomputedAgentRun("node1", runBaseline.minusMinutes(5),  Some("NodeConfigId1"), 1, insertionTimeBaseline.minusMinutes(5))
    ,   DB.UncomputedAgentRun("node1", runBaseline,                  Some("NodeConfigId1"), 2, insertionTimeBaseline)
    ,   DB.UncomputedAgentRun("node2", runBaseline.minusMinutes(15), Some("NodeConfigId2"), 3, insertionTimeBaseline.minusMinutes(15))
    ,   DB.UncomputedAgentRun("node2", runBaseline.minusMinutes(10), Some("NodeConfigId2"), 4, insertionTimeBaseline.minusMinutes(10))
    ,   DB.UncomputedAgentRun("node2", runBaseline.minusMinutes(5),  Some("NodeConfigId2"), 5, insertionTimeBaseline.minusMinutes(5))
    ,   DB.UncomputedAgentRun("node3", runBaseline.minusMinutes(15), Some("NodeConfigId3"), 6, insertionTimeBaseline.minusMinutes(15))
    ,   DB.UncomputedAgentRun("node3", runBaseline.minusMinutes(5),  Some("NodeConfigId3"), 7, insertionTimeBaseline.minusMinutes(5))
  )
  sequential


  "insert runs" should {
    "insert reports" in {
      val q = DB.insertUncomputedAgentRun(runs.toList)

      val r = transacRun(xa => q.transact(xa))

      (r must be_==(7))
    }

    "detect that we have the correct number of unprocessed run" in {
      roRunRepo.getUnprocessedRuns().toBox.map(x => x.size) mustFullEq(7)
    }
  }

  "setting compliance computation date" should {
    "update the values" in {
      val result = woRunRepo.setComplianceComputationDate(runs.map(_.toAgentRunWithoutCompliance).toList)
      result.toBox mustFullEq(7)
    }

    "don't have any unprocessed runs after" in {
      roRunRepo.getUnprocessedRuns().toBox.map(x => x.size) mustFullEq(0)
    }
  }


}
