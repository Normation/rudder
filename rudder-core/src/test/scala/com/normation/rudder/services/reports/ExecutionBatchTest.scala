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

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyModeOverrides
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.SerialedRuleId
import com.normation.rudder.domain.policies.SerialedRuleId
import com.normation.rudder.domain.policies.SerialedRuleId
import com.normation.rudder.domain.reports._
import com.normation.rudder.domain.reports.DirectiveExpectedReports
import com.normation.rudder.reports.ChangesOnly
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.FullCompliance
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.reports.ReportsDisabled
import com.normation.rudder.reports.ResolvedAgentRunInterval
import com.normation.rudder.reports.execution.AgentRun
import com.normation.rudder.reports.execution.AgentRunId

import org.joda.time.DateTime
import org.joda.time.Duration
import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import com.normation.rudder.services.policies.NodeConfigData
import com.normation.rudder.reports.AgentRunInterval



@RunWith(classOf[JUnitRunner])
class ExecutionBatchTest extends Specification {
  private implicit def str2directiveId(s:String) = DirectiveId(s)
  private implicit def str2ruleId(s:String) = RuleId(s)
  private implicit def str2nodeId(s:String) = NodeId(s)
  private implicit def str2nodeConfigIds(ss:Seq[String]) = ss.map(s =>  (NodeId(s), Some(NodeConfigId("version_" + s)))).toMap

  import ReportType._

