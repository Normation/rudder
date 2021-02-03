/*
*************************************************************************************
* Copyright 2016 Normation SAS
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

package com.normation.rudder.rest

import com.normation.cfclerk.services.TechniquesLibraryUpdateNotification
import com.normation.cfclerk.services.UpdateTechniqueLibrary
import com.normation.errors.IOResult
import com.normation.eventlog.EventActor
import com.normation.eventlog.EventLog
import com.normation.eventlog.EventLogFilter
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.NodeInventory
import com.normation.rudder.AuthorizationType
import com.normation.rudder.MockDirectives
import com.normation.rudder.MockGitConfigRepo
import com.normation.rudder.MockRules
import com.normation.rudder.MockTechniques
import com.normation.rudder.RudderAccount
import com.normation.rudder.User
import com.normation.rudder.UserService
import com.normation.rudder.api.{ApiAuthorization => ApiAuthz}
import com.normation.rudder.batch._
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.domain.reports.NodeExpectedReports
import com.normation.rudder.domain.reports.NodeModeConfig
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.hooks.HookEnvPairs
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.repository._
import com.normation.rudder.rest.lift._
import com.normation.rudder.rest.v1.RestStatus
import com.normation.rudder.rule.category.RuleCategoryService
import com.normation.rudder.services.ClearCacheService
import com.normation.rudder.services.eventlog.EventLogDeploymentService
import com.normation.rudder.services.eventlog.EventLogFactory
import com.normation.rudder.services.healthcheck.CheckCoreNumber
import com.normation.rudder.services.healthcheck.CheckFileDescriptorLimit
import com.normation.rudder.services.healthcheck.CheckFreeSpace
import com.normation.rudder.services.healthcheck.HealthcheckNotificationService
import com.normation.rudder.services.healthcheck.HealthcheckService
import com.normation.rudder.services.marshalling.DeploymentStatusSerialisation
import com.normation.rudder.services.nodes.NodeInfoServiceCachedImpl
import com.normation.rudder.services.policies.InterpolationContext
import com.normation.rudder.services.policies.NodeConfiguration
import com.normation.rudder.services.policies.NodeConfigurations
import com.normation.rudder.services.policies.NodesContextResult
import com.normation.rudder.services.policies.PromiseGenerationService
import com.normation.rudder.services.policies.RuleVal
import com.normation.rudder.services.policies.TestNodeConfiguration
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationHash
import com.normation.rudder.services.reports.CacheComplianceQueueAction
import com.normation.rudder.services.system.DebugInfoScriptResult
import com.normation.rudder.services.system.DebugInfoService
import com.normation.rudder.services.user.PersonIdentService
import com.normation.rudder.services.workflows.CommitAndDeployChangeRequestService
import com.normation.rudder.services.workflows.CommitAndDeployChangeRequestServiceImpl
import com.normation.rudder.services.workflows.DefaultWorkflowLevel
import com.normation.rudder.services.workflows.NoWorkflowServiceImpl
import com.normation.rudder.web.services.StatelessUserPropertyService
import com.normation.utils.StringUuidGeneratorImpl
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import net.liftweb.http.LiftResponse
import net.liftweb.http.LiftRules
import net.liftweb.http.LiftRulesMocker
import net.liftweb.http.Req
import net.liftweb.http.S
import net.liftweb.json.JsonAST.JValue
import net.liftweb.mocks.MockHttpServletRequest
import net.liftweb.mockweb.MockWeb
import net.liftweb.util.NamedPF
import org.eclipse.jgit.lib.PersonIdent
import org.joda.time.DateTime
import org.specs2.matcher.MatchResult
import zio._
import zio.syntax._
import zio.duration._

import scala.concurrent.duration.FiniteDuration
import scala.xml.Elem


/*
 * This file provides all the necessary plumbing to allow test REST API.
 *
 * Also responsible for setting up data and mock services.
 */
object RestTestSetUp {

