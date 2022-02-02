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

package com.normation.rudder.services.reports

import com.normation.cfclerk.domain.ReportingLogic
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyModeOverrides
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.reports._
import com.normation.rudder.domain.reports.DirectiveExpectedReports
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.FullCompliance
import com.normation.rudder.reports.GlobalComplianceMode

import org.joda.time.DateTime
import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.services.reports.ExecutionBatch.ComputeComplianceTimer
import com.normation.rudder.services.reports.ExecutionBatch.MergeInfo
import com.normation.rudder.services.reports.UnexpectedReportBehavior.UnboundVarValues



@RunWith(classOf[JUnitRunner])
class ExecutionBatchTest extends Specification {
  private implicit def str2directiveId(s:String) = DirectiveId(DirectiveUid(s))
  private implicit def str2ruleId(s:String) = RuleId(RuleUid(s))
  private implicit def str2nodeId(s:String) = NodeId(s)
  private implicit def str2ruleUid(s:String) = RuleUid(s)

  // a logger for timing information
  val logger = org.slf4j.LoggerFactory.getLogger("timing-test").asInstanceOf[ch.qos.logback.classic.Logger]
  // set to trace to see timing
  logger.setLevel(ch.qos.logback.classic.Level.OFF)
  // also disable executionbatch since we are testing error cases:
  org.slf4j.LoggerFactory.getLogger("com.normation.rudder.services.reports.ExecutionBatch").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.OFF)

  import ReportType._

  val strictUnexpectedInterpretation = UnexpectedReportInterpretation(Set())
  val executionTimestamp = new DateTime()

  val globalPolicyMode = GlobalPolicyMode(PolicyMode.Enforce, PolicyModeOverrides.Always)
  val mode = NodeModeConfig(GlobalComplianceMode(FullCompliance, 30), None, AgentRunInterval(None, 5, 14, 5, 4), None, globalPolicyMode, Some(PolicyMode.Enforce))

  /**
   * Construct all the data for mergeCompareByRule, to have pleinty of data
   */
  def buildDataForMergeCompareByRule(
      nodeId         : String
    , nbRules        : Int
    , nbDirectives   : Int
    , nbReportsPerDir: Int
  ) = {
    val ruleIds = (1 to nbRules).map("rule_id_"+_+nodeId).toSeq
    val directiveIds = (1 to nbDirectives).map("directive_id_"+_+nodeId).toSeq
    val dirPerRule = ruleIds.map(rule => (RuleId(rule), directiveIds.map(dir => DirectiveId(DirectiveUid(dir + "@@" + rule)))))


    val treeOfData = dirPerRule map { case (ruleId, directives) =>
      (ruleId, directives.map(dirId => (dirId, (1 to nbReportsPerDir).map("component"+dirId.serialize+_).toSeq)))
    }

    // create RuleExpectedReports
    val expectedReports = treeOfData.map { case (ruleId, directives) =>
        RuleExpectedReports(ruleId,
          directives.map { case (directiveId, components) =>
            DirectiveExpectedReports(directiveId, None, false,
              components.map(componentName => ValueExpectedReport(componentName, ExpectedValueMatch(componentName,componentName) :: Nil)).toList
            )
          }.toList
        )
    }
    val now = DateTime.now

    val executionReports = expectedReports.flatMap { case RuleExpectedReports(ruleId, directives) =>
      def mapComponent(c: ComponentExpectedReport, directiveId: DirectiveId): List[ResultSuccessReport] = {
        c match {
          case ValueExpectedReport(componentName, componentsValues) =>
            componentsValues.map { case ExpectedValueId(value,_) =>
              ResultSuccessReport(now, ruleId, directiveId, nodeId, "report_id", componentName, value, now, "empty text")
            case ExpectedValueMatch(value,_)  =>
              ResultSuccessReport(now, ruleId, directiveId, nodeId, "report_id", componentName, value, now, "empty text")
            }
          case BlockExpectedReport(componentName, logic, sub) =>
            sub.map(mapComponent(_, directiveId)).flatten
        }
      }

      directives.flatMap { case DirectiveExpectedReports(directiveId, _, _, components) =>
        components.flatMap(mapComponent(_, directiveId))
      }
    }

    val nodeConfigId = NodeConfigId("version_" + nodeId)

    val nodeExpectedReport = NodeExpectedReports(
         NodeId(nodeId)
      ,  nodeConfigId
      , now
      , None
      , mode
      , expectedReports.toList
      , Nil
    )

    val mergeInfo = MergeInfo(NodeId(nodeId), Some(now), Some(nodeConfigId), now.plus(100))

    (mergeInfo, executionReports, nodeExpectedReport, nodeExpectedReport, strictUnexpectedInterpretation)

  }

  def buildExpected(
      nodeIds: Seq[String]
    , ruleId : String
    , serial : Int
    , directives: List[DirectiveExpectedReports]
  ): Map[NodeId, NodeExpectedReports] = {
    val globalPolicyMode = GlobalPolicyMode(PolicyMode.Audit, PolicyModeOverrides.Always)
    val now = DateTime.now
    val mode = NodeModeConfig(GlobalComplianceMode(FullCompliance, 30), None, AgentRunInterval(None, 5, 14, 5, 4), None, globalPolicyMode, Some(PolicyMode.Enforce))
    nodeIds.map { id =>
      (NodeId(id) -> NodeExpectedReports(NodeId(id), NodeConfigId("version_" + id), now, None, mode
                       , List(RuleExpectedReports(RuleId(ruleId), directives)), Nil
                     )
      )
    }.toMap
  }

  def getNodeStatusReportsByRule(
      nodeExpectedReports   : Map[NodeId, NodeExpectedReports]
    , reportsParam          : Seq[Reports]
    // this is the agent execution interval, in minutes
    , complianceMode        : ComplianceMode
  ): Map[NodeId, NodeStatusReport] = {

    val res = (for {
      (nodeId, expected) <- nodeExpectedReports.toSeq
    } yield {
      val runTime = reportsParam.headOption.map( _.executionTimestamp).getOrElse(DateTime.now)
      val info = nodeExpectedReports(nodeId)
      val runInfo = ComputeCompliance(runTime, info, runTime.plusMinutes(5))

      (nodeId, ExecutionBatch.getNodeStatusReports(nodeId, runInfo, reportsParam, strictUnexpectedInterpretation))
    })

    res.toMap
  }

  val getNodeStatusByRule = (getNodeStatusReportsByRule _).tupled
  val one = NodeId("one")


  /*
   * Test the general run information (do we have a run, is it an expected version, etc)
   */
  // TODO: CORRECT TESTS
