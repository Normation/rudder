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

import com.normation.BoxSpecMatcher
import com.normation.GitVersion
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueName
import com.normation.errors.*
import com.normation.inventory.domain.NodeId
import com.normation.rudder.db.DB
import com.normation.rudder.db.DBCommon
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.*
import com.normation.rudder.facts.nodes.CoreNodeFactRepository
import com.normation.rudder.facts.nodes.NodeFact
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.NoopFactStorage
import com.normation.rudder.facts.nodes.NoopGetNodesbySofwareName
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.AgentRunIntervalService
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.reports.ResolvedAgentRunInterval
import com.normation.rudder.reports.execution.*
import com.normation.rudder.repository.CategoryWithActiveTechniques
import com.normation.rudder.repository.ComplianceRepository
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.score.ScoreServiceManager
import com.normation.rudder.services.policies.NodeConfigData
import com.normation.rudder.services.reports.CachedFindRuleNodeStatusReports
import com.normation.rudder.services.reports.CachedNodeChangesServiceImpl
import com.normation.rudder.services.reports.DefaultFindRuleNodeStatusReports
import com.normation.rudder.services.reports.NodeChangesServiceImpl
import com.normation.rudder.services.reports.NodeConfigurationService
import com.normation.rudder.services.reports.NodeConfigurationServiceImpl
import com.normation.rudder.services.reports.ReportingServiceImpl
import com.normation.rudder.services.reports.UnexpectedReportInterpretation
import com.normation.rudder.tenants.DefaultTenantService
import com.normation.zio.*
import com.softwaremill.quicklens.*
import doobie.implicits.*
import net.liftweb.common.Box
import net.liftweb.common.Empty
import net.liftweb.common.Full
import org.joda.time.DateTime
import org.joda.time.Duration
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import scala.collection.SortedMap
import scala.concurrent.duration.FiniteDuration
import zio.*
import zio.interop.catz.*
import zio.syntax.*

/**
 *
 * Test reporting service:
 *
 */
@RunWith(classOf[JUnitRunner])
class ReportingServiceTest extends DBCommon with BoxSpecMatcher {
  self =>

  // clean data base
  def cleanTables(): Int = {
    transacRun(xa => sql"DELETE FROM ReportsExecution; DELETE FROM RudderSysEvents;".update.run.transact(xa))
  }

  val nodeFactRepo: CoreNodeFactRepository = {
    def build(id: String, mode: Option[PolicyMode]) = {
      (NodeId(id), NodeConfigData.fact1.modify(_.id).setTo(NodeId(id)).modify(_.rudderSettings.policyMode).setTo(mode))
    }

    val accepted = Seq(
      build("n0", None),
      build("n1", Some(PolicyMode.Enforce)),
      build("n2", Some(PolicyMode.Audit)),
      build("n3", Some(PolicyMode.Enforce)),
      build("n4", Some(PolicyMode.Audit))
    ).toMap

    val testSavePrechecks = Chunk.empty[NodeFact => IOResult[Unit]]

    (for {
      t <- DefaultTenantService.make(Nil)
      r <-
        CoreNodeFactRepository
          .make(NoopFactStorage, NoopGetNodesbySofwareName, t, Map(), accepted, Chunk.empty, testSavePrechecks)
    } yield r).runNow
  }

  val directivesLib = NodeConfigData.directives
  val directivesRepos: RoDirectiveRepository = new RoDirectiveRepository() {
    def getFullDirectiveLibrary(): IOResult[FullActiveTechniqueCategory] = directivesLib.succeed
    def getDirective(directiveId:               DirectiveUid): IOResult[Option[Directive]] = ???
    def getDirectiveWithContext(directiveId:    DirectiveUid): IOResult[Option[(Technique, ActiveTechnique, Directive)]] = ???
    def getActiveTechniqueAndDirective(id:      DirectiveId): IOResult[Option[(ActiveTechnique, Directive)]] = ???
    def getDirectives(activeTechniqueId:        ActiveTechniqueId, includeSystem: Boolean = false): IOResult[Seq[Directive]] = ???
    def getActiveTechniqueByCategory(
        includeSystem: Boolean = false
    ): IOResult[SortedMap[List[ActiveTechniqueCategoryId], CategoryWithActiveTechniques]] = ???
    def getActiveTechniqueByActiveTechnique(id: ActiveTechniqueId): IOResult[Option[ActiveTechnique]] = ???
    def getActiveTechnique(techniqueName:       TechniqueName): IOResult[Option[ActiveTechnique]] = ???
    def activeTechniqueBreadCrump(id:           ActiveTechniqueId): IOResult[List[ActiveTechniqueCategory]] = ???
    def getActiveTechniqueLibrary: IOResult[ActiveTechniqueCategory] = ???
    def getAllActiveTechniqueCategories(includeSystem: Boolean = false):           IOResult[Seq[ActiveTechniqueCategory]]    = ???
    def getActiveTechniqueCategory(id:                 ActiveTechniqueCategoryId): IOResult[Option[ActiveTechniqueCategory]] = ???
    def getParentActiveTechniqueCategory(id:           ActiveTechniqueCategoryId): IOResult[ActiveTechniqueCategory]         = ???
    def getParentsForActiveTechniqueCategory(id:       ActiveTechniqueCategoryId): IOResult[List[ActiveTechniqueCategory]]   = ???
    def getParentsForActiveTechnique(id:               ActiveTechniqueId):         IOResult[ActiveTechniqueCategory]         = ???
    def containsDirective(id:                          ActiveTechniqueCategoryId): UIO[Boolean]                              = ???

  }