  def buildExpected(
      nodeIds: Seq[String]
    , ruleId : String
    , serial : Int
    , directives: List[DirectiveExpectedReports]
  ): Map[NodeId, NodeExpectedReports] = {
    val rid = SerialedRuleId(RuleId(ruleId), serial)
//    val exp = Map(rid -> RuleNodeExpectedReports(rid.ruleId, rid.serial, directives))
    val globalPolicyMode = GlobalPolicyMode(PolicyMode.Audit, PolicyModeOverrides.Always)
    val now = DateTime.now
    val mode = NodeModeConfig(GlobalComplianceMode(FullCompliance, 30), None, AgentRunInterval(None, 5, 14, 5, 4), None, globalPolicyMode, Some(PolicyMode.Enforce))
    nodeIds.map { id =>
      (NodeId(id) -> NodeExpectedReports(NodeId(id), NodeConfigId("version_" + id), now, None, mode
                       , List(RuleExpectedReports(RuleId(ruleId), serial, directives))
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

      (nodeId, ExecutionBatch.getNodeStatusReports(nodeId, runInfo, reportsParam))
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
    val executionTimestamp = new DateTime()
    val reports = Seq[ResultReports](
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "foo", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "bar", executionTimestamp, "message")
    )

    val badReports = Seq[ResultReports](
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "foo", executionTimestamp, "message"),
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "foo", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "bar", executionTimestamp, "message")
    )

    val expectedComponent = new ComponentExpectedReport(
        "component"
      , 2
      , List("foo", "bar")
      , List("foo", "bar")
    )

    val withGood = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, reports, NoAnswer, PolicyMode.Enforce)
    val withBad  = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, badReports, NoAnswer, PolicyMode.Enforce)

    "return a component globally repaired " in {
      withGood.compliance === ComplianceLevel(success = 1, repaired = 1)
    }
    "return a component with two key values " in {
      withGood.componentValues.size === 2
    }
    "return a component with the key values foo which is repaired " in {
      withGood.componentValues("foo").messages.size === 1 and
      withGood.componentValues("foo").messages.head.reportType ===  EnforceRepaired
    }
    "return a component with the key values bar which is a success " in {
      withGood.componentValues("bar").messages.size === 1 and
      withGood.componentValues("bar").messages.head.reportType ===  EnforceSuccess
    }

    "only one reports in plus, mark the whole key unexpected" in {
      withBad.compliance === ComplianceLevel(success = 1,  unexpected = 2)
    }
    "with bad reports return a component with two key values " in {
      withBad.componentValues.size === 2
    }
    "with bad reports return a component with the key values foo which is unknwon " in {
      withBad.componentValues("foo").messages.size === 2 and
      withBad.componentValues("foo").messages.head.reportType ===  Unexpected
    }
    "with bad reports return a component with the key values bar which is a success " in {
      withBad.componentValues("bar").messages.size === 1 and
      withBad.componentValues("bar").messages.head.reportType ===  EnforceSuccess
    }
  }

  // Test the component part
  "A component, with a None keys" should {
    val executionTimestamp = new DateTime()
    val reports = Seq[ResultReports](
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "None", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "None", executionTimestamp, "message")
    )

    val badReports = Seq[ResultReports](
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "None", executionTimestamp, "message"),
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "None", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "None", executionTimestamp, "message")
    )

    val expectedComponent = new ComponentExpectedReport(
        "component"
      , 2
      , List("None", "None")
      , List("None", "None")
    )
    val withGood = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, reports, NoAnswer, PolicyMode.Enforce)
    val withBad  = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, badReports, NoAnswer, PolicyMode.Enforce)

    "return a component with exact reporting" in {
      withGood.compliance === ComplianceLevel(repaired = 1, success = 1)
    }
    "return a component with one key value " in {
      withGood.componentValues.size === 1
    }
    "return a component with exact reporting in None key" in {
      withGood.componentValues("None").messages.size === 2 and
      withGood.componentValues("None").compliance === ComplianceLevel(repaired = 1, success = 1)
    }

    "with bad reports return a component globally unexpected " in {
      withBad.compliance === ComplianceLevel(unexpected = 3)
    }
    "with bad reports return a component with one key values (only None)" in {
      withBad.componentValues.size === 1
    }
    "with bad reports return a component with None key unexpected " in {
      withBad.componentValues("None").messages.size === 3 and
      withBad.componentValues("None").messages.forall(x => x.reportType === Unexpected)
    }
  }

  // Test the component part
  "A component, with a cfengine keys" should {
    val executionTimestamp = new DateTime()
    val reports = Seq[ResultReports](
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "/var/cfengine", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "/var/cfengine", executionTimestamp, "message")
    )

    val badReports = Seq[ResultReports](
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "/var/cfengine", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "/var/cfengine", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "/var/cfengine", executionTimestamp, "message")
    )

    val expectedComponent = new ComponentExpectedReport("component", 2
      , List("${sys.bla}", "${sys.foo}")
      , List("${sys.bla}", "${sys.foo}")
    )

    val withGood = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, reports, NoAnswer, PolicyMode.Enforce)
    val withBad   = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, badReports, NoAnswer, PolicyMode.Enforce)

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
    val reports = Seq[ResultReports](
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "bar", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "node2", executionTimestamp, "message"),
        new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "node1", executionTimestamp, "message")
    )

    val badReports = Seq[ResultReports](
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
      , List("node1", "node2", "bar")
      , List("${rudder.node.hostname}", "${rudder.node.hostname}", "bar")
    )

    val withGood = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, reports, NoAnswer, PolicyMode.Enforce)
    val withBad  = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, badReports, NoAnswer, PolicyMode.Enforce)

    "return a component with the correct number of success and repaired" in {
      //be carefull, here the second success is for the same unexpanded as the repaire,
      withGood.compliance === ComplianceLevel(success = 2, repaired = 1)
    }

    "return a component with two key values " in {
      withGood.componentValues.size === 2
    }

    "return an unexpanded component key with one key repaired and one success" in {
      val reportType = withGood.componentValues("${rudder.node.hostname}").messages.map(_.reportType)
      reportType.size === 2 and
      (reportType.exists( _ == EnforceRepaired)) and
      (reportType.exists( _ == EnforceSuccess))
    }

    "return a component with the bar key success " in {
      withGood.componentValues("bar").messages.size === 1 and
      withGood.componentValues("bar").messages.forall(x => x.reportType === EnforceSuccess)
    }

    "with some bad reports mark them as unexpected (because the check is not done in checkExpectedComponentWithReports" in {
      withBad.compliance ===  ComplianceLevel(success = 1, unexpected = 3)
    }
    "with bad reports return a component with two key values " in {
      withBad.componentValues.size === 2
    }
    "with bad reports return a component with bar as a success " in {
      withBad.componentValues("bar").messages.size === 1 and
      withBad.componentValues("bar").messages.forall(x => x.reportType === EnforceSuccess)
    }
    "with bad reports return a component with the cfengine key as unexpected " in {
      withBad.componentValues("${rudder.node.hostname}").messages.size === 3 and
      withBad.componentValues("${rudder.node.hostname}").messages.forall(x => x.reportType === Unexpected)
    }
  }

  "Compliance for cfengine vars and reports" should {

    sealed trait Kind { def value: String ; def tpe: ReportType }
    final case class Success   (value: String) extends Kind { val tpe = EnforceSuccess }
    final case class Repaired  (value: String) extends Kind { val tpe = EnforceRepaired }
    final case class Error     (value: String) extends Kind { val tpe = EnforceError }
    final case class Missing   (value: String) extends Kind { val tpe = ReportType.Missing }
    final case class Unexpected(value: String) extends Kind { val tpe = ReportType.Unexpected }

    def test(id: String, patterns: Seq[Kind], reports: Seq[Kind]) = {
      val executionTimestamp = new DateTime()

      val expectedComponent = {
        val expectOnlySuccess = patterns.collect {
          case Success(x) => x
          case Repaired(x) => x
        }
        new ComponentExpectedReport("component", 2, expectOnlySuccess.toList, expectOnlySuccess.toList)
      }

      val resultReports : Seq[ResultReports] = reports.map( x => x match {
        case Success(v) => new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", v, executionTimestamp, "message")
        case Repaired(v) => new ResultRepairedReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", v, executionTimestamp, "message")
        case x => new ResultErrorReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", x.value, executionTimestamp, "message")
      })

      val t1 = System.currentTimeMillis
      val result = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, resultReports, NoAnswer, PolicyMode.Enforce)
      val t2 = System.currentTimeMillis - t1

      val compliance = ComplianceLevel(
          success = patterns.collect { case x:Success => x }.size
        , repaired = patterns.collect { case x:Repaired => x }.size
        , error = patterns.collect { case x:Error => x }.size
        , missing = patterns.collect { case x:Missing => x }.size
        , unexpected = patterns.collect { case x:Unexpected => x }.size
      )

      s"[${id}] be OK with patterns ${patterns}" in {
        ( (compliance === result.compliance) /: patterns) { case( example, nextPattern) =>
          (example /: result.componentValues(nextPattern.value).messages) { case (newExample, nextMessage) =>
            val msgCompliance = ComplianceLevel.compute(List( nextMessage.reportType))
            val patternCompliance = ComplianceLevel.compute(List( nextPattern.tpe))
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
      , patterns = Success("/etc/foo.old.${sys.bla}") :: Repaired("/etc/foo.${sys.bla}") :: Nil
      , reports  = Success("/etc/foo.old.txt") :: Repaired("/etc/foo.txt") :: Nil
    )

    test("order2"
      , patterns = Success("/etc/foo.old.${sys.bla}") :: Repaired("/etc/foo.${sys.bla}") :: Nil
      , reports  = Repaired("/etc/foo.txt") :: Success("/etc/foo.old.txt") :: Nil
    )

    test("order3"
      , patterns = Repaired("/etc/foo.${sys.bla}") :: Success("/etc/foo.old.${sys.bla}") :: Nil
      , reports  = Success("/etc/foo.old.txt") :: Repaired("/etc/foo.txt") :: Nil
    )
    test("order4"
      , patterns = Repaired("/etc/foo.${sys.bla}") :: Success("/etc/foo.old.${sys.bla}") :: Nil
      , reports  = Repaired("/etc/foo.txt") :: Success("/etc/foo.old.txt") :: Nil
    )

    //
    test("one var"
      , patterns = Repaired("${sys.bla}") :: Success("bar") :: Nil
      , reports  = Repaired("/var/cfengine") :: Success("bar") :: Nil
    )

    /*
     * For the next three, the logic is that:
     * - we successfully matched the constant value;
     * - we successfully matched the cfengine value (taking at random the report type between
     *   success and repaired - but we have no way to decide ! Ok, and the random is more like
     *   "the first in the list", so it helps for the test)
     * - have one unexpected, for the last message - and we don't have any way to decide if it
     *   comes from a cfengine var or not !
     */
    // here, we have one unexpected report. We can't know if it is the success or the repaired, so
    // by implementation, the last is taken as unexpected.
    // So, if you swap the order of the two "/var/cfengine" reports, the test will fail
    test("one var and simple reports"
      , patterns = Repaired("${sys.bla}") :: Success("bar") :: Unexpected("/var/cfengine") :: Nil
      , reports  = Repaired("/var/cfengine") :: Success("/var/cfengine") :: Success("bar") :: Nil
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
      , patterns = Repaired("${sys.bla}") :: Success("${sys.foo}") :: Nil
      , reports  = Repaired("/var/cfengine") :: Success("/var/cfengine") :: Nil
    )

    test("same patterns with unexpected"
      , patterns = Repaired("${sys.bla}") :: Success("${sys.foo}") :: Unexpected("/var/cfengine") :: Nil
      , reports  = Repaired("/var/cfengine") :: Success("/var/cfengine") :: Success("/var/cfengine") :: Nil
    )

    /*
     * A test with a lots of variables and reports, to see if the execution time remains OK
     */
    test("lots of reports"
      , patterns =
             Success("/var/cfengine")
          :: Repaired("${sys.bla}")
          :: Success("${sys.foo}")
          :: Success("/etc/foo.old.${sys.bla}")
          :: Repaired("/etc/foo.${sys.bla}")
          :: Success("a${foo}b${bar}")
          :: Success("a${foo}b")
          :: Success("b${foo}b")
          :: Success("b${foo}c")
          :: Success("b${foo}d")
          :: Nil
      , reports  =
             Success("/var/cfengine")
          :: Repaired("/var/cfengine")
          :: Repaired("/etc/foo.txt")
          :: Success("/etc/foo.old.txt")
          :: Success("/var/cfengine")
          :: Success("aXbX")
          :: Success("aYb")
          :: Success("bXb")
          :: Success("bc")
          :: Success("bZd")
          :: Nil
    )

    test("same report for simple and pattern"
      , patterns = Success("/var/${sys.bla}") :: Success("/var/cfengine") :: Nil
      , reports  = Success("/var/cfengine") :: Success("/var/cfengine") :: Nil
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
                , List(new ComponentExpectedReport("component", 1, List("value"), List() )) //here, we automatically must have "value" infered as unexpanded var
              )
            )
        )
      , Seq[Reports](new ResultSuccessReport(DateTime.now(), "rule", "policy", "one", 12, "component", "value", DateTime.now(), "message"))
      , fullCompliance
    )

    val nodeStatus = (getNodeStatusReportsByRule _).tupled(param)

    "have one detailed reports when we create it with one report" in {
      nodeStatus(one).report.reports.size === 1
    }

    "have one detailed success node when we create it with one success report" in {
      nodeStatus.keySet.head === one
    }

    "have one detailed rule success directive when we create it with one success report" in {
      nodeStatus(one).report.reports.head.directives.head._1 === DirectiveId("policy")
    }

    "have no detailed rule non-success directive when we create it with one success report" in {
      AggregatedStatusReport(nodeStatus(one).report.reports).compliance === ComplianceLevel(success = 1)
    }
  }

  "A detailed execution Batch, with one component, cardinality one, wrong node" should {
    val param = (
        buildExpected(
            List("one")
          , "rule"
          , 12
          , List(DirectiveExpectedReports("policy", None, false
                , List(new ComponentExpectedReport("component", 1, List("value"), List() ))
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
      (nodeStatus.keySet.head === one) and
      AggregatedStatusReport(nodeStatus.values.flatMap(_.report.reports).toSet).compliance === ComplianceLevel(missing = 1)
    }
  }

  "A detailed execution Batch, with one component, cardinality one, one node" should {

    val param = (
        buildExpected(
            List("one")
          , "rule"
          , 12
          , List(DirectiveExpectedReports("policy", None, false
                , List(new ComponentExpectedReport("component", 1, List("value"), List() ))
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
      nodeStatus.head._1 === one and
      AggregatedStatusReport(nodeStatus.values.flatMap(_.report.reports).toSet).compliance === ComplianceLevel(unexpected = 2)
    }
  }

   "A detailed execution Batch, with one component, cardinality one, two nodes, including one not responding" should {

    val param = (
        buildExpected(
            List("one", "two")
          , "rule"
          , 12
          , List(DirectiveExpectedReports("policy", None, false
                , List(new ComponentExpectedReport("component", 1, List("value"), List() ))
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
      AggregatedStatusReport(nodeStatus.values.flatMap(_.report.reports).toSet).compliance === ComplianceLevel(success = 1, missing = 1)
    }
  }

  "A detailed execution Batch, with one component, cardinality one, three nodes, including one not responding" should {
    val param = (
        buildExpected(
            List("one", "two", "three")
          , "rule"
          , 12
          , List(DirectiveExpectedReports("policy", None, false
                , List(new ComponentExpectedReport("component", 1, List("value"), List() ))
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
      AggregatedStatusReport(nodeStatus.values.flatMap(_.report.reports).toSet).compliance === ComplianceLevel(success = 2, missing = 1)
    }
  }

  "A detailed execution Batch, with two directive, two component, cardinality one, three nodes, including one partly responding and one not responding" should {
    val param = (
        buildExpected(
            List("one", "two", "three")
          , "rule"
          , 12
          , List(DirectiveExpectedReports("policy", None, false, List(
                     new ComponentExpectedReport("component", 1, List("value"), List() )
                   , new ComponentExpectedReport("component2", 1, List("value"), List() )
                 ))
               , DirectiveExpectedReports("policy2", None, false, List(
                     new ComponentExpectedReport("component", 1, List("value"), List() )
                   , new ComponentExpectedReport("component2", 1, List("value"), List() )
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
    val aggregated = AggregatedStatusReport(nodeStatus.values.flatMap(_.report.reports).toSet)

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
                     new ComponentExpectedReport("component", 1, List("value"), List() )
                   , new ComponentExpectedReport("component2", 1, List("value"), List() )
                 ))
               , DirectiveExpectedReports("policy2", None, false, List(
                     new ComponentExpectedReport("component", 1, List("value"), List() )
                   , new ComponentExpectedReport("component2", 1, List("value"), List() )
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
    val aggregated = AggregatedStatusReport(nodeStatus.values.flatMap(_.report.reports).toSet)

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
            List("one", "two", "three")
          , "rule"
          , 12
          , List(DirectiveExpectedReports("policy", None, false, List(
                   new ComponentExpectedReport("component", 1, List("value", "value2", "value3"), List() )
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
    val aggregated = AggregatedStatusReport(nodeStatus.values.flatMap(_.report.reports).toSet)

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
            List("one")
          , "rule"
          , 12
          , List(DirectiveExpectedReports("policy", None, false, List(
                  new ComponentExpectedReport("component", 1, List("""some\"text"""), List("""some\text""") )
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
      nodeStatus.keySet.head === one and
      nodeStatus.head._2.report.reports.head.compliance.pc_success === 100
    }

  }

 "An execution Batch, with one component, one node, but with a component value being a cfengine variable with {, and a an escaped quote as well" should {

    val param = (
        buildExpected(
            List("nodeId")
          , "rule"
          , 12
          , List(DirectiveExpectedReports("policy", None, false, List(
                  new ComponentExpectedReport("component", 1, List("""${sys.workdir}/inputs/\"test"""), List() )
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
     nodeStatus.keySet.head === NodeId("nodeId") and
     nodeStatus.head._2.report.reports.head.compliance.pc_success === 100
    }
  }

  "An execution Batch, with one component, one node, but with a component value being a cfengine variable with {, and a quote as well" should {
    val param = (
        buildExpected(
            List("nodeId")
          , "rule"
          , 12
          , List(DirectiveExpectedReports("policy", None, false, List(
                new ComponentExpectedReport("component", 1, List("""${sys.workdir}/inputs/"test"""), List("""${sys.workdir}/inputs/"test""") )
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
     nodeStatus.keySet.head === NodeId("nodeId") and
     nodeStatus.head._2.report.reports.head.compliance.pc_success === 100
    }
  }

   // Test the component part - with NotApplicable
  "A component, with two keys and NotApplicable reports" should {
    val executionTimestamp = new DateTime()
    val reports = Seq[ResultReports](
        new ResultNotApplicableReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "/var/cfengine", executionTimestamp, "message"),
        new ResultSuccessReport(executionTimestamp, "cr", "policy", "nodeId", 12, "component", "bar", executionTimestamp, "message")
              )

    val expectedComponent = new ComponentExpectedReport(
        "component"
      , 2
      , List("/var/cfengine", "bar")
      , List("/var/cfengine", "bar")
    )

    val withGood = ExecutionBatch.checkExpectedComponentWithReports(expectedComponent, reports, NoAnswer, PolicyMode.Enforce)

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
