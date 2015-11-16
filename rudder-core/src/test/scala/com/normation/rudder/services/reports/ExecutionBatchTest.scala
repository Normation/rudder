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

package com.normation.rudder.services.reports

import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.DirectiveId
import org.joda.time.DateTime
import com.normation.rudder.domain.reports.DirectiveExpectedReports
import com.normation.rudder.domain.reports._
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.FullCompliance
import com.normation.rudder.repository.NodeConfigIdInfo
import com.normation.rudder.reports.ChangesOnly
import com.normation.rudder.domain.policies.SerialedRuleId
import com.normation.rudder.domain.policies.SerialedRuleId
import com.normation.rudder.domain.policies.SerialedRuleId
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.reports.ReportsDisabled

@RunWith(classOf[JUnitRunner])
class ExecutionBatchTest extends Specification {
  private implicit def str2directiveId(s:String) = DirectiveId(s)
  private implicit def str2ruleId(s:String) = RuleId(s)
  private implicit def str2nodeId(s:String) = NodeId(s)
  private implicit def str2nodeConfigIds(ss:Seq[String]) = ss.map(s =>  (NodeId(s), Some(NodeConfigId("version_" + s)))).toMap

  def buildExpected(
      nodeIds: Seq[String]
    , ruleId : String
    , serial : Int
    , directives: Seq[DirectiveExpectedReports]
  ): Map[NodeId, Map[NodeConfigId, Map[SerialedRuleId, RuleNodeExpectedReports]]] = {
    val rid = SerialedRuleId(RuleId(ruleId), serial)
    val exp = Map(rid -> RuleNodeExpectedReports(rid.ruleId, rid.serial, directives))
    nodeIds.map { id =>
      (NodeId(id) -> Map(NodeConfigId("version_" + id) -> exp))
    }.toMap
  }

  def getNodeStatusReportsByRule(
      ruleExpectedReports   : Map[NodeId, Map[NodeConfigId, Map[SerialedRuleId, RuleNodeExpectedReports]]]
    , reportsParam          : Seq[Reports]
    // this is the agent execution interval, in minutes
    , complianceMode        : ComplianceMode
  ): Seq[RuleNodeStatusReport] = {

    val res = (for {
      (nodeId, expected) <- ruleExpectedReports.toSeq
    } yield {
      val runTime = reportsParam.headOption.map( _.executionTimestamp).getOrElse(DateTime.now)
      val info = NodeConfigIdInfo(expected.keySet.head, DateTime.now.minusDays(1), None)
      val runInfo = complianceMode.mode match {
        case FullCompliance => ComputeCompliance(runTime, info, info, runTime.plusMinutes(5), MissingReportType)
        case ChangesOnly => ComputeCompliance(runTime, info, info, runTime.plusMinutes(5), SuccessReportType)
        case ReportsDisabled => ComputeCompliance(runTime, info, info, runTime.plusMinutes(5), DisabledReportType)
      }

      ExecutionBatch.getNodeStatusReports(nodeId, runInfo, expected, reportsParam)
    }).flatten

    res
  }

  val getNodeStatusByRule = (getNodeStatusReportsByRule _).tupled

   //Test the component part
  "A component, with two different keys" should {
    val executionTimestamp = new DateTime()
    val reports = Seq[Reports](
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "foo", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "bar", executionTimestamp, "message")
    )