  val rulesRepos: RoRuleRepository = new RoRuleRepository {
    def getOpt(ruleId:       RuleId):          IOResult[Option[Rule]] = ???
    def getAll(includeSytem: Boolean = false): IOResult[Seq[Rule]]    = ???
    def getIds(includeSytem: Boolean = false): IOResult[Set[RuleId]]  = ???

  }

  val dummyComplianceCache: dummyComplianceCache = new dummyComplianceCache
  class dummyComplianceCache extends CachedFindRuleNodeStatusReports {
    def defaultFindRuleNodeStatusReports:                                                DefaultFindRuleNodeStatusReports        = null
    def nodeFactRepository:                                                              NodeFactRepository                      = self.nodeFactRepo
    def nodeConfigrationService:                                                         NodeConfigurationService                = null
    def findDirectiveRuleStatusReportsByRule(ruleId: RuleId)(implicit qc: QueryContext): IOResult[Map[NodeId, NodeStatusReport]] =
      null
    def findNodeStatusReport(nodeId:           NodeId)(implicit qc: QueryContext): Box[NodeStatusReport] = null
    def findStatusReportsForDirective(directiveId: DirectiveId)(implicit
        qc: QueryContext
    ): IOResult[Map[NodeId, NodeStatusReport]] = null
    def findUserNodeStatusReport(nodeId:       NodeId)(implicit qc: QueryContext): Box[NodeStatusReport] = null
    def findSystemNodeStatusReport(nodeId:     NodeId)(implicit qc: QueryContext): Box[NodeStatusReport] = null
    def getGlobalUserCompliance()(implicit qc: QueryContext): Box[Option[(ComplianceLevel, Long)]] = null
    def findUncomputedNodeStatusReports(): Box[Map[NodeId, NodeStatusReport]] = null

    def getUserNodeStatusReports()(implicit qc: QueryContext):                  Box[Map[NodeId, NodeStatusReport]] = Full(Map())
    def getSystemAndUserCompliance(
        optNodeIds: Option[Set[NodeId]]
    )(implicit qc: QueryContext): IOResult[(Map[NodeId, ComplianceLevel], Map[NodeId, ComplianceLevel])] = ???
    def computeComplianceFromReports(reports:   Map[NodeId, NodeStatusReport]): Option[(ComplianceLevel, Long)]    = None

    override def batchSize: Int = 5000

    override def scoreServiceManager: ScoreServiceManager = null

    override def rulesRepo: RoRuleRepository = null
  }

  val RUDDER_JDBC_BATCH_MAX_SIZE = 5000
  lazy val pgIn                  = new PostgresqlInClause(2)
  lazy val reportsRepo           = new ReportsJdbcRepository(doobie)
  lazy val findExpected          = new FindExpectedReportsJdbcRepository(doobie, pgIn, RUDDER_JDBC_BATCH_MAX_SIZE)
  lazy val nodeConfigService     = new NodeConfigurationServiceImpl(findExpected)
  lazy val updateExpected        = new UpdateExpectedReportsJdbcRepository(doobie, pgIn, RUDDER_JDBC_BATCH_MAX_SIZE)

  lazy val agentRunService: agentRunService = new agentRunService
  class agentRunService extends AgentRunIntervalService() {
    private val interval = Duration.standardMinutes(5)
    private val nodes    = Seq("n0", "n1", "n2", "n3", "n4").map(n => (NodeId(n), ResolvedAgentRunInterval(interval, 1))).toMap
    def getGlobalAgentRun():                                  Box[AgentRunInterval]                      = Full(AgentRunInterval(None, interval.toStandardMinutes.getMinutes, 0, 0, 0))
    def getNodeReportingConfigurations(nodeIds: Set[NodeId]): Box[Map[NodeId, ResolvedAgentRunInterval]] = {
      Full(nodes.view.filterKeys(x => nodeIds.contains(x)).toMap)
    }
  }

  lazy val woAgentRun = new WoReportsExecutionRepositoryImpl(doobie)
  lazy val roAgentRun = new RoReportsExecutionRepositoryImpl(doobie, woAgentRun, nodeConfigService, pgIn, 200)

  lazy val dummyChangesCache: CachedNodeChangesServiceImpl = {
    new CachedNodeChangesServiceImpl(new NodeChangesServiceImpl(reportsRepo), () => Full(true)) {
      override def update(lowestId: Long, highestId: Long): Box[Unit] = Full(())
      override def countChangesByRuleByInterval(): Box[(Long, ChangesByRule)] = Empty
    }
  }

  lazy val dummyComplianceRepos: ComplianceRepository = new ComplianceRepository() {
    override def saveRunCompliance(reports: List[NodeStatusReport]): IOResult[Unit] = {
      ZIO.unit
    }
  }

  lazy val updateRuns: ReportsExecutionService = {
    new ReportsExecutionService(
      reportsRepo,
      new LastProcessedReportRepositoryImpl(doobie),
      dummyChangesCache,
      dummyComplianceCache,
      dummyComplianceRepos,
      FiniteDuration(1, "hour"),
      FiniteDuration(1, "hour")
    )
  }

  // help differentiate run number with the millis
  // perfect case: generation are followe by runs one minute latter

  lazy val gen1: DateTime = DateTime.now.minusMinutes(5 * 2).withMillisOfSecond(1)
  lazy val run1: DateTime = gen1.plusMinutes(1).withMillisOfSecond(2)
  lazy val gen2: DateTime = gen1.plusMinutes(5).withMillisOfSecond(3)
  lazy val run2: DateTime = gen2.plusMinutes(1).withMillisOfSecond(4)
  // now
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
   * (r2 is added, r1 is removed)
   *
   *
   * TODO: need to test for "overridden" unique directive on a node
   */