  implicit val userService = new UserService {
    val user = new User{
      val account = RudderAccount.User("test-user", "pass")
      def checkRights(auth : AuthorizationType) = true
      def getApiAuthz = ApiAuthz.allAuthz
    }
    val getCurrentUser = user
  }

  // Instantiate Service needed to feed System API constructor

  val fakeUpdatePTLibService = new UpdateTechniqueLibrary() {
      def update(modId: ModificationId, actor:EventActor, reason: Option[String])  = {
        Full(Map())
      }
      def registerCallback(callback:TechniquesLibraryUpdateNotification) : Unit = {}
    }

  val fakeUpdateDynamicGroups = new UpdateDynamicGroups(null, null, null, null, 0) {
    override def startManualUpdate: Unit = ()
  }

  val mockGitRepo = new MockGitConfigRepo("")
  val mockTechniques = MockTechniques(mockGitRepo)
  val mockDirectives = new MockDirectives(mockTechniques)
  val mockRules = new MockRules()
  val uuidGen = new StringUuidGeneratorImpl()
  val restExtractorService =
  RestExtractorService (
      mockRules.ruleRepo
    , mockDirectives.directiveRepo
    , null //roNodeGroupRepository
    , mockTechniques.techniqueRepository
    , null //queryParser
    , new StatelessUserPropertyService(() => false.succeed, () => false.succeed, () => "".succeed)
    , null //workflowService
    , uuidGen
    , null
  )

  val restDataSerializer = RestDataSerializerImpl(
      mockTechniques.techniqueRepository
    , null //diffService
  )

  // TODO
  // all other apis

  class FakeClearCacheService extends ClearCacheService {
    override def action(actor: EventActor) = null
    override def clearNodeConfigurationCache(storeEvent: Boolean, actor: EventActor) = null
  }

  val fakeNotArchivedElements = NotArchivedElements(Seq[CategoryNotArchived](), Seq[ActiveTechniqueNotArchived](), Seq[DirectiveNotArchived]())
  val fakePersonIdent = new PersonIdent("test-user", "test.user@normation.com")
  val fakeGitCommitId = GitCommitId("6d6b2ceb46adeecd845ad0c0812fee07e2727104")
  val fakeGitArchiveId = GitArchiveId(GitPath("fake/git/path"), fakeGitCommitId, fakePersonIdent)

  class FakeItemArchiveManager extends ItemArchiveManager {
    override def exportAll(commiter: PersonIdent, modId: ModificationId, actor: EventActor, reason: Option[String], includeSystem: Boolean)
     = ZIO.succeed((fakeGitArchiveId, fakeNotArchivedElements))
    override def exportRules(commiter: PersonIdent, modId: ModificationId, actor: EventActor, reason: Option[String], includeSystem: Boolean)
     = ZIO.succeed(fakeGitArchiveId)
    override def exportTechniqueLibrary(commiter: PersonIdent, modId: ModificationId, actor: EventActor, reason: Option[String], includeSystem: Boolean)
     = ZIO.succeed((fakeGitArchiveId, fakeNotArchivedElements))
    override def exportGroupLibrary(commiter: PersonIdent, modId: ModificationId, actor: EventActor, reason: Option[String], includeSystem: Boolean)
     = ZIO.succeed(fakeGitArchiveId)
    override def exportParameters(commiter: PersonIdent, modId: ModificationId, actor: EventActor, reason: Option[String], includeSystem: Boolean)
     = ZIO.succeed(fakeGitArchiveId)
    override def importAll(archiveId: GitCommitId, commiter: PersonIdent, modId: ModificationId, actor: EventActor, reason: Option[String], includeSystem: Boolean)
     = ZIO.succeed(fakeGitCommitId)
    override def importRules(archiveId: GitCommitId, commiter: PersonIdent, modId: ModificationId, actor: EventActor, reason: Option[String], includeSystem: Boolean)
     = ZIO.succeed(fakeGitCommitId)
    override def importTechniqueLibrary(archiveId: GitCommitId, commiter: PersonIdent, modId: ModificationId, actor: EventActor, reason: Option[String], includeSystem: Boolean)
     = ZIO.succeed(fakeGitCommitId)
    override def importGroupLibrary(archiveId: GitCommitId, commiter: PersonIdent, modId: ModificationId, actor: EventActor, reason: Option[String], includeSystem: Boolean)
     = ZIO.succeed(fakeGitCommitId)
    override def importParameters(archiveId: GitCommitId, commiter: PersonIdent, modId: ModificationId, actor: EventActor, reason: Option[String], includeSystem: Boolean)
     = ZIO.succeed(fakeGitCommitId)
    override def rollback(archiveId: GitCommitId, commiter: PersonIdent, modId: ModificationId, actor: EventActor, reason: Option[String], rollbackedEvents: Seq[EventLog], target: EventLog, rollbackType: String, includeSystem: Boolean)
     = ZIO.succeed(fakeGitCommitId)

