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

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.queries.CriterionComposition
import com.normation.rudder.domain.queries.NodeInfoMatcher
import com.normation.rudder.domain.reports.ComplianceLevel
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.domain.reports.NodeExpectedReports
import com.normation.rudder.domain.reports.NodeStatusReport
import com.normation.rudder.domain.reports.RuleStatusReport
import com.normation.rudder.domain.reports.RunComplianceInfo
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.reports.execution.RoReportsExecutionRepository
import com.normation.rudder.repository.FindExpectedReportRepository
import com.normation.rudder.repository.ReportsRepository
import com.normation.rudder.services.nodes.LDAPNodeInfo
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.policies.NodeConfigData
import net.liftweb.common.Box
import net.liftweb.common.Full
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

/*
 * Test the cache behaviour
 */
@RunWith(classOf[JUnitRunner])
class CachedFindRuleNodeStatusReportsTest extends Specification {

  val expired = DateTime.now.minusMinutes(5)
  val stillOk = DateTime.now.plusMinutes(5)

  // build one node by kind of reports, expired or not
  def buildNode(id: String) = {
    val node1 = NodeConfigData.node1.node
    NodeConfigData.node1.copy(node = node1.copy(id = NodeId(id)))
  }
  def run(id: String, info: RunAndConfigInfo) = NodeStatusReport(NodeId(id), info, RunComplianceInfo.OK, Nil, Nil)
  // a list of node, on node by type of reports, in a triplet:
  // (node, expired report, still ok report)
  def expected(id: String) = NodeExpectedReports(NodeId(id), NodeConfigId(id), null, null, null, Nil, Nil)

  val nodes = List(
    (
      buildNode("n0")
    , run("n0", NoRunNoExpectedReport)
    , run("n0", NoRunNoExpectedReport)
    )
  , (
      buildNode("n1")
    , run("n1", NoExpectedReport(expired, None))
    , run("n1", NoExpectedReport(stillOk, None))
    )
  , (
      buildNode("n2")
    , run("n2", NoReportInInterval(null))
    , run("n2", NoReportInInterval(null))
    )
  , (
      buildNode("n3")
    , run("n3", ReportsDisabledInInterval(null))
    , run("n3", ReportsDisabledInInterval(null))
    )
  , (
      buildNode("n4")
    , run("n4", Pending(null, None, expired))
    , run("n4", Pending(null, None, stillOk))
    )
  , (
      buildNode("n5")
    , run("n5", UnexpectedVersion(null, Some(expected("n5")), expired, null, expired))
    , run("n5", UnexpectedVersion(null, Some(expected("n5")), stillOk, null, stillOk))
    )
  , (
      buildNode("n6")
    , run("n6", UnexpectedNoVersion(null, NodeConfigId("x"), expired, null, expired))
    , run("n6", UnexpectedNoVersion(null, NodeConfigId("x"), stillOk, null, stillOk))
    )
  , (
      buildNode("n7")
    , run("n7", UnexpectedUnknowVersion(null, NodeConfigId("x"), null, expired))
    , run("n7", UnexpectedUnknowVersion(null, NodeConfigId("x"), null, stillOk))
    )
  , (
      buildNode("n8")
    , run("n8", ComputeCompliance(null, expected("n5"), expired))
    , run("n8", ComputeCompliance(null, expected("n5"), stillOk))
    )
  )

  object testNodeInfoService extends NodeInfoService {
    def getLDAPNodeInfo(nodeIds: Set[NodeId], predicates: Seq[NodeInfoMatcher], composition: CriterionComposition) : Box[Set[LDAPNodeInfo]] = ???
    def getNodeInfo(nodeId: NodeId) : Box[Option[NodeInfo]] = ???
    def getNode(nodeId: NodeId): Box[Node] = ???
    def getAllNodes() : Box[Map[NodeId, Node]] = ???
    def getAllSystemNodeIds() : Box[Seq[NodeId]] = ???
    def getPendingNodeInfos(): Box[Map[NodeId, NodeInfo]] = ???
    def getPendingNodeInfo(nodeId: NodeId): Box[Option[NodeInfo]] = ???
    def getDeletedNodeInfos(): Box[Map[NodeId, NodeInfo]] = ???
    def getDeletedNodeInfo(nodeId: NodeId): Box[Option[NodeInfo]] = ???
    def getNumberOfManagedNodes: Int = ???
    val getAll : Box[Map[NodeId, NodeInfo]] = {
      Full(nodes.map { case (n, _, _) => (n.id, n) }.toMap)
    }
  }

  class TestCache extends CachedFindRuleNodeStatusReports() {
    val batchSize = 3
    // what the backend will give to the cache
    var reports = Map[NodeId, NodeStatusReport]()
    // store all updated nodes
    var updated: List[NodeId] = Nil