  // BE CAREFUL: we don't compare on expiration dates!
  val EXPIRATION_DATE = new DateTime(0)

  val allNodes_t1: Map[NodeId, NodeConfigIdInfo] =
    Seq("n0", "n1", "n2", "n3", "n4").map(n => (NodeId(n), NodeConfigIdInfo(NodeConfigId(n + "_t1"), gen1, Some(gen2)))).toMap
  val allNodes_t2: Map[NodeId, NodeConfigIdInfo] =
    Seq("n0", "n1", "n2", "n3", "n4").map(n => (NodeId(n), NodeConfigIdInfo(NodeConfigId(n + "_t2"), gen2, None))).toMap

  val allConfigs: Map[NodeId, Seq[NodeConfigIdInfo]] = (allNodes_t1.toSeq ++ allNodes_t2).groupMap(_._1)(_._2)

  // this need to be NodeConfiguration (ex in NodeConfigData)

//  val expecteds = (
//    Map[DB.ExpectedReports[Unit], Seq[DB.ExpectedReportsNodes]]()
//    ++ expect("r0", 1)( //r0 @ t1
//        (1, "r0_d0", "r0_d0_c0", "report_id", """["r0_d0_c0_v0"]""", gen1, Some(gen2), allNodes_t1 )
//      , (1, "r0_d0", "r0_d0_c1", "report_id", """["r0_d0_c1_v0"]""", gen1, Some(gen2), allNodes_t1 )
//    )
//    ++ expect("r1", 1)( //r1 @ t1
//        (2, "r1_d1", "r1_d1_c0", "report_id", """["r1_d1_c0_v0"]""", gen1, Some(gen2), allNodes_t1 )
//    )
//    ++ expect("r0", 2)( //r0 @ t2
//        (3, "r0_d0", "r0_d0_c0", "report_id", """["r0_d0_c0_v1"]""", gen2, None, allNodes_t2 )
//      , (3, "r0_d0", "r0_d0_c1", "report_id", """["r0_d0_c1_v1"]""", gen2, None, allNodes_t2 )
//    )
//    ++ expect("r2", 1)( //r2 @ t2
//        (4, "r2_d2", "r2_d2_c0", "report_id", """["r2_d2_c0_v0"]""", gen2, None, allNodes_t2 )
//    )
//  )

  val reports: Map[String, Seq[Reports]] = (
    Map[String, Seq[Reports]]()
      + node("n0")(
        // no run
      )
      + node("n1")( // run 1: no config version ; no run 2
        ("rudder", "report_id", "common", "start", "", run1, "control", "Start execution"),
        ("r0", "report_id", "r0_d0", "r0_d0_c0", "r0_d0_c0_v0", run1, "result_success", "msg"),
        ("r0", "report_id", "r0_d0", "r0_d0_c1", "r0_d0_c1_v0", run1, "result_success", "msg"),
        ("r1", "report_id", "r1_d1", "r1_d1_c0", "r1_d1_c0_v0", run1, "result_success", "msg"),
        ("rudder", "report_id", "common", "end", "", run1, "control", "End execution")
      )
      + node("n2")( // run 1: config version ; no run 2
        ("rudder", "report_id", "common", "start", "n2_t1", run1, "control", "Start execution"),
        ("r0", "report_id", "r0_d0", "r0_d0_c0", "r0_d0_c0_v0", run1, "result_success", "msg"),
        ("r0", "report_id", "r0_d0", "r0_d0_c1", "r0_d0_c1_v0", run1, "result_success", "msg"),
        ("r1", "report_id", "r1_d1", "r1_d1_c0", "r1_d1_c0_v0", run1, "result_success", "msg"),
        ("rudder", "report_id", "common", "end", "n2_t1", run1, "control", "End execution")
      )
      + node("n3")( // run 1 and run 2 : no config version
        ("rudder", "report_id", "common", "start", "", run1, "control", "Start execution"),
        ("r0", "report_id", "r0_d0", "r0_d0_c0", "r0_d0_c0_v0", run1, "result_success", "msg"),
        ("r0", "report_id", "r0_d0", "r0_d0_c1", "r0_d0_c1_v0", run1, "result_success", "msg"),
        ("r1", "report_id", "r1_d1", "r1_d1_c0", "r1_d1_c0_v0", run1, "result_success", "msg"),
        ("rudder", "report_id", "common", "end", "", run1, "control", "End execution"),
        ("rudder", "report_id", "common", "start", "", run2, "control", "Start execution"),
        ("r0", "report_id2", "r0_d0", "r0_d0_c0", "r0_d0_c0_v1", run2, "result_success", "msg"),
        ("r0", "report_id2", "r0_d0", "r0_d0_c1", "r0_d0_c1_v1", run2, "result_success", "msg"),
        ("r2", "report_id", "r2_d2", "r2_d2_c0", "r2_d2_c0_v0", run2, "result_success", "msg"),
        ("rudder", "report_id", "common", "end", "", run2, "control", "End execution")
      )
      + node("n4")( // run 1 and run 2 : one config version
        ("rudder", "report_id", "common", "start", "n4_t1", run1, "control", "Start execution"),
        ("r0", "report_id", "r0_d0", "r0_d0_c0", "r0_d0_c0_v0", run1, "result_success", "msg"),
        ("r0", "report_id", "r0_d0", "r0_d0_c1", "r0_d0_c1_v0", run1, "result_success", "msg"),
        ("r1", "report_id", "r1_d1", "r1_d1_c0", "r1_d1_c0_v0", run1, "result_success", "msg"),
        ("rudder", "report_id", "common", "end", "n4_t1", run1, "control", "End execution"),
        ("rudder", "report_id", "common", "start", "n4_t1", run2, "control", "Start execution"),
        ("r0", "report_id2", "r0_d0", "r0_d0_c0", "r0_d0_c0_v1", run2, "result_success", "msg"),
        ("r0", "report_id2", "r0_d0", "r0_d0_c1", "r0_d0_c1_v1", run2, "result_success", "msg"),
        ("r2", "report_id", "r2_d2", "r2_d2_c0", "r2_d2_c0_v0", run2, "result_success", "msg"),
        ("rudder", "report_id", "common", "end", "n4_t1", run2, "control", "End execution")
      )
  )

