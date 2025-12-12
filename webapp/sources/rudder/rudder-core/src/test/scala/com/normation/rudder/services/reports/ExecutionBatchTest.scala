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

import ch.qos.logback.classic.Logger
import com.normation.cfclerk.domain.ReportingLogic
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyModeOverrides
import com.normation.rudder.domain.policies.PolicyTypeName
import com.normation.rudder.domain.policies.PolicyTypes
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.reports.*
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.ComplianceModeName.FullCompliance
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.reports.execution.AgentRunId
import com.normation.rudder.reports.execution.AgentRunWithNodeConfig
import com.normation.rudder.services.policies.PolicyId
import com.normation.rudder.services.reports.ExecutionBatch.ComputeComplianceTimer
import com.normation.rudder.services.reports.ExecutionBatch.MergeInfo
import com.softwaremill.quicklens.*
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.ISODateTimeFormat
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import scala.annotation.nowarn

@nowarn("msg=a type was inferred to be `\\w+`; this may indicate a programming error.")
@RunWith(classOf[JUnitRunner])
class ExecutionBatchTest extends Specification {
  implicit private def str2directiveId(s: String): DirectiveId = DirectiveId(DirectiveUid(s))
  implicit private def str2ruleId(s:      String): RuleId      = RuleId(RuleUid(s))
  implicit private def str2nodeId(s:      String): NodeId      = NodeId(s)
  implicit private def str2ruleUid(s:     String): RuleUid     = RuleUid(s)

