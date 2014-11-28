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

package com.normation.rudder.repository.jdbc

import scala.slick.driver.PostgresDriver.simple._
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.matcher.MatchResult
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import net.liftweb.common.Full
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports._
import com.normation.rudder.migration.DBCommon
import com.normation.rudder.reports.ChangesOnly
import com.normation.rudder.reports.execution._
import com.normation.rudder.reports.status.StatusUpdateSquerylRepository
import com.normation.rudder.reports.FullCompliance
import com.normation.rudder.services.reports.ExpectedReportsUpdateImpl
import com.normation.rudder.services.reports.ReportingServiceImpl
import com.normation.rudder.repository.NodeConfigIdInfo

/**
 *
 * Test reporting service:
 *
 */
@RunWith(classOf[JUnitRunner])
class ReportingServiceTest extends DBCommon {


  //clean data base
  def cleanTables() = {
    jdbcTemplate.execute("DELETE FROM ReportsExecution; DELETE FROM RudderSysEvents;")
  }

  val pgIn = new PostgresqlInClause(2)
  val reportsRepo = new ReportsJdbcRepository(jdbcTemplate)
  val slick = new SlickSchema(dataSource)
  val findExpected = new FindExpectedReportsJdbcRepository(jdbcTemplate, pgIn)
  val updateExpected = new UpdateExpectedReportsJdbcRepository(jdbcTemplate, new DataSourceTransactionManager(dataSource), findExpected, findExpected)
  val updateExpectedService = new ExpectedReportsUpdateImpl(updateExpected, updateExpected)

  val roAgentRun = new RoReportsExecutionJdbcRepository(jdbcTemplate, pgIn)
  val woAgentRun = new WoReportsExecutionSquerylRepository(squerylConnectionProvider, roAgentRun)

  val updateRuns = new ReportsExecutionService(reportsRepo, woAgentRun, new StatusUpdateSquerylRepository(squerylConnectionProvider), 1)

  import slick._


  //help differentiate run number with the millis
  //perfect case: generation are followe by runs one minute latter

  val gen1 = DateTime.now.minusMinutes(5*2).withMillisOfSecond(1)
  val run1 = gen1.plusMinutes(1).withMillisOfSecond(2)
  val gen2 = gen1.plusMinutes(5).withMillisOfSecond(3)
  val run2 = gen2.plusMinutes(1).withMillisOfSecond(4)
  //now
//  val gen3 = gen2.plusMinutes(5).withMillisOfSecond(3)
//  val run3 = gen3.minusMinutes(1)

/*
 * We need 5 nodes
 * - n0: no run
 * - n1: a run older than agent frequency, no config version
 * - n2: a run older than agent frequency, a config version n2_t1
 * - n3: both a run newer and older than agent frequency, no config version
 * - n4: both a run newer and older than agent frequency, a config version n4_t2
 *
 * Two expected reports, one for t0, one for t1
 * - at t0:
 * - r0 / d0 / c0 on all nodes
 * - r1 / d1 / c1 on all nodes
 *
 * - at t1:
 * - r0 / d0 / c0 on all nodes
 * - r2 / d2 / c2 on all nodes
 *
 *
 *
 * TODO: need to test for "overridden" unique directive on a node
 */


  val allNodes_t1 = Seq("n0", "n1", "n2", "n3", "n4").map(n => (NodeId(n), NodeConfigIdInfo(NodeConfigId(n+"_t1"), gen1, Some(gen2) ))).toMap
  val allNodes_t2 = Seq("n0", "n1", "n2", "n3", "n4").map(n => (NodeId(n), NodeConfigIdInfo(NodeConfigId(n+"_t2"), gen2, None ))).toMap

  val allConfigs = (allNodes_t1.toSeq ++ allNodes_t2).groupBy( _._1 ).mapValues( _.map( _._2 ) )

