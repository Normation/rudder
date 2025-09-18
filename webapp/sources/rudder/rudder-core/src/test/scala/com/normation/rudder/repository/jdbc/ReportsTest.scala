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

import cats.implicits.*
import com.normation.errors.*
import com.normation.inventory.domain.NodeId
import com.normation.rudder.db.DB
import com.normation.rudder.db.DBCommon
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.domain.reports.Reports
import com.normation.rudder.reports.execution.AgentRun
import com.normation.rudder.reports.execution.AgentRunId
import com.normation.zio.*
import doobie.implicits.*
import net.liftweb.common.*
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import zio.interop.catz.*

/**
 *
 * Test on database.
 *
 */
@RunWith(classOf[JUnitRunner])
class ReportsTest extends DBCommon {

  implicit class ForceOpen[A](box: Box[A]) {
    def open: A = box match {
      case Full(x) => x
      case eb: EmptyBox => throw new IllegalArgumentException(s"Test failed, open an empty box: ${eb}")
    }
  }

  implicit class ForceRun[A](io: IOResult[A]) {
    def open: A = io.either.runNow match {
      case Right(x)  => x
      case Left(err) => throw new IllegalArgumentException(s"Test failed, open an empty box: ${err.fullMsg}")
    }
  }

  // clean data base
  def cleanTables(): Int = {
    transacRun(xa => sql"DELETE FROM ReportsExecution; DELETE FROM RudderSysEvents;".update.run.transact(xa))
  }

  lazy val repostsRepo = new ReportsJdbcRepository(doobie)

  sequential

  implicit def toReport(t: (DateTime, String, String, String, String, String, String, DateTime, String, String)): Reports = {
    implicit def toRuleId(s:      String): RuleId      = RuleId(RuleUid(s))
    implicit def toDirectiveId(s: String): DirectiveId = DirectiveId(DirectiveUid(s))
    implicit def toNodeId(s:      String): NodeId      = NodeId(s)

    Reports(t._1, t._2, t._3, t._4, t._5, t._6, t._7, t._8, t._9, t._10)
  }

  //         nodeId               ruleId  dirId   serial  comp     keyVal   execTime   severity  msg
  def node(nodeId: String)(lines: (String, String, String, String, String, DateTime, String, String)*): (String, Seq[Reports]) = {
    (nodeId, lines.map(t => toReport((t._6, t._1, t._2, nodeId, t._3, t._4, t._5, t._6, t._7, t._8))))
  }

  val run1: DateTime = DateTime.now.minusMinutes(5 * 5).withMillisOfSecond(123) // check that millis are actually used
  val run2: DateTime = DateTime.now.minusMinutes(5 * 4)
  val run3: DateTime = DateTime.now.minusMinutes(5 * 3)

  implicit def toAgentIds(ids: Set[(String, DateTime)]): Set[AgentRunId] = {
    ids.map(t => AgentRunId(NodeId(t._1), t._2))
  }

  "Execution repo" should {
    val reports = (
      Map[String, Seq[Reports]]() +
        node("n0")(
          ("r0", "d1", "report_id0", "c1", "cv1", run1, "result_success", "End execution")
        ) +
        node("n1")(
          ("r0", "d1", "report_id0", "c1", "cv1", run1, "result_success", "Start execution"),
          ("r1", "d1", "report_id0", "c1", "cv1", run1, "result_success", "msg1"),
          ("r1", "d1", "report_id0", "c2", "cv2", run1, "result_success", "msg1"), // haha! run2!

          ("r1", "d1", "report_id0", "c2", "cv2", run2, "result_success", "msg1")
        ) +
        node("n2")(
          ("r0", "d1", "report_id0", "c1", "cv1", run1, "result_success", "End execution"),
          ("r1", "d1", "report_id0", "c1", "cv1", run1, "result_success", "msg1"),
          ("r1", "d1", "report_id0", "c2", "cv2", run1, "result_success", "msg1")
        )
    )
    "correctly init info" in {
      transacRun(xa => DB.insertReports(reports.values.toList.flatten).transact(xa))
      transacRun(xa => sql"""select id from ruddersysevents""".query[Long].to[Vector].transact(xa)).size === 8
    }

    "find the last reports for node0" in {
      val result = repostsRepo.getExecutionReports(Set(AgentRunId(NodeId("n0"), run1))).open
      result.values.flatten.toSeq must contain(exactly(reports("n0")(0)))
    }

    "find reports for node 0,1,2" in {
      val runs = Set(("n0", run1), ("n1", run1), ("n2", run1))
      val result: Map[NodeId, Seq[Reports]] = repostsRepo.getExecutionReports(runs).open
      val actual: Seq[Reports]              = result.values.toSeq.flatten
      actual must contain(exactly(reports("n0") ++ reports("n1").reverse.tail ++ reports("n2"): _*))
    }

    "not find report for none existing agent run id" in {
      val runs   = Set(("n2", run2), ("n3", run1))
      val result = repostsRepo.getExecutionReports(runs).open
      result must beEmpty
    }
  }

