/*
 *************************************************************************************
 * Copyright 2020 Normation SAS
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
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.domain.reports.NodeExpectedReports
import com.normation.rudder.domain.reports.NodeStatusReport
import com.normation.rudder.domain.reports.RunComplianceInfo
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.CoreNodeFactRepository
import com.normation.rudder.facts.nodes.NodeFact
import com.normation.rudder.facts.nodes.NoopFactStorage
import com.normation.rudder.facts.nodes.NoopGetNodesBySoftwareName
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.reports.ComplianceModeName
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.services.policies.NodeConfigData
import com.normation.rudder.services.reports.CacheComplianceQueueAction.ExpiredCompliance
import com.normation.rudder.tenants.DefaultTenantService
import com.normation.zio.*
import com.softwaremill.quicklens.*
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.JUnitRunner
import zio.*
import zio.syntax.*

/*
 * Test the cache behaviour
 */
@RunWith(classOf[JUnitRunner])
class CachedFindRuleNodeStatusReportsTest extends Specification {

  val expired: DateTime = DateTime.now.minusMinutes(5)
  val stillOk: DateTime = DateTime.now.plusMinutes(5)

  // build one node by kind of reports, expired or not
  def buildNode(id: String): (NodeId, CoreNodeFact) = {
    (NodeId(id), NodeConfigData.fact1.modify(_.id).setTo(NodeId(id)))
  }

  def run(id: String, info: RunAndConfigInfo): NodeStatusReport =
    NodeStatusReport(NodeId(id), info, RunComplianceInfo.OK, Nil, Set())

  // a list of node, one node by type of reports, in a triplet:
  // (node, expired report, still ok report)
  def expected(id: String): NodeExpectedReports = NodeExpectedReports(NodeId(id), NodeConfigId(id), null, null, null, Nil, Nil)

  val nodes: List[((NodeId, CoreNodeFact), NodeStatusReport, NodeStatusReport)] = List(
    (
      buildNode("n0"),
      run("n0", NoRunNoExpectedReport),
      run("n0", NoRunNoExpectedReport)
    ),
    (
      buildNode("n1"),
      run("n1", NoExpectedReport(expired, None)),
      run("n1", NoExpectedReport(stillOk, None))
    ),
    (
      buildNode("n2"),
      run("n2", NoReportInInterval(null, expired)),
      run("n2", NoReportInInterval(null, stillOk))
    ),
    (
      buildNode("n3"),
      run("n3", ReportsDisabledInInterval(null, expired)),
      run("n3", ReportsDisabledInInterval(null, stillOk))
    ),
    (
      buildNode("n4"),
      run("n4", Pending(null, None, expired)),
      run("n4", Pending(null, None, stillOk))
    ),
    (
      buildNode("n5"),
      run("n5", UnexpectedVersion(null, Some(expected("n5")), expired, null, expired, expired)),
      run("n5", UnexpectedVersion(null, Some(expected("n5")), stillOk, null, stillOk, stillOk))
    ),
    (
      buildNode("n6"),
      run("n6", UnexpectedNoVersion(null, NodeConfigId("x"), expired, null, expired, expired)),
      run("n6", UnexpectedNoVersion(null, NodeConfigId("x"), stillOk, null, stillOk, stillOk))
    ),
    (
      buildNode("n7"),
      run("n7", UnexpectedUnknownVersion(null, NodeConfigId("x"), null, expired, expired)),
      run("n7", UnexpectedUnknownVersion(null, NodeConfigId("x"), null, stillOk, stillOk))
    ),
    (
      buildNode("n8"),
      run("n8", ComputeCompliance(null, expected("n5"), expired)),
      run("n8", ComputeCompliance(null, expected("n5"), stillOk))
    )
  )

  val accepted:                             Map[NodeId, CoreNodeFact]                                 = nodes.map { case (n, _, _) => n }.toMap
  val (nodeFactRepo, nodeStatusReportRepo): (CoreNodeFactRepository, DummyNodeStatusReportRepository) = {
    val testSavePrechecks = Chunk.empty[NodeFact => IOResult[Unit]]

    (for {
      t <- DefaultTenantService.make(Nil)
      r <- CoreNodeFactRepository
             .make(NoopFactStorage, NoopGetNodesBySoftwareName, t, Map(), accepted, Chunk.empty, testSavePrechecks)
      x <- Ref.make(Map[NodeId, NodeStatusReport]())
    } yield (r, new DummyNodeStatusReportRepository(x))).runNow
  }

  class TestFindNewStatusReports extends FindNewNodeStatusReports() {
    // what the backend will give to the cache
    var reports: Map[NodeId, NodeStatusReport] = Map[NodeId, NodeStatusReport]()