  val expecteds = (
    Map[SlickExpectedReports, Seq[SlickExpectedReportsNodes]]()
    ++ expect("r0", 1)( //r0 @ t1
        (1, "r0_d0", "r0_d0_c0", 1, """["r0_d0_c0_v0"]""", gen1, Some(gen2), allNodes_t1 )
      , (1, "r0_d0", "r0_d0_c1", 1, """["r0_d0_c1_v0"]""", gen1, Some(gen2), allNodes_t1 )
    )
    ++ expect("r1", 1)( //r1 @ t1
        (2, "r1_d1", "r1_d1_c0", 1, """["r1_d1_c0_v0"]""", gen1, Some(gen2), allNodes_t1 )
    )
    ++ expect("r0", 2)( //r0 @ t2
        (3, "r0_d0", "r0_d0_c0", 1, """["r0_d0_c0_v1"]""", gen2, None, allNodes_t2 )
      , (3, "r0_d0", "r0_d0_c1", 1, """["r0_d0_c1_v1"]""", gen2, None, allNodes_t2 )
    )
    ++ expect("r2", 1)( //r2 @ t2
        (4, "r2_d2", "r2_d2_c0", 1, """["r2_d2_c0_v0"]""", gen2, None, allNodes_t2 )
    )
  )

  val reports = (
    Map[String, Seq[Reports]]()
    + node("n0")(
        // no run
    )
    + node("n1")( //run 1: no config version ; no run 2
          ("hasPolicyServer-root", 1, "common", "common", "StartRun", run1, "result_success", "Start execution")
        , ("r0", 1, "r0_d0", "r0_d0_c0", "r0_d0_c0_v0", run1, "result_success", "msg")
        , ("r0", 1, "r0_d0", "r0_d0_c1", "r0_d0_c1_v0", run1, "result_success", "msg")
        , ("r1", 1, "r1_d1", "r1_d1_c0", "r1_d1_c0_v0", run1, "result_success", "msg")
        , ("hasPolicyServer-root", 1, "common", "common", "EndRun", run1, "result_success", "End execution")
    )
    + node("n2")( //run 1: config version ; no run 2
          ("hasPolicyServer-root", 1, "common", "common", "StartRun", run1, "result_success", "Start execution [n2_t1]")
        , ("r0", 1, "r0_d0", "r0_d0_c0", "r0_d0_c0_v0", run1, "result_success", "msg")
        , ("r0", 1, "r0_d0", "r0_d0_c1", "r0_d0_c1_v0", run1, "result_success", "msg")
        , ("r1", 1, "r1_d1", "r1_d1_c0", "r1_d1_c0_v0", run1, "result_success", "msg")
        , ("hasPolicyServer-root", 1, "common", "common", "EndRun", run1, "result_success", "End execution [n2_t1]")
    )
    + node("n3")( //run 1 and run 2 : no config version
          ("hasPolicyServer-root", 1, "common", "common", "StartRun", run1, "result_success", "Start execution")
        , ("r0", 1, "r0_d0", "r0_d0_c0", "r0_d0_c0_v0", run1, "result_success", "msg")
        , ("r0", 1, "r0_d0", "r0_d0_c1", "r0_d0_c1_v0", run1, "result_success", "msg")
        , ("r1", 1, "r1_d1", "r1_d1_c0", "r1_d1_c0_v0", run1, "result_success", "msg")
        , ("hasPolicyServer-root", 1, "common", "common", "EndRun", run1, "result_success", "End execution")
        , ("hasPolicyServer-root", 1, "common", "common", "StartRun", run2, "result_success", "Start execution")
        , ("r0", 2, "r0_d0", "r0_d0_c0", "r0_d0_c0_v1", run2, "result_success", "msg")
        , ("r0", 2, "r0_d0", "r0_d0_c1", "r0_d0_c1_v1", run2, "result_success", "msg")
        , ("r2", 1, "r2_d2", "r2_d2_c0", "r2_d2_c0_v0", run2, "result_success", "msg")
        , ("hasPolicyServer-root", 1, "common", "common", "EndRun", run2, "result_success", "End execution")
    )
    + node("n4")( //run 1 and run 2 : one config version
          ("hasPolicyServer-root", 1, "common", "common", "StartRun", run1, "result_success", "Start execution [n4_t1]")
        , ("r0", 1, "r0_d0", "r0_d0_c0", "r0_d0_c0_v0", run1, "result_success", "msg")
        , ("r0", 1, "r0_d0", "r0_d0_c1", "r0_d0_c1_v0", run1, "result_success", "msg")
        , ("r1", 1, "r1_d1", "r1_d1_c0", "r1_d1_c0_v0", run1, "result_success", "msg")
        , ("hasPolicyServer-root", 1, "common", "common", "EndRun", run1, "result_success", "End execution [n4_t1]")
        , ("hasPolicyServer-root", 1, "common", "common", "StartRun", run2, "result_success", "Start execution [n4_t2]")
        , ("r0", 2, "r0_d0", "r0_d0_c0", "r0_d0_c0_v1", run2, "result_success", "msg")
        , ("r0", 2, "r0_d0", "r0_d0_c1", "r0_d0_c1_v1", run2, "result_success", "msg")
        , ("r2", 1, "r2_d2", "r2_d2_c0", "r2_d2_c0_v0", run2, "result_success", "msg")
        , ("hasPolicyServer-root", 1, "common", "common", "EndRun", run2, "result_success", "End execution [n4_t2]")
    )
  )