  // a logger for timing information
  val logger: Logger = org.slf4j.LoggerFactory.getLogger("timing-test").asInstanceOf[ch.qos.logback.classic.Logger]
  // set to trace to see timing
  logger.setLevel(ch.qos.logback.classic.Level.OFF)
  // also disable executionbatch since we are testing error cases:
  org.slf4j.LoggerFactory
    .getLogger("com.normation.rudder.services.reports.ExecutionBatch")
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.OFF)

  import ReportType.*

  val executionTimestamp = new DateTime(DateTimeZone.UTC)

  val globalPolicyMode: GlobalPolicyMode = GlobalPolicyMode(PolicyMode.Enforce, PolicyModeOverrides.Always)
  val mode:             NodeModeConfig   = NodeModeConfig(
    GlobalComplianceMode(FullCompliance),
    AgentRunInterval(None, 5, 14, 5, 4),
    None,
    globalPolicyMode,
    Some(PolicyMode.Enforce)
  )

  /**
   * Construct all the data for mergeCompareByRule, to have pleinty of data
   */
  def buildDataForMergeCompareByRule(
      nodeId:          String,
      nbRules:         Int,
      nbDirectives:    Int,
      nbReportsPerDir: Int
  ): (MergeInfo, IndexedSeq[ResultSuccessReport], NodeExpectedReports, NodeExpectedReports, List[OverriddenPolicy]) = {
    val ruleIds      = (1 to nbRules).map("rule_id_" + _ + nodeId).toSeq
    val directiveIds = (1 to nbDirectives).map("directive_id_" + _ + nodeId).toSeq
    val dirPerRule   = ruleIds.map(rule => (RuleId(rule), directiveIds.map(dir => DirectiveId(DirectiveUid(dir + "@@" + rule)))))

    val treeOfData = dirPerRule map {
      case (ruleId, directives) =>
        (ruleId, directives.map(dirId => (dirId, (1 to nbReportsPerDir).map("component" + dirId.serialize + _).toSeq)))
    }

    // create RuleExpectedReports
    val expectedReports = treeOfData.map {
      case (ruleId, directives) =>
        RuleExpectedReports(
          ruleId,
          directives.map {
            case (directiveId, components) =>
              DirectiveExpectedReports(
                directiveId,
                policyMode = None,
                PolicyTypes.rudderBase,
                components = components
                  .map(componentName =>
                    ValueExpectedReport(componentName, ExpectedValueMatch(componentName, componentName) :: Nil)
                  )
                  .toList
              )
          }.toList
        )
    }
    val now             = DateTime.now

    val executionReports = expectedReports.flatMap {
      case RuleExpectedReports(ruleId, directives) =>
        def mapComponent(c: ComponentExpectedReport, directiveId: DirectiveId): List[ResultSuccessReport] = {
          c match {
            case ValueExpectedReport(componentName, componentsValues) =>
              componentsValues.map {
                case ExpectedValueId(value, _)    =>
                  ResultSuccessReport(now, ruleId, directiveId, nodeId, "report_id", componentName, value, now, "empty text")
                case ExpectedValueMatch(value, _) =>
                  ResultSuccessReport(now, ruleId, directiveId, nodeId, "report_id", componentName, value, now, "empty text")
              }
            case BlockExpectedReport(componentName, logic, sub, _)    =>
              sub.map(mapComponent(_, directiveId)).flatten
          }
        }

        directives.flatMap {
          case DirectiveExpectedReports(directiveId, _, _, components) =>
            components.flatMap(mapComponent(_, directiveId))
        }
    }

    val nodeConfigId = NodeConfigId("version_" + nodeId)

    val nodeExpectedReport = NodeExpectedReports(
      NodeId(nodeId),
      nodeConfigId,
      now,
      None,
      mode,
      expectedReports.toList,
      Nil
    )

    val mergeInfo = MergeInfo(NodeId(nodeId), Some(now), Some(nodeConfigId), now.plus(100))

    (mergeInfo, executionReports, nodeExpectedReport, nodeExpectedReport, Nil)

  }

  def buildExpected(
      nodeIds:    Seq[String],
      ruleId:     String,
      serial:     Int,
      directives: List[DirectiveExpectedReports]
  ): Map[NodeId, NodeExpectedReports] = {
    val globalPolicyMode = GlobalPolicyMode(PolicyMode.Audit, PolicyModeOverrides.Always)
    val now              = DateTime.now
    val mode             = NodeModeConfig(
      GlobalComplianceMode(FullCompliance),
      AgentRunInterval(None, 5, 14, 5, 4),
      None,
      globalPolicyMode,
      Some(PolicyMode.Enforce)
    )
    nodeIds.map { id =>
      (NodeId(id) -> NodeExpectedReports(
        NodeId(id),
        NodeConfigId("version_" + id),
        now,
        None,
        mode,
        List(RuleExpectedReports(RuleId(ruleId), directives)),
        Nil
      ))
    }.toMap
  }

  def getNodeStatusReportsByRule(
      nodeExpectedReports: Map[NodeId, NodeExpectedReports],
      reportsParam:        Seq[Reports] // this is the agent execution interval, in minutes
  ): Map[NodeId, NodeStatusReport] = {

    val res = (for {
      (nodeId, expected) <- nodeExpectedReports.toSeq
    } yield {
      val runTime = reportsParam.headOption.map(_.executionTimestamp).getOrElse(DateTime.now)
      val info    = nodeExpectedReports(nodeId)
      val runInfo = ComputeCompliance(runTime, info, runTime.plusMinutes(5))

      (nodeId, ExecutionBatch.getNodeStatusReports(nodeId, runInfo, reportsParam))
    })

    res.toMap
  }

  val getNodeStatusByRule: ((Map[NodeId, NodeExpectedReports], Seq[Reports])) => Map[NodeId, NodeStatusReport] =
    (getNodeStatusReportsByRule).tupled
  val one:                 NodeId                                                                              = NodeId("one")

  sequential

  "On change only, we" should {
    // we expect two reports
    val allErrors = Seq[ResultReports](
      new ResultErrorReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "message"
      ),
      new ResultErrorReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "bar",
        executionTimestamp,
        "message"
      )
    )

    val mixedReports = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "message"
      )
      // report on bar is success so not reported here
    )

    // both components are success so none is returned
    val allSuccesses = Seq[ResultReports]()

    val expectedComponent = new ValueExpectedReport(
      "component",
      ExpectedValueMatch("foo", "foo") :: ExpectedValueMatch("bar", "bar") :: Nil
    )

    val withMixed   = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        mixedReports,
        ReportType.EnforceSuccess, // change only on enforce
        PolicyMode.Enforce
      )
      .head
    val withErrors  = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        allErrors,
        ReportType.EnforceSuccess, // change only on enforce
        PolicyMode.Enforce
      )
      .head
    val withSuccess = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        allSuccesses,
        ReportType.EnforceSuccess, // change only on enforce
        PolicyMode.Enforce
      )
      .head

    "all success must return a component globally success with two keys/values" in {
      (withSuccess.compliance === ComplianceLevel(success = 2)) and
      (withSuccess.componentValues.size === 2) and
      (withSuccess.componentValues("foo").head.messages.size === 1) and
      (withSuccess.componentValues("foo").head.messages.head.reportType === EnforceSuccess) and
      (withSuccess.componentValues("bar").head.messages.size === 1) and
      (withSuccess.componentValues("bar").head.messages.head.reportType === EnforceSuccess)
    }
    "mixe success/repair must return a component globally repaired with two keys/values" in {
      (withMixed.compliance === ComplianceLevel(success = 1, repaired = 1)) and
      (withMixed.componentValues.size === 2) and
      (withMixed.componentValues("foo").head.messages.size === 1) and
      (withMixed.componentValues("foo").head.messages.head.reportType === EnforceRepaired) and
      (withMixed.componentValues("bar").head.messages.size === 1) and
      (withMixed.componentValues("bar").head.messages.head.reportType === EnforceSuccess)
    }
    "all error must return a component globally error with two keys/values" in {
      (withErrors.compliance === ComplianceLevel(error = 2)) and
      (withErrors.componentValues.size === 2) and
      (withErrors.componentValues("foo").head.messages.size === 1) and
      (withErrors.componentValues("foo").head.messages.head.reportType === EnforceError) and
      (withErrors.componentValues("bar").head.messages.size === 1) and
      (withErrors.componentValues("bar").head.messages.head.reportType === EnforceError)
    }
  }

  "An old run for a different config id should lead to N/A, not UnexpectedVersion" should {

    // the expected reports will have a generation datetime of "now", we want something stable
    val (nodeId, expectedReports) = buildExpected(
      List("one"),
      "rule",
      12,
      List(
        DirectiveExpectedReports(
          "policy",
          None,
          PolicyTypes.rudderBase,
          components = List(
            new ValueExpectedReport("component", ExpectedValueMatch("value", "value") :: Nil)
          )
        )
      )
    ).head

    // For the node, we have only an old run from last week, because the server was decommissioned at that time
    val oldRunTime = ISODateTimeFormat.dateTimeParser().parseDateTime("2023-01-01T15:15:00+00:00")

    // we had a config ID for the old run (just it does not exist anymore in base)
    val oldRunExpectedReport = expectedReports.copy(
      nodeConfigId = NodeConfigId("configId-old-run"),
      beginDate = oldRunTime,
      endDate = Some(oldRunTime.plusHours(10)) // next generation was 10h after and closed that expected report span
    )

    // expected reports were generated 12 days, 5 hours after old run
    val generationTime           = oldRunTime.minusDays(11).plusHours(3)
    val generatedExpectedReports = expectedReports.copy(beginDate = generationTime)
    val currentNodeConfigs       = Map(nodeId -> Some(generatedExpectedReports))

    "if we don't have the old config for expected report" in {
      val lastKnownRun = AgentRunWithNodeConfig(
        AgentRunId(nodeId, oldRunTime),
        Some((oldRunExpectedReport.nodeConfigId, None)), // we only have the old config id, not the actual config
        42
      )

      val runs = Map(nodeId -> Some(lastKnownRun))

      // the old run is not in base anymore, the new one is
      val runInfo = {
        Map(
          nodeId -> Some(List(NodeConfigIdInfo(generatedExpectedReports.nodeConfigId, generatedExpectedReports.beginDate, None)))
        )
      }

      val res = ExecutionBatch.computeNodesRunInfo(runs, currentNodeConfigs, runInfo, DateTime.now(DateTimeZone.UTC))(nodeId)

      // here, the end date depend on run time, so we need to check by case
      res match {
        case NoReportInInterval(exp, t) => exp must beEqualTo(generatedExpectedReports)
        case x                          => ko(s"We were expected NoReportInInterval case but got: ${x}")
      }
    }

    "if we have the old expected reports still in base" in {
      val lastKnownRun = AgentRunWithNodeConfig(
        AgentRunId(nodeId, oldRunTime),
        Some((oldRunExpectedReport.nodeConfigId, Some(oldRunExpectedReport))),
        42
      )
      val runs         = Map(nodeId -> Some(lastKnownRun))

      // the old run is not in base anymore, the new one is
      val runInfo = {
        Map(
          nodeId -> Some(
            List(
              NodeConfigIdInfo(
                oldRunExpectedReport.nodeConfigId,
                oldRunExpectedReport.beginDate,
                oldRunExpectedReport.endDate
              ),
              NodeConfigIdInfo(generatedExpectedReports.nodeConfigId, generatedExpectedReports.beginDate, None)
            )
          )
        )
      }

      val res = ExecutionBatch.computeNodesRunInfo(runs, currentNodeConfigs, runInfo, DateTime.now(DateTimeZone.UTC))(nodeId)

      res match {
        case NoReportInInterval(exp, t) => exp must beEqualTo(generatedExpectedReports)
        case x                          => ko(s"We were expected NoReportInInterval case but got: ${x}")
      }
    }
  }

  // Test the component part
  "A component, with two different keys" should {
    val reports = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "bar",
        executionTimestamp,
        "message"
      )
    )

    val badReports = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "bar",
        executionTimestamp,
        "message"
      )
    )

    val expectedComponent = new ValueExpectedReport(
      "component",
      ExpectedValueMatch("foo", "foo") :: ExpectedValueMatch("bar", "bar") :: Nil
    )

    val withGood = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        reports,
        ReportType.Missing,
        PolicyMode.Enforce
      )
      .head
    val withBad  = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        badReports,
        Missing,
        PolicyMode.Enforce
      )
      .head

    "return a component globally repaired " in {
      withGood.compliance === ComplianceLevel(success = 1, repaired = 1)
    }
    "return a component with two key values " in {
      withGood.componentValues.size === 2
    }
    "return a component with the key values foo which is repaired " in {
      withGood.componentValues("foo").head.messages.size === 1 and
      withGood.componentValues("foo").head.messages.head.reportType === EnforceRepaired
    }
    "return a component with the key values bar which is a success " in {
      withGood.componentValues("bar").head.messages.size === 1 and
      withGood.componentValues("bar").head.messages.head.reportType === EnforceSuccess
    }

    "only one reports in plus, mark the whole key unexpected" in {
      withBad.compliance === ComplianceLevel(success = 1, unexpected = 2)
    }
    "with bad reports return a component with two key values " in {
      withBad.componentValues.size === 2
    }
    "with bad reports return a component with the key values foo which is unknwon " in {
      withBad.componentValues("foo").head.messages.size === 2 and
      withBad.componentValues("foo").head.messages.head.reportType === Unexpected
    }
    "with bad reports return a component with the key values bar which is a success " in {
      withBad.componentValues("bar").head.messages.size === 1 and
      withBad.componentValues("bar").head.messages.head.reportType === EnforceSuccess
    }
  }

  "A component, with a simple value and testing missing/no answer difference" should {
    val missing  = Seq[ResultReports](
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "other key",
        executionTimestamp,
        "message"
      )
    )
    val noAnswer = Seq[ResultReports]()

    val expectedComponent = new ValueExpectedReport(
      "component",
      ExpectedValueMatch("some key", "some key") :: ExpectedValueMatch("other key", "other key") :: Nil
    )

    "give a no answer if none report at all" in {
      val res = ExecutionBatch
        .checkExpectedComponentWithReports(
          expectedComponent,
          noAnswer,
          ReportType.NoAnswer,
          PolicyMode.Enforce
        )
        .head
      res.compliance === ComplianceLevel(noAnswer = 2)
    }
    "give a missing if some reports present" in {
      val res = ExecutionBatch
        .checkExpectedComponentWithReports(
          expectedComponent,
          missing,
          ReportType.Missing,
          PolicyMode.Enforce
        )
        .head
      res.compliance === ComplianceLevel(success = 1, missing = 1)
    }
  }

  // Test the component part
  "A component, with a None keys" should {
    val reports = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "None",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "None",
        executionTimestamp,
        "message"
      )
    )

    val badReports = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "None",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "None",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "None",
        executionTimestamp,
        "message"
      )
    )

    val expectedComponent = new ValueExpectedReport(
      "component",
      ExpectedValueMatch("None", "None") :: ExpectedValueMatch("None", "None") :: Nil
    )
    val withGood          = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        reports,
        ReportType.Missing,
        PolicyMode.Enforce
      )
      .head
    val withBad           = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        badReports,
        ReportType.Missing,
        PolicyMode.Enforce
      )
      .head

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
      withBad
        .componentValues("None")
        .head
        .messages
        .forall(x => (x.reportType === Unexpected) or (x.reportType === EnforceRepaired))
    }
  }

  "A block, in 'weighted' report" should {
    val reports = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "bar",
        executionTimestamp,
        "message"
      )
    )

    val badReports = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "bar",
        executionTimestamp,
        "message"
      )
    )

    val expectedComponent = BlockExpectedReport(
      "block",
      ReportingLogic.WeightedReport,
      new ValueExpectedReport(
        "component",
        ExpectedValueMatch("foo", "foo") :: ExpectedValueMatch("bar", "bar") :: Nil
      ) :: Nil,
      None
    )

    val withGood = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        reports,
        ReportType.Missing,
        PolicyMode.Enforce
      )
      .head
    val withBad  = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        badReports,
        Missing,
        PolicyMode.Enforce
      )
      .head

    "return a component globally repaired " in {
      withGood.compliance === ComplianceLevel(repaired = 1, success = 1)
    }
    "return a component with two key values " in {
      withGood.componentValues.size === 2
    }
    "return a component with the key values foo which is repaired " in {
      withGood.componentValues("foo").head.messages.size === 1 and
      withGood.componentValues("foo").head.messages.head.reportType === EnforceRepaired
    }
    "return a component with the key values bar which is a success " in {
      withGood.componentValues("bar").head.messages.size === 1 and
      withGood.componentValues("bar").head.messages.head.reportType === EnforceSuccess
    }

    "only one reports in addition, mark only foo (ie repaired) unexpected" in {
      withBad.compliance === ComplianceLevel(unexpected = 2, success = 1)
    }
    "with bad reports return a component with two key values " in {
      withBad.componentValues.size === 2
    }
    "with bad reports return a component with the key values foo which is unexpected " in {
      withBad.componentValues("foo").head.messages.size === 2 and
      withBad.componentValues("foo").head.messages.head.reportType === Unexpected
    }
    "with bad reports return a component with the key values bar which is a success " in {
      withBad.componentValues("bar").head.messages.size === 1 and
      withBad.componentValues("bar").head.messages.head.reportType === EnforceSuccess
    }
  }

  "A block, in 'worst case weight sum' report" should {
    val reports = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "bar",
        executionTimestamp,
        "message"
      )
    )

    val badReports = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "bar",
        executionTimestamp,
        "message"
      )
    )

    val expectedComponent = BlockExpectedReport(
      "block",
      ReportingLogic.WorstReportWeightedSum,
      new ValueExpectedReport(
        "component",
        ExpectedValueMatch("foo", "foo") :: ExpectedValueMatch("bar", "bar") :: Nil
      ) :: Nil,
      None
    )

    val withGood = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        reports,
        ReportType.Missing,
        PolicyMode.Enforce
      )
      .head
    val withBad  = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        badReports,
        Missing,
        PolicyMode.Enforce
      )
      .head

    "return a component globally repaired " in {
      withGood.compliance === ComplianceLevel(repaired = 2)
    }
    "return a component with two key values " in {
      withGood.componentValues.size === 2
    }
    "return a component with the key values foo which is repaired " in {
      withGood.componentValues("foo").head.messages.size === 1 and
      withGood.componentValues("foo").head.messages.head.reportType === EnforceRepaired
    }
    "return a component with the key values bar which is a success " in {
      withGood.componentValues("bar").head.messages.size === 1 and
      withGood.componentValues("bar").head.messages.head.reportType === EnforceSuccess
    }

    "only one reports in addition, mark the whole weighted block unexpected" in {
      withBad.compliance === ComplianceLevel(unexpected = 3)
    }
    "with bad reports return a component with two key values " in {
      withBad.componentValues.size === 2
    }
    "with bad reports return a component with the key values foo which is unexpected " in {
      withBad.componentValues("foo").head.messages.size === 2 and
      withBad.componentValues("foo").head.messages.head.reportType === Unexpected
    }
    "with bad reports return a component with the key values bar which is a success " in {
      withBad.componentValues("bar").head.messages.size === 1 and
      withBad.componentValues("bar").head.messages.head.reportType === EnforceSuccess
    }
  }

  "A block, in 'worst case weight one' report" should {
    val reports = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "bar",
        executionTimestamp,
        "message"
      )
    )

    val badReports = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "bar",
        executionTimestamp,
        "message"
      )
    )

    val expectedComponent = BlockExpectedReport(
      "block",
      ReportingLogic.WorstReportWeightedOne,
      new ValueExpectedReport(
        "component",
        ExpectedValueMatch("foo", "foo") :: ExpectedValueMatch("bar", "bar") :: Nil
      ) :: Nil,
      None
    )

    val withGood = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        reports,
        ReportType.Missing,
        PolicyMode.Enforce
      )
      .head
    val withBad  = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        badReports,
        Missing,
        PolicyMode.Enforce
      )
      .head

    "return a component globally repaired " in {
      withGood.compliance === ComplianceLevel(repaired = 1)
    }
    "return a component with two key values " in {
      withGood.componentValues.size === 2 // yes, still 2 here, we keep access to sub values to show details
    }
    "return a component with the key values foo which is repaired " in {
      withGood.componentValues("foo").head.messages.size === 1 and
      withGood.componentValues("foo").head.messages.head.reportType === EnforceRepaired
    }
    "return a component with the key values bar which is a success " in {
      withGood.componentValues("bar").head.messages.size === 1 and
      withGood.componentValues("bar").head.messages.head.reportType === EnforceSuccess
    }

    "only one reports in addition, mark the whole block unexpected with weight 1" in {
      withBad.compliance === ComplianceLevel(unexpected = 1)
    }
    "with bad reports return a component with two key values " in {
      withBad.componentValues.size === 2
    }
    "with bad reports return a component with the key values foo which is unexpected " in {
      withBad.componentValues("foo").head.messages.size === 2 and
      withBad.componentValues("foo").head.messages.head.reportType === Unexpected
    }
    "with bad reports return a component with the key values bar which is a success " in {
      withBad.componentValues("bar").head.messages.size === 1 and
      withBad.componentValues("bar").head.messages.head.reportType === EnforceSuccess
    }
  }

  "A block, with reporting focus " should {
    val reports = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_0",
        "component",
        "foo",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_1",
        "component1",
        "bar",
        executionTimestamp,
        "message"
      )
    )

    val badReports = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_1",
        "component1",
        "foo",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_0",
        "component",
        "foo",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_0",
        "component",
        "bar",
        executionTimestamp,
        "message"
      )
    )

    val expectedComponent = BlockExpectedReport(
      "block",
      ReportingLogic.FocusReport("component"),
      new ValueExpectedReport(
        "component",
        ExpectedValueId("foo", "report_0") :: Nil
      ) :: new ValueExpectedReport(
        "component1",
        ExpectedValueId("bar", "report_1") :: Nil
      ) :: Nil,
      None
    )

    val withGood = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        reports,
        ReportType.Missing,
        PolicyMode.Enforce
      )
      .head
    val withBad  = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        badReports,
        Missing,
        PolicyMode.Enforce
      )
      .head

    val goodBlock = withGood.asInstanceOf[BlockStatusReport]

    "return a repaired block " in {
      withGood.compliance === ComplianceLevel(repaired = 1)
    }
    "return a component with two key values" in {
      withGood.componentValues.size === 2
    }
    "return a component with the key values foo which is repaired " in {
      goodBlock.componentValues("foo").head.messages.size === 1 and
      goodBlock.componentValues("foo").head.messages.head.reportType === EnforceRepaired
    }
    "return a component with the key values bar which is a success " in {
      goodBlock.componentValues("bar").head.messages.size === 1 and
      goodBlock.componentValues("bar").head.messages.head.reportType === EnforceSuccess
    }

    // bad:
    // we have one repair and one success for 'component' (EVEN IF 'bar' is not the expected value  because
    // we only match on reportId (see https://issues.rudder.io/issues/23084)
    // we have two errors for 'component1': missing the actual 'bar' report, and unexpected 'foo'

    "focus on 'component' and get its two reports" in {
      withBad.compliance === ComplianceLevel(repaired = 1, success = 1)
    }
    "with bad reports return 3 values (no unexpected)" in {
      withBad.componentValues.size === 3
    }
    "with bad reports return a component with the key values foo with one unexpected and one repaired " in {
      withBad.componentValues("foo").flatMap(_.messages.map(_.reportType)) === EnforceRepaired :: EnforceRepaired :: Nil
    }
    "with bad reports return a component with the key values bar which is a success " in {
      withBad.componentValues("bar").flatMap(_.messages.map(_.reportType)) === EnforceSuccess :: Nil
    }
  }

  "Sub block with same component names are authorised, with reporting sum " should {
    val reports = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component1",
        "b1c1",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component2",
        "b1c2",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component1",
        "b2c1",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component2",
        "b2c2",
        executionTimestamp,
        "message"
      )
    )

    val badReports = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component1",
        "b1c1",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component2",
        "b1c2",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component1",
        "b2c1",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component2",
        "b2c2",
        executionTimestamp,
        "message"
      ), // bad ones

      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component2",
        "b2c2",
        executionTimestamp,
        "message"
      )
    )

    val expectedComponent        = BlockExpectedReport(
      "blockRoot",
      ReportingLogic.WeightedReport,
      BlockExpectedReport(
        "block1",
        ReportingLogic.WeightedReport,
        new ValueExpectedReport(
          "component1",
          ExpectedValueMatch("b1c1", "b1c1") :: Nil
        ) :: new ValueExpectedReport(
          "component2",
          ExpectedValueMatch("b1c2", "b1c2") :: Nil
        ) :: Nil,
        None
      ) :: BlockExpectedReport(
        "block2",
        ReportingLogic.WeightedReport,
        new ValueExpectedReport(
          "component1",
          ExpectedValueMatch("b2c1", "b2c1") :: Nil
        ) :: new ValueExpectedReport(
          "component2",
          ExpectedValueMatch("b2c2", "b2c2") :: Nil
        ) :: Nil,
        None
      ) :: Nil,
      None
    )
    val directiveExpectedReports = {
      DirectiveExpectedReports(
        DirectiveId(DirectiveUid("policy")),
        None,
        PolicyTypes.rudderBase,
        components = expectedComponent :: Nil
      )
    }
    val ruleExpectedReports      = RuleExpectedReports(RuleId("cr"), directiveExpectedReports :: Nil)
    val mergeInfo                = MergeInfo(NodeId("nodeId"), None, None, DateTime.now(DateTimeZone.UTC))

    val withGood = ExecutionBatch
      .getComplianceForRule(
        mergeInfo,
        reports,
        mode,
        ruleExpectedReports,
        new ComputeComplianceTimer(),
        Nil
      )
      .collect { case r => r.directives("policy") }
    val withBad  = ExecutionBatch
      .getComplianceForRule(
        mergeInfo,
        badReports,
        mode,
        ruleExpectedReports,
        new ComputeComplianceTimer(),
        Nil
      )
      .collect { case r => r.directives("policy") }

    "return a success block " in {
      (withGood.size === 1) and
      (withGood.head.compliance === ComplianceLevel(success = 1, repaired = 3))
    }
    "return one root component with 4 key values " in {
      withGood.head.components.filter(_.componentName == "blockRoot").head.componentValues.size === 4
    }
    "return 3 component with the key values b1c1,b1c2,b2c2 which is repaired " in {
      val block1 = withGood.head.components
        .filter(_.componentName == "blockRoot")
        .head
        .asInstanceOf[BlockStatusReport]
        .subComponents
        .find(_.componentName == "block1")
        .get
      val block2 = withGood.head.components
        .filter(_.componentName == "blockRoot")
        .head
        .asInstanceOf[BlockStatusReport]
        .subComponents
        .find(_.componentName == "block2")
        .get

      (block1.componentValues("b1c1").head.messages.size === 1) and
      (block1.componentValues("b1c1").head.messages.head.reportType === EnforceRepaired) and
      (block1.componentValues("b1c2").head.messages.size === 1) and
      (block1.componentValues("b1c2").head.messages.head.reportType === EnforceRepaired) and
      (block2.componentValues("b2c2").head.messages.size === 1) and
      (block2.componentValues("b2c2").head.messages.head.reportType === EnforceRepaired)
    }
    "return a component with the key values b2c1 which is a success " in {
      val block2 = withGood.head.components
        .filter(_.componentName == "blockRoot")
        .head
        .asInstanceOf[BlockStatusReport]
        .subComponents
        .find(_.componentName == "block2")
        .get
      block2.componentValues("b2c1").head.messages.size === 1 and
      block2.componentValues("b2c1").head.messages.head.reportType === EnforceSuccess
    }

    "only one reports in plus, mark the whole key unexpected" in {
      (withBad.size === 1) and
      (withBad.head.compliance === ComplianceLevel(success = 1, unexpected = 2, repaired = 2))
    }

  }

  // see correction with report id in next test
  "Sub block with looping component and same component names are not correctly reported, see https://issues.rudder.io/issues/20071" should {
    val reportsWithLoop = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id_b1c1",
        "component1",
        "b1c1",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id_b1c2",
        "component2",
        "b1c2",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id_b2c1",
        "component1",
        "b2c1",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id_b2c2",
        "component2",
        "loop1",
        executionTimestamp,
        "message_1"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id_b2c2",
        "component2",
        "loop2",
        executionTimestamp,
        "message_2"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id_b2c2",
        "component2",
        "loop3",
        executionTimestamp,
        "message_3"
      )
    )

    val expectedComponent        = BlockExpectedReport(
      "blockRoot",
      ReportingLogic.WeightedReport,
      BlockExpectedReport(
        "block1",
        ReportingLogic.WeightedReport,
        new ValueExpectedReport(
          "component1",
          ExpectedValueMatch("b1c1", "b1c1") :: Nil
        ) :: new ValueExpectedReport(
          "component2",
          ExpectedValueMatch("b1c2", "b1c2") :: Nil
        ) :: Nil,
        None
      ) :: BlockExpectedReport(
        "block2",
        ReportingLogic.WeightedReport,
        new ValueExpectedReport(
          "component1",
          ExpectedValueMatch("b2c1", "b2c1") :: Nil
        ) :: new ValueExpectedReport(
          "component2",
          ExpectedValueMatch("${loop}", "${loop}") :: Nil
        ) :: Nil,
        None
      ) :: Nil,
      None
    )
    val directiveExpectedReports = {
      DirectiveExpectedReports(
        DirectiveId(DirectiveUid("policy")),
        policyMode = None,
        PolicyTypes.rudderBase,
        components = expectedComponent :: Nil
      )
    }
    val ruleExpectedReports      = RuleExpectedReports(RuleId("cr"), directiveExpectedReports :: Nil)
    val mergeInfo                = MergeInfo(NodeId("nodeId"), None, None, DateTime.now(DateTimeZone.UTC))

    val withLoop = ExecutionBatch
      .getComplianceForRule(
        mergeInfo,
        reportsWithLoop,
        mode,
        ruleExpectedReports,
        new ComputeComplianceTimer(),
        Nil
      )
      .collect { case r => r.directives("policy") }

    "return one too many repaired because it is match two time " in {
      (withLoop.size === 1) and
      (withLoop.head.compliance === ComplianceLevel(success = 4, repaired = 3))
    }
    "return one root component with 4 key values " in {
      withLoop.head.components.filter(_.componentName == "blockRoot").head.componentValues.size === 4
    }
    "return 1 loop component with the key values loopX which is success, and one too many time because we don't count correctly" in {
      val block2 = withLoop.head.components
        .filter(_.componentName == "blockRoot")
        .head
        .asInstanceOf[BlockStatusReport]
        .subComponents
        .find(_.componentName == "block2")
        .get

      (block2.componentValues("${loop}").head.messages.size === 4)
    }

  }

  // see above test for the case without reportid which is wrongly reported
  // we also test that when keyvalues are identical:
  // - we correctly split appart things with same component name and keyvalue
  // - for a given component name, different key values are splitted appart,
  // - for same component name, key value, then messages are merged and status is the worst
  "Sub block with looping component and same component names are correctly reported with reportid, see https://issues.rudder.io/issues/20071" should {
    val reportsWithLoop = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id_b1c1",
        "component",
        "keyvalue",
        executionTimestamp,
        "message c1"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id_b1c2",
        "component",
        "keyvalue",
        executionTimestamp,
        "message c2"
      ), // following are 3 iterations for b2c1
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id_b2c1",
        "component",
        "keyvalue",
        executionTimestamp,
        "message_1 loop1"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id_b2c1",
        "component",
        "keyvalue",
        executionTimestamp,
        "message_2 loop1"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id_b2c1",
        "component",
        "loop",
        executionTimestamp,
        "message_3 loop1"
      ), // following is 1 iterations for b2c2
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id_b2c2",
        "component",
        "loop",
        executionTimestamp,
        "message_1 loop2"
      )
    )

    val expectedComponent        = BlockExpectedReport(
      "blockRoot",
      ReportingLogic.WeightedReport,
      BlockExpectedReport(
        "block1",
        ReportingLogic.WeightedReport,
        new ValueExpectedReport(
          "component",
          ExpectedValueId("keyvalue", "report_id_b1c1") :: Nil
        ) :: new ValueExpectedReport(
          "component",
          ExpectedValueId("keyvalue", "report_id_b1c2") :: Nil
        ) :: Nil,
        None
      ) :: BlockExpectedReport(
        "block2",
        ReportingLogic.WeightedReport,
        new ValueExpectedReport(
          "component",
          ExpectedValueId("${loop1}", "report_id_b2c1") :: Nil
        ) :: new ValueExpectedReport(
          "component",
          ExpectedValueId("${loop2}", "report_id_b2c2") :: Nil
        ) :: Nil,
        None
      ) :: Nil,
      None
    )
    val directiveExpectedReports = {
      DirectiveExpectedReports(
        DirectiveId(DirectiveUid("policy")),
        policyMode = None,
        PolicyTypes.rudderBase,
        components = expectedComponent :: Nil
      )
    }
    val ruleExpectedReports      = RuleExpectedReports(RuleId("cr"), directiveExpectedReports :: Nil)
    val mergeInfo                = MergeInfo(NodeId("nodeId"), None, None, DateTime.now(DateTimeZone.UTC))

    val withLoop = ExecutionBatch
      .getComplianceForRule(
        mergeInfo,
        reportsWithLoop,
        mode,
        ruleExpectedReports,
        new ComputeComplianceTimer(),
        Nil
      )
      .collect { case r => r.directives("policy") }

    "return the correct number of repaired" in {
      (withLoop.size === 1) and
      (withLoop.head.compliance === ComplianceLevel(success = 3, repaired = 3))
    }

    "correctly split apart component with same name & diff reportId, and merge other case of same component name" in {
      // note that in 7.X branches, we don't use reportId yet - see https://issues.rudder.io/issues/23084

      val root = withLoop.head.components
        .filter(_.componentName == "blockRoot")
        .head
        .asInstanceOf[BlockStatusReport]

      val expectedRoot = {
        ComponentValueStatusReport(
          "keyvalue",
          "keyvalue",
          "report_id_b1c1",
          List(MessageStatusReport(EnforceRepaired, Some("message c1")))
        ) ::
        ComponentValueStatusReport(
          "keyvalue",
          "keyvalue",
          "report_id_b1c2",
          List(MessageStatusReport(EnforceSuccess, Some("message c2")))
        ) ::
        ComponentValueStatusReport(
          "keyvalue",
          "${loop1}",
          "report_id_b2c1",
          List(
            MessageStatusReport(EnforceSuccess, Some("message_1 loop1")),
            MessageStatusReport(EnforceRepaired, Some("message_2 loop1"))
          )
        ) ::
        ComponentValueStatusReport(
          "loop",
          "${loop1}",
          "report_id_b2c1",
          List(MessageStatusReport(EnforceSuccess, Some("message_3 loop1")))
        ) ::
        ComponentValueStatusReport(
          "loop",
          "${loop2}",
          "report_id_b2c2",
          List(MessageStatusReport(EnforceRepaired, Some("message_1 loop2")))
        ) ::
        Nil
      }

      root.componentValues must containTheSameElementsAs(expectedRoot)
    }

    "correctly find the status for a given multi-reports component" in {
      val s = withLoop.head.getByReportId("report_id_b2c1").filter(_.componentValue == "keyvalue").map(_.status)

      (s.size === 1) and
      (s.head === EnforceRepaired)
    }
  }

  "Sub block with same component names are authorised, with reporting focus " should {
    val reports = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component1",
        "b1c1",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component2",
        "b1c2",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component1",
        "b2c1",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component2",
        "b2c2",
        executionTimestamp,
        "message"
      )
    )

    val badReports = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component1",
        "b1c1",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component2",
        "b1c2",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component1",
        "b2c1",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component2",
        "b2c2",
        executionTimestamp,
        "message"
      ), // bad ones

      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component2",
        "b2c2",
        executionTimestamp,
        "message"
      )
    )

    val expectedComponent        = BlockExpectedReport(
      "blockRoot",
      ReportingLogic.FocusReport("block2"),
      BlockExpectedReport(
        "block1",
        ReportingLogic.FocusReport("component1"),
        new ValueExpectedReport(
          "component1",
          ExpectedValueMatch("b1c1", "b1c1") :: Nil
        ) :: new ValueExpectedReport(
          "component2",
          ExpectedValueMatch("b1c2", "b1c2") :: Nil
        ) :: Nil,
        None
      ) :: BlockExpectedReport(
        "block2",
        ReportingLogic.FocusReport("component1"),
        new ValueExpectedReport(
          "component1",
          ExpectedValueMatch("b2c1", "b2c1") :: Nil
        ) :: new ValueExpectedReport(
          "component2",
          ExpectedValueMatch("b2c2", "b2c2") :: Nil
        ) :: Nil,
        None
      ) :: Nil,
      None
    )
    val directiveExpectedReports = {
      DirectiveExpectedReports(
        DirectiveId(DirectiveUid("policy")),
        None,
        PolicyTypes.rudderBase,
        components = expectedComponent :: Nil
      )
    }
    val ruleExpectedReports      = RuleExpectedReports(RuleId("cr"), directiveExpectedReports :: Nil)
    val mergeInfo                = MergeInfo(NodeId("nodeId"), None, None, DateTime.now(DateTimeZone.UTC))

    val withGood = ExecutionBatch
      .getComplianceForRule(
        mergeInfo,
        reports,
        mode,
        ruleExpectedReports,
        new ComputeComplianceTimer(),
        Nil
      )
      .collect { case r => r.directives("policy") }

    val withBad = ExecutionBatch
      .getComplianceForRule(
        mergeInfo,
        badReports,
        mode,
        ruleExpectedReports,
        new ComputeComplianceTimer(),
        Nil
      )
      .collect { case r => r.directives("policy") }

    "return a success block " in {
      (withGood.size === 1) and
      (withGood.head.compliance === ComplianceLevel(success = 1))
    }
    "return one root component with 4 key values " in {
      withGood.head.components.filter(_.componentName == "blockRoot").head.componentValues.size === 4
    }
    "return 3 component with the key values b1c1,b1c2,b2c2 which is repaired " in {
      val block1 = withGood.head.components
        .filter(_.componentName == "blockRoot")
        .head
        .asInstanceOf[BlockStatusReport]
        .subComponents
        .find(_.componentName == "block1")
        .get
      val block2 = withGood.head.components
        .filter(_.componentName == "blockRoot")
        .head
        .asInstanceOf[BlockStatusReport]
        .subComponents
        .find(_.componentName == "block2")
        .get

      (block1.componentValues("b1c1").head.messages.size === 1) and
      (block1.componentValues("b1c1").head.messages.head.reportType === EnforceRepaired) and
      (block1.componentValues("b1c2").head.messages.size === 1) and
      (block1.componentValues("b1c2").head.messages.head.reportType === EnforceRepaired) and
      (block2.componentValues("b2c2").head.messages.size === 1) and
      (block2.componentValues("b2c2").head.messages.head.reportType === EnforceRepaired)
    }
    "return a component with the key values b2c1 which is a success " in {
      val block2 = withGood.head.components
        .filter(_.componentName == "blockRoot")
        .head
        .asInstanceOf[BlockStatusReport]
        .subComponents
        .find(_.componentName == "block2")
        .get
      block2.componentValues("b2c1").head.messages.size === 1 and
      block2.componentValues("b2c1").head.messages.head.reportType === EnforceSuccess
    }

    "Ignore the unexpected reports from components that are not within the focus" in {
      (withBad.size === 1) and
      (withBad.head.compliance === ComplianceLevel(success = 1))
    }

  }

  "Sub block with same component names are authorised, with reporting 'worst case weighted sum'" should {
    val reports = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component1",
        "b1c1",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component2",
        "b1c2",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component1",
        "b2c1",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component2",
        "b2c2",
        executionTimestamp,
        "message"
      )
    )

    val badReports = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component1",
        "b1c1",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component2",
        "b1c2",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component1",
        "b2c1",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component2",
        "b2c2",
        executionTimestamp,
        "message"
      ), // bad ones

      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component2",
        "b2c2",
        executionTimestamp,
        "message"
      )
    )

    val expectedComponent        = BlockExpectedReport(
      "blockRoot",
      ReportingLogic.WorstReportWeightedSum,
      BlockExpectedReport(
        "block1",
        ReportingLogic.WeightedReport,
        new ValueExpectedReport(
          "component1",
          ExpectedValueMatch("b1c1", "b1c1") :: Nil
        ) :: new ValueExpectedReport(
          "component2",
          ExpectedValueMatch("b1c2", "b1c2") :: Nil
        ) :: Nil,
        None
      ) :: BlockExpectedReport(
        "block2",
        ReportingLogic.WeightedReport,
        new ValueExpectedReport(
          "component1",
          ExpectedValueMatch("b2c1", "b2c1") :: Nil
        ) :: new ValueExpectedReport(
          "component2",
          ExpectedValueMatch("b2c2", "b2c2") :: Nil
        ) :: Nil,
        None
      ) :: Nil,
      None
    )
    val directiveExpectedReports = {
      DirectiveExpectedReports(
        DirectiveId(DirectiveUid("policy")),
        None,
        PolicyTypes.rudderBase,
        components = expectedComponent :: Nil
      )
    }
    val ruleExpectedReports      = RuleExpectedReports(RuleId("cr"), directiveExpectedReports :: Nil)
    val mergeInfo                = MergeInfo(NodeId("nodeId"), None, None, DateTime.now(DateTimeZone.UTC))

    val withGood = ExecutionBatch
      .getComplianceForRule(
        mergeInfo,
        reports,
        mode,
        ruleExpectedReports,
        new ComputeComplianceTimer(),
        Nil
      )
      .collect { case r => r.directives("policy") }

    val withBad = ExecutionBatch
      .getComplianceForRule(
        mergeInfo,
        badReports,
        mode,
        ruleExpectedReports,
        new ComputeComplianceTimer(),
        Nil
      )
      .collect { case r => r.directives("policy") }

    "return a repaired block " in {
      (withGood.size === 1) and
      (withGood.head.compliance === ComplianceLevel(repaired = 4))
    }
    "return one root component with 4 key values " in {
      withGood.head.components.filter(_.componentName == "blockRoot").head.componentValues.size === 4
    }
    "return 3 component with the key values b1c1,b1c2,b2c2 which is repaired " in {
      val block1 = withGood.head.components
        .filter(_.componentName == "blockRoot")
        .head
        .asInstanceOf[BlockStatusReport]
        .subComponents
        .find(_.componentName == "block1")
        .get
      val block2 = withGood.head.components
        .filter(_.componentName == "blockRoot")
        .head
        .asInstanceOf[BlockStatusReport]
        .subComponents
        .find(_.componentName == "block2")
        .get

      (block1.componentValues("b1c1").head.messages.size === 1) and
      (block1.componentValues("b1c1").head.messages.head.reportType === EnforceRepaired) and
      (block1.componentValues("b1c2").head.messages.size === 1) and
      (block1.componentValues("b1c2").head.messages.head.reportType === EnforceRepaired) and
      (block2.componentValues("b2c2").head.messages.size === 1) and
      (block2.componentValues("b2c2").head.messages.head.reportType === EnforceRepaired)
    }
    "return a component with the key values b2c1 which is a success " in {
      val block2 = withGood.head.components
        .filter(_.componentName == "blockRoot")
        .head
        .asInstanceOf[BlockStatusReport]
        .subComponents
        .find(_.componentName == "block2")
        .get
      block2.componentValues("b2c1").head.messages.size === 1 and
      block2.componentValues("b2c1").head.messages.head.reportType === EnforceSuccess
    }

    "Return an unexpected Block" in {
      (withBad.size === 1) and
      (withBad.head.compliance === ComplianceLevel(unexpected = 5))
    }

  }

  "Sub block with reporting 'worst case by percent'" should {
    val reports = Seq[ResultReports](
      new ResultErrorReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component1",
        "b1c1",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component2",
        "b1c2",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component3",
        "b1c3",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component1",
        "b2c1",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component1",
        "b3c1",
        executionTimestamp,
        "message"
      )
    )

    val expectedComponent        = BlockExpectedReport(
      "blockRoot",
      ReportingLogic.FocusWorst,
      BlockExpectedReport(
        "block1",
        ReportingLogic.WeightedReport,
        new ValueExpectedReport(
          "component1",
          ExpectedValueMatch("b1c1", "b1c1") :: Nil
        ) :: new ValueExpectedReport(
          "component2",
          ExpectedValueMatch("b1c2", "b1c2") :: Nil
        ) :: new ValueExpectedReport(
          "component3",
          ExpectedValueMatch("b1c3", "b1c3") :: Nil
        ) :: Nil,
        None
      ) :: BlockExpectedReport(
        "block2",
        ReportingLogic.WeightedReport,
        new ValueExpectedReport(
          "component1",
          ExpectedValueMatch("b2c1", "b2c1") :: Nil
        ) :: Nil,
        None
      ) :: BlockExpectedReport(
        "block3",
        ReportingLogic.WeightedReport,
        new ValueExpectedReport(
          "component1",
          ExpectedValueMatch("b3c1", "b3c1") :: Nil
        ) :: Nil,
        None
      ) :: Nil,
      None
    )
    val directiveExpectedReports = {
      DirectiveExpectedReports(
        DirectiveId(DirectiveUid("policy")),
        None,
        PolicyTypes.rudderBase,
        components = expectedComponent :: Nil
      )
    }
    val ruleExpectedReports      = RuleExpectedReports(RuleId("cr"), directiveExpectedReports :: Nil)
    val mergeInfo                = MergeInfo(NodeId("nodeId"), None, None, DateTime.now(DateTimeZone.UTC))

    val statusReports = ExecutionBatch
      .getComplianceForRule(
        mergeInfo,
        reports,
        mode,
        ruleExpectedReports,
        new ComputeComplianceTimer(),
        Nil
      )
      .collect { case r => r.directives("policy") }

    "return one root component with 5 key values " in {
      (statusReports.size === 1) and
      (statusReports.head.components.filter(_.componentName == "blockRoot").head.componentValues.size === 5)
    }
    "return 4 component with the key values b1c2,b1c3,b2c1,b3c1 which is repaired " in {
      val block1 = statusReports.head.components
        .filter(_.componentName == "blockRoot")
        .head
        .asInstanceOf[BlockStatusReport]
        .subComponents
        .find(_.componentName == "block1")
        .get
      val block2 = statusReports.head.components
        .filter(_.componentName == "blockRoot")
        .head
        .asInstanceOf[BlockStatusReport]
        .subComponents
        .find(_.componentName == "block2")
        .get
      val block3 = statusReports.head.components
        .filter(_.componentName == "blockRoot")
        .head
        .asInstanceOf[BlockStatusReport]
        .subComponents
        .find(_.componentName == "block3")
        .get

      (block1.componentValues("b1c1").head.messages.size === 1) and
      (block1.componentValues("b1c1").head.messages.head.reportType === EnforceError) and
      (block1.componentValues("b1c2").head.messages.size === 1) and
      (block1.componentValues("b1c2").head.messages.head.reportType === EnforceRepaired) and
      (block1.componentValues("b1c3").head.messages.size === 1) and
      (block1.componentValues("b1c3").head.messages.head.reportType === EnforceRepaired) and
      (block2.componentValues("b2c1").head.messages.size === 1) and
      (block2.componentValues("b2c1").head.messages.head.reportType === EnforceSuccess)
      (block3.componentValues("b3c1").head.messages.size === 1) and
      (block3.componentValues("b3c1").head.messages.head.reportType === EnforceRepaired)
    }

    "Return the worst Block" in { // b1 is the worst subComponent with 33% error
      statusReports.head.compliance === ComplianceLevel(error = 1, repaired = 2)
    }
  }

  // same as above with b1 being in worst-sum instead of weighted, so overall we should have errors and 0%
  "Sub block with reporting 'worst case by percent' with case" should {
    val reports = Seq[ResultReports](
      new ResultErrorReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component1",
        "b1c1",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component2",
        "b1c2",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component3",
        "b1c3",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component1",
        "b2c1",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component1",
        "b3c1",
        executionTimestamp,
        "message"
      )
    )

    val expectedComponent        = BlockExpectedReport(
      "blockRoot",
      ReportingLogic.FocusWorst,
      BlockExpectedReport(
        "block1",
        ReportingLogic.WorstReportWeightedSum,
        new ValueExpectedReport(
          "component1",
          ExpectedValueMatch("b1c1", "b1c1") :: Nil
        ) :: new ValueExpectedReport(
          "component2",
          ExpectedValueMatch("b1c2", "b1c2") :: Nil
        ) :: new ValueExpectedReport(
          "component3",
          ExpectedValueMatch("b1c3", "b1c3") :: Nil
        ) :: Nil,
        None
      ) :: BlockExpectedReport(
        "block2",
        ReportingLogic.WeightedReport,
        new ValueExpectedReport(
          "component1",
          ExpectedValueMatch("b2c1", "b2c1") :: Nil
        ) :: Nil,
        None
      ) :: BlockExpectedReport(
        "block3",
        ReportingLogic.WeightedReport,
        new ValueExpectedReport(
          "component1",
          ExpectedValueMatch("b3c1", "b3c1") :: Nil
        ) :: Nil,
        None
      ) :: Nil,
      None
    )
    val directiveExpectedReports = {
      DirectiveExpectedReports(
        DirectiveId(DirectiveUid("policy")),
        None,
        PolicyTypes.rudderBase,
        components = expectedComponent :: Nil
      )
    }
    val ruleExpectedReports      = RuleExpectedReports(RuleId("cr"), directiveExpectedReports :: Nil)
    val mergeInfo                = MergeInfo(NodeId("nodeId"), None, None, DateTime.now(DateTimeZone.UTC))

    val statusReports = ExecutionBatch
      .getComplianceForRule(
        mergeInfo,
        reports,
        mode,
        ruleExpectedReports,
        new ComputeComplianceTimer(),
        Nil
      )
      .collect { case r => r.directives("policy") }

    "Return the worst Block" in { // b1 is the worst subComponent with sum of 3
      statusReports.head.compliance === ComplianceLevel(error = 3)
    }
  }

  "A block, with Sum reporting logic" should {
    val reports = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "bar",
        executionTimestamp,
        "message"
      )
    )

    val badReports = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "bar",
        executionTimestamp,
        "message"
      )
    )

    val expectedComponent = BlockExpectedReport(
      "block",
      ReportingLogic.WeightedReport,
      new ValueExpectedReport(
        "component",
        ExpectedValueMatch("foo", "foo") :: ExpectedValueMatch("bar", "bar") :: Nil
      ) :: Nil,
      None
    )

    val withGood = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        reports,
        ReportType.Missing,
        PolicyMode.Enforce
      )
      .head
    val withBad  = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        badReports,
        Missing,
        PolicyMode.Enforce
      )
      .head

    "return a component globally repaired " in {
      withGood.compliance === ComplianceLevel(success = 1, repaired = 1)
    }
    "return a component with two key values " in {
      withGood.componentValues.size === 2
    }
    "return a component with the key values foo which is repaired " in {
      withGood.componentValues("foo").head.messages.size === 1 and
      withGood.componentValues("foo").head.messages.head.reportType === EnforceRepaired
    }
    "return a component with the key values bar which is a success " in {
      withGood.componentValues("bar").head.messages.size === 1 and
      withGood.componentValues("bar").head.messages.head.reportType === EnforceSuccess
    }

    "only one reports in plus, mark the whole key unexpected" in {
      withBad.compliance === ComplianceLevel(success = 1, unexpected = 2)
    }
    "with bad reports return a component with two key values " in {
      withBad.componentValues.size === 2
    }
    "with bad reports return a component with the key values foo which is unknwon " in {
      withBad.componentValues("foo").head.messages.size === 2 and
      withBad.componentValues("foo").head.messages.head.reportType === Unexpected
    }
    "with bad reports return a component with the key values bar which is a success " in {
      withBad.componentValues("bar").head.messages.size === 1 and
      withBad.componentValues("bar").head.messages.head.reportType === EnforceSuccess
    }
  }

  // Test the component part
  "A component, with a cfengine keys" should {
    val reports = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "/var/cfengine",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "/var/cfengine",
        executionTimestamp,
        "message"
      )
    )

    val badReports = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "/var/cfengine",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "/var/cfengine",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "/var/cfengine",
        executionTimestamp,
        "message"
      )
    )

    val expectedComponent = new ValueExpectedReport(
      "component",
      ExpectedValueMatch("${sys.bla}", "${sys.bla}") :: ExpectedValueMatch("${sys.foo}", "${sys.foo}") :: Nil
    )

    val withGood = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        reports,
        ReportType.Missing,
        PolicyMode.Enforce
      )
      .head
    val withBad  = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        badReports,
        ReportType.Missing,
        PolicyMode.Enforce
      )
      .head

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
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "bar",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "node2",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "node1",
        executionTimestamp,
        "message"
      )
    )

    val badReports = Seq[ResultReports](
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "node1",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "node1",
        executionTimestamp,
        "message"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "node2",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "bar",
        executionTimestamp,
        "message"
      )
    )

    /*
     * Here, we must be able to decide between node1 and node2 value for the repair, because we know at generation time
     * what is expected.
     */
    val expectedComponent = new ValueExpectedReport(
      "component",
      ExpectedValueMatch("node1", "${rudder.node.hostname}") :: ExpectedValueMatch(
        "node2",
        "${rudder.node.hostname}"
      ) :: ExpectedValueMatch("bar", "bar") :: Nil
    )

    val withGood = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        reports,
        ReportType.Missing,
        PolicyMode.Enforce
      )
      .head
    val withBad  = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        badReports,
        ReportType.Missing,
        PolicyMode.Enforce
      )
      .head

    "return a component with the correct number of success and repaired" in {
      // be careful, here the second success is for the same unexpanded as the repaire,
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
      withBad.compliance === ComplianceLevel(success = 1, repaired = 1, unexpected = 2)
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

  /*
   * We want to allow variable in component name and values with report id, so that for example:
   * - block1: ${users} // iterator on users
   *   - gm1: "Chech user ${user} created": /bin/createUserScript ${user}
   *   - gm2: "Check right OK for ${user}: /bin/checkRightsOK ${user}
   *
   * Is possible.
   * And we accept both *anything* after $
   */

  "A component with reportId, with variable in its name" should {

    val expectedComponent = BlockExpectedReport(
      "${users}",
      ReportingLogic.WeightedReport,
      new ValueExpectedReport(
        "Check user ${user} created",
        ExpectedValueId("/bin/createUserScript ${user}", "report_0") :: Nil
      ) :: new ValueExpectedReport(
        "Check right OK for ${user} and what follows '$' does not matter in pattern matching",
        ExpectedValueId("/bin/checkRightsOK ${user}", "report_1") :: Nil
      ) :: Nil,
      None
    )

    val reports = Seq[ResultReports](
      // first user: alice, already there
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "dir",
        "nodeId",
        "report_0",
        "Check user alice created",
        "/bin/createUserScript alice",
        executionTimestamp,
        "alice is correctly created"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "dir",
        "nodeId",
        "report_1",
        "Check right OK for alice and that part is whatever we want",
        "/bin/checkRightsOK alice",
        executionTimestamp,
        "alice rights are correct"
      ),
      // second user: bob, new
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "dir",
        "nodeId",
        "report_0",
        "Check user bob created",
        "/bin/createUserScript bob",
        executionTimestamp,
        "bob is correctly created"
      ),
      new ResultRepairedReport(
        executionTimestamp,
        "cr",
        "dir",
        "nodeId",
        "report_1",
        "Check right OK for bob",
        "/bin/checkRightsOK bob",
        executionTimestamp,
        "bob rights are correct"
      )
    )

    /*
     * we check the pattern of returned component name
     */
    val badReports = Seq[ResultReports](
      // user mallory
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "dir",
        "nodeId",
        "report_0",
        "I'm doing whatever", // does not match pattern beginning: `Check user $...`
        "/bin/badScript",
        executionTimestamp,
        "mallory is correctly created"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "dir",
        "nodeId",
        "report_1",
        "Really, nobody looks to green compliance", // does not match pattern beginning: `Check right OK for $...`
        "/bin/moreBadThings",
        executionTimestamp,
        "mallory rights are correct"
      )
    )

    val withGood = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        reports,
        ReportType.Missing,
        PolicyMode.Enforce
      )
      .head
    val withBad  = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        badReports,
        ReportType.Missing,
        PolicyMode.Enforce
      )
      .head

    "return 2 components globally (because they work by reportId)" in {
      withGood.componentValues.size === 4
    }
    "return a total with weight 4 (2x(2 each))" in {
      withGood.compliance === ComplianceLevel(success = 2, repaired = 2)
    }
    "components are grouped by reportId - which not optimal, we would like them grouped by ${user}" in {
      (withGood.componentValues
        .filter(_.expectedComponentValue == "/bin/createUserScript ${user}")
        .flatMap(_.messages.map(_.debugString)) must containTheSameElementsAs(
        List("""Success:"alice is correctly created"""", """Repaired:"bob is correctly created"""")
      )) and
      (withGood.componentValues
        .filter(_.expectedComponentValue == "/bin/checkRightsOK ${user}")
        .flatMap(_.messages.map(_.debugString)) must containTheSameElementsAs(
        List("""Success:"alice rights are correct"""", """Repaired:"bob rights are correct"""")
      ))
    }
    // see https://issues.rudder.io/issues/23084
    "we don't check anymore that component name matches the variable regex from expected" in {
      (withBad.compliance === ComplianceLevel(success = 2)) and
      (withBad.componentValues
        .filter(_.expectedComponentValue == "/bin/createUserScript ${user}")
        .flatMap(_.messages.map(_.debugString)) must containTheSameElementsAs(
        List("""Success:"mallory is correctly created"""")
      )) and
      (withBad.componentValues
        .filter(_.expectedComponentValue == "/bin/checkRightsOK ${user}")
        .flatMap(_.messages.map(_.debugString)) must containTheSameElementsAs(
        List("""Success:"mallory rights are correct"""")
      ))

    }
  }

  /*
   * A rule with:
   * - one historical technique/directive without reportId: d1
   * - one ncf technique with reportId: gm1 and gm2 are in block (block1), gm3 is under root
   * Data from a real cases https://issues.rudder.io/issues/20804
   *
   *  r = 32377fd7-02fd-43d0-aab7-28460a91347b (RuleId(RuleUid(uuid),Revision(default)))
   *  d1 = DirectiveId(DirectiveUid(50a48619-5f04-489b-85f4-80f46968ac40),Revision(default))
   *  d2 = DirectiveId(DirectiveUid(24ea2fd9-93e0-4de0-8ceb-773e86554e76),Revision(default))
   *  nodeId = NodeId(root)
   *  message0 = No post-modification script was defined
   *  reportid1 = 4c4858a1-ed05-4a74-9362-624d5475d451
   *  value1 = /bin/true
   *  message1 = Execute the command /bin/true was repaired
   *  reportid2 = 10957b22-20e5-43c3-8ef2-aa5b666b3bd9
   *  value2 = /tmp/rudder-file-gm2
   *  message2 = Presence of file /tmp/rudder-file-gm2 was correct
   *  reportid3 = e0a496c2-14a5-4cd2-81bf-8d4630549b41
   *  value3 = /tmp/rudder-file-gm3
   *  message3 = Presence of file /tmp/rudder-file-gm3 was correct
   *
   *  t1 = 2022-02-23T11:40:30.000+01:00
   *  t2 = 2022-02-23T11:40:23.000+01:00
   *
   *  [2022-02-23 11:40:32+0100] TRACE explain_compliance.root - Expected reports for rule 'r':
   *   [expected] DirectiveExpectedReports(d1,None,false,List(
   *                ValueExpectedReport(Package,List(ExpectedValueMatch(vim,vim))),  // the report for this one is missing due to a bug with virtual packages
   *                ValueExpectedReport(Post-modification script,List(ExpectedValueMatch(vim,vim)))))
   *   [expected] DirectiveExpectedReports(d2,None,false,List(
   *                BlockExpectedReport(block1,WeightedReport,List(
   *                  ValueExpectedReport(gm1,List(ExpectedValueId(value1,reportid1))),
   *                  ValueExpectedReport(gm2,List(ExpectedValueId(value2,reportid2))))),
   *                ValueExpectedReport(gm3,List(ExpectedValueId(value3,reportid3)))))
   *
   *  [2022-02-23 11:40:32+0100] TRACE explain_compliance.root - Reports for rule 'r':
   *   [report] ResultNotApplicableReport(t1,r,d1,nodeId,0,Post-modification script,vim,t2,message1)
   *   [report] ResultRepairedReport(t1,r,d2,nodeId,reportid1,gm1,value1,t2,message1)
   *   [report] ResultSuccessReport(t1,r,d2,nodeId,reportid2,gm2,value2,t2,message2)
   *   [report] ResultSuccessReport(t1,r,d2,nodeId,reportid3,gm3,value3,t2,message3)
   *
   *  [2022-02-23 11:40:32+0100] TRACE explain_compliance.root - Compliance for rule 'r':
   *    [[root: r; run: t2;20220223-091215-f4ad7d27->2022-02-23T11:50:23.000+01:00]
   *    compliance:[p:0 s:0 r:0 e:0 u:0 m:1 nr:0 na:1 rd:0 c:0 ana:0 nc:0 ae:0 bpm:0]
   *    [24ea2fd9-93e0-4de0-8ceb-773e86554e76 =>
   *      BlockStatusReport(block1,WeightedReport,List())
   *    ]
   *    [50a48619-5f04-489b-85f4-80f46968ac40 =>
   *      Package:[vim(<-> vim):[Missing:"[Missing report #0]"]]
   *      Post-modification script:[vim(<-> vim):[NotApplicable:"message1"]]
   *    ]]
   *
   */
  "A rule with directive with expected reports with and without reportId should report missing" >> {
    val t1      = executionTimestamp
    val t2      = executionTimestamp.minusSeconds(7)
    val reports = Seq[ResultReports](
      new ResultNotApplicableReport(t1, "r", "d1", "nodeId", "0", "Post-modification script", "vim", t2, "message0"),
      new ResultRepairedReport(t1, "r", "d2", "nodeId", "reportid1", "gm1", "value1", t2, "message1"),
      new ResultSuccessReport(t1, "r", "d2", "nodeId", "reportid2", "gm2", "value2", t2, "message2"),
      new ResultSuccessReport(t1, "r", "d2", "nodeId", "reportid3", "gm3", "value3", t2, "message3")
    )

    val d1 = DirectiveExpectedReports(
      "d1",
      None,
      PolicyTypes.rudderBase,
      components = List(
        ValueExpectedReport("Package", List(ExpectedValueMatch("vim", "vim"))),
        ValueExpectedReport("Post-modification script", List(ExpectedValueMatch("vim", "vim")))
      )
    )
    val d2 = DirectiveExpectedReports(
      directiveId = "d2",
      policyMode = None,
      PolicyTypes.rudderBase,
      components = List(
        BlockExpectedReport(
          "block1",
          ReportingLogic.WeightedReport,
          List(
            ValueExpectedReport("gm1", List(ExpectedValueId("value1", "reportid1"))),
            ValueExpectedReport("gm2", List(ExpectedValueId("value2", "reportid2")))
          ),
          None
        ),
        ValueExpectedReport("gm3", List(ExpectedValueId("value3", "reportid3"))),
        ValueExpectedReport("gm4", List(ExpectedValueId("value4", "reportid4")))
      )
    )

    val ruleExpectedReports = RuleExpectedReports(RuleId("cr"), d1 :: d2 :: Nil)
    val mergeInfo           = MergeInfo(NodeId("nodeId"), None, None, DateTime.now(DateTimeZone.UTC))

    val result = ExecutionBatch.getComplianceForRule(
      mergeInfo,
      reports,
      mode,
      ruleExpectedReports,
      new ComputeComplianceTimer(),
      Nil
    )

    (result.size === 1) and
    (result.head.compliance === ComplianceLevel(success = 2, repaired = 1, notApplicable = 1, missing = 2))

  }

  "Shall we speak about unexpected" should {
    // we asked for a value "foo" and a variable ${param}
    val expectedComponent =
      new ValueExpectedReport("component", ExpectedValueMatch("foo", "foo") :: ExpectedValueMatch("${param}", "${param}") :: Nil)

    // syslog duplicated a message
    val duplicated = Seq[ResultReports](
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "foo message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "foo message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "param expended",
        executionTimestamp,
        "param message"
      )
    )

    val tooMuchDuplicated = Seq[ResultReports](
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "foo message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "foo message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "foo message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "foo message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "param expended",
        executionTimestamp,
        "param message"
      )
    )

    // ${param} was an iterato on: foo, bar, baz
    val unboundedVars = Seq[ResultReports](
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "foo message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        executionTimestamp,
        "foo expanded"
      ), // here foo should not be a duplicated because the message is different, which is likely the case in real life
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "bar",
        executionTimestamp,
        "bar expanded"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "baz",
        executionTimestamp,
        "baz expanded"
      )
    )

    "when strict mode is set, duplicate messages lead to unexpected" in {
      val res = ExecutionBatch
        .checkExpectedComponentWithReports(
          expectedComponent,
          duplicated,
          ReportType.Missing,
          PolicyMode.Enforce
        )
        .head
      res.compliance === ComplianceLevel(success = 1, unexpected = 2) // 2 unexpected because the whole "foo" becomes unexpected
    }
    "when allow duplicated, duplicate messages is ignored" in {
      val res = ExecutionBatch
        .checkExpectedComponentWithReports(
          expectedComponent,
          duplicated,
          ReportType.Missing,
          PolicyMode.Enforce
        )
        .head
      res.compliance === ComplianceLevel(success = 1, unexpected = 2)
    }
    "when allow duplicated, duplicate messages is ignored but not for 4 duplications" in {
      val res = ExecutionBatch
        .checkExpectedComponentWithReports(
          expectedComponent,
          tooMuchDuplicated,
          ReportType.Missing,
          PolicyMode.Enforce
        )
        .head
      res.compliance === ComplianceLevel(success = 1, unexpected = 4)
    }

    "when on strict mode, out of bound vars are unexpected" in {
      val res = ExecutionBatch
        .checkExpectedComponentWithReports(
          expectedComponent,
          unboundedVars,
          ReportType.Missing,
          PolicyMode.Enforce
        )
        .head
      res.compliance === ComplianceLevel(success = 4)
    }

  }

  "A component with var in expected value and a multiline returned value matching the pattern" should {

    val expectedComponent = new ValueExpectedReport(
      "test",
      ExpectedValueMatch(
        "${list_failed_login.sanitized_content} list of failed login",
        "${list_failed_login.sanitized_content} list of failed login"
      ) :: Nil
    )

    val reports = Seq[ResultReports](
      // first user: alice, already there
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "dir",
        "nodeId",
        "report_0",
        "test",
        """nicarles ssh:notty Tue Jul 18 21:53:59 2023 - Tue Jul 18 21:53:59 2023 (00:00) 192.168.254.30
          |nicarles ssh:notty Tue Jul 18 21:53:56 2023 - Tue Jul 18 21:53:56 2023 (00:00) 192.168.254.30
          |nicolas. ssh:notty Tue Jul 18 21:53:52 2023 - Tue Jul 18 21:53:52 2023 (00:00) 192.168.254.30
          |nicolas. ssh:notty Tue Jul 18 21:53:47 2023 - Tue Jul 18 21:53:47 2023 (00:00) 192.168.254.30
          |nicolas. ssh:notty Tue Jul 18 21:53:39 2023 - Tue Jul 18 21:53:39 2023 (00:00) 192.168.254.30
          |nicolas. ssh:notty Tue Jul 18 21:53:36 2023 - Tue Jul 18 21:53:36 2023 (00:00) 192.168.254.30 list of failed login""".stripMargin,
        executionTimestamp,
        "alice is correctly created"
      )
    )

    val withGood = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        reports,
        ReportType.Missing,
        PolicyMode.Enforce
      )
      .head

    "return a success" in {
      withGood.compliance === ComplianceLevel(success = 1)
    }
  }

  "Compliance for cfengine vars and reports" should {

    sealed trait Kind { def tpe: ReportType }
    case object Success    extends Kind { val tpe: ReportType = EnforceSuccess        }
    case object Repaired   extends Kind { val tpe: ReportType = EnforceRepaired       }
    case object Error      extends Kind { val tpe: ReportType = EnforceError          }
    case object Missing    extends Kind { val tpe: ReportType = ReportType.Missing    }
    case object Unexpected extends Kind { val tpe: ReportType = ReportType.Unexpected }

    /*
     * Values are expected values with the corresponding status list
     */
    def test(
        id:                 String,
        patterns:           Seq[(String, Seq[Kind])],
        reports:            Seq[(String, Kind)],
        unexpectedNotValue: Seq[String] = Nil
    ) = {

      // expected components are the list of key for patterns
      val expectedComponent = {
        val values = patterns.map(_._1)
        new ValueExpectedReport("component", values.map(v => ExpectedValueMatch(v, v)).toList)
      }

      val resultReports: Seq[ResultReports] = reports.map(x => {
        x match {
          case (v, Success)  =>
            new ResultSuccessReport(
              executionTimestamp,
              "cr",
              "policy",
              "nodeId",
              "report_id12",
              "component",
              v,
              executionTimestamp,
              "message"
            )
          case (v, Repaired) =>
            new ResultRepairedReport(
              executionTimestamp,
              "cr",
              "policy",
              "nodeId",
              "report_id12",
              "component",
              v,
              executionTimestamp,
              "message"
            )
          case (v, x)        =>
            new ResultErrorReport(
              executionTimestamp,
              "cr",
              "policy",
              "nodeId",
              "report_id12",
              "component",
              v,
              executionTimestamp,
              "message"
            )
        }
      })

      val t1     = System.currentTimeMillis
      val result = ExecutionBatch.checkExpectedComponentWithReports(
        expectedComponent,
        resultReports,
        ReportType.Missing,
        PolicyMode.Enforce
      )
      val t2     = System.currentTimeMillis - t1

      // distribute compliance on each pattern to be able to count them
      val p = patterns.flatMap { case (x, seq) => seq.map(s => (x, s)) } ++ unexpectedNotValue.map(u => (u, Unexpected))

      val compliance = ComplianceLevel(
        success = p.collect { case (x, Success) => x }.size,
        repaired = p.collect { case (x, Repaired) => x }.size,
        error = p.collect { case (x, Error) => x }.size,
        missing = p.collect { case (x, Missing) => x }.size,
        unexpected = p.collect { case (x, Unexpected) => x }.size
      )

      s"[${id}] be OK with patterns ${patterns}" in {
        p.foldLeft((result.head.compliance === compliance)) {
          case (example, nextPattern) =>
            result.head.componentValues(nextPattern._1).head.messages.foldLeft(example) {
              case (newExample, nextMessage) =>
                val msgCompliance     = ComplianceLevel.compute(List(nextMessage.reportType))
                val patternCompliance = ComplianceLevel.compute(List(nextPattern._2.tpe))
                newExample and msgCompliance === patternCompliance
            }
        } and (t2 must be_<(200L)) // take less than these number of ms
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

    test(
      "order1",
      patterns = ("/etc/foo.old.${sys.bla}", Seq(Success)) :: ("/etc/foo.${sys.bla}", Seq(Repaired)) :: Nil,
      reports = ("/etc/foo.old.txt", Success) :: ("/etc/foo.txt", Repaired) :: Nil
    )

    test(
      "order2",
      patterns = ("/etc/foo.old.${sys.bla}", Seq(Success)) :: ("/etc/foo.${sys.bla}", Seq(Repaired)) :: Nil,
      reports = ("/etc/foo.txt", Repaired) :: ("/etc/foo.old.txt", Success) :: Nil
    )

    test(
      "order3",
      patterns = ("/etc/foo.${sys.bla}", Seq(Repaired)) :: ("/etc/foo.old.${sys.bla}", Seq(Success)) :: Nil,
      reports = ("/etc/foo.old.txt", Success) :: ("/etc/foo.txt", Repaired) :: Nil
    )
    test(
      "order4",
      patterns = ("/etc/foo.${sys.bla}", Seq(Repaired)) :: ("/etc/foo.old.${sys.bla}", Seq(Success)) :: Nil,
      reports = ("/etc/foo.txt", Repaired) :: ("/etc/foo.old.txt", Success) :: Nil
    )

    //
    test(
      "one var",
      patterns = ("${sys.bla}", Seq(Repaired)) :: ("bar", Seq(Success)) :: Nil,
      reports = ("/var/cfengine", Repaired) :: ("bar", Success) :: Nil
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

    test(
      "only simple reports",
      patterns = ("foo", Seq(Repaired)) :: ("bar", Seq(Success)) :: Nil,
      reports = ("baz", Repaired) :: ("foo", Repaired) :: ("bar", Success) :: Nil
      // , unexpectedNotValue = Seq("baz")
    )

    // here, the var get all extra reports
    test(
      "one var and simple reports",
      patterns = ("${sys.bla}", Seq(Success, Success)) :: ("bar", Seq(Success)) :: Nil,
      reports = ("/var/cfengine", Success) :: ("/var/cfengine", Success) :: ("bar", Success) :: Nil
    )

    /*
     * Testing cfengine variables which give the same patterns
     *
     * Again, if you switch the order of reports, tests will fail because
     * we don't have any mean to assign a given report to a given patterns in
     * that case - the only way to correct that is to be able to have unique identification
     * of reports by expected component.
     */
    test(
      "same patterns",
      patterns = ("${sys.bla}", Seq(Repaired)) :: ("${sys.foo}", Seq(Success)) :: Nil,
      reports = ("/var/cfengine", Repaired) :: ("/var/cfengine", Success) :: Nil
    )

    // the first var get extra reports
    test(
      "same patterns with unexpected",
      patterns = ("${sys.bla}", Seq(Repaired)) :: ("${sys.foo}", Seq(Success, Success)) :: Nil,
      reports = ("/var/cfengine", Repaired) :: ("/var/cfengine", Success) :: ("/var/cfengine", Success) :: Nil
    )

    /*
     * Test for ticket https://issues.rudder.io/issues/15007
     */
    test(
      "string size should not matter",
      patterns = ("${nagios_knowledge.nrpe_conf_file}", Seq(Repaired))
        :: ("/tmp/usr/local/nagios/libexec/security/check_clean.pl", Seq(Error))
        :: ("/tmp/usr/local/nagios/libexec/security/check_clean", Seq(Success))
        :: Nil,
      reports = ("/tmp/usr/local/etc/nrpe.cfg", Repaired)
        :: ("/tmp/usr/local/nagios/libexec/security/check_clean.pl", Error)
        :: ("/tmp/usr/local/nagios/libexec/security/check_clean", Success)
        :: Nil
    )
    test(
      "string size should not matter (2)",
      patterns = ("${nagios_knowledge.nrpe_conf_file}", Seq(Repaired))
        :: ("/tmp/usr/local/nagios/libexec/security/check_clean.pl", Seq(Error))
        :: ("/tmp/usr/local/nagios/libexec/security/check_clean", Seq(Success))
        :: Nil,
      reports = ("/tmp/usr/local/etc/nrpe.cfg_but_here_we_now_have_a_long_string", Repaired)
        :: ("/tmp/usr/local/nagios/libexec/security/check_clean.pl", Error)
        :: ("/tmp/usr/local/nagios/libexec/security/check_clean", Success)
        :: Nil
    )

    /*
     * A test with a lots of variables and reports, to see if the execution time remains OK
     */
    test(
      "lots of reports",
      patterns = ("/var/cfengine", Seq(Success))
        :: ("${sys.foo}", Seq(Success))  // we actually haven't any way to distinguish between that one ( <- )
        :: ("${sys.bla}", Seq(Repaired)) // and that one ( <- ). Correct solution is to use different report components.
        :: ("/etc/foo.old.${sys.bla}", Seq(Success))
        :: ("/etc/foo.${sys.bla}", Seq(Repaired))
        :: ("a${foo}b${bar}", Seq(Success))
        :: ("a${foo}b", Seq(Success))
        :: ("b${foo}b", Seq(Success))
        :: ("b${foo}c", Seq(Success))
        :: ("b${foo}d", Seq(Success))
        :: Nil,
      reports = ("/var/cfengine", Success)
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

    test(
      "same report for simple and pattern",
      patterns = ("/var/${sys.bla}", Seq(Success)) :: ("/var/cfengine", Seq(Success)) :: Nil,
      reports = ("/var/cfengine", Success) :: ("/var/cfengine", Success) :: Nil
    )

    // handle correctly ${boo}bar} vs ${foo}xxx} (ie matches only the variable part)
    test(
      "matches only the variable part of variable",
      patterns = ("${foo}xxx}", Seq(Repaired)) :: ("$(bar)yyyy)", Seq(Success)) :: Nil,
      reports = ("ayyyy)", Success) :: ("bxxx}", Repaired) :: Nil
    )

    // there should be nothing special about "\" even if cfengine escape them
    test(
      """nothing special with \ when a ${var} is present""",
      patterns = ("${foo}x\\x}", Seq(Repaired)) :: Nil,
      reports = ("yx\\x}", Repaired) :: Nil
    )

    test("""nothing special with \""", patterns = ("x\\x}", Seq(Repaired)) :: Nil, reports = ("x\\x}", Repaired) :: Nil)

    // we need to take care of the fact that ${const.dollar} is always replaced by cfengine
    test(
      "consider regex special chars as normal chars",
      patterns = ("[^foo$]", Seq(Repaired)) :: ("(bar)", Seq(Success)) :: ("""\D\p{Lower}""", Seq(Success)) :: Nil,
      reports = ("[^foo$]", Repaired) :: ("(bar)", Success) :: ("""\D\p{Lower}""", Success) :: Nil
    )

    // we need to take care of the fact that ${const.dollar} is always replaced by cfengine
    test(
      "correctly understand ${const.dollar}",
      patterns = ("""[ ${const.dollar}(echo "enabled") = 'enabled' ]""", Seq(Repaired)) :: (
        "/var/${const.dollar}cfengine",
        Seq(Success)
      ) :: Nil,
      reports = ("""[ $(echo "enabled") = 'enabled' ]""", Repaired) :: ("/var/$cfengine", Success) :: Nil
    )
  }

  val fullCompliance: GlobalComplianceMode = GlobalComplianceMode(FullCompliance)

  "A detailed execution Batch, with one component, cardinality one, one node" should {

    val param = (
      buildExpected(
        List("one"),
        "rule",
        12,
        List(
          DirectiveExpectedReports(
            directiveId = "policy",
            policyMode = None,
            PolicyTypes.rudderBase,
            components = List(
              new ValueExpectedReport("component", ExpectedValueMatch("value", "value") :: Nil)
            ) // here, we automatically must have "value" infered as unexpanded var
          )
        )
      ),
      Seq[Reports](
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy",
          "one",
          "report_id12",
          "component",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        )
      )
    )

    val nodeStatus = (getNodeStatusReportsByRule).tupled(param)

    "have one detailed reports when we create it with one report" in {
      nodeStatus(one).reports.size === 1
    }

    "have one detailed success node when we create it with one success report" in {
      nodeStatus.keySet.head === one
    }

    "have one detailed rule success directive when we create it with one success report" in {
      (nodeStatus(one).reports.size === 1) and
      (nodeStatus(one).reports.head._2.directives.head._1.serialize === "policy")
    }

    "have no detailed rule non-success directive when we create it with one success report" in {
      nodeStatus(one).reports.head._2.compliance === ComplianceLevel(success = 1)
    }
  }

  "A detailed execution Batch, with one component, cardinality one, wrong node" should {
    val param = (
      buildExpected(
        List("one"),
        "rule",
        12,
        List(
          DirectiveExpectedReports(
            directiveId = "policy",
            policyMode = None,
            PolicyTypes.rudderBase,
            components = List(new ValueExpectedReport("component", ExpectedValueMatch("value", "value") :: Nil))
          )
        )
      ),
      Seq[Reports](
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy",
          "two",
          "report_id12",
          "component",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        )
      )
    )

    val nodeStatus = getNodeStatusByRule(param)

    "have one detailed reports when we create it with one report" in {
      nodeStatus.size === 1
    }

    "have a pending node when we create it with one wrong success report right now" in {
      (nodeStatus.keySet.head === one) and
      AggregatedStatusReport(nodeStatus.values.flatMap(_.reports.values.flatMap(_.reports)).toSet).compliance === ComplianceLevel(
        missing = 1
      )
    }
  }

  "A detailed execution Batch, with one component, cardinality one, one node" should {

    val param      = (
      buildExpected(
        List("one"),
        "rule",
        12,
        List(
          DirectiveExpectedReports(
            directiveId = "policy",
            policyMode = None,
            PolicyTypes.rudderBase,
            components = List(new ValueExpectedReport("component", ExpectedValueMatch("value", "value") :: Nil))
          )
        )
      ),
      Seq[Reports](
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy",
          "one",
          "report_id12",
          "component",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy",
          "one",
          "report_id12",
          "component",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        )
      )
    )
    val nodeStatus = getNodeStatusByRule(param)

    "have one detailed reports when we create it" in {
      nodeStatus.size == 1
    }

    "have one unexpected node when we create it with one success report" in {
      nodeStatus.head._1 === one and
      AggregatedStatusReport(nodeStatus.values.flatMap(_.reports.values.flatMap(_.reports)).toSet).compliance === ComplianceLevel(
        unexpected = 2
      )
    }
  }

  "A detailed execution Batch, with one component, cardinality one, two nodes, including one not responding" should {

    val param      = (
      buildExpected(
        List("one", "two"),
        "rule",
        12,
        List(
          DirectiveExpectedReports(
            directiveId = "policy",
            policyMode = None,
            PolicyTypes.rudderBase,
            components = List(new ValueExpectedReport("component", ExpectedValueMatch("value", "value") :: Nil))
          )
        )
      ),
      Seq[Reports](
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy",
          "one",
          "report_id12",
          "component",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        )
      )
    )
    val nodeStatus = getNodeStatusByRule(param)

    "have two detailed reports when we create it" in {
      nodeStatus.size == 2
    }

    "have one success, and one pending node, in the component detail of the rule" in {
      AggregatedStatusReport(nodeStatus.values.flatMap(_.reports.values.flatMap(_.reports)).toSet).compliance === ComplianceLevel(
        success = 1,
        missing = 1
      )
    }
  }

  "A detailed execution Batch, with one component, cardinality one, three nodes, including one not responding" should {
    val param      = (
      buildExpected(
        List("one", "two", "three"),
        "rule",
        12,
        List(
          DirectiveExpectedReports(
            directiveId = "policy",
            policyMode = None,
            PolicyTypes.rudderBase,
            components = List(new ValueExpectedReport("component", ExpectedValueMatch("value", "value") :: Nil))
          )
        )
      ),
      Seq[Reports](
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy",
          "one",
          "report_id12",
          "component",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy",
          "two",
          "report_id12",
          "component",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        )
      )
    )
    val nodeStatus = getNodeStatusByRule(param)

    "have three node rule report" in {
      nodeStatus.size === 3
    }
    "have one detailed rule report with a 67% compliance" in {
      AggregatedStatusReport(nodeStatus.values.flatMap(_.reports.values.flatMap(_.reports)).toSet).compliance === ComplianceLevel(
        success = 2,
        missing = 1
      )
    }
  }

  "A detailed execution Batch, with two directive, two component, cardinality one, three nodes, including one partly responding and one not responding" should {
    val param      = (
      buildExpected(
        List("one", "two", "three"),
        "rule",
        12,
        List(
          DirectiveExpectedReports(
            directiveId = "policy",
            policyMode = None,
            PolicyTypes.rudderBase,
            components = List(
              new ValueExpectedReport("component", ExpectedValueMatch("value", "value") :: Nil),
              new ValueExpectedReport("component2", ExpectedValueMatch("value", "value") :: Nil)
            )
          ),
          DirectiveExpectedReports(
            directiveId = "policy2",
            policyMode = None,
            PolicyTypes.rudderBase,
            components = List(
              new ValueExpectedReport("component", ExpectedValueMatch("value", "value") :: Nil),
              new ValueExpectedReport("component2", ExpectedValueMatch("value", "value") :: Nil)
            )
          )
        )
      ),
      Seq[Reports](
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy",
          "one",
          "report_id12",
          "component",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy",
          "one",
          "report_id12",
          "component2",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy2",
          "one",
          "report_id12",
          "component",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy2",
          "one",
          "report_id12",
          "component2",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy",
          "two",
          "report_id12",
          "component",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy",
          "two",
          "report_id12",
          "component2",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy2",
          "two",
          "report_id12",
          "component",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        )
      )
    )
    val nodeStatus = getNodeStatusByRule(param)
    val aggregated = AggregatedStatusReport(nodeStatus.values.flatMap(_.reports.values.flatMap(_.reports)).toSet)

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
    val param      = (
      buildExpected(
        List("one", "two", "three"),
        "rule",
        12,
        List(
          DirectiveExpectedReports(
            directiveId = "policy",
            policyMode = None,
            PolicyTypes.rudderBase,
            components = List(
              new ValueExpectedReport("component", ExpectedValueMatch("value", "value") :: Nil),
              new ValueExpectedReport("component2", ExpectedValueMatch("value", "value") :: Nil)
            )
          ),
          DirectiveExpectedReports(
            directiveId = "policy2",
            policyMode = None,
            PolicyTypes.rudderBase,
            components = List(
              new ValueExpectedReport("component", ExpectedValueMatch("value", "value") :: Nil),
              new ValueExpectedReport("component2", ExpectedValueMatch("value", "value") :: Nil)
            )
          )
        )
      ),
      Seq[Reports](
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy",
          "one",
          "report_id12",
          "component",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy",
          "one",
          "report_id12",
          "component2",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy2",
          "one",
          "report_id12",
          "component",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy2",
          "one",
          "report_id12",
          "component2",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy",
          "two",
          "report_id12",
          "component",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy",
          "two",
          "report_id12",
          "component2",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy2",
          "two",
          "report_id12",
          "component",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy",
          "three",
          "report_id12",
          "component",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        )
      )
    )
    val nodeStatus = getNodeStatusByRule(param)
    val aggregated = AggregatedStatusReport(nodeStatus.values.flatMap(_.reports.values.flatMap(_.reports)).toSet)

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
      aggregated
        .directives("policy")
        .components
        .filter(_.componentName == "component")
        .head
        .compliance === ComplianceLevel(success = 3)
    }
    "have detailed rule report for policy-component2 of 67%" in {
      aggregated.directives("policy").components.filter(_.componentName == "component2").head.compliance === ComplianceLevel(
        success = 2,
        missing = 1
      )
    }
    "have detailed rule report for policy2-component2 of 33%" in {
      aggregated.directives("policy2").components.filter(_.componentName == "component2").head.compliance === ComplianceLevel(
        success = 1,
        missing = 2
      )
    }
  }

  "A detailed execution Batch, with two directive, two component, cardinality three, three nodes, including two not completely responding" should {
    val param      = (
      buildExpected(
        List("one", "two", "three"),
        "rule",
        12,
        List(
          DirectiveExpectedReports(
            directiveId = "policy",
            policyMode = None,
            PolicyTypes.rudderBase,
            components = List(
              new ValueExpectedReport(
                "component",
                List(
                  ExpectedValueMatch("value", "value"),
                  ExpectedValueMatch("value2", "value2"),
                  ExpectedValueMatch("value3", "value3")
                )
              )
            )
          )
        )
      ),
      Seq[Reports](
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy",
          "one",
          "report_id12",
          "component",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy",
          "one",
          "report_id12",
          "component",
          "value2",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy",
          "one",
          "report_id12",
          "component",
          "value3",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy",
          "two",
          "report_id12",
          "component",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy",
          "two",
          "report_id12",
          "component",
          "value2",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy",
          "three",
          "report_id12",
          "component",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        )
      )
    )
    val nodeStatus = getNodeStatusByRule(param)
    val aggregated = AggregatedStatusReport(nodeStatus.values.flatMap(_.reports.values.flatMap(_.reports)).toSet)

    "have 3 detailed node rule report" in {
      nodeStatus.size === 3
    }
    "have detailed rule report for policy of 67%" in {
      aggregated.directives("policy").compliance === ComplianceLevel(success = 6, missing = 3)
    }
    "have detailed rule report for policy/component/value of 100%" in {
      aggregated
        .directives("policy")
        .components
        .filter(_.componentName == "component")
        .head
        .componentValues("value")
        .head
        .compliance ===
      ComplianceLevel(success = 3)
    }
    "have detailed rule report for policy/component/value2 of 67%" in {
      aggregated
        .directives("policy")
        .components
        .filter(_.componentName == "component")
        .head
        .componentValues("value2")
        .head
        .compliance ===
      ComplianceLevel(success = 2, missing = 1)
    }
    "have detailed rule report for policy/component/value3 of 33%" in {
      aggregated
        .directives("policy")
        .components
        .filter(_.componentName == "component")
        .head
        .componentValues("value3")
        .head
        .compliance ===
      ComplianceLevel(success = 1, missing = 2)
    }
  }

  "An execution Batch, with one component with a quote in its value, cardinality one, one node" should {

    val param      = (
      buildExpected(
        List("one"),
        "rule",
        12,
        List(
          DirectiveExpectedReports(
            directiveId = "policy",
            policyMode = None,
            PolicyTypes.rudderBase,
            components = List(
              ValueExpectedReport("component", ExpectedValueMatch(("""some\"text"""), ("""some\text""")) :: Nil)
            )
          )
        )
      ),
      Seq[Reports](
        new ResultSuccessReport(
          new DateTime(DateTimeZone.UTC),
          "rule",
          "policy",
          "one",
          "report_id12",
          "component",
          """some\"text""",
          new DateTime(DateTimeZone.UTC),
          "message"
        )
      )
    )
    val nodeStatus = getNodeStatusByRule(param)

    "have one detailed reports when we create it with one report" in {
      nodeStatus.size === 1
    }

    "have one detailed success node when we create it with one success report" in {
      nodeStatus.keySet.head === one and
      nodeStatus.head._2.reports.head._2.compliance.computePercent().success === 100
    }

  }

  "An execution Batch, with one component, one node, but with a component value being a cfengine variable with {, and a an escaped quote as well" should {

    val param = (
      buildExpected(
        List("nodeId"),
        "rule",
        12,
        List(
          DirectiveExpectedReports(
            directiveId = "policy",
            policyMode = None,
            PolicyTypes.rudderBase,
            components = List(
              ValueExpectedReport(
                "component",
                ExpectedValueMatch("""${sys.workdir}/inputs/\"test""", """${sys.workdir}/inputs/\"test""") :: Nil
              )
            )
          )
        )
      ),
      Seq[Reports](
        new ResultSuccessReport(
          new DateTime(DateTimeZone.UTC),
          "rule",
          "policy",
          "nodeId",
          "report_id12",
          "component",
          """/var/cfengine/inputs/\"test""",
          new DateTime(DateTimeZone.UTC),
          "message"
        )
      )
    )

    val nodeStatus = getNodeStatusByRule(param)

    "have one detailed reports when we create it with one report" in {
      nodeStatus.size === 1
    }

    "have one detailed success node when we create it with one success report" in {
      nodeStatus.keySet.head === NodeId("nodeId") and
      nodeStatus.head._2.reports.head._2.compliance.computePercent().success === 100
    }
  }

  "An execution Batch, with one component, one node, but with a component value being a cfengine variable with {, and a quote as well" should {
    val param      = (
      buildExpected(
        List("nodeId"),
        "rule",
        12,
        List(
          DirectiveExpectedReports(
            directiveId = "policy",
            policyMode = None,
            PolicyTypes.rudderBase,
            components = List(
              ValueExpectedReport(
                "component",
                ExpectedValueMatch("""${sys.workdir}/inputs/"test""", """${sys.workdir}/inputs/"test""") :: Nil
              )
            )
          )
        )
      ),
      Seq[Reports](
        new ResultSuccessReport(
          new DateTime(DateTimeZone.UTC),
          "rule",
          "policy",
          "nodeId",
          "report_id12",
          "component",
          """/var/cfengine/inputs/"test""",
          new DateTime(DateTimeZone.UTC),
          "message"
        )
      )
    )
    val nodeStatus = getNodeStatusByRule(param)

    "have one detailed reports when we create it with one report" in {
      nodeStatus.size === 1
    }

    "have one detailed success node when we create it with one success report" in {
      nodeStatus.keySet.head === NodeId("nodeId") and
      nodeStatus.head._2.reports.head._2.compliance.computePercent().success === 100
    }
  }

  // Test the component part - with NotApplicable
  "A component, with two keys and NotApplicable reports" should {
    val reports = Seq[ResultReports](
      new ResultNotApplicableReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "/var/cfengine",
        executionTimestamp,
        "message"
      ),
      new ResultSuccessReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "bar",
        executionTimestamp,
        "message"
      )
    )

    val expectedComponent = ValueExpectedReport(
      "component",
      List(ExpectedValueMatch("/var/cfengine", "/var/cfengine"), ExpectedValueMatch("bar", "bar"))
    )

    val withGood = ExecutionBatch
      .checkExpectedComponentWithReports(
        expectedComponent,
        reports,
        ReportType.Missing,
        PolicyMode.Enforce
      )
      .head

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
    val initData   = buildDataForMergeCompareByRule("test", nbRuleInit, 12, 4)

    val nodeList = (1 to 100).map("nodeId_" + _).toSeq

    val runData = nodeList.map(buildDataForMergeCompareByRule(_, 15, 12, 5))

    "init correctly" in {
      val result = (ExecutionBatch.mergeCompareByRule).tupled(initData)
      result.size === nbRuleInit and
      result.toSeq.map(x => x.compliance).map(x => x.success).sum === 576
    }

    "run fast enough" in {
      runData.map(x => (ExecutionBatch.mergeCompareByRule).tupled(x))

      val t0 = System.currentTimeMillis

      for (i <- 1 to 10) {
        val t0_0 = System.currentTimeMillis
        runData.map(x => (ExecutionBatch.mergeCompareByRule).tupled(x))
        val t1_1 = System.currentTimeMillis
        logger.trace(s"${i}th call to mergeCompareByRule for ${nodeList.size} nodes took ${t1_1 - t0_0}ms")
      }
      val t1 = System.currentTimeMillis
      logger.debug(s"Time to run test is ${t1 - t0} ms")
      (t1 - t0) must be lessThan (50000) // On my Dell XPS15, this test runs in 7500-8500 ms
    }
  }

  "We can split directive by policy type" >> {
    val tag1  = PolicyTypeName("tag1")
    val tag2  = PolicyTypeName("tag2")
    val param = (
      buildExpected(
        List("one"),
        "rule",
        12,
        List(
          DirectiveExpectedReports(
            directiveId = "policy1",
            policyMode = None,
            PolicyTypes.fromTypes(tag1),
            components = List(
              new ValueExpectedReport("component1", ExpectedValueMatch("value", "value") :: Nil),
              new ValueExpectedReport("component2", ExpectedValueMatch("value", "value") :: Nil)
            )
          ),
          DirectiveExpectedReports(
            directiveId = "policy2",
            policyMode = None,
            PolicyTypes.fromTypes(tag1, tag2),
            components = List(
              new ValueExpectedReport("component1", ExpectedValueMatch("value", "value") :: Nil),
              new ValueExpectedReport("component2", ExpectedValueMatch("value", "value") :: Nil)
            )
          )
        )
      ),
      Seq[Reports](
        new ResultErrorReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy1",
          "one",
          "report_id12",
          "component1",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy1",
          "one",
          "report_id12",
          "component2",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy2",
          "one",
          "report_id12",
          "component1",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy2",
          "one",
          "report_id12",
          "component2",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        )
      )
    )

    // we have only 1 node ("one")
    val nodeStatus = getNodeStatusByRule(param).head._2

    "There is two policy types in the report" in {
      nodeStatus.reports.size === 2
    }
    "have detailed rule report for policy1 of 50% and 100% for policy2 on tag1" in {
      nodeStatus.reports(tag1).directives("policy1").compliance === ComplianceLevel(success = 1, error = 1) and
      nodeStatus.reports(tag1).directives("policy2").compliance === ComplianceLevel(success = 2)
    }
    "have detailed rule report for policy1 of 100% for policy2 on tag2 and policy is not on that tag" in {
      nodeStatus.reports(tag2).directives.get("policy1") must beNone and
      nodeStatus.reports(tag2).directives("policy2").compliance === ComplianceLevel(success = 2)
    }

    "have compliance of 75% for tag1 and 100% for tag2" in {
      nodeStatus.reports(tag1).compliance === ComplianceLevel(success = 3, error = 1) and
      nodeStatus.reports(tag2).compliance === ComplianceLevel(success = 2)
    }
  }

  "A rule with two directives, one overridden in another rule, has the overridden rule" >> {
    val overridingPolicyId =
      PolicyId(RuleId(RuleUid("overridingRule")), DirectiveId(DirectiveUid("policy1")), TechniqueVersion.V1_0)

    val param = (
      buildExpected(
        List("one"),
        "rule",
        12,
        List(
          DirectiveExpectedReports(
            directiveId = "policy1",
            policyMode = None,
            PolicyTypes.rudderBase,
            components = List(
              new ValueExpectedReport("component1", ExpectedValueMatch("value", "value") :: Nil),
              new ValueExpectedReport("component2", ExpectedValueMatch("value", "value") :: Nil)
            )
          ),
          DirectiveExpectedReports(
            directiveId = "policy2",
            policyMode = None,
            PolicyTypes.rudderBase,
            components = List(
              new ValueExpectedReport("component1", ExpectedValueMatch("value", "value") :: Nil),
              new ValueExpectedReport("component2", ExpectedValueMatch("value", "value") :: Nil)
            )
          )
        )
      ).map { // we want to say we have an overriding rule, `overridingRule`
        case (k, v) =>
          (
            k,
            v.modify(_.overrides)
              .setTo(
                List(
                  OverriddenPolicy(
                    policy = PolicyId(RuleId(RuleUid("rule")), DirectiveId(DirectiveUid("policy1")), TechniqueVersion.V1_0),
                    overriddenBy = overridingPolicyId
                  )
                )
              )
          )
      },
      Seq[Reports](
        new ResultErrorReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy1",
          "one",
          "report_id12",
          "component1",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy1",
          "one",
          "report_id12",
          "component2",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy2",
          "one",
          "report_id12",
          "component1",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        ),
        new ResultSuccessReport(
          DateTime.now(DateTimeZone.UTC),
          "rule",
          "policy2",
          "one",
          "report_id12",
          "component2",
          "value",
          DateTime.now(DateTimeZone.UTC),
          "message"
        )
      )
    )

    // we have only 1 node ("one")
    val nodeStatus = getNodeStatusByRule(param).head._2

    "have detailed rule report for policy1 empty and overridden and 100% for policy2" in {
      val policy1 = nodeStatus.reports(PolicyTypeName.rudderBase).directives("policy1")

      policy1.compliance === ComplianceLevel() // it's overridden
      policy1.overridden === Some(overridingPolicyId.ruleId)

      nodeStatus.reports(PolicyTypeName.rudderBase).directives("policy2").compliance === ComplianceLevel(success = 2)
    }
  }

  /*
   * Rule with all directive overridden ARE present in the NodeStatusReport.
   */
  "A rule with one directive overridden in another rule, has the overridden rule" >> {
    val overridingPolicyId =
      PolicyId(RuleId(RuleUid("overridingRule")), DirectiveId(DirectiveUid("policy1")), TechniqueVersion.V1_0)

    val param = (
      buildExpected(
        List("one"),
        "rule",
        12,
        List(
          DirectiveExpectedReports(
            directiveId = "policy1",
            policyMode = None,
            PolicyTypes.rudderBase,
            components = List()
          )
        )
      ).map { // we want to say we have an overriding rule, `overridingRule`
        case (k, v) =>
          (
            k,
            v.modify(_.overrides)
              .setTo(
                List(
                  OverriddenPolicy(
                    policy = PolicyId(RuleId(RuleUid("rule")), DirectiveId(DirectiveUid("policy1")), TechniqueVersion.V1_0),
                    overriddenBy = overridingPolicyId
                  )
                )
              )
          )
      },
      Seq[Reports]()
    )

    // we have only 1 node ("one")
    val nodeStatus = getNodeStatusByRule(param).head._2

    "have rule report for policy1 empty and overridden" in {
      val policy1 = nodeStatus.reports(PolicyTypeName.rudderBase).directives.get("policy1")

      policy1 must beSome
      policy1.get.compliance === ComplianceLevel() // it's overridden
      policy1.get.overridden === Some(overridingPolicyId.ruleId)
    }
  }

}