  def buildReportingService(compliance: GlobalComplianceMode) = new ReportingServiceImpl(
    findExpected,
    reportsRepo,
    roAgentRun,
    agentRunService,
    nodeFactRepo,
    directivesRepos,
    rulesRepos,
    nodeConfigService,
    () => Full(compliance),
    () => GlobalPolicyMode(PolicyMode.Audit, PolicyModeOverrides.Always).succeed,
    () => Full(UnexpectedReportInterpretation(Set())),
    RUDDER_JDBC_BATCH_MAX_SIZE
  )

  sequential

  "Init data" should {

//    "insert reports 1" in {
//
//      val q = for {
//        r <- DB.insertReports(reports.values.toList.flatten)
//        e <- DB.insertExpectedReports(expecteds.keySet.toList)
//        n <- DB.insertExpectedReportsNode(expecteds.values.toSet.flatten.toList)
//      } yield {
//        (r, e, n)
//      }
//
//      val (r, e, n) = q.transact(xa).unsafeRunSync
//
//      (r must be_==(30)) and (e must be_==(6)) and (n must be_==(20))
//    }

    "insert reports 1" in {

      val q = DB.insertReports(reports.values.toList.flatten)

      val r = transacRun(xa => q.transact(xa))

      (r must be_==(30))
    }

//    "insert execution" in {
//      //need to be done one time for init, one time for actual work
//      (updateRuns.findAndSaveExecutions(42) mustFull) and
//      (updateRuns.findAndSaveExecutions(43) mustFull)
//    }

//    "insert node config info" in {
//      //add node configuration in repos, testing "add" method
//      (updateExpected.addNodeConfigIdInfo(allNodes_t1.mapValues(_.configId), gen1) mustFull) and
//      (updateExpected.addNodeConfigIdInfo(allNodes_t2.mapValues(_.configId), gen2) mustFull)
//    }
//  }
//
//  "Testing set-up for expected reports and agent run" should {  //be in ExpectedReportsTest!
    // essentially test the combination of in/in(values clause

    // TODO : CORRECT TESTS
//    "be correct for in(tuple) clause" in {
//      val res = findExpected.getExpectedReports(configs(("n1","n1_t1"))).openOrThrowException("'Test failed'")
//      (res.size must beEqualTo(1)) and
//      (res("n1").ruleExpectedReports.size must beEqualTo(2)) and
//      (res("n1").nodeConfigId.value must beEqualTo("n1_t1"))
//    }
//
//    "be correct for in(value(tuple)) clause" in {
//      val res = findExpected.getExpectedReports(configs(("n1","n1_t1"),("n2","n2_t1"))).openOrThrowException("'Test failed'")
//      (res.size must beEqualTo(2)) and (
//       res("n1").ruleExpectedReports.size must beEqualTo(2)
//      ) and (
//       res("n1").nodeConfigId.value must beEqualTo("n1_t1")
//      ) and (
//       res("n2").ruleExpectedReports.size must beEqualTo(2)
//      ) and (
//       res("n1").nodeConfigId.value must beEqualTo("n2_t1")
//      )
//    }

//    "contains runs for node" in {
//      val res = roAgentRun.getNodesLastRun(Set("n0", "n1", "n2", "n3", "n4").map(NodeId(_))).openOrThrowException("test failed")
//
//      res must beEqualTo(agentRuns(
//          ("n0" -> None                               )
//        , ("n1" -> Some(( run1, None         , true, 106 )))
//        , ("n2" -> Some(( run1, Some("n2_t1"), true, 102 )))
//        , ("n3" -> Some(( run2, None         , true, 126 )))
//        , ("n4" -> Some(( run2, Some("n4_t2"), true, 116 )))
//      ))
//
//    }

    // TODO : CORRECT TESTS
//    "return the correct expected reports on hardcode query" in {
//
//      val expectedInBase = findExpected.getExpectedReports(Set(
//          NodeAndConfigId(NodeId("n4"),NodeConfigId("n4_t2"))
//        , NodeAndConfigId(NodeId("n0"),NodeConfigId("n0_t2"))
//        , NodeAndConfigId(NodeId("n3"),NodeConfigId("n3_t1"))
//        , NodeAndConfigId(NodeId("n1"),NodeConfigId("n1_t1"))
//        , NodeAndConfigId(NodeId("n2"),NodeConfigId("n2_t1"))
//      ), Set(RuleId("r2")) ).openOrThrowException("Failed test, should be a full box")
//
//      val r = Map(
//          SerialedRuleId(RuleId("r2"), 1) ->
//          RuleNodeExpectedReports(
//                RuleId("r2")
//              , 1
//              , List(DirectiveExpectedReports(DirectiveId("r2_d2"), Some(PolicyMode.Audit),
//                  List(ComponentExpectedReport("r2_d2_c0",1,List("r2_d2_c0_v0"),List("r2_d2_c0_v0"))))
//                )
//              , gen2
//              , None
//      ))
//
//      val expected = Map(
//        NodeId("n1") -> Map(NodeConfigId("n1_t1") -> Map()),
//        NodeId("n2") -> Map(NodeConfigId("n2_t1") -> Map()),
//        NodeId("n0") -> Map(NodeConfigId("n0_t2") -> r),
//        NodeId("n3") -> Map(NodeConfigId("n3_t1") -> Map()),
//        NodeId("n4") -> Map(NodeConfigId("n4_t2") -> r)
//      )
//
//      expectedInBase must beEqualTo(expected)
//
//    }
//
//    "contains the node config ids" in {
//
//      val configs = findExpected.getNodeConfigIdInfos(allConfigs.keySet).openOrThrowException("Test failed: the box should be full")
//
//       configs.values.flatten.flatten must containTheSameElementsAs(allConfigs.values.toSeq.flatten)
//
//    }

  }