    // store all updated nodes
    var updated: List[NodeId] = Nil

    override def findRuleNodeStatusReports(nodeIds: Set[NodeId])(implicit
        qc: QueryContext
    ): IOResult[Map[NodeId, NodeStatusReport]] = {
      updated = (updated ++ nodeIds)
      reports.filter(x => nodeIds.contains(x._1)).succeed
    }

    override def buildNodeStatusReports(
        runInfos:           Map[NodeId, RunAndConfigInfo],
        complianceModeName: ComplianceModeName
    ): IOResult[Map[NodeId, NodeStatusReport]] = {
      ???
    }

    override def getNodeRunInfos(
        nodeIds:        Set[NodeId],
        complianceMode: GlobalComplianceMode
    ): IOResult[Map[NodeId, RunAndConfigInfo]] = ???

    override def findUncomputedNodeStatusReports(): IOResult[Map[NodeId, NodeStatusReport]] = ???
  }

  def newServices: (DummyNodeStatusReportRepository, TestFindNewStatusReports, ComputeNodeStatusReportServiceImpl) = {
    val x = new DummyNodeStatusReportRepository(Ref.make(Map[NodeId, NodeStatusReport]()).runNow)
    val y = new TestFindNewStatusReports()
    (x, y, new ComputeNodeStatusReportServiceImpl(x, nodeFactRepo, y, 3))
  }

  implicit val qc: QueryContext = QueryContext.testQC

  /*
   * rule1/dir1 is applied on node1 and node2 and is both here (node1) and skipped (node2)
   */
  "When a value is experied, it is added to compliance repos" >> {
    val (repo, finder, computer) = newServices
    val id                       = NodeId("n1")
    finder.reports = nodes.collect { case (n, a, _) if (n._1 == id) => (n._1, a) }.toMap

    // node not in cache, miss
    val n1 = repo.getNodeStatusReports(Set(id)).runNow
    computer.invalidateWithAction(List((id, ExpiredCompliance(id)))).runNow
    // let a chance for zio to exec
    Thread.sleep(1000)
    // now node was ask, it will be returned
    val n2 = repo.getNodeStatusReports(Set(id)).runNow

    (n1 must beEqualTo(Map())) and
    (n2 must beEqualTo(finder.reports)) and
    (finder.updated must beEqualTo(List(id)))
  }

  "Cache should return expired compliance but also ask for renew" >> {
    val (repo, finder, computer) = newServices
    finder.reports = nodes.map { case (n, a, _) => (n._1, a) }.toMap

    // node not in cache, empty, returns nothing
    val n1 = repo.getNodeStatusReports(finder.reports.keySet).runNow
    computer.invalidateWithAction(finder.reports.keySet.toList.map(n => (n, ExpiredCompliance(n)))).runNow

    // let a chance for zio to exec
    Thread.sleep(1000)

    // now node was ask, it will return all nodes, even expired, see: https://issues.rudder.io/issues/16612
    val n2 = repo.getNodeStatusReports(finder.reports.keySet).runNow
    // check for outdated compliance
    computer.outDatedCompliance().runNow

    // let a chance for zio to exec again to find back expired
    Thread.sleep(1000)

    // these one are not expired (but false)
    val okId = finder.reports.keySet

    (n1 must beEqualTo(Map())) and
    (n2 must beEqualTo(finder.reports.filter(x => okId.contains(x._1)))) and
    // second time, only expired are invalidate (8) ; excepted UnexpectedVersion (1) and
    // non ExpiringStatus (NoExpectedConfig => 6
    (finder.updated.size must beEqualTo(9 + 6))
  }

  "Cache should not return ask for renew of up to date components" >> {
    val (repo, finder, computer) = newServices
    finder.reports = nodes.map { case (n, _, b) => (n._1, b) }.toMap

    // node not in cache, empty, returns nothing
    val n1 = repo.getNodeStatusReports(finder.reports.keySet).runNow
    computer.invalidateWithAction(finder.reports.keySet.toList.map(n => (n, ExpiredCompliance(n)))).runNow

    // let a chance for zio to exec
    Thread.sleep(1000)

    // now node was ask, it will return only non expired reports (ie only NoReport and such here)
    val n2 = repo.getNodeStatusReports(finder.reports.keySet).runNow
    // check for outdated compliance
    computer.outDatedCompliance().runNow

    // let a chance for zio to exec again to find back expired
    Thread.sleep(1000)

    (n1 must beEqualTo(Map())) and
    (n2 must beEqualTo(finder.reports)) and
    (finder.updated.size must beEqualTo(9)) // second time, only expired are invalidate: none here
  }
}