    val badReports = Seq[Reports](
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "foo", executionTimestamp, "message"),
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "foo", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "bar", executionTimestamp, "message")
    )

    val expectedComponent = new ComponentExpectedReport(
        "component"
      , 2
      , Seq("foo", "bar")
      , Seq("foo", "bar")
    )

    val withGood = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, reports, NoAnswerReportType)
    val withBad  = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, badReports, NoAnswerReportType)

    "return a component globally repaired " in {
      withGood.compliance === ComplianceLevel(success = 1, repaired = 1)
    }
    "return a component with two key values " in {
      withGood.componentValues.size === 2
    }
    "return a component with the key values foo which is repaired " in {
      withGood.componentValues("foo").messages.size === 1 and
      withGood.componentValues("foo").messages.head.reportType ===  RepairedReportType
    }
    "return a component with the key values bar which is a success " in {
      withGood.componentValues("bar").messages.size === 1 and
      withGood.componentValues("bar").messages.head.reportType ===  SuccessReportType
    }

    "only some missing reports mark them as missing, not unexpected" in {
      withBad.compliance === ComplianceLevel(success = 1, unexpected = 2)
    }
    "with bad reports return a component with two key values " in {
      withBad.componentValues.size === 2
    }
    "with bad reports return a component with the key values foo which is unknwon " in {
      withBad.componentValues("foo").messages.size === 2 and
      withBad.componentValues("foo").messages.head.reportType ===  UnexpectedReportType
    }
    "with bad reports return a component with the key values bar which is a success " in {
      withBad.componentValues("bar").messages.size === 1 and
      withBad.componentValues("bar").messages.head.reportType ===  SuccessReportType
    }
  }

  // Test the component part
  "A component, with a None keys" should {
    val executionTimestamp = new DateTime()
    val reports = Seq[Reports](
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "None", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "None", executionTimestamp, "message")
    )

    val badReports = Seq[Reports](
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "None", executionTimestamp, "message"),
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "None", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "None", executionTimestamp, "message")
    )

    val expectedComponent = new ComponentExpectedReport(
        "component"
      , 2
      , Seq("None", "None")
      , Seq("None", "None")
    )
    val withGood = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, reports, NoAnswerReportType)
    val withBad  = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, badReports, NoAnswerReportType)

    "return a component globally repaired " in {
      withGood.compliance === ComplianceLevel(success = 1, repaired = 1)
    }
    "return a component with one key value " in {
      withGood.componentValues.size === 1
    }
    "return a component with both None key repaired " in {
      withGood.componentValues("None").messages.size === 2 and
      withGood.componentValues("None").compliance === ComplianceLevel(success = 1, repaired = 1)
    }

    "with bad reports return a component globally unexpected " in {
      withBad.compliance === ComplianceLevel(unexpected = 3)
    }
    "with bad reports return a component with one key values (only None)" in {
      withBad.componentValues.size === 1
    }
    "with bad reports return a component with None key unexpected " in {
      withBad.componentValues("None").messages.size === 3 and
      withBad.componentValues("None").messages.forall(x => x.reportType === UnexpectedReportType)
    }
  }

  // Test the component part
  "A component, with a cfengine keys" should {
    val executionTimestamp = new DateTime()
    val reports = Seq[Reports](
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "/var/cfengine", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "/var/cfengine", executionTimestamp, "message")
    )

    val badReports = Seq[Reports](
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "/var/cfengine", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "/var/cfengine", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "/var/cfengine", executionTimestamp, "message")
    )

    val expectedComponent = new ComponentExpectedReport("component", 2
      , Seq("${sys.bla}", "${sys.foo}")
      , Seq("${sys.bla}", "${sys.foo}")
    )

    val withGood = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, reports, NoAnswerReportType)
    val withBad   = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, badReports, NoAnswerReportType)

    "return a component globally repaired " in {
      withGood.compliance === ComplianceLevel(success = 1, repaired = 1)
    }
    "return a component with two key values " in {
      withGood.componentValues.size === 2
    }
    "return a component with both cfengine keys repaired " in {
      withGood.componentValues("${sys.bla}").messages.size === 1
    }
  }

   // Test the component part
  "A component, with generation-time known keys" should {
    val executionTimestamp = new DateTime()
    val reports = Seq[Reports](
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "bar", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "node2", executionTimestamp, "message"),
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "node1", executionTimestamp, "message")
    )

    val badReports = Seq[Reports](
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "node1", executionTimestamp, "message"),
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "node1", executionTimestamp, "message"),
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "node2", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "bar", executionTimestamp, "message")
    )

    /*
     * Here, we must be able to decide between node1 and node2 value for the repair, because we know at generation time
     * what is expected.
     */
    val expectedComponent = new ComponentExpectedReport("component", 2
      , Seq("node1", "node2", "bar")
      , Seq("${rudder.node.hostname}", "${rudder.node.hostname}", "bar")
    )

    val withGood = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, reports, NoAnswerReportType)
    val withBad  = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, badReports, NoAnswerReportType)

    "return a component with the correct number of success and repaired" in {
      withGood.compliance === ComplianceLevel(success = 2, repaired = 1)
    }

    "return a component with two key values " in {
      withGood.componentValues.size === 2
    }

    "return an unexpanded component key with both a success and a repaired" in {
      val messages = withGood.componentValues("${rudder.node.hostname}").messages.map(_.reportType)
      messages.size === 2 and
      (messages must contain(RepairedReportType)) and
      (messages must contain(SuccessReportType))
    }

    "return a component with the bar key success " in {
      withGood.componentValues("bar").messages.size === 1 and
      withGood.componentValues("bar").messages.forall(x => x.reportType === SuccessReportType)
    }

    "with some bad reports mark them as unexpected (because the check is not done in checkExpectedComponentWithReports" in {
      withBad.compliance ===  ComplianceLevel(success = 1, unexpected = 3)
    }
    "with bad reports return a component with two key values " in {
      withBad.componentValues.size === 2
    }
    "with bad reports return a component with bar as a success " in {
      withBad.componentValues("bar").messages.size === 1 and
      withBad.componentValues("bar").messages.forall(x => x.reportType === SuccessReportType)
    }
    "with bad reports return a component with the cfengine key as unexpected " in {
      withBad.componentValues("${rudder.node.hostname}").messages.size === 3 and
      withBad.componentValues("${rudder.node.hostname}").messages.forall(x => x.reportType === UnexpectedReportType)
    }
  }

  // Test the component part
  "A component, with distinguishable keys" should {
    val executionTimestamp = new DateTime()
    val reports = Seq[Reports](
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "/var/cfengine", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "bar", executionTimestamp, "message")
    )

    val badReports = Seq[Reports](
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "/var/cfengine", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "/var/cfengine", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "bar", executionTimestamp, "message")
    )

    val expectedComponent = new ComponentExpectedReport("component", 2
      , Seq("${sys.bla}", "bar")
      , Seq("${sys.bla}", "bar")
    )

    val withGood = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, reports, NoAnswerReportType)
    val withBad  = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, badReports, NoAnswerReportType)

    "return a component globally repaired " in {
      withGood.compliance === ComplianceLevel(success = 1, repaired = 1)
    }
    "return a component with two key values " in {
      withGood.componentValues.size === 2
    }
    "return a component with the cfengine keys repaired " in {
      withGood.componentValues("${sys.bla}").messages.size === 1 and
      withGood.componentValues("${sys.bla}").messages.forall(x => x.reportType === RepairedReportType)
    }
    "return a component with the bar key success " in {
      withGood.componentValues("bar").messages.size === 1 and
      withGood.componentValues("bar").messages.forall(x => x.reportType === SuccessReportType)
    }
    "with some bad reports mark only one as unexpected (because the check is not done in checkExpectedComponentWithReports" in {
      //that's because we are filtering out cfengine values step by step and we don't have any mean, for now,
      //to know what other values should be marked as unexpected to.
      withBad.compliance ===  ComplianceLevel(success = 1, repaired = 1, unexpected = 1)
    }
    "with bad reports return a component with three key values (one unexpected)" in {
      withBad.componentValues.size === 3
    }

    /*
     * For the next three, the logic is that:
     * - we successfully matched the constant value;
     * - we successfully matched the cfengine value (taking at random the report type between
     *   success and repaired - but we have no way to decide ! Ok, and the random is more like
     *   "the first in the list", so it helps for the test)
     * - have one unexpected, for the last message - and we don't have any way to decide if it
     *   comes from a cfengine var or not !
     */
    "with bad reports return a component with bar as a success " in {
      withBad.componentValues("bar").messages.size === 1 and
      withBad.componentValues("bar").messages.forall(x => x.reportType === SuccessReportType)
    }
    "with bad reports return a component with the cfengine key as reparaired" in {
      withBad.componentValues("${sys.bla}").messages.size === 1 and
      withBad.componentValues("${sys.bla}").messages.forall(x => x.reportType === RepairedReportType)
    }
    "with bad reports return a component with an unknow key as unknown " in {
      withBad.componentValues("/var/cfengine").messages.size === 1 and
      withBad.componentValues("/var/cfengine").messages.forall(x => x.reportType === UnexpectedReportType)
    }
  }

  val fullCompliance = GlobalComplianceMode(FullCompliance, 1)
  "A detailed execution Batch, with one component, cardinality one, one node" should {

    val param = (
        buildExpected(
            Seq("one")
          , "rule"
          , 12
          , Seq(DirectiveExpectedReports("policy"
                , Seq(new ComponentExpectedReport("component", 1, Seq("value"), Seq() )) //here, we automatically must have "value" infered as unexpanded var
              )
            )
        )
      , Seq[Reports](new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", 12, "component", "value", DateTime.now(), "message"))
      , fullCompliance
    )

    val nodeStatus = (getNodeStatusReportsByRule _).tupled(param)

    "have one detailed reports when we create it with one report" in {
      nodeStatus.size ==1
    }

    "have one detailed success node when we create it with one success report" in {
      nodeStatus.head.nodeId === str2nodeId("one")
    }

    "have one detailed rule success directive when we create it with one success report" in {
      nodeStatus.head.directives.head._1 === DirectiveId("policy")
    }

    "have no detailed rule non-success directive when we create it with one success report" in {
      AggregatedStatusReport(nodeStatus.toSet).compliance === ComplianceLevel(success = 1)
    }
  }

  "A detailed execution Batch, with one component, cardinality one, wrong node" should {
    val param = (
        buildExpected(
            Seq("one")
          , "rule"
          , 12
          , Seq(DirectiveExpectedReports("policy"
                , Seq(new ComponentExpectedReport("component", 1, Seq("value"), Seq() ))
              )
            )
        )
      , Seq[Reports](new ResultSuccessReport(DateTime.now(), "rule", "policy", "two", 12, "component", "value",DateTime.now(), "message"))
      , fullCompliance
    )

    val nodeStatus = getNodeStatusByRule(param)

    "have one detailed reports when we create it with one report" in {
      nodeStatus.size === 1
    }

    "have a pending node when we create it with one wrong success report right now" in {
      AggregatedStatusReport(nodeStatus.toSet).compliance === ComplianceLevel(missing = 1) and
      nodeStatus.head.nodeId === str2nodeId("one")
    }
  }

  "A detailed execution Batch, with one component, cardinality one, one node" should {

    val param = (
        buildExpected(
            Seq("one")
          , "rule"
          , 12
          , Seq(DirectiveExpectedReports("policy"
                , Seq(new ComponentExpectedReport("component", 1, Seq("value"), Seq() ))
              )
            )
         )
       , Seq[Reports](
             new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", 12, "component", "value",DateTime.now(), "message")
           , new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", 12, "component", "value",DateTime.now(), "message")
         )
      , fullCompliance
    )
    val nodeStatus = getNodeStatusByRule(param)

    "have one detailed reports when we create it" in {
      nodeStatus.size ==1
    }

    "have one unexpected node when we create it with one success report" in {
      AggregatedStatusReport(nodeStatus.toSet).compliance === ComplianceLevel(unexpected = 2) and
      nodeStatus.head.nodeId === str2nodeId("one")
    }
  }

   "A detailed execution Batch, with one component, cardinality one, two nodes, including one not responding" should {

    val param = (
        buildExpected(
            Seq("one", "two")
          , "rule"
          , 12
          , Seq(DirectiveExpectedReports("policy"
                , Seq(new ComponentExpectedReport("component", 1, Seq("value"), Seq() ))
              )
            )
        )
      , Seq[Reports](new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", 12, "component", "value",DateTime.now(), "message"))
      , fullCompliance
    )
    val nodeStatus = getNodeStatusByRule(param)

    "have two detailed reports when we create it" in {
      nodeStatus.size == 2
    }

    "have one success, and one pending node, in the component detail of the rule" in {
      AggregatedStatusReport(nodeStatus.toSet).compliance === ComplianceLevel(success = 1, missing = 1)
    }
  }

  "A detailed execution Batch, with one component, cardinality one, three nodes, including one not responding" should {
    val param = (
        buildExpected(
            Seq("one", "two", "three")
          , "rule"
          , 12
          , Seq(DirectiveExpectedReports("policy"
                , Seq(new ComponentExpectedReport("component", 1, Seq("value"), Seq() ))
              )
            )
         )
       , Seq[Reports](
             new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", 12, "component", "value", DateTime.now(), "message")
           , new ResultSuccessReport(DateTime.now(), "rule", "policy", "two", 12, "component", "value", DateTime.now(), "message")
         )
      , fullCompliance
    )
    val nodeStatus = getNodeStatusByRule(param)

    "have three node rule report" in {
      nodeStatus.size === 3
    }
    "have one detailed rule report with a 67% compliance" in {
      AggregatedStatusReport(nodeStatus.toSet).compliance === ComplianceLevel(success = 2, missing = 1)
    }
  }

  "A detailed execution Batch, with two directive, two component, cardinality one, three nodes, including one partly responding and one not responding" should {
    val param = (
        buildExpected(
            Seq("one", "two", "three")
          , "rule"
          , 12
          , Seq(DirectiveExpectedReports("policy", Seq(
                     new ComponentExpectedReport("component", 1, Seq("value"), Seq() )
                   , new ComponentExpectedReport("component2", 1, Seq("value"), Seq() )
                 ))
               , DirectiveExpectedReports("policy2", Seq(
                     new ComponentExpectedReport("component", 1, Seq("value"), Seq() )
                   , new ComponentExpectedReport("component2", 1, Seq("value"), Seq() )
                 ))
            )
        )
      , Seq[Reports](
          new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", 12, "component", "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", 12, "component2", "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy2", "one", 12, "component", "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy2", "one", 12, "component2", "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy", "two", 12, "component", "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy", "two", 12, "component2", "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy2", "two", 12, "component", "value",DateTime.now(), "message")
        )
      , fullCompliance
    )
    val nodeStatus = getNodeStatusByRule(param)
    val aggregated = AggregatedStatusReport(nodeStatus.toSet)

    "have two detailed node rule report" in {
      nodeStatus.size === 3
    }
    "have detailed rule report for policy of 67% (node 1 and 2), pending for node 3" in {
      aggregated.directives("policy").compliance === ComplianceLevel(success = 4, missing = 2)
    }
    "have detailed rule report for policy2 of 33% (node1), missing (node2 and 3)" in {
      aggregated.directives("policy2").compliance === ComplianceLevel(success = 3, missing = 3)
    }
  }

  "A detailed execution Batch, with two directive, two component, cardinality three, three nodes, including two not responding" should {
    val param = (
        buildExpected(
            Seq("one", "two", "three")
          , "rule"
          , 12
          , Seq(DirectiveExpectedReports("policy", Seq(
                     new ComponentExpectedReport("component", 1, Seq("value"), Seq() )
                   , new ComponentExpectedReport("component2", 1, Seq("value"), Seq() )
                 ))
               , DirectiveExpectedReports("policy2",Seq(
                     new ComponentExpectedReport("component", 1, Seq("value"), Seq() )
                   , new ComponentExpectedReport("component2", 1, Seq("value"), Seq() )
                 ))
             )
        )
      , Seq[Reports](
          new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", 12, "component", "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", 12, "component2", "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy2", "one", 12, "component", "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy2", "one", 12, "component2", "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy", "two", 12, "component", "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy", "two", 12, "component2", "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy2", "two", 12, "component", "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy", "three", 12, "component", "value",DateTime.now(), "message")
        )
      , fullCompliance
    )
    val nodeStatus = getNodeStatusByRule(param)
    val aggregated = AggregatedStatusReport(nodeStatus.toSet)

    "have 3 detailed node rule report" in {
      nodeStatus.size === 3
    }
    "have detailed rule report for policy of 83%" in {
      aggregated.directives("policy").compliance === ComplianceLevel(success = 5, missing = 1)
    }

    "have detailed rule report for policy2 of 33%" in {
      aggregated.directives("policy2").compliance === ComplianceLevel(success = 3, missing = 3)
    }
    "have detailed rule report for policy-component of 100%" in {
      aggregated.directives("policy").components("component").compliance === ComplianceLevel(success = 3)
    }
    "have detailed rule report for policy-component2 of 67%" in {
      aggregated.directives("policy").components("component2").compliance === ComplianceLevel(success = 2, missing = 1)
    }
    "have detailed rule report for policy2-component2 of 33%" in {
      aggregated.directives("policy2").components("component2").compliance === ComplianceLevel(success = 1, missing = 2)
    }
  }

  "A detailed execution Batch, with two directive, two component, cardinality three, three nodes, including two not completely responding" should {
    val param = (
        buildExpected(
            Seq("one", "two", "three")
          , "rule"
          , 12
          , Seq(DirectiveExpectedReports("policy", Seq(
                   new ComponentExpectedReport("component", 1, Seq("value", "value2", "value3"), Seq() )
               ))
             )
        )
      , Seq[Reports](
          new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", 12, "component", "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", 12, "component", "value2",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", 12, "component", "value3",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy", "two", 12, "component", "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy", "two", 12, "component", "value2",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy", "three", 12, "component", "value",DateTime.now(), "message")
        )
      , fullCompliance
    )
    val nodeStatus = getNodeStatusByRule(param)
    val aggregated = AggregatedStatusReport(nodeStatus.toSet)

    "have 3 detailed node rule report" in {
      nodeStatus.size === 3
    }
    "have detailed rule report for policy of 67%" in {
      aggregated.directives("policy").compliance === ComplianceLevel(success = 6, missing = 3)
    }
    "have detailed rule report for policy/component/value of 100%" in {
      aggregated.directives("policy").components("component").componentValues("value").compliance ===
        ComplianceLevel(success = 3)
    }
    "have detailed rule report for policy/component/value2 of 67%" in {
      aggregated.directives("policy").components("component").componentValues("value2").compliance ===
        ComplianceLevel(success = 2, missing = 1)
    }
    "have detailed rule report for policy/component/value3 of 33%" in {
      aggregated.directives("policy").components("component").componentValues("value3").compliance ===
        ComplianceLevel(success = 1, missing = 2)
    }
  }

  "An execution Batch, with one component with a quote in its value, cardinality one, one node" should {

    val param = (
        buildExpected(
            Seq("one")
          , "rule"
          , 12
          , Seq(DirectiveExpectedReports("policy", Seq(
                  new ComponentExpectedReport("component", 1, Seq("""some\"text"""), Seq("""some\text""") )
              ))
            )
        )
      , Seq[Reports](new ResultSuccessReport(new DateTime(), "rule", "policy", "one", 12, "component", """some\"text""",new DateTime(), "message"))
      , fullCompliance
    )
    val nodeStatus = getNodeStatusByRule(param)

    "have one detailed reports when we create it with one report" in {
      nodeStatus.size ===1
    }

    "have one detailed success node when we create it with one success report" in {
      nodeStatus.head.nodeId === str2nodeId("one") and
      nodeStatus.head.compliance.pc_success === 100
    }

  }

 "An execution Batch, with one component, one node, but with a component value being a cfengine variable with {, and a an escaped quote as well" should {

    val param = (
        buildExpected(
            Seq("nodeId")
          , "rule"
          , 12
          , Seq(DirectiveExpectedReports("policy", Seq(
                  new ComponentExpectedReport("component", 1, Seq("""${sys.workdir}/inputs/\"test"""), Seq() )
              ))
            )
        )
      , Seq[Reports](new ResultSuccessReport(new DateTime(), "rule", "policy", "nodeId", 12, "component", """/var/cfengine/inputs/\"test""", new DateTime(), "message"))
      , fullCompliance
    )

    val nodeStatus = getNodeStatusByRule(param)

    "have one detailed reports when we create it with one report" in {
     nodeStatus.size ===1
    }

    "have one detailed success node when we create it with one success report" in {
     nodeStatus.head.nodeId === str2nodeId("nodeId") and
     nodeStatus.head.compliance.pc_success === 100
    }
  }

  "An execution Batch, with one component, one node, but with a component value being a cfengine variable with {, and a quote as well" should {
    val param = (
        buildExpected(
            Seq("nodeId")
          , "rule"
          , 12
          , Seq(DirectiveExpectedReports("policy", Seq(
                new ComponentExpectedReport("component", 1, Seq("""${sys.workdir}/inputs/"test"""), Seq("""${sys.workdir}/inputs/"test""") )
              ))
            )
        )
      , Seq[Reports](new ResultSuccessReport(new DateTime(), "rule", "policy", "nodeId", 12, "component", """/var/cfengine/inputs/"test""", new DateTime(), "message"))
      , fullCompliance
    )
    val nodeStatus = getNodeStatusByRule(param)

    "have one detailed reports when we create it with one report" in {
     nodeStatus.size === 1
    }

    "have one detailed success node when we create it with one success report" in {
     nodeStatus.head.nodeId === str2nodeId("nodeId") and
     nodeStatus.head.compliance.pc_success === 100
    }
  }

   // Test the component part - with NotApplicable
  "A component, with two keys and NotApplicable reports" should {
    val executionTimestamp = new DateTime()
    val reports = Seq[Reports](
        new ResultNotApplicableReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "/var/cfengine", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "bar", executionTimestamp, "message")
              )

    val expectedComponent = new ComponentExpectedReport(
        "component"
      , 2
      , Seq("/var/cfengine", "bar")
      , Seq("/var/cfengine", "bar")
    )

    val withGood = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, reports, NoAnswerReportType)

    "return a component globally success " in {
      withGood.compliance === ComplianceLevel(success = 1, notApplicable = 1)
    }
    "return a component with two key values " in {
      withGood.componentValues.size === 2
    }
    "return a component with the /var/cfengine in NotApplicable " in {
      withGood.componentValues("/var/cfengine").messages.size === 1 and
      withGood.componentValues("/var/cfengine").compliance.pc_notApplicable === 100
    }
    "return a component with the bar key success " in {
      withGood.componentValues("bar").messages.size == 1 and
      withGood.componentValues("bar").compliance.pc_success === 100
    }
  }

}
