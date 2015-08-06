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

package com.normation.rudder.repository.jdbc

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

import org.joda.time.DateTime

import org.junit.runner.RunWith
import org.specs2.mutable._

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.migration.DBCommon
import com.normation.rudder.reports.execution.AgentRun
import com.normation.rudder.reports.execution.AgentRunId
import com.normation.rudder.reports.execution.RoReportsExecutionJdbcRepository
import com.normation.rudder.reports.execution.WoReportsExecutionSquerylRepository

import net.liftweb.common.Full


/**
 *
 * Test on database.
 *
 */
@RunWith(classOf[JUnitRunner])
class AgentRunsTest extends DBCommon {

  //clean data base
  def cleanTables() = {
    jdbcTemplate.execute("DELETE FROM ReportsExecution;")
  }


  lazy val roRunRepo = new RoReportsExecutionJdbcRepository(jdbcTemplate, new PostgresqlInClause(2))
  lazy val woRunRepo = new WoReportsExecutionSquerylRepository(squerylConnectionProvider, roRunRepo)


  val (n1, n2) = (NodeId("n1"), NodeId("n2"))
  val runMinus2 = DateTime.now.minusMinutes(7)
  val runMinus1 = DateTime.now.minusMinutes(2)

  sequential


  "Execution repo" should {
    val runs = Seq(
        AgentRun(AgentRunId(n1, runMinus2), Some(NodeConfigId("nodeConfig_n1_v1")), true, 12)
      , AgentRun(AgentRunId(n1, runMinus1), Some(NodeConfigId("nodeConfig_n1_v1")), false, 42)
    )

    "correctly insert" in {
      woRunRepo.updateExecutions(Seq(runs(0))) must beEqualTo( Full(Seq(runs(0)) ))
    }

    "correctly find back" in {
      roRunRepo.getNodesLastRun(Set(n1)) must beEqualTo(Full(Map(n1 -> Some(runs(0)))))
    }

    "correctly ignore incomplete" in {
      //(woRunRepo.updateExecutions(runs) must beEqualTo( Full(Seq(runs(1))) )) and
      (roRunRepo.getNodesLastRun(Set(n1)) must beEqualTo(Full(Map(n1 -> Some(runs(0))))))
    }

    "don't find report when none was added" in {
      roRunRepo.getNodesLastRun(Set(n2)) must beEqualTo(Full(Map(n2 -> None)))
    }
  }


  val initRuns = Seq(
      AgentRun(AgentRunId(n1, runMinus2.minusMinutes(5)), Some(NodeConfigId("nodeConfig_n1_v1")), true, 12)
    , AgentRun(AgentRunId(n1, runMinus2), Some(NodeConfigId("nodeConfig_n1_v1")), true, 42)
    , AgentRun(AgentRunId(n1, runMinus1), Some(NodeConfigId("nodeConfig_n1_v1")), false, 64)
  )

  /*
   * BE VERY CAREFULL: code just above the "should" is not
   * executed when one's thinking. So if you have logic
   * to exec before fragment, it MUST go in a step...
   */
  step {
    cleanTables
    woRunRepo.updateExecutions(initRuns)
  }

  "Updating execution" should {

    "correctly close and let closed existing execution" in {
      val all = Seq(
          initRuns(0).copy(isCompleted = false) //not updated
        , initRuns(1).copy(nodeConfigVersion = Some(NodeConfigId("nodeConfig_n1_v2")))
        , initRuns(2).copy(isCompleted = true)
        , AgentRun(AgentRunId(n1, runMinus2.minusMinutes(10)), Some(NodeConfigId("nodeConfig_n1_v1")), true, 12)
        , AgentRun(AgentRunId(n1, runMinus1.plusMinutes(5)), Some(NodeConfigId("nodeConfig_n1_v1")), false, 42)
      )

      //only the first one should not be modified
      woRunRepo.updateExecutions(all).openOrThrowException("Failed test") must contain(exactly(all.tail:_*))

    }
  }

}