    /**
      * These methods are called by the Archive API to get the git archives.
      * The API then provides the logic to transform the Box[Map][DateTime, GitArchiveId] into JSON
      * Here, we want to make these methods returning fake archives for testing the API logic.
      */
    val fakeArchives = Map[DateTime, GitArchiveId](
            new DateTime(42) -> fakeGitArchiveId
    )
    override def getFullArchiveTags = ZIO.succeed(fakeArchives)
    override def getGroupLibraryTags = ZIO.succeed(fakeArchives)
    override def getTechniqueLibraryTags = ZIO.succeed(fakeArchives)
    override def getRulesTags = ZIO.succeed(fakeArchives)
    override def getParametersTags = ZIO.succeed(fakeArchives)
  }
  val fakeItemArchiveManager = new FakeItemArchiveManager
  val fakeClearCacheService = new FakeClearCacheService
  val fakePersonIndentService = new PersonIdentService {
    override def getPersonIdentOrDefault(username: String) = ZIO.succeed(fakePersonIdent)
  }
  val apiAuthorizationLevelService = new DefaultApiAuthorizationLevel(LiftApiProcessingLogger)
  val apiDispatcher = new RudderEndpointDispatcher(LiftApiProcessingLogger)
  val testNodeConfiguration = new TestNodeConfiguration()
  val fakeRepo = testNodeConfiguration.repo
  val fakeScriptLauncher = new DebugInfoService {
    override def launch() = DebugInfoScriptResult("test", new Array[Byte](42)).succeed
  }
  val nodeInfoService = new NodeInfoServiceCachedImpl(
    null
    , null
    , null
    , null
    , null
    , null
    , null
    , FiniteDuration(100, "millis")
  )
  val deploymentStatusSerialisation = new DeploymentStatusSerialisation {
    override def serialise(deploymentStatus: CurrentDeploymentStatus): Elem = <test/>
  }
  val eventLogRepo = new EventLogRepository {
    override def saveEventLog(modId: ModificationId, eventLog: EventLog): IOResult[EventLog] = eventLog.succeed

    override def eventLogFactory: EventLogFactory = ???
    override def getEventLogByCriteria(criteria: Option[String], limit: Option[Int], orderBy: Option[String], extendedFilter: Option[String]): IOResult[Seq[EventLog]] = ???
    override def getEventLogById(id: Long): IOResult[EventLog] = ???
    override def getEventLogCount(criteria: Option[String], extendedFilter: Option[String]): IOResult[Long] = ???
    override def getEventLogByChangeRequest(changeRequest: ChangeRequestId, xpath: String, optLimit: Option[Int], orderBy: Option[String], eventTypeFilter: List[EventLogFilter]): IOResult[Vector[EventLog]] = ???
    override def getEventLogWithChangeRequest(id: Int): IOResult[Option[(EventLog, Option[ChangeRequestId])]] = ???
    override def getLastEventByChangeRequest(xpath: String, eventTypeFilter: List[EventLogFilter]): IOResult[Map[ChangeRequestId, EventLog]] = ???
  }
  val eventLogger = new EventLogDeploymentService(eventLogRepo, null) {
    override def getLastDeployement(): Box[CurrentDeploymentStatus] = Full(NoStatus)
  }
  val policyGeneration = new PromiseGenerationService {
    override def deploy(): Box[Set[NodeId]] = Full(Set())
    override def getAllNodeInfos(): Box[Map[NodeId, NodeInfo]] = ???
    override def getDirectiveLibrary(): Box[FullActiveTechniqueCategory] = ???
    override def getGroupLibrary(): Box[FullNodeGroupCategory] = ???
    override def getAllGlobalParameters: Box[Seq[GlobalParameter]] = ???
    override def getAllInventories(): Box[Map[NodeId, NodeInventory]] = ???
    override def getGlobalComplianceMode(): Box[GlobalComplianceMode] = ???
    override def getGlobalAgentRun(): Box[AgentRunInterval] = ???
    override def getScriptEngineEnabled: () => Box[FeatureSwitch] = ???
    override def getGlobalPolicyMode: () => Box[GlobalPolicyMode] = ???
    override def getComputeDynGroups: () => Box[Boolean] = ???
    override def getMaxParallelism: () => Box[String] = ???
    override def getJsTimeout: () => Box[Int] = ???
    override def getGenerationContinueOnError: () => Box[Boolean] = ???
    override def writeCertificatesPem(allNodeInfos: Map[NodeId, NodeInfo]): Unit = ???
    override def triggerNodeGroupUpdate(): Box[Unit] = ???
    override def beforeDeploymentSync(generationTime: DateTime): Box[Unit] = ???
    override def HOOKS_D: String = ???
    override def HOOKS_IGNORE_SUFFIXES: List[String] = ???
    override def UPDATED_NODE_IDS_PATH: String = ???
    override def GENERATION_FAILURE_MSG_PATH: String = ???
    override def getAppliedRuleIds(rules: Seq[Rule], groupLib: FullNodeGroupCategory, directiveLib: FullActiveTechniqueCategory, allNodeInfos: Map[NodeId, NodeInfo]): Set[RuleId] = ???
    override def findDependantRules(): Box[Seq[Rule]] = ???
    override def buildRuleVals(activesRules: Set[RuleId], rules: Seq[Rule], directiveLib: FullActiveTechniqueCategory, groupLib: FullNodeGroupCategory, allNodeInfos: Map[NodeId, NodeInfo]): Box[Seq[RuleVal]] = ???
    override def getNodeContexts(nodeIds: Set[NodeId], allNodeInfos: Map[NodeId, NodeInfo], allGroups: FullNodeGroupCategory, globalParameters: List[GlobalParameter], globalAgentRun: AgentRunInterval, globalComplianceMode: ComplianceMode, globalPolicyMode: GlobalPolicyMode): Box[NodesContextResult] = ???
    override def buildNodeConfigurations(activeNodeIds: Set[NodeId], ruleVals: Seq[RuleVal], nodeContexts: Map[NodeId, InterpolationContext], allNodeModes: Map[NodeId, NodeModeConfig], scriptEngineEnabled: FeatureSwitch, globalPolicyMode: GlobalPolicyMode, maxParallelism: Int, jsTimeout: FiniteDuration, generationContinueOnError: Boolean): Box[NodeConfigurations] = ???
    override def forgetOtherNodeConfigurationState(keep: Set[NodeId]): Box[Set[NodeId]] = ???
    override def getNodeConfigurationHash(): Box[Map[NodeId, NodeConfigurationHash]] = ???
    override def getNodesConfigVersion(allNodeConfigs: Map[NodeId, NodeConfiguration], hashes: Map[NodeId, NodeConfigurationHash], generationTime: DateTime): Map[NodeId, NodeConfigId] = ???
    override def writeNodeConfigurations(rootNodeId: NodeId, updated: Map[NodeId, NodeConfigId], allNodeConfig: Map[NodeId, NodeConfiguration], allNodeInfos: Map[NodeId, NodeInfo], globalPolicyMode: GlobalPolicyMode, generationTime: DateTime, maxParallelism: Int): Box[Set[NodeId]] = ???
    override def computeExpectedReports(allNodeConfigurations: Map[NodeId, NodeConfiguration], updatedId: Map[NodeId, NodeConfigId], generationTime: DateTime, allNodeModes: Map[NodeId, NodeModeConfig]): List[NodeExpectedReports] = ???
    override def saveExpectedReports(expectedReports: List[NodeExpectedReports]): Box[Seq[NodeExpectedReports]] = ???
    override def invalidateComplianceCache(actions: Seq[(NodeId, CacheComplianceQueueAction)]): Unit = ???
    override def historizeData(rules: Seq[Rule], directiveLib: FullActiveTechniqueCategory, groupLib: FullNodeGroupCategory, allNodeInfos: Map[NodeId, NodeInfo], globalAgentRun: AgentRunInterval): Box[Unit] = ???
    override def runPreHooks(generationTime: DateTime, systemEnv: HookEnvPairs): Box[Unit] = ???
    override def runPostHooks(generationTime: DateTime, endTime: DateTime, idToConfiguration: Map[NodeId, NodeInfo], systemEnv: HookEnvPairs, nodeIdsPath: String): Box[Unit] = ???
    override def runFailureHooks(generationTime: DateTime, endTime: DateTime, systemEnv: HookEnvPairs, errorMessage: String, errorMessagePath: String): Box[Unit] = ???
  }
  val asyncDeploymentActor = new AsyncDeploymentActor(policyGeneration, eventLogger, deploymentStatusSerialisation)