//  "A node, an expected version, and a run" should {
//
//    // general configuration option: node id, run period...
//    val root = NodeId("root")
//
//    val insertionId = 102030
//    val isCompleted = true
//
//    val nodeConfigIdInfos = Map(root -> ResolvedAgentRunInterval(Duration.parse("PT300S"),1))
//    val mode = GlobalComplianceMode(FullCompliance, 5)
//
//    val now = DateTime.now()
//
//    // known configuration in Rudder database
//    val startConfig0 = now.minusMinutes(60)
//    val startConfig1 = now.minusMinutes(37)
//    val startConfig2 = now.minusMinutes(16)
//    val configId0    = NodeConfigId("-1000")
//    val configId1    = NodeConfigId( "2000")
//    val configId2    = NodeConfigId("-4000")
//
//    val config0 = NodeConfigIdInfo( configId0, startConfig0, Some(startConfig1) )
//    val config1 = NodeConfigIdInfo( configId1, startConfig1, Some(startConfig2) )
//    val config2 = NodeConfigIdInfo( configId2, startConfig2, None               )
//
//    val knownConfigs = Map(root -> Some(List(config0, config1, config2)))
//
//    "have no report in interval if the run is older than 10 minutes" in {
//      val runs = Map(root -> Some(AgentRun(AgentRunId(root, now.minusMinutes(11)), Some(configId2), isCompleted, insertionId)))
//      ExecutionBatch.computeNodesRunInfo(nodeConfigIdInfos, runs, knownConfigs, mode) === Map(root -> NoReportInInterval(config2))
//    }
//
//    "raise UnexpectedUnknowVersion when the run version is not know" in {
//      val runTime = now.minusMinutes(3)
//      val epoch = new DateTime(0)
//      val runs = Map(root -> Some(AgentRun(AgentRunId(root, runTime), Some(NodeConfigId("123456")), isCompleted, insertionId)))
//      ExecutionBatch.computeNodesRunInfo(nodeConfigIdInfos, runs, knownConfigs, mode) === Map(root ->
//        UnexpectedUnknowVersion(runTime, NodeConfigId("123456"), config2, startConfig2.plusMinutes(10))
//      )
//    }
//  }



   //Test the component part
  "A component, with two different keys" should {
    val reports = Seq[ResultReports](
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "foo", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "bar", executionTimestamp, "message")
    )

    val badReports = Seq[ResultReports](
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "foo", executionTimestamp, "message"),
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "foo", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "bar", executionTimestamp, "message")
    )

    val expectedComponent = new ValueExpectedReport(
        "component"
      , ExpectedValueMatch("foo", "foo") :: ExpectedValueMatch("bar", "bar") :: Nil
    )

    val withGood = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, reports, ReportType.Missing, PolicyMode.Enforce, strictUnexpectedInterpretation).head
    val withBad  = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, badReports, Missing, PolicyMode.Enforce, strictUnexpectedInterpretation).head

    "return a component globally repaired " in {
      withGood.compliance === ComplianceLevel(success = 1, repaired = 1)
    }
    "return a component with two key values " in {
      withGood.componentValues.size === 2
    }
    "return a component with the key values foo which is repaired " in {
      withGood.componentValues("foo").head.messages.size === 1 and
      withGood.componentValues("foo").head.messages.head.reportType ===  EnforceRepaired
    }
    "return a component with the key values bar which is a success " in {
      withGood.componentValues("bar").head.messages.size === 1 and
      withGood.componentValues("bar").head.messages.head.reportType ===  EnforceSuccess
    }

    "only one reports in plus, mark the whole key unexpected" in {
      withBad.compliance === ComplianceLevel(success = 1,  unexpected = 2)
    }
    "with bad reports return a component with two key values " in {
      withBad.componentValues.size === 2
    }
    "with bad reports return a component with the key values foo which is unknwon " in {
      withBad.componentValues("foo").head.messages.size === 2 and
      withBad.componentValues("foo").head.messages.head.reportType ===  Unexpected
    }
    "with bad reports return a component with the key values bar which is a success " in {
      withBad.componentValues("bar").head.messages.size === 1 and
      withBad.componentValues("bar").head.messages.head.reportType ===  EnforceSuccess
    }
  }

  "A component, with a simple value and testing missing/no answer difference" should {
    val missing = Seq[ResultReports](
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "other key", executionTimestamp, "message"),
    )
    val noAnswer = Seq[ResultReports]()

    val expectedComponent = new ValueExpectedReport(
        "component"
      , ExpectedValueMatch("some key","some key") :: ExpectedValueMatch ( "other key",  "other key") :: Nil
    )

    "give a no answer if none report at all" in {
      val res = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, noAnswer, ReportType.NoAnswer, PolicyMode.Enforce, strictUnexpectedInterpretation).head
      res.compliance === ComplianceLevel(noAnswer = 2)
    }
    "give a missing if some reports present" in {
      val res = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, missing, ReportType.NoAnswer, PolicyMode.Enforce, strictUnexpectedInterpretation).head
      res.compliance === ComplianceLevel(success = 1, missing = 1)
    }
  }

  // Test the component part
  "A component, with a None keys" should {
    val reports = Seq[ResultReports](
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "None", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "None", executionTimestamp, "message")
    )

    val badReports = Seq[ResultReports](
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "None", executionTimestamp, "message"),
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "None", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "None", executionTimestamp, "message")
    )

    val expectedComponent = new ValueExpectedReport(
        "component"
      , ExpectedValueMatch("None", "None") :: ExpectedValueMatch("None", "None") :: Nil
    )
    val withGood = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, reports, ReportType.Missing, PolicyMode.Enforce, strictUnexpectedInterpretation).head
    val withBad  = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, badReports, ReportType.Missing, PolicyMode.Enforce, strictUnexpectedInterpretation).head

    "return a component with exact reporting" in {
      withGood.compliance === ComplianceLevel(repaired = 1, success = 1)
    }
    "return a component with one key value " in {
      withGood.componentValues.size === 1
    }
    "return a component with exact reporting in None key" in {
      withGood.componentValues("None").head.messages.size === 2 and
      withGood.componentValues("None").head.compliance === ComplianceLevel(repaired = 1, success = 1)
    }

    "with bad reports return a component globally unexpected " in {
      withBad.compliance === ComplianceLevel(repaired = 1, unexpected = 2)
    }
    "with bad reports return a component with one key values (only None)" in {
      withBad.componentValues.size === 1
    }
    "with bad reports return a component with None key unexpected " in {
      withBad.componentValues("None").head.messages.size === 3 and
      withBad.componentValues("None").head.messages.forall(x => (x.reportType === Unexpected) or (x.reportType === EnforceRepaired) )
    }
  }


  "A block, in 'weighted' report" should {
    val reports = Seq[ResultReports](
      new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "foo", executionTimestamp, "message"),
      new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "bar", executionTimestamp, "message")
    )

    val badReports = Seq[ResultReports](
      new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "foo", executionTimestamp, "message"),
      new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "foo", executionTimestamp, "message"),
      new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "bar", executionTimestamp, "message")
    )

    val expectedComponent = BlockExpectedReport(
      "block"
    , ReportingLogic.WeightedReport
    , new ValueExpectedReport(
      "component"
      , ExpectedValueMatch("foo", "foo") :: ExpectedValueMatch("bar", "bar")  :: Nil
    ) :: Nil
    )

    val withGood = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, reports, ReportType.Missing, PolicyMode.Enforce, strictUnexpectedInterpretation).head
    val withBad  = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, badReports, Missing, PolicyMode.Enforce, strictUnexpectedInterpretation).head

    "return a component globally repaired " in {
      withGood.compliance === ComplianceLevel( repaired = 1, success = 1)
    }
    "return a component with two key values " in {
      withGood.componentValues.size === 2
    }
    "return a component with the key values foo which is repaired " in {
      withGood.componentValues("foo").head.messages.size === 1 and
        withGood.componentValues("foo").head.messages.head.reportType ===  EnforceRepaired
    }
    "return a component with the key values bar which is a success " in {
      withGood.componentValues("bar").head.messages.size === 1 and
        withGood.componentValues("bar").head.messages.head.reportType ===  EnforceSuccess
    }

    "only one reports in addition, mark only foo (ie repaired) unexpected" in {
      withBad.compliance === ComplianceLevel(unexpected = 2, success = 1)
    }
    "with bad reports return a component with two key values " in {
      withBad.componentValues.size === 2
    }
    "with bad reports return a component with the key values foo which is unexpected " in {
      withBad.componentValues("foo").head.messages.size === 2 and
        withBad.componentValues("foo").head.messages.head.reportType ===  Unexpected
    }
    "with bad reports return a component with the key values bar which is a success " in {
      withBad.componentValues("bar").head.messages.size === 1 and
        withBad.componentValues("bar").head.messages.head.reportType ===  EnforceSuccess
    }
  }


  "A block, in 'worst case weight sum' report" should {
    val reports = Seq[ResultReports](
      new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "foo", executionTimestamp, "message"),
      new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "bar", executionTimestamp, "message")
    )

    val badReports = Seq[ResultReports](
      new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "foo", executionTimestamp, "message"),
      new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "foo", executionTimestamp, "message"),
      new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "bar", executionTimestamp, "message")
    )

    val expectedComponent = BlockExpectedReport(
      "block"
    , ReportingLogic.WorstReportWeightedSum
    , new ValueExpectedReport(
      "component"
        , ExpectedValueMatch("foo", "foo") :: ExpectedValueMatch("bar", "bar")  :: Nil
    ) :: Nil
    )

    val withGood = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, reports, ReportType.Missing, PolicyMode.Enforce, strictUnexpectedInterpretation).head
    val withBad  = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, badReports, Missing, PolicyMode.Enforce, strictUnexpectedInterpretation).head

    "return a component globally repaired " in {
      withGood.compliance === ComplianceLevel( repaired = 2)
    }
    "return a component with two key values " in {
      withGood.componentValues.size === 2
    }
    "return a component with the key values foo which is repaired " in {
      withGood.componentValues("foo").head.messages.size === 1 and
        withGood.componentValues("foo").head.messages.head.reportType ===  EnforceRepaired
    }
    "return a component with the key values bar which is a success " in {
      withGood.componentValues("bar").head.messages.size === 1 and
        withGood.componentValues("bar").head.messages.head.reportType ===  EnforceSuccess
    }

    "only one reports in addition, mark the whole weighted block unexpected" in {
      withBad.compliance === ComplianceLevel(unexpected = 3)
    }
    "with bad reports return a component with two key values " in {
      withBad.componentValues.size === 2
    }
    "with bad reports return a component with the key values foo which is unexpected " in {
      withBad.componentValues("foo").head.messages.size === 2 and
        withBad.componentValues("foo").head.messages.head.reportType ===  Unexpected
    }
    "with bad reports return a component with the key values bar which is a success " in {
      withBad.componentValues("bar").head.messages.size === 1 and
        withBad.componentValues("bar").head.messages.head.reportType ===  EnforceSuccess
    }
  }

  "A block, in 'worst case weight one' report" should {
    val reports = Seq[ResultReports](
      new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "foo", executionTimestamp, "message"),
      new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "bar", executionTimestamp, "message")
    )

    val badReports = Seq[ResultReports](
      new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "foo", executionTimestamp, "message"),
      new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "foo", executionTimestamp, "message"),
      new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "bar", executionTimestamp, "message")
    )

    val expectedComponent = BlockExpectedReport(
      "block"
    , ReportingLogic.WorstReportWeightedOne
    , new ValueExpectedReport(
      "component"
        , ExpectedValueMatch("foo", "foo") :: ExpectedValueMatch("bar", "bar")  :: Nil
    ) :: Nil
    )

    val withGood = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, reports, ReportType.Missing, PolicyMode.Enforce, strictUnexpectedInterpretation).head
    val withBad  = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, badReports, Missing, PolicyMode.Enforce, strictUnexpectedInterpretation).head

    "return a component globally repaired " in {
      withGood.compliance === ComplianceLevel( repaired = 1)
    }
    "return a component with two key values " in {
      withGood.componentValues.size === 2 // yes, still 2 here, we keep access to sub values to show details
    }
    "return a component with the key values foo which is repaired " in {
      withGood.componentValues("foo").head.messages.size === 1 and
        withGood.componentValues("foo").head.messages.head.reportType ===  EnforceRepaired
    }
    "return a component with the key values bar which is a success " in {
      withGood.componentValues("bar").head.messages.size === 1 and
        withGood.componentValues("bar").head.messages.head.reportType ===  EnforceSuccess
    }

    "only one reports in addition, mark the whole block unexpected with weight 1" in {
      withBad.compliance === ComplianceLevel(unexpected = 1)
    }
    "with bad reports return a component with two key values " in {
      withBad.componentValues.size === 2
    }
    "with bad reports return a component with the key values foo which is unexpected " in {
      withBad.componentValues("foo").head.messages.size === 2 and
        withBad.componentValues("foo").head.messages.head.reportType ===  Unexpected
    }
    "with bad reports return a component with the key values bar which is a success " in {
      withBad.componentValues("bar").head.messages.size === 1 and
        withBad.componentValues("bar").head.messages.head.reportType ===  EnforceSuccess
    }
  }

  "A block, with reporting focus " should {
    val reports = Seq[ResultReports](
      new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_0", "component1", "foo", executionTimestamp, "message"),
      new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_1", "component", "bar", executionTimestamp, "message")
    )

    val badReports = Seq[ResultReports](
      new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_1", "component1", "foo", executionTimestamp, "message"),
      new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_0", "component", "foo", executionTimestamp, "message"),
      new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_0", "component", "bar", executionTimestamp, "message")
    )

    val expectedComponent = BlockExpectedReport(
      "block"
      , ReportingLogic.FocusReport("component")
      , new ValueExpectedReport(
        "component"
        , ExpectedValueId("bar", "report_0")  :: Nil
      )  :: new ValueExpectedReport(
        "component1"
        , ExpectedValueId("bar", "report_1")  :: Nil
      )  :: Nil
    )

    val withGood = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, reports, ReportType.Missing, PolicyMode.Enforce, strictUnexpectedInterpretation).head
    val withBad  = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, badReports, Missing, PolicyMode.Enforce, strictUnexpectedInterpretation).head
    "return a success block " in {
      withGood.compliance === ComplianceLevel(success = 1)
    }
    "return a component with two key values " in {
      withGood.componentValues.size === 2
    }
    "return a component with the key values foo which is repaired " in {
      withGood.componentValues("foo").head.messages.size === 1 and
        withGood.componentValues("foo").head.messages.head.reportType ===  EnforceRepaired
    }
    "return a component with the key values bar which is a success " in {
      withGood.componentValues("bar").head.messages.size === 1 and
        withGood.componentValues("bar").head.messages.head.reportType ===  EnforceSuccess
    }

    "only one reports in plus, mark the whole key unexpected" in {
      withBad.compliance === ComplianceLevel(success = 1,  repaired = 1)
    }
    "with bad reports return a component with two key values " in {
      withBad.componentValues.size === 2
    }
    "with bad reports return a component with the key values foo with one unexpected and one repaired " in {
      withBad.componentValues("foo").head.messages.size === 2 and
        withBad.componentValues("foo").head.messages.map(_.reportType) ===  EnforceRepaired :: EnforceRepaired :: Nil
    }
    "with bad reports return a component with the key values bar which is a success " in {
      withBad.componentValues("bar").head.messages.size === 1 and
        withBad.componentValues("bar").head.messages.head.reportType ===  EnforceSuccess
    }
  }

  "Sub block with same component names are authorised, with reporting sum " should {
    val reports = Seq[ResultReports](
      new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component1", "b1c1", executionTimestamp, "message")
      , new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component2", "b1c2", executionTimestamp, "message")
      , new ResultSuccessReport (executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component1", "b2c1", executionTimestamp, "message")
      , new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component2", "b2c2", executionTimestamp, "message")
    )

    val badReports = Seq[ResultReports](
      new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component1", "b1c1", executionTimestamp, "message")
      , new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component2", "b1c2", executionTimestamp, "message")
      , new ResultSuccessReport (executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component1", "b2c1", executionTimestamp, "message")
      , new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component2", "b2c2", executionTimestamp, "message")
      // bad ones
      , new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component2", "b2c2", executionTimestamp, "message")
    )

    val expectedComponent = BlockExpectedReport(
      "blockRoot"
      , ReportingLogic.WeightedReport
      , BlockExpectedReport(
        "block1"
        , ReportingLogic.WeightedReport
        , new ValueExpectedReport(
          "component1"
          , ExpectedValueMatch("b1c1", "b1c1")  :: Nil
        )  :: new ValueExpectedReport(
          "component2"
          , ExpectedValueMatch("b1c2", "b1c2") :: Nil
        )  :: Nil
      ) :: BlockExpectedReport(
        "block2"
        , ReportingLogic.WeightedReport
        , new ValueExpectedReport(
          "component1"
          , ExpectedValueMatch("b2c1", "b2c1") :: Nil
        )  :: new ValueExpectedReport(
          "component2"
          , ExpectedValueMatch("b2c2", "b2c2") :: Nil

        )  :: Nil
      ) :: Nil
    )
    val directiveExpectedReports = DirectiveExpectedReports(DirectiveId(DirectiveUid("policy")), None, false, expectedComponent :: Nil)
    val ruleExpectedReports = RuleExpectedReports(RuleId("cr"), directiveExpectedReports :: Nil)
    val mergeInfo = MergeInfo(NodeId("nodeId"), None, None, DateTime.now())


    val withGood = ExecutionBatch.getComplianceForRule(mergeInfo, reports   , mode, ruleExpectedReports, strictUnexpectedInterpretation, new ComputeComplianceTimer()).directives("policy")
    val withBad  = ExecutionBatch.getComplianceForRule(mergeInfo, badReports, mode, ruleExpectedReports, strictUnexpectedInterpretation, new ComputeComplianceTimer()).directives("policy")

    "return a success block " in {
      withGood.compliance === ComplianceLevel(success = 1, repaired = 3)
    }
    "return one root component with 4 key values " in {
      withGood.components.filter(_.componentName == "blockRoot").head.componentValues.size === 4
    }
    "return 3 component with the key values b1c1,b1c2,b2c2 which is repaired " in {
      val block1 = withGood.components.filter(_.componentName == "blockRoot").head.asInstanceOf[BlockStatusReport].subComponents.find(_.componentName == "block1").get
      val block2 = withGood.components.filter(_.componentName == "blockRoot").head.asInstanceOf[BlockStatusReport].subComponents.find(_.componentName == "block2").get

      (block1.componentValues("b1c1").head.messages.size === 1) and
        (block1.componentValues("b1c1").head.messages.head.reportType ===  EnforceRepaired) and
        (block1.componentValues("b1c2").head.messages.size === 1) and
        (block1.componentValues("b1c2").head.messages.head.reportType ===  EnforceRepaired) and
        (block2.componentValues("b2c2").head.messages.size === 1) and
        (block2.componentValues("b2c2").head.messages.head.reportType ===  EnforceRepaired)
    }
    "return a component with the key values b2c1 which is a success " in {
      val block2 = withGood.components.filter(_.componentName == "blockRoot").head.asInstanceOf[BlockStatusReport].subComponents.find(_.componentName == "block2").get
      block2.componentValues("b2c1").head.messages.size === 1 and
        block2.componentValues("b2c1").head.messages.head.reportType ===  EnforceSuccess
    }

    "only one reports in plus, mark the whole key unexpected" in {
      withBad.compliance === ComplianceLevel(success = 1,  unexpected = 2, repaired = 2)
    }

  }


  "Sub block with looping component and same component names are not correctly reported, see https://issues.rudder.io/issues/20071" should {
    val reportsWithLoop = Seq[ResultReports](
      new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component1", "b1c1", executionTimestamp, "message")
    , new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component2", "b1c2", executionTimestamp, "message")
    , new ResultSuccessReport (executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component1", "b2c1", executionTimestamp, "message")
    , new ResultSuccessReport (executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component2", "loop1", executionTimestamp, "message_1")
    , new ResultSuccessReport (executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component2", "loop2", executionTimestamp, "message_2")
    , new ResultSuccessReport (executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component2", "loop3", executionTimestamp, "message_3")
    )

    val expectedComponent = BlockExpectedReport(
      "blockRoot"
      , ReportingLogic.WeightedReport
      , BlockExpectedReport(
          "block1"
          , ReportingLogic.WeightedReport
          , new ValueExpectedReport(
            "component1"
          , ExpectedValueMatch("b1c1", "b1c1") :: Nil
          )  :: new ValueExpectedReport(
            "component2"
          , ExpectedValueMatch("b1c2", "b1c2") :: Nil
          )  :: Nil
        ) :: BlockExpectedReport(
          "block2"
          , ReportingLogic.WeightedReport
          , new ValueExpectedReport(
            "component1"
          , ExpectedValueMatch("b2c1", "b2c1") :: Nil
          )  :: new ValueExpectedReport(
            "component2"
          , ExpectedValueMatch("${loop}", "${loop}") :: Nil
          )  :: Nil
        ) :: Nil
    )
    val directiveExpectedReports = DirectiveExpectedReports(DirectiveId(DirectiveUid("policy")), None, false, expectedComponent :: Nil)
    val ruleExpectedReports = RuleExpectedReports(RuleId("cr"), directiveExpectedReports :: Nil)
    val mergeInfo = MergeInfo(NodeId("nodeId"), None, None, DateTime.now())


    val withLoop = ExecutionBatch.getComplianceForRule(mergeInfo, reportsWithLoop, mode, ruleExpectedReports, UnexpectedReportInterpretation(Set(UnboundVarValues)), new ComputeComplianceTimer()).directives("policy")

    "return one too many repaired because it is match two time " in {
      withLoop.compliance === ComplianceLevel(success = 4, repaired = 3)
    }
    "return one root component with 4 key values " in {
      withLoop.components.filter(_.componentName == "blockRoot").head.componentValues.size === 4
    }
    "return 1 loop component with the key values loopX which is success, and one too many time because we don't count correctly" in {
      val block2 = withLoop.components.filter(_.componentName == "blockRoot").head.asInstanceOf[BlockStatusReport].subComponents.find(_.componentName == "block2").get

      (block2.componentValues("${loop}").head.messages.size === 4)
    }


  }


  "Sub block with same component names are authorised, with reporting focus " should {
    val reports = Seq[ResultReports](
      new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component1", "b1c1", executionTimestamp, "message")
      , new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component2", "b1c2", executionTimestamp, "message")
      , new ResultSuccessReport (executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component1", "b2c1", executionTimestamp, "message")
      , new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component2", "b2c2", executionTimestamp, "message")
    )

    val badReports = Seq[ResultReports](
      new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component1", "b1c1", executionTimestamp, "message")
      , new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component2", "b1c2", executionTimestamp, "message")
      , new ResultSuccessReport (executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component1", "b2c1", executionTimestamp, "message")
      , new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component2", "b2c2", executionTimestamp, "message")
      // bad ones
      , new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component2", "b2c2", executionTimestamp, "message")
    )

    val expectedComponent = BlockExpectedReport(
      "blockRoot"
      , ReportingLogic.FocusReport("block2")
      , BlockExpectedReport(
        "block1"
        , ReportingLogic.FocusReport("component1")
        , new ValueExpectedReport(
          "component1"
          , ExpectedValueMatch("b1c1", "b1c1") :: Nil
        )  :: new ValueExpectedReport(
          "component2"
          , ExpectedValueMatch("b1c2", "b1c2") :: Nil
        )  :: Nil
      ) :: BlockExpectedReport(
        "block2"
        , ReportingLogic.FocusReport("component1")
        , new ValueExpectedReport(
          "component1"
          , ExpectedValueMatch("b2c1", "b2c1") :: Nil
        )  :: new ValueExpectedReport(
          "component2"
          , ExpectedValueMatch("b2c2", "b2c2") :: Nil
        )  :: Nil
      ) :: Nil
    )
    val directiveExpectedReports = DirectiveExpectedReports(DirectiveId(DirectiveUid("policy")), None, false, expectedComponent :: Nil)
    val ruleExpectedReports = RuleExpectedReports(RuleId("cr"), directiveExpectedReports :: Nil)
    val mergeInfo = MergeInfo(NodeId("nodeId"), None, None, DateTime.now())


    val withGood = ExecutionBatch.getComplianceForRule(mergeInfo, reports   , mode, ruleExpectedReports, strictUnexpectedInterpretation, new ComputeComplianceTimer()).directives("policy")
    val withBad  = ExecutionBatch.getComplianceForRule(mergeInfo, badReports, mode, ruleExpectedReports, strictUnexpectedInterpretation, new ComputeComplianceTimer()).directives("policy")

    "return a success block " in {
      withGood.compliance === ComplianceLevel(success = 1)
    }
    "return one root component with 4 key values " in {
      withGood.components.filter(_.componentName == "blockRoot").head.componentValues.size === 4
    }
    "return 3 component with the key values b1c1,b1c2,b2c2 which is repaired " in {
      val block1 = withGood.components.filter(_.componentName == "blockRoot").head.asInstanceOf[BlockStatusReport].subComponents.find(_.componentName == "block1").get
      val block2 = withGood.components.filter(_.componentName == "blockRoot").head.asInstanceOf[BlockStatusReport].subComponents.find(_.componentName == "block2").get

      (block1.componentValues("b1c1").head.messages.size === 1) and
        (block1.componentValues("b1c1").head.messages.head.reportType ===  EnforceRepaired) and
        (block1.componentValues("b1c2").head.messages.size === 1) and
        (block1.componentValues("b1c2").head.messages.head.reportType ===  EnforceRepaired) and
        (block2.componentValues("b2c2").head.messages.size === 1) and
        (block2.componentValues("b2c2").head.messages.head.reportType ===  EnforceRepaired)
    }
    "return a component with the key values b2c1 which is a success " in {
      val block2 = withGood.components.filter(_.componentName == "blockRoot").head.asInstanceOf[BlockStatusReport].subComponents.find(_.componentName == "block2").get
      block2.componentValues("b2c1").head.messages.size === 1 and
        block2.componentValues("b2c1").head.messages.head.reportType ===  EnforceSuccess
    }

    "Ignore the unexpected reports from components that are not within the focus" in {
      withBad.compliance === ComplianceLevel(success = 1)
    }

  }



  "Sub block with same component names are authorised, with reporting 'worst case weighted sum'" should {
    val reports = Seq[ResultReports](
      new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component1", "b1c1", executionTimestamp, "message")
      , new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component2", "b1c2", executionTimestamp, "message")
      , new ResultSuccessReport (executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component1", "b2c1", executionTimestamp, "message")
      , new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component2", "b2c2", executionTimestamp, "message")
    )

    val badReports = Seq[ResultReports](
      new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component1", "b1c1", executionTimestamp, "message")
      , new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component2", "b1c2", executionTimestamp, "message")
      , new ResultSuccessReport (executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component1", "b2c1", executionTimestamp, "message")
      , new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component2", "b2c2", executionTimestamp, "message")
      // bad ones
      , new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component2", "b2c2", executionTimestamp, "message")
    )

    val expectedComponent = BlockExpectedReport(
      "blockRoot"
      , ReportingLogic.WorstReportWeightedSum
      , BlockExpectedReport(
        "block1"
        , ReportingLogic.WeightedReport
        , new ValueExpectedReport(
          "component1"
          , ExpectedValueMatch("b1c1", "b1c1") :: Nil
        )  :: new ValueExpectedReport(
          "component2"
          , ExpectedValueMatch("b1c2", "b1c2") :: Nil
        )  :: Nil
      ) :: BlockExpectedReport(
        "block2"
        , ReportingLogic.WeightedReport
        , new ValueExpectedReport(
          "component1"
          , ExpectedValueMatch("b2c1", "b2c1") :: Nil
        )  :: new ValueExpectedReport(
          "component2"
          , ExpectedValueMatch("b2c2", "b2c2") :: Nil
        )  :: Nil
      ) :: Nil
    )
    val directiveExpectedReports = DirectiveExpectedReports(DirectiveId(DirectiveUid("policy")), None, false, expectedComponent :: Nil)
    val ruleExpectedReports = RuleExpectedReports(RuleId("cr"), directiveExpectedReports :: Nil)
    val mergeInfo = MergeInfo(NodeId("nodeId"), None, None, DateTime.now())


    val withGood = ExecutionBatch.getComplianceForRule(mergeInfo, reports   , mode, ruleExpectedReports, strictUnexpectedInterpretation, new ComputeComplianceTimer()).directives("policy")
    val withBad  = ExecutionBatch.getComplianceForRule(mergeInfo, badReports, mode, ruleExpectedReports, strictUnexpectedInterpretation, new ComputeComplianceTimer()).directives("policy")

    "return a repaired block " in {
      withGood.compliance === ComplianceLevel(repaired = 4)
    }
    "return one root component with 4 key values " in {
      withGood.components.filter(_.componentName == "blockRoot").head.componentValues.size === 4
    }
    "return 3 component with the key values b1c1,b1c2,b2c2 which is repaired " in {
      val block1 = withGood.components.filter(_.componentName == "blockRoot").head.asInstanceOf[BlockStatusReport].subComponents.find(_.componentName == "block1").get
      val block2 = withGood.components.filter(_.componentName == "blockRoot").head.asInstanceOf[BlockStatusReport].subComponents.find(_.componentName == "block2").get

      (block1.componentValues("b1c1").head.messages.size === 1) and
        (block1.componentValues("b1c1").head.messages.head.reportType ===  EnforceRepaired) and
        (block1.componentValues("b1c2").head.messages.size === 1) and
        (block1.componentValues("b1c2").head.messages.head.reportType ===  EnforceRepaired) and
        (block2.componentValues("b2c2").head.messages.size === 1) and
        (block2.componentValues("b2c2").head.messages.head.reportType ===  EnforceRepaired)
    }
    "return a component with the key values b2c1 which is a success " in {
      val block2 = withGood.components.filter(_.componentName == "blockRoot").head.asInstanceOf[BlockStatusReport].subComponents.find(_.componentName == "block2").get
      block2.componentValues("b2c1").head.messages.size === 1 and
        block2.componentValues("b2c1").head.messages.head.reportType ===  EnforceSuccess
    }

    "Return an unexpected Block" in {
      withBad.compliance === ComplianceLevel(unexpected = 5)
    }

  }

  "A block, with Sum reporting logic" should {
    val reports = Seq[ResultReports](
      new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "foo", executionTimestamp, "message"),
      new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "bar", executionTimestamp, "message")
    )

    val badReports = Seq[ResultReports](
      new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "foo", executionTimestamp, "message"),
      new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "foo", executionTimestamp, "message"),
      new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "bar", executionTimestamp, "message")
    )

    val expectedComponent = BlockExpectedReport(
      "block"
      , ReportingLogic.WeightedReport
      , new ValueExpectedReport(
        "component"
        , ExpectedValueMatch("foo", "foo") ::ExpectedValueMatch("bar", "bar") :: Nil
      ) :: Nil
    )

    val withGood = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, reports, ReportType.Missing, PolicyMode.Enforce, strictUnexpectedInterpretation).head
    val withBad  = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, badReports, Missing, PolicyMode.Enforce, strictUnexpectedInterpretation).head

    "return a component globally repaired " in {
      withGood.compliance === ComplianceLevel(success = 1, repaired = 1)
    }
    "return a component with two key values " in {
      withGood.componentValues.size === 2
    }
    "return a component with the key values foo which is repaired " in {
      withGood.componentValues("foo").head.messages.size === 1 and
        withGood.componentValues("foo").head.messages.head.reportType ===  EnforceRepaired
    }
    "return a component with the key values bar which is a success " in {
      withGood.componentValues("bar").head.messages.size === 1 and
        withGood.componentValues("bar").head.messages.head.reportType ===  EnforceSuccess
    }

    "only one reports in plus, mark the whole key unexpected" in {
      withBad.compliance === ComplianceLevel(success = 1,  unexpected = 2)
    }
    "with bad reports return a component with two key values " in {
      withBad.componentValues.size === 2
    }
    "with bad reports return a component with the key values foo which is unknwon " in {
      withBad.componentValues("foo").head.messages.size === 2 and
        withBad.componentValues("foo").head.messages.head.reportType ===  Unexpected
    }
    "with bad reports return a component with the key values bar which is a success " in {
      withBad.componentValues("bar").head.messages.size === 1 and
        withBad.componentValues("bar").head.messages.head.reportType ===  EnforceSuccess
    }
  }

  // Test the component part
  "A component, with a cfengine keys" should {
    val reports = Seq[ResultReports](
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "/var/cfengine", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "/var/cfengine", executionTimestamp, "message")
    )

    val badReports = Seq[ResultReports](
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "/var/cfengine", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "/var/cfengine", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "/var/cfengine", executionTimestamp, "message")
    )

    val expectedComponent = new ValueExpectedReport("component"
      , ExpectedValueMatch("${sys.bla}", "${sys.bla}") :: ExpectedValueMatch("${sys.foo}", "${sys.foo}") :: Nil
    )

    val withGood = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, reports, ReportType.Missing, PolicyMode.Enforce, strictUnexpectedInterpretation).head
    val withBad   = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, badReports, ReportType.Missing, PolicyMode.Enforce, strictUnexpectedInterpretation).head

    "return a component globally repaired " in {
      withGood.compliance === ComplianceLevel(success = 1, repaired = 1)
    }
    "return a component with two key values " in {
      withGood.componentValues.size === 2
    }
    "return a component with both cfengine keys repaired " in {
      withGood.componentValues("${sys.bla}").head.messages.size === 1
    }
    "with bad reports return a component with 2 values and 3 messages" in {
      (withBad.componentValues.size === 2) and withBad.compliance.total === 3
    }
  }

   // Test the component part
  "A component, with generation-time known keys" should {
    val reports = Seq[ResultReports](
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "bar", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "node2", executionTimestamp, "message"),
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "node1", executionTimestamp, "message")
    )

    val badReports = Seq[ResultReports](
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "node1", executionTimestamp, "message"),
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "node1", executionTimestamp, "message"),
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "node2", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "bar", executionTimestamp, "message")
    )

    /*
     * Here, we must be able to decide between node1 and node2 value for the repair, because we know at generation time
     * what is expected.
     */
    val expectedComponent = new ValueExpectedReport("component"
      , ExpectedValueMatch("node1","${rudder.node.hostname}") :: ExpectedValueMatch ( "node2","${rudder.node.hostname}") :: ExpectedValueMatch ( "bar", "bar") ::Nil

    )

    val withGood = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, reports, ReportType.Missing, PolicyMode.Enforce, strictUnexpectedInterpretation).head
    val withBad  = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, badReports, ReportType.Missing, PolicyMode.Enforce, strictUnexpectedInterpretation).head

    "return a component with the correct number of success and repaired" in {
      //be careful, here the second success is for the same unexpanded as the repaire,
      withGood.compliance === ComplianceLevel(success = 2, repaired = 1)
    }

    "return a component with three key values " in {
      withGood.componentValues.size === 3
    }

    "return a component with the bar key success " in {
      withGood.componentValues("bar").head.messages.size === 1 and
      withGood.componentValues("bar").head.messages.forall(x => x.reportType === EnforceSuccess)
    }

    "with some bad reports mark them as unexpected (because the check is not done in checkExpectedComponentWithReports" in {
      withBad.compliance ===  ComplianceLevel(success = 1, repaired = 1, unexpected = 2)
    }
    "with bad reports return a component with three key values " in {
      withBad.componentValues.size === 3
    }
    "with bad reports return a component with bar as a success " in {
      withBad.componentValues("bar").head.messages.size === 1 and
      withBad.componentValues("bar").head.messages.forall(x => x.reportType === EnforceSuccess)
    }
    "with bad reports return a component with the cfengine key as unexpected " in {
      withBad.componentValues("node1").head.messages.size === 2 and
      withBad.componentValues("node1").head.messages.forall(x => x.reportType === Unexpected)
    }
  }

  "Shall we speak about unexpected" should {
    // we asked for a value "foo" and a variable ${param}
    val expectedComponent = new ValueExpectedReport("component"
      , ExpectedValueMatch ("foo", "foo") :: ExpectedValueMatch("${param}", "${param}") :: Nil
    )

    //syslog duplicated a message
    val duplicated = Seq[ResultReports](
      new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "foo", executionTimestamp, "foo message"),
      new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "foo", executionTimestamp, "foo message"),
      new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "param expended", executionTimestamp, "param message")
    )

    val tooMuchDuplicated = Seq[ResultReports](
      new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "foo", executionTimestamp, "foo message"),
      new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "foo", executionTimestamp, "foo message"),
      new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "foo", executionTimestamp, "foo message"),
      new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "foo", executionTimestamp, "foo message"),
      new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "param expended", executionTimestamp, "param message")
    )


    // ${param} was an iterato on: foo, bar, baz
    val unboundedVars = Seq[ResultReports](
      new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "foo", executionTimestamp, "foo message"),
      new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "foo", executionTimestamp, "foo expanded"), // here foo should not be a duplicated because the message is different, which is likely the case in real life
      new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "bar", executionTimestamp, "bar expanded"),
      new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "baz", executionTimestamp, "baz expanded")
    )


    "when strict mode is set, duplicate messages lead to unexpected" in {
      val res = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, duplicated, ReportType.Missing, PolicyMode.Enforce, strictUnexpectedInterpretation).head
      res.compliance === ComplianceLevel(success = 1, unexpected = 2) // 2 unexpected because the whole "foo" becomes unexpected
    }
    "when allow duplicated, duplicate messages is ignored" in {
      val res = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, duplicated, ReportType.Missing, PolicyMode.Enforce, UnexpectedReportInterpretation(Set())).head
      res.compliance === ComplianceLevel(success = 1, unexpected = 2)
    }
    "when allow duplicated, duplicate messages is ignored but not for 4 duplications" in {
      val res = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, tooMuchDuplicated, ReportType.Missing, PolicyMode.Enforce, UnexpectedReportInterpretation(Set())).head
      res.compliance === ComplianceLevel(success = 1, unexpected = 4)
    }

    "when on strict mode, out of bound vars are unexpected" in {
      val res = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, unboundedVars, ReportType.Missing, PolicyMode.Enforce, strictUnexpectedInterpretation).head
      res.compliance === ComplianceLevel(success = 1, unexpected = 3)
    }
    "when on strict mode, out of bound vars are unexpected" in {
      val res = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, unboundedVars, ReportType.Missing, PolicyMode.Enforce, UnexpectedReportInterpretation(Set(UnexpectedReportBehavior.UnboundVarValues))).head
      res.compliance === ComplianceLevel(success = 4)
    }

  }

  "Compliance for cfengine vars and reports" should {

    sealed trait Kind { def tpe: ReportType }
    final case object Success    extends Kind { val tpe = EnforceSuccess }
    final case object Repaired   extends Kind { val tpe = EnforceRepaired }
    final case object Error      extends Kind { val tpe = EnforceError }
    final case object Missing    extends Kind { val tpe = ReportType.Missing }
    final case object Unexpected extends Kind { val tpe = ReportType.Unexpected }

    /*
     * Values are expected values with the corresponding status list
     */
    def test(
        id: String
      , patterns: Seq[(String, Seq[Kind])]
      , reports: Seq[(String, Kind)]
      , unexpectedNotValue: Seq[String] = Nil
      , mode: UnexpectedReportInterpretation = strictUnexpectedInterpretation
    ) = {

      // expected components are the list of key for patterns
      val expectedComponent = {
        val values = patterns.map( _._1 )
        new ValueExpectedReport("component", values.map(v => ExpectedValueMatch(v,v)).toList)
      }

      val resultReports : Seq[ResultReports] = reports.map( x => x match {
        case (v, Success) => new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", v, executionTimestamp, "message")
        case (v, Repaired)=> new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", v, executionTimestamp, "message")
        case (v, x)       => new ResultErrorReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", v, executionTimestamp, "message")
      })

      val t1 = System.currentTimeMillis
      val result = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, resultReports, ReportType.Missing, PolicyMode.Enforce, mode)
      val t2 = System.currentTimeMillis - t1

      //distribute compliance on each pattern to be able to count them
      val p = patterns.flatMap { case (x, seq) => seq.map(s => (x, s))} ++ unexpectedNotValue.map(u => (u, Unexpected))

      val compliance = ComplianceLevel(
          success    = p.collect { case (x, Success   ) => x }.size
        , repaired   = p.collect { case (x, Repaired  ) => x }.size
        , error      = p.collect { case (x, Error     ) => x }.size
        , missing    = p.collect { case (x, Missing   ) => x }.size
        , unexpected = p.collect { case (x, Unexpected) => x }.size
      )

      s"[${id}] be OK with patterns ${patterns}" in {
        p.foldLeft((result.head.compliance === compliance)) { case( example, nextPattern) =>
          result.head.componentValues(nextPattern._1).head.messages.foldLeft(example) { case (newExample, nextMessage) =>
            val msgCompliance = ComplianceLevel.compute(List( nextMessage.reportType))
            val patternCompliance = ComplianceLevel.compute(List( nextPattern._2.tpe))
            newExample and msgCompliance === patternCompliance
          }
        } and (t2 must be_<(200L)) //take less than these number of ms
      }
    }

    /*
     * Test the hard case where we have two components that may return undistinguishable reports, like:
     * "edit file with names: 1. /etc/foo.${xxx} 2. /etc/foo.old.${yyy}"
     * And you get [/etc/foo.old.txt, /etc/foo.txt] <- if matched on that order, the first is OK but not the
     * second.
     *
     * Here, the order should not matter because given the pattern, we are able to exactly
     * find one message for it.
     */

    test("order1"
      , patterns = ("/etc/foo.old.${sys.bla}", Seq(Success)) :: ("/etc/foo.${sys.bla}", Seq(Repaired)) :: Nil
      , reports  = ("/etc/foo.old.txt", Success) :: ("/etc/foo.txt", Repaired) :: Nil
    )

    test("order2"
      , patterns = ("/etc/foo.old.${sys.bla}", Seq(Success)) :: ("/etc/foo.${sys.bla}", Seq(Repaired)) :: Nil
      , reports  = ("/etc/foo.txt", Repaired) :: ("/etc/foo.old.txt", Success) :: Nil
    )

    test("order3"
      , patterns = ("/etc/foo.${sys.bla}", Seq(Repaired)) :: ("/etc/foo.old.${sys.bla}", Seq(Success)) :: Nil
      , reports  = ("/etc/foo.old.txt", Success) :: ("/etc/foo.txt", Repaired) :: Nil
    )
    test("order4"
      , patterns = ("/etc/foo.${sys.bla}", Seq(Repaired)) :: ("/etc/foo.old.${sys.bla}", Seq(Success)) :: Nil
      , reports  = ("/etc/foo.txt", Repaired) :: ("/etc/foo.old.txt", Success) :: Nil
    )

    //
    test("one var"
      , patterns = ("${sys.bla}", Seq(Repaired)) :: ("bar", Seq(Success)) :: Nil
      , reports  = ("/var/cfengine", Repaired) :: ("bar", Success) :: Nil
    )

    /*
     * For the next tests, the logic is that:
     * - we successfully matched the constant value;
     * - we successfully matched the cfengine value (taking at random the report type between
     *   success and repaired - but we have no way to decide ! Ok, and the random is more like
     *   "the first in the list", so it helps for the test)
     * - have one unexpected, for the last message - and we try to match on key (a constant key
     *   can not be the culprit, and we will chose a var more likely than an exotic key)
     */
    // So, if you swap the order of the two "/var/cfengine" reports, the test will fail

    test("only simple reports"
      , patterns = ("foo", Seq(Repaired)) :: ("bar", Seq(Success)) :: Nil
      , reports  = ("baz", Repaired) :: ("foo", Repaired) :: ("bar", Success) :: Nil
      //, unexpectedNotValue = Seq("baz")
    )

    test("one var and simple reports"
      , patterns = ("${sys.bla}", Seq(Unexpected, Unexpected)) :: ("bar", Seq(Success)) :: Nil
      , reports  = ("/var/cfengine", Repaired) :: ("/var/cfengine", Success) :: ("bar", Success) :: Nil
    )

    /*
     * Testing cfengine variables which give the same patterns
     *
     * Again, if you switch the order of reports, tests will fail because
     * we don't have any mean to assign a given report to a given patterns in
     * that case - the only way to correct that is to be able to have unique identification
     * of reports by expected component.
     */
    test("same patterns"
      , patterns = ("${sys.bla}", Seq(Repaired)) :: ("${sys.foo}", Seq(Success)) :: Nil
      , reports  = ("/var/cfengine", Repaired) :: ("/var/cfengine", Success) :: Nil
    )

    test("same patterns with unexpected"
      , patterns = ("${sys.bla}", Seq(Repaired)) :: ("${sys.foo}", Seq(Unexpected, Unexpected)) :: Nil
      , reports  = ("/var/cfengine", Repaired) :: ("/var/cfengine", Success) :: ("/var/cfengine", Success) :: Nil
    )

    /*
     * Test for ticket https://issues.rudder.io/issues/15007
     */
    test( "string size should not matter"
      , patterns =
           ("${nagios_knowledge.nrpe_conf_file}"                   , Seq(Repaired))
        :: ("/tmp/usr/local/nagios/libexec/security/check_clean.pl", Seq(Error   ))
        :: ("/tmp/usr/local/nagios/libexec/security/check_clean"   , Seq(Success ))
        :: Nil
      , reports  =
           ("/tmp/usr/local/etc/nrpe.cfg"                          , Repaired)
        :: ("/tmp/usr/local/nagios/libexec/security/check_clean.pl", Error   )
        :: ("/tmp/usr/local/nagios/libexec/security/check_clean"   , Success )
        :: Nil
    )
    test( "string size should not matter (2)"
      , patterns =
           ("${nagios_knowledge.nrpe_conf_file}"                   , Seq(Repaired))
        :: ("/tmp/usr/local/nagios/libexec/security/check_clean.pl", Seq(Error   ))
        :: ("/tmp/usr/local/nagios/libexec/security/check_clean"   , Seq(Success ))
        :: Nil
      , reports  =
           ("/tmp/usr/local/etc/nrpe.cfg_but_here_we_now_have_a_long_string", Repaired)
        :: ("/tmp/usr/local/nagios/libexec/security/check_clean.pl"         , Error   )
        :: ("/tmp/usr/local/nagios/libexec/security/check_clean"            , Success )
        :: Nil
    )

    /*
     * A test with a lots of variables and reports, to see if the execution time remains OK
     */
    test("lots of reports"
      , patterns =
             ("/var/cfengine", Seq(Success))
          :: ("${sys.foo}", Seq(Success))   // we actually haven't any way to distinguish between that one ( <- )
          :: ("${sys.bla}", Seq(Repaired))  // and that one ( <- ). Correct solution is to use different report components.
          :: ("/etc/foo.old.${sys.bla}", Seq(Success))
          :: ("/etc/foo.${sys.bla}", Seq(Repaired))
          :: ("a${foo}b${bar}", Seq(Success))
          :: ("a${foo}b", Seq(Success))
          :: ("b${foo}b", Seq(Success))
          :: ("b${foo}c", Seq(Success))
          :: ("b${foo}d", Seq(Success))
          :: Nil
      , reports  =
             ("/var/cfengine", Success)
          :: ("/var/cfengine", Success)
          :: ("/etc/foo.txt", Repaired)
          :: ("/etc/foo.old.txt", Success)
          :: ("/var/cfengine", Repaired)
          :: ("aXbX", Success)
          :: ("aYb", Success)
          :: ("bXb", Success)
          :: ("bc", Success)
          :: ("bZd", Success)
          :: Nil
    )

    test("same report for simple and pattern"
      , patterns = ("/var/${sys.bla}", Seq(Success)) :: ("/var/cfengine", Seq(Success)) :: Nil
      , reports  = ("/var/cfengine", Success) :: ("/var/cfengine", Success) :: Nil
    )

    // handle correctly ${boo}bar} vs ${foo}xxx} (ie matches only the variable part)
    test("matches only the variable part of variable"
      , patterns = ("${foo}xxx}", Seq(Repaired)) :: ("$(bar)yyyy)", Seq(Success))  :: Nil
      , reports  = ("ayyyy)", Success) :: ("bxxx}", Repaired) :: Nil
    )

    // there should be nothing special about "\" even if cfengine escape them
    test("""nothing special with \ when a ${var} is present"""
      , patterns = ("${foo}x\\x}", Seq(Repaired)) :: Nil
      , reports  = ("yx\\x}", Repaired) :: Nil
    )

    test("""nothing special with \"""
      , patterns = ("x\\x}", Seq(Repaired)) :: Nil
      , reports  = ("x\\x}", Repaired) :: Nil
    )

    // we need to take care of the fact that ${const.dollar} is always replaced by cfengine
    test("consider regex special chars as normal chars"
      , patterns = ("[^foo$]", Seq(Repaired)) :: ("(bar)", Seq(Success)) :: ("""\D\p{Lower}""", Seq(Success)) :: Nil
      , reports  = ("[^foo$]", Repaired) :: ("(bar)", Success) :: ("""\D\p{Lower}""", Success) :: Nil
    )

    // we need to take care of the fact that ${const.dollar} is always replaced by cfengine
    test("correctly understand ${const.dollar}"
      , patterns = ("""[ ${const.dollar}(echo "enabled") = 'enabled' ]""", Seq(Repaired)) :: ("/var/${const.dollar}cfengine", Seq(Success)) :: Nil
      , reports  = ("""[ $(echo "enabled") = 'enabled' ]""", Repaired) :: ("/var/$cfengine", Success) :: Nil
    )
  }

  val fullCompliance = GlobalComplianceMode(FullCompliance, 1)

  "A detailed execution Batch, with one component, cardinality one, one node" should {

    val param = (
        buildExpected(
            List("one")
          , "rule"
          , 12
          , List(DirectiveExpectedReports("policy", None, false
                , List(new ValueExpectedReport("component", ExpectedValueMatch("value","value") :: Nil )) //here, we automatically must have "value" infered as unexpanded var
              )
            )
        )
      , Seq[Reports](new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", "report_id12", "component", "value", DateTime.now(), "message"))
      , fullCompliance
    )

    val nodeStatus = (getNodeStatusReportsByRule _).tupled(param)

    "have one detailed reports when we create it with one report" in {
      nodeStatus(one).reports.size === 1
    }

    "have one detailed success node when we create it with one success report" in {
      nodeStatus.keySet.head === one
    }

    "have one detailed rule success directive when we create it with one success report" in {
      nodeStatus(one).reports.head.directives.head._1.serialize === "policy"
    }

    "have no detailed rule non-success directive when we create it with one success report" in {
      AggregatedStatusReport(nodeStatus(one).reports).compliance === ComplianceLevel(success = 1)
    }
  }

  "A detailed execution Batch, with one component, cardinality one, wrong node" should {
    val param = (
        buildExpected(
            List("one")
          , "rule"
          , 12
          , List(DirectiveExpectedReports("policy", None, false
                , List(new ValueExpectedReport("component", ExpectedValueMatch("value","value") :: Nil ))
              )
            )
        )
      , Seq[Reports](new ResultSuccessReport(DateTime.now(), "rule", "policy", "two", "report_id12", "component", "value",DateTime.now(), "message"))
      , fullCompliance
    )

    val nodeStatus = getNodeStatusByRule(param)

    "have one detailed reports when we create it with one report" in {
      nodeStatus.size === 1
    }

    "have a pending node when we create it with one wrong success report right now" in {
      (nodeStatus.keySet.head === one) and
      AggregatedStatusReport(nodeStatus.values.flatMap(_.reports).toSet).compliance === ComplianceLevel(missing = 1)
    }
  }

  "A detailed execution Batch, with one component, cardinality one, one node" should {

    val param = (
        buildExpected(
            List("one")
          , "rule"
          , 12
          , List(DirectiveExpectedReports("policy", None, false
                , List(new ValueExpectedReport("component", ExpectedValueMatch("value","value") :: Nil ))
              )
            )
         )
       , Seq[Reports](
             new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", "report_id12", "component", "value",DateTime.now(), "message")
           , new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", "report_id12", "component", "value",DateTime.now(), "message")
         )
      , fullCompliance
    )
    val nodeStatus = getNodeStatusByRule(param)

    "have one detailed reports when we create it" in {
      nodeStatus.size ==1
    }

    "have one unexpected node when we create it with one success report" in {
      nodeStatus.head._1 === one and
      AggregatedStatusReport(nodeStatus.values.flatMap(_.reports).toSet).compliance === ComplianceLevel(unexpected = 2)
    }
  }

   "A detailed execution Batch, with one component, cardinality one, two nodes, including one not responding" should {

    val param = (
        buildExpected(
            List("one", "two")
          , "rule"
          , 12
          , List(DirectiveExpectedReports("policy", None, false
                , List(new ValueExpectedReport("component", ExpectedValueMatch("value","value") :: Nil ))
              )
            )
        )
      , Seq[Reports](new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", "report_id12", "component", "value",DateTime.now(), "message"))
      , fullCompliance
    )
    val nodeStatus = getNodeStatusByRule(param)

    "have two detailed reports when we create it" in {
      nodeStatus.size == 2
    }

    "have one success, and one pending node, in the component detail of the rule" in {
      AggregatedStatusReport(nodeStatus.values.flatMap(_.reports).toSet).compliance === ComplianceLevel(success = 1, missing = 1)
    }
  }

  "A detailed execution Batch, with one component, cardinality one, three nodes, including one not responding" should {
    val param = (
        buildExpected(
            List("one", "two", "three")
          , "rule"
          , 12
          , List(DirectiveExpectedReports("policy", None, false
                , List(new ValueExpectedReport("component", ExpectedValueMatch("value","value") :: Nil ))
              )
            )
         )
       , Seq[Reports](
             new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", "report_id12", "component", "value", DateTime.now(), "message")
           , new ResultSuccessReport(DateTime.now(), "rule", "policy", "two", "report_id12", "component", "value", DateTime.now(), "message")
         )
      , fullCompliance
    )
    val nodeStatus = getNodeStatusByRule(param)

    "have three node rule report" in {
      nodeStatus.size === 3
    }
    "have one detailed rule report with a 67% compliance" in {
      AggregatedStatusReport(nodeStatus.values.flatMap(_.reports).toSet).compliance === ComplianceLevel(success = 2, missing = 1)
    }
  }

  "A detailed execution Batch, with two directive, two component, cardinality one, three nodes, including one partly responding and one not responding" should {
    val param = (
        buildExpected(
            List("one", "two", "three")
          , "rule"
          , 12
          , List(DirectiveExpectedReports("policy", None, false, List(
                     new ValueExpectedReport("component" , ExpectedValueMatch("value","value") :: Nil )
                   , new ValueExpectedReport("component2", ExpectedValueMatch("value","value") :: Nil )
                 ))
               , DirectiveExpectedReports("policy2", None, false, List(
                     new ValueExpectedReport("component" , ExpectedValueMatch("value","value") :: Nil )
                   , new ValueExpectedReport("component2", ExpectedValueMatch("value","value") :: Nil )
                 ))
            )
        )
      , Seq[Reports](
          new ResultSuccessReport(DateTime.now(), "rule", "policy" , "one", "report_id12", "component" , "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy" , "one", "report_id12", "component2", "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy2", "one", "report_id12", "component" , "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy2", "one", "report_id12", "component2", "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy" , "two", "report_id12", "component" , "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy" , "two", "report_id12", "component2", "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy2", "two", "report_id12", "component" , "value",DateTime.now(), "message")
        )
      , fullCompliance
    )
    val nodeStatus = getNodeStatusByRule(param)
    val aggregated = AggregatedStatusReport(nodeStatus.values.flatMap(_.reports).toSet)

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
            List("one", "two", "three")
          , "rule"
          , 12
          , List(DirectiveExpectedReports("policy", None, false, List(
                     new ValueExpectedReport("component" , ExpectedValueMatch("value","value") :: Nil )
                   , new ValueExpectedReport("component2", ExpectedValueMatch("value","value") :: Nil )
                 ))
               , DirectiveExpectedReports("policy2", None, false, List(
                     new ValueExpectedReport("component" , ExpectedValueMatch("value","value") :: Nil )
                   , new ValueExpectedReport("component2", ExpectedValueMatch("value","value") :: Nil )
                 ))
             )
        )
      , Seq[Reports](
          new ResultSuccessReport(DateTime.now(), "rule", "policy" , "one"  , "report_id12", "component" , "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy" , "one"  , "report_id12", "component2", "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy2", "one"  , "report_id12", "component" , "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy2", "one"  , "report_id12", "component2", "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy" , "two"  , "report_id12", "component" , "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy" , "two"  , "report_id12", "component2", "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy2", "two"  , "report_id12", "component" , "value",DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy" , "three", "report_id12", "component" , "value",DateTime.now(), "message")
        )
      , fullCompliance
    )
    val nodeStatus = getNodeStatusByRule(param)
    val aggregated = AggregatedStatusReport(nodeStatus.values.flatMap(_.reports).toSet)

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
      aggregated.directives("policy").components.filter(_.componentName == "component").head.compliance === ComplianceLevel(success = 3)
    }
    "have detailed rule report for policy-component2 of 67%" in {
      aggregated.directives("policy").components.filter(_.componentName == "component2").head.compliance === ComplianceLevel(success = 2, missing = 1)
    }
    "have detailed rule report for policy2-component2 of 33%" in {
      aggregated.directives("policy2").components.filter(_.componentName == "component2").head.compliance === ComplianceLevel(success = 1, missing = 2)
    }
  }

  "A detailed execution Batch, with two directive, two component, cardinality three, three nodes, including two not completely responding" should {
    val param = (
        buildExpected(
            List("one", "two", "three")
          , "rule"
          , 12
          , List(DirectiveExpectedReports("policy", None, false, List(
                   new ValueExpectedReport("component", List(ExpectedValueMatch("value","value"), ExpectedValueMatch("value2","value2"), ExpectedValueMatch("value3","value3")))
               ))
             )
        )
      , Seq[Reports](
          new ResultSuccessReport(DateTime.now(), "rule", "policy", "one"  , "report_id12", "component", "value" , DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy", "one"  , "report_id12", "component", "value2", DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy", "one"  , "report_id12", "component", "value3", DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy", "two"  , "report_id12", "component", "value" , DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy", "two"  , "report_id12", "component", "value2", DateTime.now(), "message"),
          new ResultSuccessReport(DateTime.now(), "rule", "policy", "three", "report_id12", "component", "value" , DateTime.now(), "message")
        )
      , fullCompliance
    )
    val nodeStatus = getNodeStatusByRule(param)
    val aggregated = AggregatedStatusReport(nodeStatus.values.flatMap(_.reports).toSet)

    "have 3 detailed node rule report" in {
      nodeStatus.size === 3
    }
    "have detailed rule report for policy of 67%" in {
      aggregated.directives("policy").compliance === ComplianceLevel(success = 6, missing = 3)
    }
    "have detailed rule report for policy/component/value of 100%" in {
      aggregated.directives("policy").components.filter(_.componentName == "component").head.componentValues("value").head.compliance ===
        ComplianceLevel(success = 3)
    }
    "have detailed rule report for policy/component/value2 of 67%" in {
      aggregated.directives("policy").components.filter(_.componentName == "component").head.componentValues("value2").head.compliance ===
        ComplianceLevel(success = 2, missing = 1)
    }
    "have detailed rule report for policy/component/value3 of 33%" in {
      aggregated.directives("policy").components.filter(_.componentName == "component").head.componentValues("value3").head.compliance ===
        ComplianceLevel(success = 1, missing = 2)
    }
  }

  "An execution Batch, with one component with a quote in its value, cardinality one, one node" should {

    val param = (
        buildExpected(
            List("one")
          , "rule"
          , 12
          , List(DirectiveExpectedReports("policy", None, false, List(
                  ValueExpectedReport("component", ExpectedValueMatch(("""some\"text"""),("""some\text""") ) :: Nil)
              ))
            )
        )
      , Seq[Reports](new ResultSuccessReport(new DateTime(), "rule", "policy", "one", "report_id12", "component", """some\"text""",new DateTime(), "message"))
      , fullCompliance
    )
    val nodeStatus = getNodeStatusByRule(param)

    "have one detailed reports when we create it with one report" in {
      nodeStatus.size ===1
    }

    "have one detailed success node when we create it with one success report" in {
      nodeStatus.keySet.head === one and
      nodeStatus.head._2.reports.head.compliance.computePercent().success === 100
    }

  }

 "An execution Batch, with one component, one node, but with a component value being a cfengine variable with {, and a an escaped quote as well" should {

    val param = (
        buildExpected(
            List("nodeId")
          , "rule"
          , 12
          , List(DirectiveExpectedReports("policy", None, false, List(
                ValueExpectedReport("component", ExpectedValueMatch("""${sys.workdir}/inputs/\"test""","""${sys.workdir}/inputs/\"test""")  :: Nil)
              ))
            )
        )
      , Seq[Reports](new ResultSuccessReport(new DateTime(), "rule", "policy", "nodeId", "report_id12", "component", """/var/cfengine/inputs/\"test""", new DateTime(), "message"))
      , fullCompliance
    )

    val nodeStatus = getNodeStatusByRule(param)

    "have one detailed reports when we create it with one report" in {
     nodeStatus.size ===1
    }

    "have one detailed success node when we create it with one success report" in {
     nodeStatus.keySet.head === NodeId("nodeId") and
     nodeStatus.head._2.reports.head.compliance.computePercent().success === 100
    }
  }

  "An execution Batch, with one component, one node, but with a component value being a cfengine variable with {, and a quote as well" should {
    val param = (
        buildExpected(
            List("nodeId")
          , "rule"
          , 12
          , List(DirectiveExpectedReports("policy", None, false, List(
                ValueExpectedReport("component", ExpectedValueMatch("""${sys.workdir}/inputs/"test""","""${sys.workdir}/inputs/"test""") :: Nil)
              ))
            )
        )
      , Seq[Reports](new ResultSuccessReport(new DateTime(), "rule", "policy", "nodeId", "report_id12", "component", """/var/cfengine/inputs/"test""", new DateTime(), "message"))
      , fullCompliance
    )
    val nodeStatus = getNodeStatusByRule(param)

    "have one detailed reports when we create it with one report" in {
     nodeStatus.size === 1
    }

    "have one detailed success node when we create it with one success report" in {
     nodeStatus.keySet.head === NodeId("nodeId") and
     nodeStatus.head._2.reports.head.compliance.computePercent().success === 100
    }
  }

   // Test the component part - with NotApplicable
  "A component, with two keys and NotApplicable reports" should {
    val reports = Seq[ResultReports](
        new ResultNotApplicableReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "/var/cfengine", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", "report_id12", "component", "bar", executionTimestamp, "message")
              )

    val expectedComponent = ValueExpectedReport(
        "component"
      , List(ExpectedValueMatch("/var/cfengine","/var/cfengine"), ExpectedValueMatch("bar", "bar"))
    )

    val withGood = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, reports, ReportType.Missing, PolicyMode.Enforce, strictUnexpectedInterpretation).head

    "return a component globally success " in {
      withGood.compliance === ComplianceLevel(success = 1, notApplicable = 1)
    }
    "return a component with two key values " in {
      withGood.componentValues.size === 2
    }
    "return a component with the /var/cfengine in NotApplicable " in {
      withGood.componentValues("/var/cfengine").head.messages.size === 1 and
      withGood.componentValues("/var/cfengine").head.compliance.computePercent().notApplicable === 100
    }
    "return a component with the bar key success " in {
      withGood.componentValues("bar").head.messages.size == 1 and
      withGood.componentValues("bar").head.compliance.computePercent().success === 100
    }
  }

  "performance for mergeCompareByRule" should {
    val nbRuleInit = 12
    val initData = buildDataForMergeCompareByRule("test", nbRuleInit, 12, 4)

    val nodeList = (1 to 100).map("nodeId_" + _).toSeq

    val runData = nodeList.map(buildDataForMergeCompareByRule(_, 15, 12, 5))

    "init correctly" in {
      val result = (ExecutionBatch.mergeCompareByRule _).tupled(initData)
      result.size === nbRuleInit and
      result.toSeq.map(x => x.compliance).map(x => x.success).sum === 576
    }

    "run fast enough" in {
      runData.map(x =>  (ExecutionBatch.mergeCompareByRule _).tupled(x) )

      val t0 = System.currentTimeMillis

      for (i <- 1 to 10) {
        val t0_0 = System.currentTimeMillis
        runData.map(x => (ExecutionBatch.mergeCompareByRule _).tupled(x))
        val t1_1 = System.currentTimeMillis
        logger.trace(s"${i}th call to mergeCompareByRule for ${nodeList.size} nodes took ${t1_1-t0_0}ms")
      }
      val t1 = System.currentTimeMillis
      logger.debug(s"Time to run test is ${t1-t0} ms")
      (t1-t0) must be lessThan( 50000 ) // On my Dell XPS15, this test runs in 7500-8500 ms
    }
  }
}