  sequential

  step {
    slick.insertReports(reports.values.toSeq.flatten)
    slickExec { implicit session =>
      expectedReportsTable ++= expecteds.keySet
      expectedReportsNodesTable ++= expecteds.values.toSet.flatten
    }
    updateRuns.findAndSaveExecutions(42)
    updateRuns.findAndSaveExecutions(43) //need to be done one time for init, one time for actual work

    //add node configuration in repos, testing "add" method
    updateExpected.addNodeConfigIdInfo(allNodes_t1.mapValues(_.configId), gen1).openOrThrowException("I should be able to add node config id info")
    updateExpected.addNodeConfigIdInfo(allNodes_t2.mapValues(_.configId), gen2).openOrThrowException("I should be able to add node config id info")
  }


  "Testing set-up for expected reports and agent run" should {  //be in ExpectedReportsTest!
    //essentially test the combination of in/in(values clause

    "be correct for in(tuple) clause" in {
      val res = findExpected.getExpectedReports(configs(("n1","n1_t1")), Set()).openOrThrowException("'Test failled'")
      res.size must beEqualTo(2)
    }
    "be correct for in(value(tuple)) clause" in {
      val res = findExpected.getExpectedReports(configs(("n1","n1_t1"),("n2","n2_t1")), Set()).openOrThrowException("'Test failled'")
      res.size must beEqualTo(2)
    }

    "be correct for in('string') clause" in {
      val res = findExpected.getLastExpectedReports(nodes("n1"), Set()).openOrThrowException("'Test failled'")
      res.size must beEqualTo(2)
    }

    "be correct for in(value('string')) clause" in {
      val res = findExpected.getLastExpectedReports(nodes("n1","n2"), Set()).openOrThrowException("'Test failled'")
      res.size must beEqualTo(2)
    }

    "contains runs for node" in {
      val res = roAgentRun.getNodesLastRun(Set("n0", "n1", "n2", "n3", "n4").map(NodeId(_))).openOrThrowException("test failed")

      res must beEqualTo(agentRuns(
          ("n0" -> None                               )
        , ("n1" -> Some(( run1, None         , true )))
        , ("n2" -> Some(( run1, Some("n2_t1"), true )))
        , ("n3" -> Some(( run2, None         , true )))
        , ("n4" -> Some(( run2, Some("n4_t2"), true )))
      ))

    }

    "return the correct expected reports on hardcode query" in {

      val expectedInBase = findExpected.getExpectedReports(Set(
          NodeAndConfigId(NodeId("n4"),NodeConfigId("n4_t2"))
        , NodeAndConfigId(NodeId("n0"),NodeConfigId("n0_t2"))
        , NodeAndConfigId(NodeId("n3"),NodeConfigId("n3_t1"))
        , NodeAndConfigId(NodeId("n1"),NodeConfigId("n1_t1"))
        , NodeAndConfigId(NodeId("n2"),NodeConfigId("n2_t1"))
      ), Set(RuleId("r2")) ).openOrThrowException("Failed test, should be a full box")

      val expected = Seq(
          RuleExpectedReports(RuleId("r2"),1, List(
               DirectivesOnNodes(4
                   , Map(NodeId("n4") -> Some(NodeConfigId("n4_t2")), NodeId("n0") -> Some(NodeConfigId("n0_t2")))
                   , List(DirectiveExpectedReports(DirectiveId("r2_d2"), Seq(ComponentExpectedReport("r2_d2_c0",1,List("r2_d2_c0_v0"),List("r2_d2_c0_v0"))))))),gen2,None)
      )

      expectedInBase must containTheSameElementsAs(expected)

    }

    "contains the node config ids" in {

      val configs = findExpected.getNodeConfigIdInfos(allConfigs.keySet).openOrThrowException("Test failed: the box should be full")

       configs.values.flatten.flatten must containTheSameElementsAs(allConfigs.values.toSeq.flatten)

    }

  }