  // since #18093, we assume that all run are complete, so we ignore runs without an "end" control element
  "Finding execution, ignoring non-ending runs" should {
    val reports = (
      Map[String, Seq[Reports]]() +
        node("n0")(
          // ruleId  dirId serial comp  keyVal  execTime   severity      msg
          ("rudder", "run", "report_id0", "end", "", run1, "control", "End execution")
        ) +
        node("n1")(
          // run1
          ("rudder", "run", "report_id0", "start", "n1_run1", run1, "control", "Start execution"),
          ("r1", "d1", "report_id0", "c1", "cv1", run1, "result_success", "msg1"),
          ("r1", "d1", "report_id0", "c2", "cv2", run1, "result_success", "msg2"),
          ("r1", "d1", "report_id0", "c2", "cv1", run1, "result_success", "msg1"),
          ("r1", "d1", "report_id0", "c2", "cv3", run1, "result_success", "msg3"),
          ("rudder", "run", "report_id0", "end", "n1_run1", run1, "control", "End execution"), // run2

          ("rudder", "run", "report_id0", "start", "n1_run2", run2, "control", "Start execution"),
          ("r1", "d1", "report_id0", "c2", "cv2", run2, "result_success", "msg1"),
          (
            "rudder",
            "run",
            "report_id0",
            "end",
            "n1_run2",
            run2,
            "control",
            "End execution"
          ), // run3 will be ignore: no end element

          ("rudder", "run", "report_id0", "start", "n1_run3", run3, "control", "Start execution")
        ) +
        node("n2")(
          // run will be taken even without a start: only 'end' counts.
          ("rudder", "run", "report_id0", "end", "n2_run1", run1, "control", "End execution"),
          ("r1", "d1", "report_id0", "c1", "cv1", run1, "result_success", "msg1"),
          ("r1", "d1", "report_id0", "c2", "cv2", run1, "result_success", "msg1")
        )
    )
    step {
      cleanTables()
      transacRun(xa => DB.insertReports(reports.values.toList.flatten).transact(xa))
    }

    /* TODO:
     * - test with a nodeConfigVersion when run exists without one
     * - test without a nodeConfigVersion when run exists with one
     * - test case where there is no StartRun/EndRun
     */
    "get reports" in {
      val res      = repostsRepo.getReportsFromId(0, DateTime.now().plusDays(1)).open
      val expected = Seq(
        AgentRun(AgentRunId(NodeId("n0"), run1), None, 109),
        AgentRun(AgentRunId(NodeId("n1"), run1), Some(NodeConfigId("n1_run1")), 115),
        AgentRun(AgentRunId(NodeId("n1"), run2), Some(NodeConfigId("n1_run2")), 118),
        AgentRun(AgentRunId(NodeId("n2"), run1), Some(NodeConfigId("n2_run1")), 120)
      )

      val checkInsert = transacRun(xa => sql"""select id from ruddersysevents""".query[Long].to[Vector].transact(xa)).size

      checkInsert must beEqualTo(14)
      res._1 must contain(exactly(expected*))
    }

  }

}
