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

import scala.concurrent.Await

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.migration.DBCommon
import com.normation.rudder.reports.execution.AgentRun
import com.normation.rudder.reports.execution.AgentRunId
import com.normation.rudder.reports.execution.RoReportsExecutionRepositoryImpl
import com.normation.rudder.reports.execution.WoReportsExecutionRepositoryImpl

import org.joda.time.DateTime

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

import net.liftweb.common.Full
import org.specs2.concurrent.ExecutionEnv


/**
 *
 * Test on database.
 *
 */
@RunWith(classOf[JUnitRunner])
class AgentRunsTest extends DBCommon {

  type EE = ExecutionEnv

  //clean data base
  def cleanTables() = {
    jdbcTemplate.execute("DELETE FROM ReportsExecution;")
  }


  lazy val roRunRepo = new RoReportsExecutionRepositoryImpl(slickSchema, new PostgresqlInClause(2))
  lazy val woRunRepo = new WoReportsExecutionRepositoryImpl(slickSchema, roRunRepo)


  val (n1, n2) = (NodeId("n1"), NodeId("n2"))
  val runMinus2 = DateTime.now.minusMinutes(7)
  val runMinus1 = DateTime.now.minusMinutes(2)

  sequential


  "Execution repo" should {
    val runs = Seq(
        AgentRun(AgentRunId(n1, runMinus2), Some(NodeConfigId("nodeConfig_n1_v1")), true, 12)
      , AgentRun(AgentRunId(n1, runMinus1), Some(NodeConfigId("nodeConfig_n1_v1")), false, 42)
    )

    "correctly insert" in { implicit ee: EE =>
      woRunRepo.updateExecutions(Seq(runs(0))) must beEqualTo( Seq(Full(runs(0)) )).await
    }

    "correctly find back" in { implicit ee: EE =>
      roRunRepo.getNodesLastRun(Set(n1)) must beEqualTo(Full(Map(n1 -> Some(runs(0))))).await
    }

    "correctly ignore incomplete" in { implicit ee: EE =>
      //(woRunRepo.updateExecutions(runs) must beEqualTo( Full(Seq(runs(1))) )) and
      roRunRepo.getNodesLastRun(Set(n1)) must beEqualTo(Full(Map(n1 -> Some(runs(0))))).await
    }

    "don't find report when none was added" in { implicit ee: EE =>
      roRunRepo.getNodesLastRun(Set(n2)) must beEqualTo(Full(Map(n2 -> None))).await
    }
  }


  val n = (0 to 6).map(i => NodeId("n" + i))
  val initRuns = Seq(
      AgentRun(AgentRunId(n(0), runMinus1), Some(NodeConfigId("nodeConfig_n1_v1")), true, 12)
    , AgentRun(AgentRunId(n(1), runMinus1), Some(NodeConfigId("nodeConfig_n1_v1")), false, 44)
    , AgentRun(AgentRunId(n(2), runMinus1), Some(NodeConfigId("nodeConfig_n1_v1")), true, 64)
    , AgentRun(AgentRunId(n(3), runMinus1), Some(NodeConfigId("nodeConfig_n1_v1")), true, 80)
    , AgentRun(AgentRunId(n(4), runMinus1), Some(NodeConfigId("nodeConfig_n1_v1")), true, 97)
    , AgentRun(AgentRunId(n(5), runMinus1), Some(NodeConfigId("nodeConfig_n1_v1")), true, 167)
    , AgentRun(AgentRunId(n(6), runMinus1), Some(NodeConfigId("nodeConfig_n1_v1")), true, 167)
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

    "correctly close and let closed existing execution" in { implicit ee: EE =>
      val news = Seq(
          AgentRun(AgentRunId(n1, runMinus2.minusMinutes(10)), Some(NodeConfigId("nodeConfig_n1_v1")), true, 12)
        , AgentRun(AgentRunId(n1, runMinus1.plusMinutes(5)), Some(NodeConfigId("nodeConfig_n1_v1")), false, 42)
      )
      val updated = Seq(
          initRuns(0).copy(nodeConfigVersion = Some(NodeConfigId("nodeConfig_n1_v2")))
        , initRuns(1).copy(isCompleted = true)
        , initRuns(2).copy(insertionId = 218)
      )
      val notToUpdate = Seq(
          //ignore if switch back to false
          initRuns(3).copy(isCompleted = false) //not updated
        , initRuns(3).copy(isCompleted = false, insertionId = 18435)
        , initRuns(4).copy(isCompleted = false, nodeConfigVersion = Some(NodeConfigId("nodeConfig_n1_v2")))
          //ignore if equals
        , initRuns(6)
      )
      val all = news ++ updated ++ notToUpdate

      Await.result(woRunRepo.updateExecutions(all), MAX_TIME).flatten must contain(exactly((news ++ updated):_*))

    }
  }

}