  /////////////////////////////////////////////////////////////////
  /////////////////////// RuleStatusReports ///////////////////////
  /////////////////////////////////////////////////////////////////

  ///////////////////////////////// changes only mode /////////////////////////////////

  "Finding rule status reports for the change only mode" should {
    val errorOnlyReportingService = new ReportingServiceImpl(findExpected, reportsRepo, roAgentRun, findExpected, () => 5, () => Full(ChangesOnly(1)))

    "get r0" in {
      val r = errorOnlyReportingService.findDirectiveRuleStatusReportsByRule(RuleId("r0"))
      val result = r.openOrThrowException("'Test failled'")

      val expected = Seq(
          /*
           * no run at all, so we are not getting anything.
           * As we have two configId in the node config base, and
           * the oldest is older that run period+5, it's "no anwser"
           * and we were expecting t2 here
           */
          nodeStatus("n0", None, Some("n0_t2"), "r0", 2,
              ("r0_d0", Seq(
                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", NoAnswerReportType, List("")))
                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", NoAnswerReportType, List("")))
              )
          ))
          /*
           * No second run, and since we don't have configId, we look how far
           * we are from the oldest config. As if it's ok, we compare with current run.
           * (because nothing better from user point of view).
           * So the run 1 are unexpected, and run 2 is success.
           */
        , nodeStatus("n1", Some(run1), Some("n1_t2"), "r0", 2,
              ("r0_d0", Seq(
                  compStatus("r0_d0_c0", ("r0_d0_c0_v0", UnexpectedReportType, List("msg")))
                , compStatus("r0_d0_c1", ("r0_d0_c1_v0", UnexpectedReportType, List("msg")))
                , compStatus("r0_d0_c0", ("r0_d0_c0_v1", SuccessReportType, List("")))
                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", SuccessReportType, List("")))
              )
          ))
          /*
           * Here, we have a config version in the run, but we only have one run for t1.
           * As t1 is still in grace period, that's a success for the
           * older config (and we are hoping for the new one).
           */
        , nodeStatus("n2", Some(run1), Some("n2_t1"), "r0", 1,
              ("r0_d0", Seq(
                  compStatus("r0_d0_c0", ("r0_d0_c0_v0", SuccessReportType, List("msg")))
                , compStatus("r0_d0_c1", ("r0_d0_c1_v0", SuccessReportType, List("msg")))
              )
          ))
          /*
           * Here, we don't have config id, and we had several run without.
           * The last run is *not* more than 5+5 minute after the oldest config, so we are
           * comparing. We compare with current state(not anything better).
           * Here, we would like to have run 1 data, and got run 2.
           *
           */
        , nodeStatus("n3", Some(run2), Some("n3_t2"), "r0", 2,
              ("r0_d0", Seq(
                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", SuccessReportType, List("msg")))
                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", SuccessReportType, List("msg")))
              )
          ))

          //here, it just works as expected
        , nodeStatus("n4", Some(run2), Some("n4_t2"), "r0", 2,
              ("r0_d0", Seq(
                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", SuccessReportType, List("msg")))
                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", SuccessReportType, List("msg")))
              )
          ))
      )

      compareNodeStatus(result.report.reports, expected)
    }

    "get r1" in {
      val r = errorOnlyReportingService.findDirectiveRuleStatusReportsByRule(RuleId("r1"))
      val result = r.openOrThrowException("'Test failled'")
      result.report.reports must beEqualTo(Set())
    }

    "get r2" in {
      val r = errorOnlyReportingService.findDirectiveRuleStatusReportsByRule(RuleId("r2"))
      val result = r.openOrThrowException("'Test failled'")


      /*
       * for r2, we only have a config at t2, so we:
       * - expects n0 because why not (still hoping for something for it)
       * - n4 : yes, of course
       * - n1: only run1, no config for r2 => no
       * - n2 and n3: no config in runs, but we do have runs: don't expects r2.
       */
      val expected = Seq(
          nodeStatus("n0", None, Some("n0_t2"), "r2", 1,
              ("r2_d2", Seq(
                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", NoAnswerReportType, List("")))
              )
          ))
          /*
           * Here, that's a success because we think that we still can get a run 2,
           * and so for now, all is good.
           */
        , nodeStatus("n1", Some(run1), Some("n1_t2"), "r2", 1,
              ("r2_d2", Seq(
                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", SuccessReportType, List("")))
              )
          ))
          /*
           * For n2, we don't expects anything for r2 because it ran a first run
           * and we are still in the grace period. So we are still in the previous
           * state.
           */
//        , nodeStatus("n2", Some(run1), Some("n2_t2"), "r2", 2,
//              ("r2_d2", Seq(
//                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", MissingReportType, List("")))
//              )
//          ))
        , nodeStatus("n3", Some(run2), Some("n3_t2"), "r2", 1,
              ("r2_d2", Seq(
                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", SuccessReportType, List("msg")))
              )
          ))
        , nodeStatus("n4", Some(run2), Some("n4_t2"), "r2", 1,
              ("r2_d2", Seq(
                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", SuccessReportType, List("msg")))
              )
          ))

      )
      compareNodeStatus(result.report.reports, expected)
    }
  }