  /////////////////////////////////////////////////////////////////
  /////////////////////// RuleStatusReports ///////////////////////
  /////////////////////////////////////////////////////////////////

  ///////////////////////////////// changes only mode /////////////////////////////////
//
//  "Finding rule status reports for the change only mode" should {
//    lazy val errorOnlyReportingService = buildReportingService(GlobalComplianceMode(ChangesOnly,1))
//
//    "get r0" in {
//
//      ComplianceDebugLogger.info("change only / get r0")
//
//      val r = errorOnlyReportingService.findDirectiveRuleStatusReportsByRule(RuleId("r0"))
//      val result = r.openOrThrowException("'Test failed'")
//
//      val expected = Seq(
//          /*
//           * no run at all, so we are not getting anything.
//           * The current config is not older than period+5
//           * so it's pending.
//           */
//          nodeStatus("n0", None, Some("n0_t2"), "r0", "report_id2",
//              ("r0_d0", Seq(
//                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", Pending, List("")))
//                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", Pending, List("")))
//              )
//          ))
//          /*
//           * No second run, and since we don't have configId, we look how far
//           * we are from the oldest config. As if it's ok, the node is not sending
//           * bad reports.
//           * So we look for current config, it's still pending.
//           */
//        , nodeStatus("n1", Some(run1), Some("n1_t2"), "r0", "report_id2",
//              ("r0_d0", Seq(
//                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", Pending, List("")))
//                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", Pending, List("")))
//              )
//          ))
//          /*
//           * Here, we have a config version in the run, but we only have one run for t1.
//           * As all serial changed for the second run, we are pending everything
//           */
//        , nodeStatus("n2", Some(run1), Some("n2_t2"), "r0", "report_id2",
//              ("r0_d0", Seq(
//                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", Pending, List("")))
//                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", Pending, List("")))
//              )
//          ))
//          /*
//           * Here, we don't have config id, and we had several run without.
//           * The last run is *not* too recent, so we are pending
//           *
//           */
//        , nodeStatus("n3", Some(run2), Some("n3_t2"), "r0", "report_id2",
//              ("r0_d0", Seq(
//                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", Pending, List("")))
//                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", Pending, List("")))
//              )
//          ))
//
//          //here, it just works as expected
//        , nodeStatus("n4", Some(run2), Some("n4_t2"), "r0", "report_id2",
//              ("r0_d0", Seq(
//                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", EnforceSuccess, List("msg")))
//                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", EnforceSuccess, List("msg")))
//              )
//          ))
//      )
//
//      compareNodeStatus(result.report.reports, expected)
//    }
//
//    "get r1" in {
//      ComplianceDebugLogger.info("change only / get r1")
//
//      val r = errorOnlyReportingService.findDirectiveRuleStatusReportsByRule(RuleId("r1"))
//      val result = r.openOrThrowException("'Test failed'")
//      result.report.reports must beEqualTo(Set())
//    }
//
//    "get r2" in {
//      ComplianceDebugLogger.info("change only / get r2")
//
//      val r = errorOnlyReportingService.findDirectiveRuleStatusReportsByRule(RuleId("r2"))
//      val result = r.openOrThrowException("'Test failed'")
//
//      val expected = Seq(
//          /**
//           * changed not to long ago => pending
//           */
//          nodeStatus("n0", None, Some("n0_t2"), "r2", "report_id",
//              ("r2_d2", Seq(
//                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", Pending, List("")))
//              )
//          ))
//          /*
//           * Here, that might have been a success because we think that we still can get a run "report_id2",
//           * and so for now, all is good.
//           * But as serial changed, and r2 is completlly new => pending
//           */
//        , nodeStatus("n1", Some(run1), Some("n1_t2"), "r2", "report_id",
//              ("r2_d2", Seq(
//                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", Pending, List("")))
//              )
//          ))
//          /*
//           * For n2, simple pending
//           */
//        , nodeStatus("n2", Some(run1), Some("n2_t2"), "r2", "report_id",
//              ("r2_d2", Seq(
//                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", Pending, List("")))
//              )
//          ))
//          /*
//           * no config version but still in pending/grace
//           */
//        , nodeStatus("n3", Some(run2), Some("n3_t2"), "r2", "report_id",
//              ("r2_d2", Seq(
//                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", Pending, List("")))
//              )
//          ))
//        , nodeStatus("n4", Some(run2), Some("n4_t2"), "r2", "report_id",
//              ("r2_d2", Seq(
//                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", EnforceSuccess, List("msg")))
//              )
//          ))
//
//      )
//      compareNodeStatus(result.report.reports, expected)
//    }
//  }
//
//  val fullCompliance = GlobalComplianceMode(FullCompliance, 1)
//  ///////////////////////////////// full compliance mode /////////////////////////////////
//
//  "Finding rule status reports for the compliance mode" should {
//    lazy val complianceReportingService = buildReportingService(fullCompliance)
//
//    "get r0" in {
//
//      ComplianceDebugLogger.info("compliance / get r0")
//
//      val r = complianceReportingService.findDirectiveRuleStatusReportsByRule(RuleId("r0"))
//      val result = r.openOrThrowException("'Test failed'")
//
//      val expected = Seq(
//
//          /*
//           * n0: no run at all, so no run in the last 5 minutes but we are still
//           * not expired => Applying
//           */
//          nodeStatus("n0", None, Some("n0_t2"), "r0", "report_id2",
//              ("r0_d0", Seq(
//                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", Pending, List("")))
//                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", Pending, List("")))
//              )
//          ))
//          /*
//           * n1: only run1 and no config version, so one run in the last 5 minutes but
//           * since no config version, we are looking for a newer result.
//           * As the grace period is still running, it's "pending"
//           */
//        , nodeStatus("n1", Some(run1), Some("n1_t2"), "r0", "report_id2",
//              ("r0_d0", Seq(
//                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", Pending, List("")))
//                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", Pending, List("")))
//              )
//          ))
//          /*
//           * Here, we have pending, because we are still in the grace period and
//           * hoping for run with config 2 to happen
//           */
//        , nodeStatus("n2", Some(run1), Some("n2_t2"), "r0", "report_id2",
//              ("r0_d0", Seq(
//                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", Pending, List("")))
//                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", Pending, List("")))
//              )
//          ))
//          /*
//           * We don't have a run with the correct version for now, but
//           * the oldest config is still recend and we are still in grace period
//           * for the latest => pending
//           */
//        , nodeStatus("n3", Some(run2), Some("n3_t2"), "r0", "report_id2",
//              ("r0_d0", Seq(
//                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", Pending, List("")))
//                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", Pending, List("")))
//              )
//          ))
//        , nodeStatus("n4", Some(run2), Some("n4_t2"), "r0", "report_id2",
//              ("r0_d0", Seq(
//                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", EnforceSuccess, List("msg")))
//                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", EnforceSuccess, List("msg")))
//              )
//          ))
//      )
//      compareNodeStatus(result.report.reports, expected)
//    }
//
//    "get r1" in {
//      ComplianceDebugLogger.info("compliance / get r1")
//      val r = complianceReportingService.findDirectiveRuleStatusReportsByRule(RuleId("r1"))
//      val result = r.openOrThrowException("'Test failed'")
//      result.report.reports must beEqualTo(Set())
//    }
//
//    "get r2" in {
//      ComplianceDebugLogger.info("compliance / get r2")
//      val r = complianceReportingService.findDirectiveRuleStatusReportsByRule(RuleId("r2"))
//      val result = r.openOrThrowException("'Test failed'")
//
//      val expected = Seq(
//          nodeStatus("n0", None, Some("n0_t2"), "r2", "report_id",
//              ("r2_d2", Seq(
//                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", Pending, List("")))
//              )
//          ))
//          /*
//           * We got a run without config, so it can still happen
//           */
//        , nodeStatus("n1", Some(run1), Some("n1_t2"), "r2", "report_id",
//              ("r2_d2", Seq(
//                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", Pending, List("")))
//              )
//          ))
//        , nodeStatus("n2", Some(run1), Some("n2_t2"), "r2", "report_id",
//              ("r2_d2", Seq(
//                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", Pending, List("")))
//              )
//          ))
//        , nodeStatus("n3", Some(run2), Some("n3_t2"), "r2", "report_id",
//              ("r2_d2", Seq(
//                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", Pending, List("")))
//              )
//          ))
//        , nodeStatus("n4", Some(run2), Some("n4_t2"), "r2", "report_id",
//              ("r2_d2", Seq(
//                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", EnforceSuccess, List("msg")))
//              )
//          ))
//
//      )
//      compareNodeStatus(result.report.reports, expected)
//    }
//  }
//
//  /////////////////////////////////////////////////////////////////
//  /////////////////////// NodeStatusReports ///////////////////////
//  /////////////////////////////////////////////////////////////////
//
//  ///////////////////////////////// error only mode /////////////////////////////////
//
//  "Finding node status reports for the changes only mode" should {
//    lazy val errorOnlyReportingService =buildReportingService(GlobalComplianceMode(ChangesOnly,1))
//
//    "get pending for node 0 on gen2 data" in {
//      ComplianceDebugLogger.info("changes only / node0")
//      val r = errorOnlyReportingService.findNodeStatusReport(NodeId("n0"))
//      val result = r.openOrThrowException("'Test failed'")
//      compareNodeStatus(result.report.reports, Seq(
//          nodeStatus("n0", None, Some("n0_t2"), "r0", "report_id2",
//              ("r0_d0", Seq(
//                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", Pending, List("")))
//                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", Pending, List("")))
//              )
//          ))
//        , nodeStatus("n0", None, Some("n0_t2"), "r2", "report_id",
//              ("r2_d2", Seq(
//                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", Pending, List("")))
//              )
//          ))
//      ))
//    }
//
//    "report pending for node 1 on gen2 data, because we don't have config id no config mismatch but choose the last one and bad serial" in {
//      ComplianceDebugLogger.info("changes only / node1")
//      val r = errorOnlyReportingService.findNodeStatusReport(NodeId("n1"))
//      val result = r.openOrThrowException("'Test failed'")
//      compareNodeStatus(result.byRules("r0").reports, Seq(
//          nodeStatus("n1", Some(run1), Some("n1_t2"), "r0", "report_id2",
//              ("r0_d0", Seq(
//                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", Pending, List("")))
//                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", Pending, List("")))
//              )
//          ))
//      ))
//    }
//
//    "report pending for node 2" in {
//      ComplianceDebugLogger.info("changes only / node2")
//      val r = errorOnlyReportingService.findNodeStatusReport(NodeId("n2"))
//      val result = r.openOrThrowException("'Test failed'")
//      val all = result.byRules("r0").reports
//      compareNodeStatus(all, Seq(
//          nodeStatus("n2", Some(run1), Some("n2_t2"), "r0", "report_id2",
//              ("r0_d0", Seq(
//                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", Pending, List("")))
//                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", Pending, List("")))
//              )
//          ))
//      ))
//    }
//
//    "find the correct pending status because serial changed" in {
//      ComplianceDebugLogger.info("changes only / node3")
//      val r = errorOnlyReportingService.findNodeStatusReport(NodeId("n3"))
//      val result = r.openOrThrowException("'Test failed'")
//      val all = result.byRules("r0").reports
//      compareNodeStatus(all, Seq(
//          nodeStatus("n3", Some(run2), Some("n3_t2"), "r0", "report_id2",
//              ("r0_d0", Seq(
//                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", Pending, List("")))
//                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", Pending, List("")))
//              )
//          ))
//      ))
//    }
//
//    "find the correct last report based on configuration for node 4" in {
//      ComplianceDebugLogger.info("changes only / node4")
//      val r = errorOnlyReportingService.findNodeStatusReport(NodeId("n4"))
//      val result = r.openOrThrowException("'Test failed'")
//      val all = result.byRules("r0").reports ++ result.byRules("r2").reports
//      compareNodeStatus(all, Seq(
//          nodeStatus("n4", Some(run2), Some("n4_t2"), "r0", "report_id2",
//              ("r0_d0", Seq(
//                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", EnforceSuccess, List("msg")))
//                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", EnforceSuccess, List("msg")))
//              )
//          ))
//        , nodeStatus("n4", Some(run2), Some("n4_t2"), "r2", "report_id",
//              ("r2_d2", Seq(
//                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", EnforceSuccess, List("msg")))
//              )
//          ))
//      ))
//    }
//  }
//
//  ///////////////////////////////// full compliance mode /////////////////////////////////
//
//  "Finding node status reports for the compliance mode" should {
//    lazy val complianceReportingService:ReportingServiceImpl = buildReportingService(fullCompliance)
//
//    "get pending for node 0 on gen2 data (without msg)" in {
//      ComplianceDebugLogger.info("compliance / node0")
//      val r = complianceReportingService.findNodeStatusReport(NodeId("n0"))
//      val result = r.openOrThrowException("'Test failed'")
//      val all = result.byRules("r0").reports
//      compareNodeStatus(all, Seq(
//          nodeStatus("n0", None, Some("n0_t2"), "r0", "report_id2",
//              ("r0_d0", Seq(
//                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", Pending, List("")))
//                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", Pending, List("")))
//              )
//          ))
//      ))
//    }
//
//    "report pending for node 1 even because we can find the correct reports and run1 is still yound and not expired" in {
//      ComplianceDebugLogger.info("compliance / node1")
//      val r = complianceReportingService.findNodeStatusReport(NodeId("n1"))
//      val result = r.openOrThrowException("'Test failed'")
//      compareNodeStatus(result.report.reports, Seq(
//          nodeStatus("n1", Some(run1), Some("n1_t2"), "r0", "report_id2",
//              ("r0_d0", Seq(
//                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", Pending, List("")))
//                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", Pending, List("")))
//              )
//          ))
//        , nodeStatus("n1", Some(run1), Some("n1_t2"), "r2", "report_id",
//              ("r2_d2", Seq(
//                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", Pending, List("")))
//              )
//          ))
//      ))
//    }
//
//    /*
//     * This case is a little touchy because node2 sent reports for gen1/run1,
//     * but not for gen2 (current expectation).
//     * But reports from run1 don't have expired (they are not too far from now),
//     * they are older than gen-time. And we can compare on nodeconfigid,
//     * and on serial
//     *
//     * So here, we really expect data from gen2, and we get pending because the
//     * expiration time is not spent for now.
//     */
//    "report 'pending' for node 2 even if we can find the correct reports and they are not expired" in {
//      ComplianceDebugLogger.info("compliance / node2")
//      val r = complianceReportingService.findNodeStatusReport(NodeId("n2"))
//      val result = r.openOrThrowException("'Test failed'")
//      val all = result.byRules("r0").reports
//      compareNodeStatus(all, Seq(
//          nodeStatus("n2", Some(run1), Some("n2_t2"), "r0", "report_id2",
//              ("r0_d0", Seq(
//                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", Pending, List("")))
//                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", Pending, List("")))
//              )
//          ))
//      ))
//    }
//
//    "find the correct pending status because serial changed on node 3" in {
//      ComplianceDebugLogger.info("compliance / node3")
//      val r = complianceReportingService.findNodeStatusReport(NodeId("n3"))
//      val result = r.openOrThrowException("'Test failed'")
//      val all = result.byRules("r0").reports
//      compareNodeStatus(all, Seq(
//          nodeStatus("n3", Some(run2), Some("n3_t2"), "r0", "report_id2",
//              ("r0_d0", Seq(
//                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", Pending, List("")))
//                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", Pending, List("")))
//              )
//          ))
//      ))
//    }
//
//    "find the correct last report based on configuration for node 4" in {
//      ComplianceDebugLogger.info("compliance / node4")
//      val r = complianceReportingService.findNodeStatusReport(NodeId("n4"))
//      val result = r.openOrThrowException("'Test failed'")
//      compareNodeStatus(result.report.reports, Seq(
//          nodeStatus("n4", Some(run2), Some("n4_t2"), "r0", "report_id2",
//              ("r0_d0", Seq(
//                  compStatus("r0_d0_c0", ("r0_d0_c0_v1", EnforceSuccess, List("msg")))
//                , compStatus("r0_d0_c1", ("r0_d0_c1_v1", EnforceSuccess, List("msg")))
//              )
//          ))
//        , nodeStatus("n4", Some(run2), Some("n4_t2"), "r2", "report_id",
//              ("r2_d2", Seq(
//                  compStatus("r2_d2_c0", ("r2_d2_c0_v0", EnforceSuccess, List("msg")))
//              )
//          ))
//      ))
//    }
//
//    /*
//     * TODO: a case for a node where the report sent are from the n-1 expectation
//     * BUT they where sent AFTER the n expectation time, so they could be OK.
//     * The check must happen on NodeConfigId for that case if we don't
//     * want to have unexpected reports (what we get if we check on serial).
//     * The case can even be set so that the reports look OK (same serial, same values)
//     * but in fact, they are from a previous nodeConfigVersion (because one other directive
//     * changed).
//     */
//
//  }
//
//  ////////// utility methods //////////////
//
//  /*
//   * A comparator for NodeStatusReport that allows to more
//   * quickly understand what is the problem
//   *
//   * BE CAREFUL: NO EXPIRATION DATE COMPARISON
//   *
//   */
//  def compareNodeStatus(results:Set[RuleNodeStatusReport], expecteds:Seq[RuleNodeStatusReport]) = {
//    val x = results.toSeq.sortBy(ruleNodeComparator).map(a => a.copy(expirationDate = EXPIRATION_DATE))
//    val y = expecteds.sortBy(ruleNodeComparator).map(a => a.copy(expirationDate = EXPIRATION_DATE))
//    x must containTheSameElementsAs(y)
//  }
//
//  implicit def nodes(ids:String*):Set[NodeId] = ids.map( NodeId(_) ).toSet
//  implicit def configs(ids:(String,String)*): Set[NodeAndConfigId] = {
//    ids.map(id => NodeAndConfigId(NodeId(id._1), NodeConfigId(id._2))).toSet
//  }