  val commitAndDeployChangeRequest : CommitAndDeployChangeRequestService =
    new CommitAndDeployChangeRequestServiceImpl(
        uuidGen
      , mockDirectives.directiveRepo
      , mockDirectives.directiveRepo
      , null // roNodeGroupRepository
      , null // woNodeGroupRepository
      , mockRules.ruleRepo
      , mockRules.ruleRepo
      , null // roLDAPParameterRepository
      , null // woLDAPParameterRepository
      , asyncDeploymentActor
      , null // dependencyAndDeletionService
      , () => false.succeed // configService.rudder_workflow_enabled _
      , null // xmlSerializer
      , null // xmlUnserializer
      , null // sectionSpecParser
      , null // dynGroupUpdaterService
    )
  val workflowLevelService = new DefaultWorkflowLevel(new NoWorkflowServiceImpl(
    commitAndDeployChangeRequest
  ))
  val apiService11 = new SystemApiService11(
        fakeUpdatePTLibService
      , fakeScriptLauncher
      , fakeClearCacheService
      , asyncDeploymentActor
      , uuidGen
      , fakeUpdateDynamicGroups
      , fakeItemArchiveManager
      , fakePersonIndentService
      , fakeRepo
  )
  val fakeHealthcheckService = new HealthcheckService(
    List(
      CheckCoreNumber
      , CheckFreeSpace
      , new CheckFileDescriptorLimit(nodeInfoService) //should I create all services to get this one ?
    )
  )
  val fakeHcNotifService = new HealthcheckNotificationService(fakeHealthcheckService, 5.minute)
  val apiService13 = new SystemApiService13(
      fakeHealthcheckService
    , fakeHcNotifService
    , restDataSerializer
    , null
  )