  ///////////////////////////////// full compliance mode /////////////////////////////////

  "Finding rule status reports for the compliance mode" should {
    val complianceReportingService = new ReportingServiceImpl(findExpected, reportsRepo, roAgentRun, findExpected, () => 5, () => Full(FullCompliance))


    "get r0" in {
      val r = complianceReportingService.findDirectiveRuleStatusReportsByRule(RuleId("r0"))
      val result = r.openOrThrowException("'Test failled'")

      val expected = Seq(

          /*
           * n0: no run at all, so no run in the last 5 minutes but we are still
           * not expired => pending
           */
          nodeStatus("n0", None, Some("n0_t2"), "r0", 2,
              ("r0_d0", Seq(
                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", PendingReportType, List("")))
                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", PendingReportType, List("")))
              )
          ))
          /*
           * n1: only run1 and no config version, so one run in the last 5 minutes but
           * since no config version, we are looking for a newer result.
           * As the grace period is still running, it's "pending"
           */
        , nodeStatus("n1", None, Some("n1_t2"), "r0", 2,
              ("r0_d0", Seq(
                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", PendingReportType, List("")))
                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", PendingReportType, List("")))
              )
          ))
          /*
           * Here, we have pending, because we are still in the grace period and
           * hoping for run with config 2 to happen
           */
        , nodeStatus("n2", None, Some("n2_t2"), "r0", 2,
              ("r0_d0", Seq(
                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", PendingReportType, List("")))
                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", PendingReportType, List("")))
              )
          ))
          /*
           * We don't have a run with the correct version for now, but
           * the oldest config is still recend and we are still in grace period
           * for the latest => pending
           */
        , nodeStatus("n3", None, Some("n3_t2"), "r0", 2,
              ("r0_d0", Seq(
                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", PendingReportType, List("")))
                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", PendingReportType, List("")))
              )
          ))
        , nodeStatus("n4", Some(run2), Some("n4_t2"), "r0", 2,
              ("r0_d0", Seq(
                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", SuccessReportType, List("msg")))
                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", SuccessReportType, List("msg")))
              )
          ))
      )
      compareNodeStatus(result.report.reports, expected)
    }

    "get r1" in {
      val r = complianceReportingService.findDirectiveRuleStatusReportsByRule(RuleId("r1"))
      val result = r.openOrThrowException("'Test failled'")
      result.report.reports must beEqualTo(Set())
    }

    "get r2" in {
      val r = complianceReportingService.findDirectiveRuleStatusReportsByRule(RuleId("r2"))
      val result = r.openOrThrowException("'Test failled'")

      val expected = Seq(
          nodeStatus("n0", None, Some("n0_t2"), "r2", 1,
              ("r2_d2", Seq(
                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", PendingReportType, List("")))
              )
          ))
          /*
           * We got a run without config, so it can still happen
           */
        , nodeStatus("n1", None, Some("n1_t2"), "r2", 1,
              ("r2_d2", Seq(
                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", PendingReportType, List("")))
              )
          ))
        , nodeStatus("n2", None, Some("n2_t2"), "r2", 1,
              ("r2_d2", Seq(
                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", PendingReportType, List("")))
              )
          ))
        , nodeStatus("n3", None, Some("n3_t2"), "r2", 1,
              ("r2_d2", Seq(
                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", PendingReportType, List("")))
              )
          ))
        , nodeStatus("n4", Some(run2), Some("n4_t2"), "r2", 1,
              ("r2_d2", Seq(
                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", SuccessReportType, List("msg")))
              )
          ))

      )
      compareNodeStatus(result.report.reports, expected)
    }
  }