  implicit def toReport(t: (DateTime, String, String, String, String, String, String, DateTime, String, String)): Reports = {
    implicit def toRuleId(s:      String): RuleId      = RuleId(RuleUid(s))
    implicit def toDirectiveId(s: String): DirectiveId = DirectiveId(DirectiveUid(s), GitVersion.DEFAULT_REV)
    implicit def toNodeId(s:      String): NodeId      = NodeId(s)

    Reports(t._1, t._2, t._3, t._4, t._5, t._6, t._7, t._8, t._9, t._10)
  }
//
//  def ruleNodeComparator(x: RuleNodeStatusReport): String = {
//    x.nodeId.value.toLowerCase + x.ruleId.value.toLowerCase
//  }
//
//  def compStatus(id: String, values: (String, ReportType, List[String])*): ComponentStatusReport = {
//    val v = values.map { case(value, tpe, msgs) =>
//      val messages = msgs.map(m => MessageStatusReport(tpe, m))
//      tpe match {
//        case Unexpected => ComponentValueStatusReport(value, "None", messages)
//        case _ => ComponentValueStatusReport(value, value, messages)
//      }
//    }
//    ComponentStatusReport(id, ComponentValueStatusReport.merge(v))
//  }
//
//  def nodeStatus(id: String, run:Option[DateTime], version: Option[String], ruleId: String, serial: Int
//      , directives: (String, Seq[ComponentStatusReport])*
//  ): RuleNodeStatusReport = {
//    RuleNodeStatusReport(
//          NodeId(id), RuleId(ruleId), run, version.map(NodeConfigId(_))
//        , DirectiveStatusReport.merge(directives.map(d =>
//            DirectiveStatusReport(DirectiveId(d._1), ComponentStatusReport.merge(d._2))
//          ))
//        , DateTime.now().plusHours(1)
//    )
//  }

//  def expect(ruleId: String, serial: Int)
//            //           nodeJoinKey, directiveId  component   cardinality   componentValues  beging     end            , (nodeId, version)
//            (expecteds: (Int        , String     , String    , Int         , String         , DateTime, Option[DateTime], Map[NodeId,NodeConfigIdInfo])*)
//  : Map[DB.ExpectedReports[Unit], Seq[DB.ExpectedReportsNodes]] = {
//    expecteds.map { exp =>
//      DB.ExpectedReports[Unit]((), exp._1, RuleId(ruleId), serial, DirectiveId(exp._2), exp._3, exp._4, exp._5, exp._5, exp._6, exp._7) ->
//      exp._8.map{ case (nodeId, version) =>
//        DB.ExpectedReportsNodes(exp._1, nodeId.value, version.configId.value :: Nil)
//      }.toSeq
//    }.toMap
//  }

  //         nodeId               ruleId  serial dirId   comp     keyVal   execTime   severity  msg
  def node(nodeId: String)(lines: (String, String, String, String, String, DateTime, String, String)*): (String, Seq[Reports]) = {
    (nodeId, lines.map(t => toReport((t._6, t._1, t._3, nodeId, t._2, t._4, t._5, t._6, t._7, t._8))))
  }

  implicit def toRuleId(id:   String): RuleId       = RuleId(RuleUid(id))
  implicit def toNodeId(id:   String): NodeId       = NodeId(id)
  implicit def toConfigId(id: String): NodeConfigId = NodeConfigId(id)

  implicit def agentRuns(runs: (String, Option[(DateTime, Option[String], Long)])*): Map[NodeId, Option[AgentRun]] = {
    runs.map {
      case (id, opt) =>
        NodeId(id) -> opt.map(e => AgentRun(AgentRunId(NodeId(id), e._1), e._2.map(NodeConfigId(_)), e._3))
    }.toMap
  }
}