  val ruleApiService2 = new RuleApiService2(
        mockRules.ruleRepo
      , mockRules.ruleRepo
      , uuidGen
      , asyncDeploymentActor
      , workflowLevelService
      , restExtractorService
      , restDataSerializer
    )

  val ruleApiService6 = new RuleApiService6 (
        mockRules.ruleCategoryRepo
      , mockRules.ruleRepo
      , mockRules.ruleCategoryRepo
      , new RuleCategoryService()
      , restDataSerializer
    )
  val systemApi = new SystemApi(restExtractorService, apiService11, apiService13, "5.0", "5.0.0", "some time")
  val authzToken = AuthzToken(EventActor("fakeToken"))
  val systemStatusPath = "api" + systemApi.Status.schema.path

  val ApiVersions = ApiVersion(11 , false) :: Nil

  val rudderApi = {
    //append to list all new format api to test it
    val modules = List(
        systemApi
      , new RuleApi(restExtractorService, ruleApiService2, ruleApiService6, uuidGen)

    )
    val api = new LiftHandler(apiDispatcher, ApiVersions, new AclApiAuthorization(LiftApiProcessingLogger, userService, () => apiAuthorizationLevelService.aclEnabled), None)
    modules.foreach { module =>
      api.addModules(module.getLiftEndpoints())
    }
    api
  }