  /////////////////////////////////////////////////////////////////
  /////////////////////// NodeStatusReports ///////////////////////
  /////////////////////////////////////////////////////////////////

  ///////////////////////////////// error only mode /////////////////////////////////

  "Finding node status reports for the error only mode" should {
    val errorOnlyReportingService = new ReportingServiceImpl(findExpected, reportsRepo, roAgentRun, findExpected, () => 5, () => Full(ChangesOnly(1)))

    "get no answer for node 0 on gen2 data" in {
      val r = errorOnlyReportingService.findNodeStatusReport(NodeId("n0"))
      val result = r.openOrThrowException("'Test failled'")
      compareNodeStatus(result.report.reports, Seq(
          nodeStatus("n0", None, Some("n0_t2"), "r0", 2,
              ("r0_d0", Seq(
                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", NoAnswerReportType, List("")))
                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", NoAnswerReportType, List("")))
              )
          ))
        , nodeStatus("n0", None, Some("n0_t2"), "r2", 1,
              ("r2_d2", Seq(
                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", NoAnswerReportType, List("")))
              )
          ))
      ))
    }

    "report unexpected AND succes for node 1 on gen2 data, because we don't have config id no config mismatch but choose the last one and bad serial" in {
      val r = errorOnlyReportingService.findNodeStatusReport(NodeId("n1"))
      val result = r.openOrThrowException("'Test failled'")
      compareNodeStatus(result.byRules("r0").reports, Seq(
          nodeStatus("n1", Some(run1), Some("n1_t2"), "r0", 2,
              ("r0_d0", Seq(
                  compStatus("r0_d0_c0", ("r0_d0_c0_v0", UnexpectedReportType, List("msg")))
                , compStatus("r0_d0_c1", ("r0_d0_c1_v0", UnexpectedReportType, List("msg")))
                , compStatus("r0_d0_c0", ("r0_d0_c0_v1", SuccessReportType, List("")))
                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", SuccessReportType, List("")))
              )
          ))
      ))
    }

    "find the correct, older that agent frequency, last report based on configuration for node 2" in {
      val r = errorOnlyReportingService.findNodeStatusReport(NodeId("n2"))
      val result = r.openOrThrowException("'Test failled'")
      val all = result.byRules("r0").reports ++ result.byRules("r1").reports
      compareNodeStatus(all, Seq(
          nodeStatus("n2", Some(run1), Some("n2_t1"), "r0", 1,
              ("r0_d0", Seq(
                  compStatus("r0_d0_c0", ("r0_d0_c0_v0", SuccessReportType, List("msg")))
                , compStatus("r0_d0_c1", ("r0_d0_c1_v0", SuccessReportType, List("msg")))
              )
          ))
        , nodeStatus("n2", Some(run1), Some("n2_t1"), "r1", 1,
              ("r1_d1", Seq(
                  compStatus("r1_d1_c0", ("r1_d1_c0_v0", SuccessReportType, List("msg")))
              )
          ))
      ))
    }

    "find the correct last report based on last expectation for node 3" in {
      val r = errorOnlyReportingService.findNodeStatusReport(NodeId("n3"))
      val result = r.openOrThrowException("'Test failled'")
      val all = result.byRules("r0").reports
      compareNodeStatus(all, Seq(
          nodeStatus("n3", Some(run2), Some("n3_t2"), "r0", 2,
              ("r0_d0", Seq(
                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", SuccessReportType, List("msg")))
                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", SuccessReportType, List("msg")))
              )
          ))
      ))
    }

    "find the correct last report based on configuration for node 4" in {
      val r = errorOnlyReportingService.findNodeStatusReport(NodeId("n4"))
      val result = r.openOrThrowException("'Test failled'")
      val all = result.byRules("r0").reports ++ result.byRules("r2").reports
      compareNodeStatus(all, Seq(
          nodeStatus("n4", Some(run2), Some("n4_t2"), "r0", 2,
              ("r0_d0", Seq(
                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", SuccessReportType, List("msg")))
                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", SuccessReportType, List("msg")))
              )
          ))
        , nodeStatus("n4", Some(run2), Some("n4_t2"), "r2", 1,
              ("r2_d2", Seq(
                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", SuccessReportType, List("msg")))
              )
          ))
      ))
    }
  }

