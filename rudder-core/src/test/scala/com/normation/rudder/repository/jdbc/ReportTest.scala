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

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.domain.reports.Reports
import com.normation.rudder.migration.DBCommon
import com.normation.rudder.reports.execution.AgentRun
import com.normation.rudder.reports.execution.AgentRunId

import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import com.normation.rudder.db.DB

/**
 *
 * Test on database.
 *
 */
@RunWith(classOf[JUnitRunner])
class ReportsTest extends DBCommon {


  //clean data base
  def cleanTables() = {
    jdbcTemplate.execute("DELETE FROM ReportsExecution; DELETE FROM RudderSysEvents;")
  }

  lazy val repostsRepo = new ReportsJdbcRepository(jdbcTemplate)

  import slickSchema.api._

  sequential

  implicit def toReport(t:(DateTime,String, String, String, Int, String, String, DateTime, String, String)) = {
    implicit def toRuleId(s:String) = RuleId(s)
    implicit def toDirectiveId(s: String) = DirectiveId(s)
    implicit def toNodeId(s: String) = NodeId(s)

    Reports(t._1, t._2, t._3,t._4,t._5,t._6,t._7,t._8,t._9,t._10)
  }

  //         nodeId               ruleId  dirId   serial  comp     keyVal   execTime   severity  msg
  def node(nodeId:String)(lines: (String, String, Int,    String,  String,  DateTime,  String,   String)*): (String, Seq[Reports]) = {
    (nodeId, lines.map(t => toReport((t._6, t._1, t._2, nodeId, t._3,t._4,t._5,t._6,t._7,t._8))))
  }

  val run1 = DateTime.now.minusMinutes(5*5).withMillisOfSecond(123) //check that millis are actually used
  val run2 = DateTime.now.minusMinutes(5*4)
  val run3 = DateTime.now.minusMinutes(5*3)

  implicit def toAgentIds(ids:Set[(String, DateTime)]):Set[AgentRunId] = {
    ids.map(t => AgentRunId(NodeId(t._1), t._2))
  }



  "Execution repo" should {
    val reports = (
      Map[String, Seq[Reports]]() +
      node("n0")(
          ("r0", "d1", 1, "c1", "cv1", run1, "result_success", "End execution")
      ) +
      node("n1")(
          ("r0", "d1", 1, "c1", "cv1", run1, "result_success", "Start execution")
        , ("r1", "d1", 1, "c1", "cv1", run1, "result_success", "msg1")
        , ("r1", "d1", 1, "c2", "cv2", run1, "result_success", "msg1")
        //haha! run2!
        , ("r1", "d1", 1, "c2", "cv2", run2, "result_success", "msg1")
      ) +
      node("n2")(
          ("r0", "d1", 1, "c1", "cv1", run1, "result_success", "End execution")
        , ("r1", "d1", 1, "c1", "cv1", run1, "result_success", "msg1")
        , ("r1", "d1", 1, "c2", "cv2", run1, "result_success", "msg1")
      )
    )
    step {
      insertReports(reports.values.toSeq.flatten)
    }

    "find the last reports for node0" in {
      val result = repostsRepo.getExecutionReports(Set(AgentRunId(NodeId("n0"), run1)), Set()).openOrThrowException("Test failed with exception")
      result.values.flatten.toSeq must contain(exactly(reports("n0")(0)))
    }

    "find reports for node 0,1,2" in {
      val runs = Set(("n0", run1), ("n1", run1), ("n2", run1) )
      val result = repostsRepo.getExecutionReports(runs, Set() ).openOrThrowException("Test failed with exception")
      result.values.flatten.toSeq must contain(exactly(reports("n0")++reports("n1").reverse.tail++reports("n2"):_*))
    }

    "not find report for none existing agent run id" in {
      val runs = Set( ("n2", run2), ("n3", run1))
      val result = repostsRepo.getExecutionReports(runs, Set() ).openOrThrowException("Test failed with exception")
      result must beEmpty
    }
  }

  "Finding execution" should {
    val reports = (
      Map[String, Seq[Reports]]() +
      node("n0")(
          ("hasPolicyServer-root", "d1", 1, "common", "EndRun", run1, "result_success", "End execution")
      ) +
      node("n1")(
        //run1
          ("hasPolicyServer-root", "d1", 1, "common", "StartRun", run1, "result_success", "Start execution [n1_run1]")
        , ("r1", "d1", 1, "c1", "cv1", run1, "result_success", "msg1")
        , ("r1", "d1", 1, "c2", "cv2", run1, "result_success", "msg2")
        , ("r1", "d1", 1, "c2", "cv1", run1, "result_success", "msg1")
        , ("r1", "d1", 1, "c2", "cv3", run1, "result_success", "msg3")
        , ("hasPolicyServer-root", "d1", 1, "common", "EndRun", run1, "result_success", "End execution [n1_run1]")
        //run2
        , ("hasPolicyServer-root", "d1", 1, "common", "StartRun", run2, "result_success", "Start execution [n1_run2]")
        , ("r1", "d1", 1, "c2", "cv2", run2, "result_success", "msg1")
        //run3
        , ("hasPolicyServer-root", "d1", 1, "common", "StartRun", run3, "result_success", "Start execution [n1_run3]")
      ) +
      node("n2")(
          ("hasPolicyServer-root", "d1", 1, "common", "EndRun", run1, "result_success", "End execution [n2_run1]")
        , ("r1", "d1", 1, "c1", "cv1", run1, "result_success", "msg1")
        , ("r1", "d1", 1, "c2", "cv2", run1, "result_success", "msg1")
      )
    )
    step {
      cleanTables()
      insertReports(reports.values.toSeq.flatten)
    }


    /* TODO:
     * - test with a nodeConfigVersion when run exists without one
     * - test without a nodeConfigVersion when run exists with one
     * - test case where there is no StartRun/EndRun
     */
    "get reports" in {
      val res = repostsRepo.getReportsfromId(0, DateTime.now().plusDays(1)).openOrThrowException("Test failed")
      val expected = Seq(
          AgentRun(AgentRunId(NodeId("n1"),run2),None,false, 116)
        , AgentRun(AgentRunId(NodeId("n2"),run1),Some(NodeConfigId("n2_run1")),true, 119)
        , AgentRun(AgentRunId(NodeId("n1"),run1),Some(NodeConfigId("n1_run1")),true, 110)
        , AgentRun(AgentRunId(NodeId("n1"),run3),None,false, 118)
        , AgentRun(AgentRunId(NodeId("n0"),run1),None,true, 109)
      )

      res._1 must contain(exactly(expected:_*))
    }

  }

}