  val liftRules = {
    val l = new LiftRules()
    l.statelessDispatch.append(RestStatus)
    l.statelessDispatch.append(rudderApi.getLiftRestApi())
    //TODO: add all other rest classes here
    l
  }

  /*
   * Correctly build and scope mutable things to use the request in a safe
   * way in the context of LiftRules.
   */
  def doReq[T](mockReq: MockHttpServletRequest)(tests: Req => MatchResult[T]) = {
    LiftRulesMocker.devTestLiftRulesInstance.doWith(liftRules) {
      MockWeb.useLiftRules.doWith(true) {
        MockWeb.testReq(mockReq)(tests)
      }
    }
  }

  /**
   * Execute the request and get the response.
   * The request must be a stateless one, else a failure
   * will follow.
   */
  def execRequestResponse[T](mockReq: MockHttpServletRequest)(tests: Box[LiftResponse] => MatchResult[T])= {
    doReq(mockReq){ req =>
      //the test logic is taken from LiftServlet#doServices.
      //perhaps we should call directly that methods, but it need
      //much more set-up, and I don't know for sure *how* to set-up things.
      NamedPF.applyBox(req, LiftRules.statelessDispatch.toList).map(_.apply() match {
        case Full(a) => Full(LiftRules.convertResponse((a, Nil, S.responseCookies, req)))
        case r => r
      }) match {
        case Full(x)      => tests(x)
        case eb: EmptyBox => tests(eb)
      }
    }
  }

  private[this] def mockRequest (path : String, method : String) = {
    val mockReq = new MockHttpServletRequest("http://localhost:8080")
    mockReq.method = method
    mockReq.path = path
    mockReq
  }
  def GET(path: String) = mockRequest(path,"GET")
  def POST(path: String) = mockRequest(path, "POST")
  def DELETE(path: String) = mockRequest(path,"DELETE")

  private[this] def mockJsonRequest (path : String, method : String, data : JValue) = {
    val mockReq = mockRequest(path,method)
    mockReq.body = data
    mockReq
  }

  def jsonPUT(path: String, json : JValue) = {
    mockJsonRequest(path,"PUT", json)
  }

  def jsonPOST(path: String, json : JValue) = {
    mockJsonRequest(path,"POST", json)
  }

  def testGET[T](path: String)(tests: Req => MatchResult[T]) = {
    doReq(GET(path))(tests)
  }

  def testDELETE[T](path: String)(tests: Req => MatchResult[T]) = {
    doReq(DELETE(path))(tests)
  }

  def testPUT[T](path: String, json : JValue)(tests: Req => MatchResult[T]) = {
    doReq(jsonPUT(path, json))(tests)
  }
  def testPOST[T](path: String, json : JValue)(tests: Req => MatchResult[T]) = {
    doReq(jsonPOST(path, json))(tests)
  }

  //This is a method to call when testing POST endpoint without data to pass.
  //Ex: reload techniques endpoints which reload all techniques.
  //It has to be a POST because it executes an action on the server side even if it doesn't need additional data to do so.
  def testEmptyPost[T](path: String)(tests: Req => MatchResult[T]): MatchResult[T] = {
    doReq(POST(path))(tests)
  }
}