  ///////////////////////////////// full compliance mode /////////////////////////////////

  "Finding node status reports for the compliance mode" should {
    val complianceReportingService = new ReportingServiceImpl(findExpected, reportsRepo, roAgentRun, findExpected, () => 5, () => Full(FullCompliance))

    "get pending for node 0 on gen2 data (without msg)" in {
      val r = complianceReportingService.findNodeStatusReport(NodeId("n0"))
      val result = r.openOrThrowException("'Test failled'")
      val all = result.byRules("r0").reports
      compareNodeStatus(all, Seq(
          nodeStatus("n0", None, Some("n0_t2"), "r0", 2,
              ("r0_d0", Seq(
                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", PendingReportType, List("")))
                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", PendingReportType, List("")))
              )
          ))
      ))
    }

    "report pending for node 1 even because we can find the correct reports and run1 is still yound and not expired" in {
      val r = complianceReportingService.findNodeStatusReport(NodeId("n1"))
      val result = r.openOrThrowException("'Test failled'")
      compareNodeStatus(result.report.reports, Seq(
          nodeStatus("n1", None, Some("n1_t2"), "r0", 2,
              ("r0_d0", Seq(
                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", PendingReportType, List("")))
                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", PendingReportType, List("")))
              )
          ))
        , nodeStatus("n1", None, Some("n1_t2"), "r2", 1,
              ("r2_d2", Seq(
                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", PendingReportType, List("")))
              )
          ))
      ))
    }


    /*
     * This case is a little touchy because node2 sent reports for gen1/run1,
     * but not for gen2 (current expectation).
     * But reports from run1 don't have expired (they are not too far from now),
     * they are older than gen-time. And we can compare on nodeconfigid,
     * and on serial
     *
     * So here, we really expect data from gen2, and we get pending because the
     * expiration time is not spent for now.
     */
    "report 'pending' for node 2 even if we can find the correct reports and they are not expired" in {
      val r = complianceReportingService.findNodeStatusReport(NodeId("n2"))
      val result = r.openOrThrowException("'Test failled'")
      val all = result.byRules("r0").reports
      compareNodeStatus(all, Seq(
          nodeStatus("n2", None, Some("n2_t2"), "r0", 2,
              ("r0_d0", Seq(
                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", PendingReportType, List("")))
                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", PendingReportType, List("")))
              )
          ))
      ))
    }

    "find the correct last report based last expectation for node 3" in {
      val r = complianceReportingService.findNodeStatusReport(NodeId("n3"))
      val result = r.openOrThrowException("'Test failled'")
      val all = result.byRules("r0").reports
      compareNodeStatus(all, Seq(
          nodeStatus("n3", None, Some("n3_t2"), "r0", 2,
              ("r0_d0", Seq(
                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", PendingReportType, List("")))
                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", PendingReportType, List("")))
              )
          ))
      ))
    }

    "find the correct last report based on configuration for node 4" in {
      val r = complianceReportingService.findNodeStatusReport(NodeId("n4"))
      val result = r.openOrThrowException("'Test failled'")
      compareNodeStatus(result.report.reports, Seq(
          nodeStatus("n4", Some(run2), Some("n4_t2"), "r0", 2,
              ("r0_d0", Seq(
                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", SuccessReportType, List("msg")))
                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", SuccessReportType, List("msg")))
              )
          ))
        , nodeStatus("n4", Some(run2), Some("n4_t2"), "r2", 1,
              ("r2_d2", Seq(
                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", SuccessReportType, List("msg")))
              )
          ))
      ))
    }

    /*
     * TODO: a case for a node where the report sent are from the n-1 expectation
     * BUT they where sent AFTER the n expectation time, so they could be OK.
     * The check must happen on NodeConfigId for that case if we don't
     * want to have unexpected reports (what we get if we check on serial).
     * The case can even be set so that the reports look OK (same serial, same values)
     * but in fact, they are from a previous nodeConfigVersion (because one other directive
     * changed).
     */

  }

