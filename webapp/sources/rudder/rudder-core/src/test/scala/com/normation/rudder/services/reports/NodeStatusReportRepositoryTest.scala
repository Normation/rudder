/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

package com.normation.rudder.services.reports

import com.normation.errors.IOResult
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.PolicyTypeName
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.domain.reports.NodeExpectedReports
import com.normation.rudder.domain.reports.NodeStatusReport
import com.normation.rudder.domain.reports.RuleNodeStatusReport
import com.normation.rudder.domain.reports.RunAnalysisKind
import com.normation.rudder.domain.reports.RunComplianceInfo
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.services.policies.NodeConfigData
import com.normation.zio.*
import com.softwaremill.quicklens.*
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.JUnitRunner
import zio.*

/*
 * Test about the NodeStatusReportRepository, in particular:
 * - behavior with missing reports,
 * - behavior with keepLast
 */
@RunWith(classOf[JUnitRunner])
class NodeStatusReportRepositoryTest extends Specification {

  val expiration: DateTime = DateTime.now(DateTimeZone.UTC)
  val beginDate:  DateTime = expiration.minusMinutes(10) // report generation time
  val expired:    DateTime = expiration.minusMinutes(5)
  val stillOk:    DateTime = expiration.plusMinutes(5)

  // build one node by kind of reports, expired or not
  def buildNode(id: String): (NodeId, CoreNodeFact) = {
    (NodeId(id), NodeConfigData.fact1.modify(_.id).setTo(NodeId(id)))
  }

  def nsr(id: String, info: RunAndConfigInfo, bootstrapRules: Boolean = false): NodeStatusReport = {
    val rules = if (bootstrapRules) {
      Set(RuleNodeStatusReport(NodeId(id), RuleId(RuleUid("initRule")), PolicyTypeName.rudderBase, None, None, Map(), expiration))
    } else Set.empty[RuleNodeStatusReport]
    NodeStatusReportInternal.buildWith(NodeId(id), info, RunComplianceInfo.OK, Nil, rules).toNodeStatusReport()
  }

  // a list of node, one node by type of reports, in a triplet:
  // (node, expired report, still ok report)
  def expected(id: String, endDate: Option[DateTime]): NodeExpectedReports =
    NodeExpectedReports(NodeId(id), NodeConfigId(id), beginDate, endDate, null, Nil, Nil)

  def initReportEachKind(endDate: Option[DateTime], runDate: DateTime): List[(NodeId, NodeStatusReport)] = {
    def buildOne(id: String)(f: NodeExpectedReports => RunAndConfigInfo) = {
      val e = expected(id, endDate)
      (NodeId(id), nsr(id, f(e), bootstrapRules = true))
    }
    List(
      buildOne("nrner")(_ => NoRunNoExpectedReport),
      buildOne("ner")(x => NoExpectedReport(runDate, None)),
      buildOne("nurd")(x => NoUserRulesDefined(runDate, x, x.nodeConfigId, Some(x), expiration)),
      buildOne("nrii")(x => NoReportInInterval(x, expiration)),
      buildOne("klc")(x => KeepLastCompliance(x, expiration.minusMinutes(10), expiration, None)),
      buildOne("rdii")(x => ReportsDisabledInInterval(x, expiration)),
      buildOne("p")(x => Pending(x, None, expiration)),
      buildOne("uv")(x => UnexpectedVersion(runDate, Some(x), expiration, x, expiration, expiration)),
      buildOne("unv")(x => UnexpectedNoVersion(runDate, NodeConfigId("x"), expiration, x, expiration, expiration)),
      buildOne("uuv")(x => UnexpectedUnknownVersion(runDate, NodeConfigId("x"), x, expiration, expiration)),
      buildOne("cc")(x => ComputeCompliance(runDate, x, expiration))
    )
  }

  class Counter(s: NodeStatusReportStorage) extends NodeStatusReportStorage {
    val counters: Ref[Map[NodeId, Int]] = Ref.make(Map[NodeId, Int]()).runNow

    // utility methods for tests
    def getCount(id: String): Int = counters.get.runNow.getOrElse(NodeId(id), 0)
    def get(id: String): NodeStatusReport =
      s.getAll().map(_.getOrElse(NodeId(id), throw new RuntimeException(s"Id '${id}' is not present in storage"))).runNow

    override def getAll(): IOResult[Map[NodeId, NodeStatusReport]] = {
      s.getAll()
    }

    override def save(reports: Iterable[(NodeId, NodeStatusReport)]): IOResult[Unit] = {
      for {
        _ <- counters.update(m => m ++ reports.map { case (id, _) => (id, m.getOrElse(id, 0) + 1) })
        _ <- s.save(reports)
      } yield ()
    }

    override def delete(nodes: Iterable[NodeId]): IOResult[Unit] = {
      s.delete(nodes)
    }
  }

  def initServices(moreReports: NodeStatusReport*): (Counter, NodeStatusReportRepository) = {
    val n = initReportEachKind(None, expiration)
    (for {
      x <- Ref.make((n ++ moreReports.map(x => (x.nodeId, x))).toMap)
      s  = new Counter(new InMemoryNodeStatusReportStorage(x))
    } yield (s, new NodeStatusReportRepositoryImpl(s, x))).runNow
  }

  "If we save exactly the same reports, no storage is done at all" >> {

    val (counter, repo) = initServices()
    implicit val cc     = ChangeContext.newForRudder()

    val counters = (for {
      rs <- counter.getAll()
      _  <- repo.saveNodeStatusReports(rs)
      c  <- counter.counters.get
    } yield c).runNow

    counters must beEmpty
  }

  "If we change runDate, 5 kind of reports are impacted" >> {

    val (counter, repo) = initServices()
    implicit val cc     = ChangeContext.newForRudder()
    val newReports      = initReportEachKind(None, expiration.plusMinutes(5))

    val counters = (for {
      _ <- repo.saveNodeStatusReports(newReports)
      c <- counter.counters.get
    } yield c).runNow.map(x => (x._1.value, x._2))

    counters must containTheSameElementsAs(
      ("ner", 1) :: ("nurd", 1) :: ("uv", 1) :: ("uuv", 1) :: ("unv", 1) :: ("cc", 1) :: Nil
    )
  }

  "A Pending or a ComputeCompliance which becomes missing is missing when no keep compliance" >> {

    // generate a missing but with "keep last" flag set
    def missing(id: String) = {
      val x = expected(id, None)
      nsr(id, NoReportInInterval(x, expiration)).modify(_.runInfo.kind).setTo(RunAnalysisKind.KeepLastCompliance)
    }

    val (counter, repo) = initServices()
    implicit val cc     = ChangeContext.newForRudder()

    repo.saveNodeStatusReports((missing("p") :: Nil).map(x => (x.nodeId, x))).runNow

    // we have one modification because we changed kind from pending to "keep"
    counter.getCount("p") === 1
    // but we kept the existing report with some report (vs missing which has no rule status reports)
    counter.get("p").reports must not(beEmpty)
  }

}