    override def defaultFindRuleNodeStatusReports = new DefaultFindRuleNodeStatusReports() {
      override def confExpectedRepo: FindExpectedReportRepository = ???
      override def reportsRepository: ReportsRepository = ???
      override def agentRunRepository: RoReportsExecutionRepository = ???
      override def getGlobalComplianceMode: () => Box[GlobalComplianceMode] = ???
      override def getUnexpectedInterpretation: () => Box[UnexpectedReportInterpretation] = ???
      override def findDirectiveRuleStatusReportsByRule(ruleId: RuleId): Box[RuleStatusReport] = ???
      override def getUserNodeStatusReports(): Box[Map[NodeId, NodeStatusReport]] = ???
      override def computeComplianceFromReports(reports: Map[NodeId, NodeStatusReport]): Option[(ComplianceLevel, Long)] = ???
      override def getGlobalUserCompliance(): Box[Option[(ComplianceLevel, Long)]] = ???
      override def findNodeStatusReport(nodeId: NodeId): Box[NodeStatusReport] = ???

      override def jdbcMaxBatchSize: Int = batchSize
      override def findRuleNodeStatusReports(nodeIds: Set[NodeId], ruleIds: Set[RuleId]): Box[Map[NodeId, NodeStatusReport]] = {
        updated = (updated ++ nodeIds)
        Full(reports.filter(x => nodeIds.contains(x._1)))
      }
    }
    override def nodeInfoService: NodeInfoService = testNodeInfoService
    override def findDirectiveRuleStatusReportsByRule(ruleId: RuleId): Box[RuleStatusReport] = ???
    override def findNodeStatusReport(nodeId: NodeId): Box[NodeStatusReport] = ???
    override def getUserNodeStatusReports(): Box[Map[NodeId, NodeStatusReport]] = ???
    override def computeComplianceFromReports(reports: Map[NodeId, NodeStatusReport]): Option[(ComplianceLevel, Long)] = ???
    override def getGlobalUserCompliance(): Box[Option[(ComplianceLevel, Long)]] = ???
  }

  /*
   * rule1/dir1 is applied on node1 and node2 and is both here (node1) and skipped (node2)
   */
  "Uninitialized cache should ask for node compliance if it's not in cache already" >> {
    val cache = new TestCache
    val id = NodeId("n1")
    cache.reports = nodes.collect { case (n, a, _) if(n.id == id) => (n.id, a) }.toMap

    // node not in cache, miss
    val n1 = cache.findRuleNodeStatusReports(Set(id), Set())

    // let a chance for zio to exec
    Thread.sleep(1000)
    // now node was ask, it will be returned
    val n2 = cache.findRuleNodeStatusReports(Set(id), Set())

    (n1 must beEqualTo(Full(Map()) ) ) and
    (n2 must beEqualTo(Full(cache.reports))) and
    (cache.updated must beEqualTo(List(id)))
  }

  "Cache should not return expired compliance ask for renew" >> {
    val cache = new TestCache
    cache.reports = nodes.map { case (n, a, _) => (n.id, a) }.toMap

    // node not in cache, empty, returns nothing
    val n1 = cache.findRuleNodeStatusReports(cache.reports.keySet, Set())

    // let a chance for zio to exec
    Thread.sleep(1000)

    // now node was ask, it will return only non expired reports (ie only NoReport and such here)
    val n2 = cache.findRuleNodeStatusReports(cache.reports.keySet, Set())

    // let a chance for zio to exec again to find back expired
    Thread.sleep(1000)

    // these one are not expired (but false)
    val okId = Set("n0", "n1", "n2", "n3").map(NodeId(_))

    (n1 must beEqualTo(Full(Map()) )) and
    (n2 must beEqualTo(Full(cache.reports.filter(x => okId.contains(x._1))))) and
    (cache.updated.size must beEqualTo(9 + 5)) //second time, only expired are invalidate
  }

  "Cache should not return ask for renew of up tu date components" >> {
    val cache = new TestCache
    cache.reports = nodes.map { case (n, _, b) => (n.id, b) }.toMap

    // node not in cache, empty, returns nothing
    val n1 = cache.findRuleNodeStatusReports(cache.reports.keySet, Set())

    // let a chance for zio to exec
    Thread.sleep(1000)

    // now node was ask, it will return only non expired reports (ie only NoReport and such here)
    val n2 = cache.findRuleNodeStatusReports(cache.reports.keySet, Set())

    // let a chance for zio to exec again to find back expired
    Thread.sleep(1000)

    (n1 must beEqualTo(Full(Map()) )) and
    (n2 must beEqualTo(Full(cache.reports))) and
    (cache.updated.size must beEqualTo(9)) //second time, only expired are invalidate: none here
  }
}