  ////////// utility methods //////////////

  /*
   * A comparator for NodeStatusReport that allows to more
   * quickly understand what is the problem
   */
  def compareNodeStatus(results:Set[RuleNodeStatusReport], expecteds:Seq[RuleNodeStatusReport]) = {
    results.toSeq.sortBy(ruleNodeComparator) must containTheSameElementsAs(expecteds.sortBy(ruleNodeComparator))
  }

  implicit def nodes(ids:String*):Set[NodeId] = ids.map( NodeId(_) ).toSet
  implicit def configs(ids:(String,String)*): Set[NodeAndConfigId] = {
    ids.map(id => NodeAndConfigId(NodeId(id._1), NodeConfigId(id._2))).toSet
  }

  implicit def toReport(t:(DateTime,String, String, String, Int, String, String, DateTime, String, String)): Reports = {
    implicit def toRuleId(s:String) = RuleId(s)
    implicit def toDirectiveId(s: String) = DirectiveId(s)
    implicit def toNodeId(s: String) = NodeId(s)

    Reports(t._1, t._2, t._3,t._4,t._5,t._6,t._7,t._8,t._9,t._10)
  }

  def ruleNodeComparator(x: RuleNodeStatusReport): String = {
    x.nodeId.value.toLowerCase + x.ruleId.value.toLowerCase + x.serial
  }

  def compStatus(id: String, values: (String, ReportType, List[String])*): ComponentStatusReport = {
    val v = values.map { case(value, tpe, msgs) =>
      val messages = msgs.map(m => MessageStatusReport(tpe, m))
      tpe match {
        case UnexpectedReportType => ComponentValueStatusReport(value, None, messages)
        case _ => ComponentValueStatusReport(value, Some(value), messages)
      }
    }
    ComponentStatusReport(id, ComponentValueStatusReport.merge(v))
  }

  def nodeStatus(id: String, run:Option[DateTime], version: Option[String], ruleId: String, serial: Int
      , directives: (String, Seq[ComponentStatusReport])*
  ): RuleNodeStatusReport = {
    RuleNodeStatusReport(
          NodeId(id), RuleId(ruleId), serial, run, version.map(NodeConfigId(_))
        , DirectiveStatusReport.merge(directives.map(d =>
            DirectiveStatusReport(DirectiveId(d._1), ComponentStatusReport.merge(d._2))
          ))
    )
  }

  def expect(ruleId: String, serial: Int)
            //           nodeJoinKey, directiveId  component   cardinality   componentValues  beging     end            , (nodeId, version)
            (expecteds: (Int        , String     , String    , Int         , String         , DateTime, Option[DateTime], Map[NodeId,NodeConfigIdInfo])*)
  : Map[SlickExpectedReports, Seq[SlickExpectedReportsNodes]] = {
    expecteds.map { exp =>
      SlickExpectedReports(None, exp._1, ruleId, serial, exp._2, exp._3, exp._4, exp._5, exp._5, exp._6, exp._7) ->
      exp._8.map{ case (nodeId, version) =>
        SlickExpectedReportsNodes(exp._1, nodeId.value, version.configId.value :: Nil)
      }.toSeq
    }.toMap
  }

  //         nodeId               ruleId  serial dirId   comp     keyVal   execTime   severity  msg
  def node(nodeId:String)(lines: (String, Int,   String, String,  String,  DateTime,  String,   String)*): (String, Seq[Reports]) = {
    (nodeId, lines.map(t => toReport((t._6, t._1, t._3, nodeId, t._2,t._4,t._5,t._6,t._7,t._8))))
  }

  implicit def toRuleId(id: String): RuleId = RuleId(id)

  implicit def agentRuns(runs:(String, Option[(DateTime, Option[String], Boolean)])*): Map[NodeId, Option[AgentRun]] = {
    runs.map { case (id, opt) =>
      NodeId(id) -> opt.map(e => AgentRun(AgentRunId(NodeId(id), e._1), e._2.map(NodeConfigId(_)), e._3))
    }.toMap
  }
}
