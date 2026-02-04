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

import better.files.File
import com.normation.box.*
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.VariableSpec
import com.normation.cfclerk.services.TechniquesLibraryUpdateNotification
import com.normation.cfclerk.services.TechniquesLibraryUpdateType
import com.normation.cfclerk.services.UpdateTechniqueLibrary
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.EventLog
import com.normation.eventlog.EventLogDetails
import com.normation.eventlog.EventLogFilter
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.FullInventory
import com.normation.inventory.domain.Linux
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.RockyLinux
import com.normation.inventory.domain.Version as IVersion
import com.normation.plugins.*
import com.normation.rudder.*
import com.normation.rudder.api.{ApiAuthorization as ApiAuthz, *}
import com.normation.rudder.api.HttpAction.GET
import com.normation.rudder.apidata.ZioJsonExtractor
import com.normation.rudder.batch.*
import com.normation.rudder.batch.PolicyGenerationTrigger
import com.normation.rudder.campaigns.CampaignSerializer
import com.normation.rudder.config.StatelessUserPropertyService
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.rudder.domain.eventlog.ModifyNodeGroup
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyMode.Audit
import com.normation.rudder.domain.policies.PolicyMode.Enforce
import com.normation.rudder.domain.policies.PolicyModeOverrides
import com.normation.rudder.domain.policies.PolicyModeOverrides.Always
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.ResolvedNodePropertyHierarchy
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.domain.reports.NodeExpectedReports
import com.normation.rudder.domain.reports.NodeModeConfig
import com.normation.rudder.domain.reports.NodeStatusReport
import com.normation.rudder.domain.secret.Secret
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.git.GitArchiveId
import com.normation.rudder.git.GitCommitId
import com.normation.rudder.git.GitPath
import com.normation.rudder.hooks.CmdResult
import com.normation.rudder.hooks.HookEnvPairs
import com.normation.rudder.metrics.FrequentNodeMetrics
import com.normation.rudder.metrics.JvmInfo
import com.normation.rudder.metrics.PrivateSystemInfo
import com.normation.rudder.metrics.PublicSystemInfo
import com.normation.rudder.metrics.RelayInfo
import com.normation.rudder.metrics.SystemInfo
import com.normation.rudder.metrics.SystemInfoService
import com.normation.rudder.ncf.BundleName
import com.normation.rudder.ncf.Constraints
import com.normation.rudder.ncf.EditorTechnique
import com.normation.rudder.ncf.EditorTechniqueReader
import com.normation.rudder.ncf.GenericMethod
import com.normation.rudder.ncf.MethodCall
import com.normation.rudder.ncf.MethodParameter
import com.normation.rudder.ncf.ParameterId
import com.normation.rudder.ncf.ParameterType
import com.normation.rudder.ncf.ParameterType.BasicParameterTypeService
import com.normation.rudder.ncf.ResourceFile
import com.normation.rudder.ncf.ResourceFileService
import com.normation.rudder.ncf.ResourceFileState
import com.normation.rudder.ncf.TechniqueParameter
import com.normation.rudder.ncf.TechniqueSerializer
import com.normation.rudder.ncf.TechniqueWriter
import com.normation.rudder.ncf.TechniqueWriterImpl
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.reports.execution.AgentRunWithNodeConfig
import com.normation.rudder.reports.execution.AgentRunWithoutCompliance
import com.normation.rudder.reports.execution.RoReportsExecutionRepository
import com.normation.rudder.repository.*
import com.normation.rudder.rest.EndpointSchema.syntax.*
import com.normation.rudder.rest.data.Creation
import com.normation.rudder.rest.data.Creation.CreationError
import com.normation.rudder.rest.data.NodeSetup
import com.normation.rudder.rest.internal.EventLogAPI
import com.normation.rudder.rest.internal.EventLogService
import com.normation.rudder.rest.internal.GroupInternalApiService
import com.normation.rudder.rest.internal.GroupsInternalApi
import com.normation.rudder.rest.internal.RestQuicksearch
import com.normation.rudder.rest.internal.RuleInternalApiService
import com.normation.rudder.rest.internal.RulesInternalApi
import com.normation.rudder.rest.internal.SharedFilesAPI
import com.normation.rudder.rest.lift.*
import com.normation.rudder.rest.v1.RestStatus
import com.normation.rudder.rule.category.RuleCategoryService
import com.normation.rudder.services.ClearCacheService
import com.normation.rudder.services.eventlog.EventLogDeploymentService
import com.normation.rudder.services.eventlog.EventLogDetailsServiceImpl
import com.normation.rudder.services.eventlog.EventLogFactory
import com.normation.rudder.services.healthcheck.CheckCoreNumber
import com.normation.rudder.services.healthcheck.CheckFileDescriptorLimit
import com.normation.rudder.services.healthcheck.CheckFreeSpace
import com.normation.rudder.services.healthcheck.HealthcheckNotificationService
import com.normation.rudder.services.healthcheck.HealthcheckService
import com.normation.rudder.services.marshalling.DeploymentStatusSerialisation
import com.normation.rudder.services.modification.ModificationService
import com.normation.rudder.services.policies.DependencyAndDeletionServiceImpl
import com.normation.rudder.services.policies.FindDependencies
import com.normation.rudder.services.policies.InterpolationContext
import com.normation.rudder.services.policies.NodeConfiguration
import com.normation.rudder.services.policies.NodeConfigurations
import com.normation.rudder.services.policies.NodesContextResult
import com.normation.rudder.services.policies.PromiseGenerationService
import com.normation.rudder.services.policies.RuleApplicationStatusServiceImpl
import com.normation.rudder.services.policies.RuleVal
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationHash
import com.normation.rudder.services.policies.write.RuleValGeneratedHookService
import com.normation.rudder.services.queries.DynGroupService
import com.normation.rudder.services.queries.DynGroupUpdaterServiceImpl
import com.normation.rudder.services.quicksearch.FullQuickSearchService
import com.normation.rudder.services.reports.CacheExpectedReportAction
import com.normation.rudder.services.servers.DeleteMode
import com.normation.rudder.services.servers.InstanceId
import com.normation.rudder.services.servers.InstanceIdService
import com.normation.rudder.services.system.DebugInfoScriptResult
import com.normation.rudder.services.system.DebugInfoService
import com.normation.rudder.services.user.PersonIdentService
import com.normation.rudder.services.workflows.CommitAndDeployChangeRequestService
import com.normation.rudder.services.workflows.CommitAndDeployChangeRequestServiceImpl
import com.normation.rudder.services.workflows.DefaultWorkflowLevel
import com.normation.rudder.services.workflows.NoWorkflowServiceImpl
import com.normation.rudder.tenants.ChangeContext
import com.normation.rudder.tenants.QueryContext
import com.normation.rudder.tenants.TenantAccessGrant
import com.normation.rudder.users.*
import com.normation.rudder.web.model.DirectiveField
import com.normation.rudder.web.model.LinkUtil
import com.normation.rudder.web.services.DirectiveEditorServiceImpl
import com.normation.rudder.web.services.DirectiveFieldFactory
import com.normation.rudder.web.services.EventLogDetailsGenerator
import com.normation.rudder.web.services.Section2FieldService
import com.normation.rudder.web.services.Translator
import com.normation.utils.DateFormaterService.toJavaInstant
import com.normation.utils.ParseVersion
import com.normation.utils.StringUuidGeneratorImpl
import com.normation.zio.*
import doobie.*
import enumeratum.*
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZonedDateTime
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import net.liftweb.http.LiftResponse
import net.liftweb.http.LiftRules
import net.liftweb.http.LiftRulesMocker
import net.liftweb.http.Req
import net.liftweb.http.S
import net.liftweb.http.SHtml
import net.liftweb.mocks.MockHttpServletRequest
import net.liftweb.mockweb.MockWeb
import net.liftweb.util.FieldError
import net.liftweb.util.NamedPF
import org.apache.commons.httpclient.methods.multipart.ByteArrayPartSource
import org.apache.commons.httpclient.methods.multipart.FilePart
import org.apache.commons.httpclient.methods.multipart.StringPart
import org.apache.commons.httpclient.params.HttpMethodParams
import org.apache.commons.io.FileUtils
import org.apache.commons.io.output.ByteArrayOutputStream
import org.eclipse.jgit.lib.PersonIdent
import org.joda.time.DateTime
import org.json4s.JsonAST.JValue
import org.json4s.other.JsonUtils.*
import org.specs2.matcher.MatchResult
import scala.annotation.nowarn
import scala.collection.MapView
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import scala.reflect.classTag
import scala.xml.Elem
import scala.xml.NodeSeq
import sourcecode.Line
import zio.*
import zio.syntax.*
import zio.test.*

/*
 * This file provides all the necessary plumbing to allow test REST API.
 *
 * Also responsible for setting up data and mock services.
 *
 * There is two main objects:
 * - RestTestSetUp which is responsible for initialization of all services,
 * - RestTest which provides methods to actually test things (ie, it does the  binding between mocked
 *   services and lift test framework)
 */

/**
 * A stub user service with full rights to API
 */
class TestUserService extends UserService {
  val user:                    AuthenticatedUser         = new AuthenticatedUser {
    override val account:     RudderAccount     = RudderAccount.User("test-user", UserPassword.unsafeHashed("pass"))
    override val authz:       Rights            = Rights.AnyRights
    override val apiAuthz:    ApiAuthz          = ApiAuthz.allAuthz
    override val accessGrant: TenantAccessGrant = TenantAccessGrant.All

    override def actorIp: Option[String] = None

    override def checkRights(auth: AuthorizationType): Boolean = true
  }
  override val getCurrentUser: Option[AuthenticatedUser] = Some(user)
}

/*
 * Mock everything needed to test rest API, ie almost a whole rudder.
 * One can customize API version, but by default they match the one supported in Rudder.
 */
class RestTestSetUp(val apiVersions: List[ApiVersion] = SupportedApiVersion.apiVersions) {
  import RestTestSetUp.*

  implicit val userService: TestUserService = new TestUserService

  // Instantiate Service needed to feed System API constructor

  val fakeUpdatePTLibService: UpdateTechniqueLibrary = new UpdateTechniqueLibrary() {
    def update()(implicit cc: ChangeContext): Box[Map[TechniqueName, TechniquesLibraryUpdateType]] = {
      Full(Map())
    }
    def registerCallback(callback: TechniquesLibraryUpdateNotification): Unit = {}
  }

  val mockGitRepo = new MockGitConfigRepo("")
  val mockTechniques: MockTechniques = MockTechniques(mockGitRepo)
  val mockDirectives                                 = new MockDirectives(mockTechniques)
  val mockRules                                      = new MockRules()
  val mockParameters                                 = new MockGlobalParam()
  val mockNodes                                      = new MockNodes()
  val mockNodeGroups                                 = new MockNodeGroups(mockNodes, mockParameters)
  val mockLdapQueryParsing                           = new MockLdapQueryParsing(mockGitRepo, mockNodeGroups)
  val uuidGen                                        = new StringUuidGeneratorImpl()
  val mockConfigRepo                                 = new MockConfigRepo(mockTechniques, mockDirectives, mockRules, mockNodeGroups, mockLdapQueryParsing)
  val mockCompliance                                 = new MockCompliance(mockDirectives)
  val (mockUserManagementTmpDir, mockUserManagement) = MockUserManagement()
  val mockInventoryFileWatcher                       = new MockInventoryFileWatcher()
  val mockInventoryDir                               = File.newTemporaryFile()

  val dynGroupUpdaterService =
    new DynGroupUpdaterServiceImpl(mockNodeGroups.groupsRepo, mockNodeGroups.groupsRepo, mockNodes.queryProcessor)

  val quickSearchService = new FullQuickSearchService()( // no LDAP : only directives, etc. are searchable
    using
    null,
    null,
    null,
    null,
    mockDirectives.directiveRepo,
    mockNodes.nodeFactRepo,
    null // not able to search techniques
  )

  val linkUtil = new LinkUtil(
    mockRules.ruleRepo,
    mockNodeGroups.groupsRepo,
    mockDirectives.directiveRepo,
    mockNodes.nodeFactRepo
  )

  object dynGroupService extends DynGroupService {
    override def getAllDynGroups(): Box[Seq[NodeGroup]] = {
      mockNodeGroups.groupsRepo
        .getFullGroupLibrary()(using QueryContext.testQC)
        .map(_.allGroups.collect {
          case (id, t) if (t.nodeGroup.isDynamic) => t.nodeGroup
        }.toSeq)
        .toBox
    }
    override def changesSince(lastTime: DateTime): Box[Boolean] = Full(false)

    override def getAllDynGroupsWithandWithoutDependencies(): Box[(Seq[NodeGroupId], Seq[NodeGroupId])] = ???
  }

  val deploymentStatusSerialisation: DeploymentStatusSerialisation = new DeploymentStatusSerialisation {
    override def serialise(deploymentStatus: CurrentDeploymentStatus): Elem = <test/>
  }
  val fakeModifyNodeGroupEventLog = new ModifyNodeGroup(
    EventLogDetails(
      id = Some(42),
      modificationId = None,
      principal = EventActor("test"),
      creationDate = DateTime.parse("2024-12-04T15:30:10Z").toJavaInstant,
      details = <test/>,
      reason = None
    )
  )
  val eventLogRepo:                  EventLogRepository            = new EventLogRepository {
    override def saveEventLog(modId: ModificationId, eventLog: EventLog): IOResult[EventLog] = eventLog.succeed

    override def eventLogFactory:           EventLogFactory    = ???
    override def getEventLogByCriteria(
        criteria:       Option[Fragment],
        limit:          Option[Int],
        orderBy:        List[Fragment],
        extendedFilter: Option[Fragment]
    ): IOResult[Seq[EventLog]] = List(fakeModifyNodeGroupEventLog).succeed
    override def getEventLogById(id: Long): IOResult[EventLog] = {
      fakeModifyNodeGroupEventLog.succeed
    }
    override def getEventLogCount(criteria: Option[Fragment], extendedFilter: Option[Fragment]): IOResult[Long] = 0L.succeed
    override def getEventLogByChangeRequest(
        changeRequest:   ChangeRequestId,
        xpath:           String,
        optLimit:        Option[Int],
        orderBy:         Option[String],
        eventTypeFilter: List[EventLogFilter]
    ): IOResult[Vector[EventLog]] = ???
    override def getEventLogWithChangeRequest(id: Int): IOResult[Option[(EventLog, Option[ChangeRequestId])]] = {
      ZIO.none
    }
    override def getLastEventByChangeRequest(
        xpath:           String,
        eventTypeFilter: List[EventLogFilter]
    ): IOResult[Map[ChangeRequestId, EventLog]] = ???

    override def saveAddSecret(
        modId:     ModificationId,
        principal: EventActor,
        secret:    Secret,
        reason:    Option[String]
    ): IOResult[EventLog] = ZIO.succeed(null)
    override def saveDeleteSecret(
        modId:     ModificationId,
        principal: EventActor,
        secret:    Secret,
        reason:    Option[String]
    ): IOResult[EventLog] = ZIO.succeed(null)
    override def saveModifySecret(
        modId:     ModificationId,
        principal: EventActor,
        oldSec:    Secret,
        newSec:    Secret,
        reason:    Option[String]
    ): IOResult[EventLog] = ZIO.succeed(null)

  }
  val eventLogDetailsService = new EventLogDetailsServiceImpl(null, null, null, null, null, null, null, null, null)
  val modificationService = new ModificationService(null, null) {
    override def restoreToEventLog(
        eventLog:         EventLog,
        commiter:         PersonIdent,
        rollbackedEvents: Seq[EventLog],
        target:           EventLog
    ): Box[GitCommitId] = Full(fakeGitCommitId)
  }
  val eventLogDetailGenerator: EventLogDetailsGenerator = new EventLogDetailsGenerator(
    eventLogDetailsService,
    mockNodeGroups.groupsRepo,
    mockRules.ruleCategoryRepo,
    modificationService,
    linkUtil,
    null
  )
  val eventLogger:      EventLogDeploymentService = new EventLogDeploymentService(eventLogRepo, null) {
    override def getLastDeployement(): Box[CurrentDeploymentStatus] = Full(NoStatus)
  }
  val policyGeneration: PromiseGenerationService  = new PromiseGenerationService {
    override def deploy():       Box[Set[NodeId]]                   = Full(Set())
    override def getNodeFacts(): Box[MapView[NodeId, CoreNodeFact]] = ???
    override def getDirectiveLibrary(ids: Set[DirectiveId]): Box[FullActiveTechniqueCategory] = ???
    override def getGroupLibrary():            Box[FullNodeGroupCategory]  = ???
    override def getAllGlobalParameters:       Box[Seq[GlobalParameter]]   = ???
    override def getGlobalComplianceMode():    Box[GlobalComplianceMode]   = ???
    override def getGlobalAgentRun():          Box[AgentRunInterval]       = ???
    override def getScriptEngineEnabled:       () => Box[FeatureSwitch]    = ???
    override def getGlobalPolicyMode:          () => Box[GlobalPolicyMode] = ???
    override def getComputeDynGroups:          () => Box[Boolean]          = ???
    override def getMaxParallelism:            () => Box[String]           = ???
    override def getJsTimeout:                 () => Box[Int]              = ???
    override def getGenerationContinueOnError: () => Box[Boolean]          = ???
    override def writeCertificatesPem(allNodeInfos: Map[NodeId, CoreNodeFact]): Unit = ???
    override def triggerNodeGroupUpdate(): Box[Unit] = ???
    override def beforeDeploymentSync(generationTime: DateTime): Box[Unit] = ???
    override def HOOKS_D:                     String                                               = ???
    override def HOOKS_IGNORE_SUFFIXES:       List[String]                                         = ???
    override def UPDATED_NODE_IDS_PATH:       String                                               = ???
    override def GENERATION_FAILURE_MSG_PATH: String                                               = ???
    override def getAppliedRuleIds(
        rules:        Seq[Rule],
        groupLib:     FullNodeGroupCategory,
        directiveLib: FullActiveTechniqueCategory,
        allNodeInfos: Map[NodeId, Boolean]
    ): Set[RuleId] = ???
    override def findDependantRules():        Box[Seq[Rule]]                                       = ???
    override def buildRuleVals(
        activesRules: Set[RuleId],
        rules:        Seq[Rule],
        directiveLib: FullActiveTechniqueCategory,
        groupLib:     FullNodeGroupCategory,
        allNodeInfos: Map[NodeId, Boolean]
    ): Box[Seq[RuleVal]] = ???
    override def getNodeProperties:           IOResult[Map[NodeId, ResolvedNodePropertyHierarchy]] = {
      ???
    }
    override def getNodeContexts(
        nodeIds:              Set[NodeId],
        allNodeInfos:         Map[NodeId, CoreNodeFact],
        inheritedProps:       Map[NodeId, ResolvedNodePropertyHierarchy],
        allGroups:            FullNodeGroupCategory,
        globalParameters:     List[GlobalParameter],
        globalAgentRun:       AgentRunInterval,
        globalComplianceMode: ComplianceMode,
        globalPolicyMode:     GlobalPolicyMode
    ): Box[NodesContextResult] = ???
    override def getFilteredTechnique():      Map[NodeId, List[TechniqueName]]                     = ???
    override def buildNodeConfigurations(
        activeNodeIds:             Set[NodeId],
        ruleVals:                  Seq[RuleVal],
        nodeContexts:              Map[NodeId, InterpolationContext],
        allNodeModes:              Map[NodeId, NodeModeConfig],
        filter:                    Map[NodeId, List[TechniqueName]],
        scriptEngineEnabled:       FeatureSwitch,
        globalPolicyMode:          GlobalPolicyMode,
        maxParallelism:            Int,
        jsTimeout:                 FiniteDuration,
        generationContinueOnError: Boolean
    ): Box[NodeConfigurations] = ???
    override def forgetOtherNodeConfigurationState(keep: Set[NodeId]): Box[Set[NodeId]] = ???
    override def getNodeConfigurationHash():  Box[Map[NodeId, NodeConfigurationHash]]              = ???
    override def getNodesConfigVersion(
        allNodeConfigs: Map[NodeId, NodeConfiguration],
        hashes:         Map[NodeId, NodeConfigurationHash],
        generationTime: DateTime
    ): Map[NodeId, NodeConfigId] = ???
    override def writeNodeConfigurations(
        rootNodeId:       NodeId,
        updated:          Map[NodeId, NodeConfigId],
        allNodeConfig:    Map[NodeId, NodeConfiguration],
        allNodeInfos:     Map[NodeId, CoreNodeFact],
        globalPolicyMode: GlobalPolicyMode,
        generationTime:   DateTime,
        maxParallelism:   Int
    ): Box[Set[NodeId]] = ???
    override def computeExpectedReports(
        allNodeConfigurations: Map[NodeId, NodeConfiguration],
        updatedId:             Map[NodeId, NodeConfigId],
        generationTime:        DateTime,
        allNodeModes:          Map[NodeId, NodeModeConfig]
    ): List[NodeExpectedReports] = ???
    override def saveExpectedReports(expectedReports: List[NodeExpectedReports]): Box[Seq[NodeExpectedReports]] = ???
    override def runPreHooks(generationTime:        DateTime, systemEnv: HookEnvPairs): Box[Unit] = ???
    override def runStartedHooks(generationTime:    DateTime, systemEnv: HookEnvPairs): Box[Unit] = ???
    override def runPostHooks(
        generationTime:    DateTime,
        endTime:           DateTime,
        idToConfiguration: Map[NodeId, CoreNodeFact],
        systemEnv:         HookEnvPairs,
        nodeIdsPath:       String
    ): Box[Unit] = ???
    override def runFailureHooks(
        generationTime:   DateTime,
        endTime:          DateTime,
        systemEnv:        HookEnvPairs,
        errorMessage:     String,
        errorMessagePath: String
    ): Box[Unit] = ???
    override def invalidateComplianceCache(actions: Seq[(NodeId, CacheExpectedReportAction)]): IOResult[Unit] = ???

    override def ruleValGeneratedHookService: RuleValGeneratedHookService = new RuleValGeneratedHookService()
  }
  val bootGuard:        Promise[Nothing, Unit]    = (for {
    p <- Promise.make[Nothing, Unit]
    _ <- p.succeed(())
  } yield p).runNow
  val asyncDeploymentAgent = new AsyncDeploymentActor(
    policyGeneration,
    eventLogger,
    deploymentStatusSerialisation,
    () => Duration("0s").succeed,
    () => PolicyGenerationTrigger.All.succeed,
    bootGuard
  )

  val findDependencies: FindDependencies = new FindDependencies { // never find any dependencies
    override def findRulesForDirective(id:  DirectiveUid): IOResult[Seq[Rule]] = Nil.succeed
    override def findRulesForTarget(target: RuleTarget):   IOResult[Seq[Rule]] = Nil.succeed
  }
  val dependencyService = new DependencyAndDeletionServiceImpl(
    findDependencies,
    mockDirectives.directiveRepo,
    mockDirectives.directiveRepo,
    mockRules.ruleRepo,
    mockNodeGroups.groupsRepo
  )

  val commitAndDeployChangeRequest: CommitAndDeployChangeRequestService = {
    new CommitAndDeployChangeRequestServiceImpl(
      uuidGen,
      mockDirectives.directiveRepo,
      mockDirectives.directiveRepo,
      mockNodeGroups.groupsRepo,
      mockNodeGroups.groupsRepo,
      mockRules.ruleRepo,
      mockRules.ruleRepo,
      mockParameters.paramsRepo,
      mockParameters.paramsRepo,
      asyncDeploymentAgent,
      dependencyService,
      () => false.succeed, // configService.rudder_workflow_enabled _

      null, // xmlSerializer

      null, // xmlUnserializer

      null, // sectionSpecParser

      dynGroupUpdaterService
    )
  }
  val workflowLevelService = new DefaultWorkflowLevel(
    new NoWorkflowServiceImpl(
      commitAndDeployChangeRequest
    )
  )
  val userPropertyService = new StatelessUserPropertyService(() => false.succeed, () => false.succeed, () => "".succeed)

  val zioJsonExtractor = new ZioJsonExtractor(mockLdapQueryParsing.queryParser)

  val sharedFilesApi = new SharedFilesAPI(
    userService,
    "unknown-shared-folder-path",
    mockGitRepo.configurationRepositoryRoot.pathAsString
  )

  // TODO
  // all other apis

  class FakeClearCacheService extends ClearCacheService {
    override def action(actor:                           EventActor): Box[String] = null
    override def clearNodeConfigurationCache(storeEvent: Boolean, actor: EventActor): Box[Unit] = null
  }

  val fakeNotArchivedElements: NotArchivedElements =
    NotArchivedElements(Seq[CategoryNotArchived](), Seq[ActiveTechniqueNotArchived](), Seq[DirectiveNotArchived]())
  val fakePersonIdent = new PersonIdent("test-user", "test.user@normation.com")
  val fakeGitCommitId:  GitCommitId  = GitCommitId("6d6b2ceb46adeecd845ad0c0812fee07e2727104")
  val fakeGitArchiveId: GitArchiveId = GitArchiveId(GitPath("fake/git/path"), fakeGitCommitId, fakePersonIdent)

  class FakeItemArchiveManager extends ItemArchiveManager {
    override def exportAll(
        commiter: PersonIdent,
        modId:    ModificationId,
        actor:    EventActor,
        reason:   Option[String]
    )(implicit qc: QueryContext): IOResult[(GitArchiveId, NotArchivedElements)] =
      ZIO.succeed((fakeGitArchiveId, fakeNotArchivedElements))
    override def exportRules(
        commiter: PersonIdent,
        modId:    ModificationId,
        actor:    EventActor,
        reason:   Option[String]
    ): IOResult[GitArchiveId] = ZIO.succeed(fakeGitArchiveId)
    override def exportTechniqueLibrary(
        commiter: PersonIdent,
        modId:    ModificationId,
        actor:    EventActor,
        reason:   Option[String]
    ): IOResult[(GitArchiveId, NotArchivedElements)] = ZIO.succeed((fakeGitArchiveId, fakeNotArchivedElements))
    override def exportGroupLibrary(
        commiter: PersonIdent,
        modId:    ModificationId,
        actor:    EventActor,
        reason:   Option[String]
    )(implicit qc: QueryContext): IOResult[GitArchiveId] = ZIO.succeed(fakeGitArchiveId)
    override def exportParameters(
        commiter: PersonIdent,
        modId:    ModificationId,
        actor:    EventActor,
        reason:   Option[String]
    ): IOResult[GitArchiveId] = ZIO.succeed(fakeGitArchiveId)
    override def importAll(
        archiveId: GitCommitId,
        commiter:  PersonIdent
    )(implicit cc: ChangeContext): IOResult[GitCommitId] = ZIO.succeed(fakeGitCommitId)
    override def importRules(
        archiveId: GitCommitId,
        commiter:  PersonIdent
    )(implicit cc: ChangeContext): IOResult[GitCommitId] = ZIO.succeed(fakeGitCommitId)
    override def importTechniqueLibrary(
        archiveId: GitCommitId,
        commiter:  PersonIdent
    )(implicit cc: ChangeContext): IOResult[GitCommitId] = ZIO.succeed(fakeGitCommitId)
    override def importGroupLibrary(
        archiveId: GitCommitId,
        commiter:  PersonIdent
    )(implicit cc: ChangeContext): IOResult[GitCommitId] = ZIO.succeed(fakeGitCommitId)
    override def importParameters(
        archiveId: GitCommitId,
        commiter:  PersonIdent
    )(implicit cc: ChangeContext): IOResult[GitCommitId] = ZIO.succeed(fakeGitCommitId)
    override def rollback(
        archiveId:        GitCommitId,
        commiter:         PersonIdent,
        rollbackedEvents: Seq[EventLog],
        target:           EventLog,
        rollbackType:     String
    )(implicit cc: ChangeContext): IOResult[GitCommitId] = ZIO.succeed(fakeGitCommitId)

    /**
      * These methods are called by the Archive API to get the git archives.
      * The API then provides the logic to transform the Box[Map][DateTime, GitArchiveId] into JSON
      * Here, we want to make these methods returning fake archives for testing the API logic.
      */
    val fakeArchives:                     Map[Instant, GitArchiveId]           = Map[Instant, GitArchiveId](
      Instant.parse("1970-01-01T01:00:00.042Z") -> fakeGitArchiveId
    )
    override def getFullArchiveTags:      IOResult[Map[Instant, GitArchiveId]] = ZIO.succeed(fakeArchives)
    override def getGroupLibraryTags:     IOResult[Map[Instant, GitArchiveId]] = ZIO.succeed(fakeArchives)
    override def getTechniqueLibraryTags: IOResult[Map[Instant, GitArchiveId]] = ZIO.succeed(fakeArchives)
    override def getRulesTags:            IOResult[Map[Instant, GitArchiveId]] = ZIO.succeed(fakeArchives)
    override def getParametersTags:       IOResult[Map[Instant, GitArchiveId]] = ZIO.succeed(fakeArchives)
  }
  val fakeItemArchiveManager = new FakeItemArchiveManager
  val fakeClearCacheService = new FakeClearCacheService
  val fakePersonIndentService: PersonIdentService = new PersonIdentService {
    override def getPersonIdentOrDefault(username: String): ZIO[Any, Nothing, PersonIdent] = ZIO.succeed(fakePersonIdent)
  }
  val fakeScriptLauncher:      DebugInfoService   = new DebugInfoService {
    override def launch(): ZIO[Any, Nothing, DebugInfoScriptResult] = DebugInfoScriptResult("test", new Array[Byte](42)).succeed
  }

  val fakeUpdateDynamicGroups: UpdateDynamicGroups = {
    new UpdateDynamicGroups(
      dynGroupService,
      dynGroupUpdaterService,
      mockNodeGroups.propSyncService,
      asyncDeploymentAgent,
      uuidGen,
      1,
      () => Full("1")
    ) {
      // for some reason known only by Scala inheritance rules, the underlying LAUpdateDyngroup is null, so we need to override that.
      override lazy val laUpdateDyngroupManager = new LAUpdateDyngroupManager() {
        override protected def messageHandler: PartialFunction[GroupUpdateMessage, Unit] = { case _ => () }
      }
      override def startManualUpdate: Unit = ()
    }
  }

  val apiService11           = new SystemApiService11(
    fakeUpdatePTLibService,
    fakeScriptLauncher,
    fakeClearCacheService,
    asyncDeploymentAgent,
    uuidGen,
    fakeUpdateDynamicGroups,
    fakeItemArchiveManager,
    fakePersonIndentService,
    mockGitRepo.gitRepo
  )
  val fakeHealthcheckService = new HealthcheckService(
    List(
      CheckCoreNumber,
      CheckFreeSpace,
      new CheckFileDescriptorLimit(mockNodes.nodeFactRepo)
    )
  )
  val fakeHcNotifService     = new HealthcheckNotificationService(fakeHealthcheckService, 5.minute)
  val apiService13           = new SystemApiService13(
    fakeHealthcheckService,
    fakeHcNotifService,
    null
  )

  val eventLogService = new EventLogService(eventLogRepo, eventLogDetailGenerator, fakePersonIndentService)
  val eventLogApi     = new EventLogAPI(
    eventLogService,
    eventLogDetailGenerator,
    _.serialize
  )

  val ruleCategoryService = new RuleCategoryService()

  val ruleApiService14        = new RuleApiService14(
    mockRules.ruleRepo,
    mockRules.ruleRepo,
    mockConfigRepo.configurationRepository,
    uuidGen,
    asyncDeploymentAgent,
    workflowLevelService,
    mockRules.ruleCategoryRepo,
    mockRules.ruleCategoryRepo,
    mockDirectives.directiveRepo,
    mockNodeGroups.groupsRepo,
    mockNodes.nodeFactRepo,
    () => GlobalPolicyMode(Enforce, Always).succeed,
    new RuleApplicationStatusServiceImpl()
  )
  val ruleInternalApiService  = new RuleInternalApiService(
    mockRules.ruleRepo,
    mockNodeGroups.groupsRepo,
    mockRules.ruleCategoryRepo,
    mockNodes.nodeFactRepo
  )
  val groupInternalApiService = new GroupInternalApiService(mockNodeGroups.groupsRepo)

  val fieldFactory: DirectiveFieldFactory = new DirectiveFieldFactory {
    override def forType(fieldType: VariableSpec, id: String): DirectiveField = default(id)
    override def default(withId: String): DirectiveField = new DirectiveField {
      self => type ValueType = String
      def manifest:               ClassTag[String]                 = classTag[String]
      val id:                     String                           = withId
      def name:                   String                           = id
      override val uniqueFieldId: Box[String]                      = Full(id)
      @nowarn("any")
      protected var _x:           String                           = getDefaultValue
      def validate:               List[FieldError]                 = Nil
      def validations:            List[String => List[FieldError]] = Nil
      def setFilter:              List[String => String]           = Nil
      def parseClient(s: String): Unit = if (null == s) _x = "" else _x = s
      def toClient: String = if (null == _x) "" else _x
      def getPossibleValues(filters: (ValueType => Boolean)*): Option[Set[ValueType]] = None // not supported in the general cases
      def getDefaultValue = ""
      def get: String = _x
      def set(x: String): String = { if (null == x) _x = "" else _x = x; _x }
      def toForm: Box[NodeSeq] = Full(SHtml.textarea("", s => parseClient(s)))
    }
  }
  val directiveEditorService = new DirectiveEditorServiceImpl(
    mockConfigRepo.configurationRepository,
    new Section2FieldService(fieldFactory, Translator.defaultTranslators)
  )

  val directiveApiService14: DirectiveApiService14 = {
    new DirectiveApiService14(
      mockDirectives.directiveRepo,
      mockConfigRepo.configurationRepository,
      mockDirectives.directiveRepo,
      uuidGen,
      asyncDeploymentAgent,
      workflowLevelService,
      directiveEditorService,
      mockTechniques.techniqueRepo
    )
  }

  object TestSystemInfoService extends SystemInfoService {
    private val pub = PublicSystemInfo(
      "8.3",
      "8.3.0",
      "2025-01-05T21:53:20+01:00",
      Linux(
        RockyLinux,
        "Rocky Linux release 8.10 (Green Obsidian)",
        new IVersion("8.10"),
        None,
        new IVersion("4.18.0-513.9.1.el8_9.x86_64")
      ),
      JvmInfo("openjdk", "21.0.32", "java -Dblablabla rudder"),
      FrequentNodeMetrics(555, 233, 144, 178, 42, 26)
    )

    private val priv = PrivateSystemInfo(
      InstanceId("c2180fd6-36e1-41d9-ad27-b71ff49eef68"),
      List(RelayInfo(NodeId("ac189019-6f8d-47ca-9f3a-ad66c338ce50"), "relay0.rudder.io", 342)),
      None,
      List(
        Plugin(
          PluginId("rudder-plugin-auth-backends"),
          "authentication backends",
          "Add new authentication backends",
          Some("8.3.0-2.4.1"),
          PluginInstallStatus.Enabled,
          None,
          ParseVersion.parse("8.3.0-2.4.1").getOrElse(throw new Exception("wrong version in tests")),
          ParseVersion.parse("8.3.0-2.4.1").getOrElse(throw new Exception("wrong version in tests")),
          PluginType.Webapp,
          Nil,
          None
        )
      )
    )

    override def getPublicInfo():  IOResult[PublicSystemInfo]  = pub.succeed
    override def getPrivateInfo(): IOResult[PrivateSystemInfo] = priv.succeed
    override def getAll():         IOResult[SystemInfo]        = SystemInfo(priv, pub).succeed
  }

  val systemApi = new SystemApi(apiService11, apiService13, TestSystemInfoService)
  val systemStatusPath: String = "api" + systemApi.Status.schema.path

  val softDao = mockNodes.softwareDao
  val roReportsExecutionRepository: RoReportsExecutionRepository = new RoReportsExecutionRepository {
    override def getNodesLastRun(nodeIds: Set[NodeId]): IOResult[Map[NodeId, Option[AgentRunWithNodeConfig]]] =
      Map.empty[NodeId, Option[AgentRunWithNodeConfig]].succeed

    def getNodesAndUncomputedCompliance(): IOResult[Map[NodeId, Option[AgentRunWithNodeConfig]]] = ???

    def getUnprocessedRuns(): IOResult[Seq[AgentRunWithoutCompliance]] = ???
  }

  val nodeApiService: nodeApiService = new nodeApiService
  class nodeApiService extends NodeApiService(
        null,
        mockNodes.nodeFactRepo,
        mockNodes.propRepo,
        roReportsExecutionRepository,
        null,
        uuidGen,
        null,
        null,
        null,
        mockNodes.newNodeManager,
        mockNodes.removeNodeService,
        zioJsonExtractor,
        mockCompliance.reportingService(Map.empty[NodeId, NodeStatusReport].toMap),
        mockNodes.queryProcessor,
        null,
        () => Full(GlobalPolicyMode(Audit, PolicyModeOverrides.Always)),
        "relay",
        mockNodes.scoreService,
        new InstanceIdService(InstanceId("rudder-test-instance"))
      ) {
    implicit val testCC: ChangeContext = {
      ChangeContext(
        EventActor("test"),
        QueryContext.testQC.accessGrant,
        ModificationId(uuidGen.newUuid),
        Instant.now(),
        None,
        None
      )
    }
    import QueryContext.testQC

    override def checkUuid(nodeId: NodeId): IO[Creation.CreationError, Unit] = {
      mockNodes.nodeFactRepo
        .get(nodeId)
        .map(_.nonEmpty)
        .mapError(err => CreationError.OnSaveInventory(s"Error during node ID check: ${err.fullMsg}"))
        .unit
    }

    override def saveInventory(inventory: FullInventory)(implicit cc: ChangeContext): IO[Creation.CreationError, NodeId] = {
      mockNodes.nodeFactRepo
        .updateInventory(inventory, None)(using cc)
        .mapBoth(
          err => CreationError.OnSaveInventory(s"Error when saving node: ${err.fullMsg}"),
          _ => inventory.node.main.id
        )
    }

    override def saveRudderNode(id: NodeId, setup: NodeSetup): IO[Creation.CreationError, NodeId] = {
      (for {
        n <- mockNodes.nodeFactRepo.get(id).notOptional(s"Can not merge node: missing")
        n2 = CoreNodeFact.updateNode(n, mergeNodeSetup(n.toNode, setup))
        _ <- mockNodes.nodeFactRepo.save(n2)(using testCC)
      } yield {
        n.id
      }).mapError(err => CreationError.OnSaveInventory(err.fullMsg))
    }
  }

  val parameterApiService14 = new ParameterApiService14(
    mockParameters.paramsRepo,
    workflowLevelService
  )

  val groupService14 = new GroupApiService14(
    mockNodes.nodeFactRepo,
    mockNodeGroups.groupsRepo,
    mockNodeGroups.groupsRepo,
    mockNodes.propRepo,
    mockNodeGroups.propService,
    uuidGen,
    asyncDeploymentAgent,
    workflowLevelService,
    mockLdapQueryParsing.queryParser,
    mockNodes.queryProcessor
  )

  val ncfTechniqueReader: EditorTechniqueReader = new EditorTechniqueReader {
    private def gm(id: Int) = GenericMethod(
      BundleName("gm" + id),
      "gm" + id,
      (0 until id).map(i => {
        MethodParameter(
          ParameterId("param" + i),
          "param desc for " + i,
          Nil,
          ParameterType.StringParameter
        )
      }),
      ParameterId("classParam" + id),
      "classPrefix" + id,
      Seq(AgentType.CfeCommunity, AgentType.Dsc),
      "gm desc " + id,
      Some("doc for gm" + id),
      None,
      None,
      Nil
    )

    private def tech(id: Int) = EditorTechnique(
      BundleName("tech" + id),
      new com.normation.inventory.domain.Version("1.0"),
      "tech" + id,
      "catTech" + id,
      (0 until id).map { i =>
        MethodCall(
          BundleName("methodCall" + i),
          "methodCall" + i,
          Map((0 until i).map(j => ((ParameterId("param" + j), "value" + j)))*),
          "condMethodCall" + i,
          "componentMethodCall" + i,
          disabledReporting = false,
          Some(PolicyMode.Enforce),
          if (i == 0) None else Some((0 until i).map(j => Map((0 until j).map(k => (("k" + k, "value" + k)))*)).toList),
          if (i == 0) None else Some("foreachName" + i)
        )
      },
      "tech description " + id,
      "tech documentation " + id,
      (0 until id).map { i =>
        TechniqueParameter(
          ParameterId("techParam" + id),
          "techParam" + id,
          Some("tech param " + id),
          Some("tech param doc " + id),
          false,
          Some(Constraints(Some(false), Some(true), Some(0), Some(42), None, None, None))
        )
      },
      (0 until id).map(i => ResourceFile("resource" + i, ResourceFileState.Untouched)),
      Map(("tag" + id, zio.json.ast.Json.Str("tag" + id))),
      Some("internalId" + id)
    )

    val techniques: List[EditorTechnique]          = List(tech(0), tech(2))
    val methods:    Map[BundleName, GenericMethod] = List(gm(0), gm(1), gm(2)).map(gm => (gm.id, gm)).toMap

    override def readTechniquesMetadataFile
        : IOResult[(List[EditorTechnique], Map[BundleName, GenericMethod], List[RudderError])] =
      (techniques, methods, List(Inconsistency("for test"))).succeed
    override def getMethodsMetadata:        IOResult[Map[BundleName, GenericMethod]] = methods.succeed
    override def updateMethodsMetadataFile: IOResult[CmdResult]                      = CmdResult(0, "", "").succeed
  }

  val techniqueSerializer: TechniqueSerializer = new TechniqueSerializer(new BasicParameterTypeService)

  val techniqueAPIService14 = new TechniqueAPIService14(
    mockDirectives.directiveRepo,
    mockTechniques.techniqueRevisionRepo,
    ncfTechniqueReader,
    techniqueSerializer,
    mockTechniques.techniqueCompiler
  )

  val ncfTechniqueWriter: TechniqueWriter = new TechniqueWriterImpl(
    mockTechniques.techniqueArchiver,
    fakeUpdatePTLibService,
    mockTechniques.deleteEditorTechnique,
    mockTechniques.techniqueCompiler,
    mockTechniques.techniqueCompilationCache,
    mockGitRepo.configurationRepositoryRoot.pathAsString
  )

  val resourceFileService: ResourceFileService = null
  val settingsService = new MockSettings(workflowLevelService, new AsyncWorkflowInfo())

  object archiveAPIModule {
    val archiveBuilderService = new ZipArchiveBuilderService(
      new FileArchiveNameService(),
      mockConfigRepo.configurationRepository,
      mockTechniques.techniqueRevisionRepo,
      mockNodeGroups.groupsRepo,
      mockRules.ruleRepo,
      mockRules.ruleCategoryRepo,
      mockDirectives.directiveRepo,
      mockTechniques.techniqueRepo
    )

    // archive name in a Ref to make it simple to change in tests
    val rootDirName: Ref[String] = Ref.make("archive").runNow
    val zipArchiveReader = new ZipArchiveReaderImpl(mockLdapQueryParsing.queryParser, mockTechniques.techniqueParser)
    // a mock save archive that stores result in a ref
    object archiveSaver   extends SaveArchiveService  {
      val base:                                                                                       Ref[Option[(PolicyArchive, MergePolicy)]] = Ref.make(Option.empty[(PolicyArchive, MergePolicy)]).runNow
      override def save(archive: PolicyArchive, mergePolicy: MergePolicy)(implicit qc: QueryContext): IOResult[Unit]                            = {
        base.set(Some((archive, mergePolicy))).unit
      }
    }
    object archiveChecker extends CheckArchiveService {
      override def check(archive: PolicyArchive): IOResult[Unit] = ZIO.unit
    }
    val api = new ArchiveApi(
      archiveBuilderService,
      rootDirName.get,
      zipArchiveReader,
      archiveSaver,
      archiveChecker
    )
  }

  val mockCampaign = new MockCampaign()
  object campaignApiModule {

    val translator = new CampaignSerializer()
    translator.addJsonTranslater(mockCampaign.dumbCampaignTranslator)
    import mockCampaign.*
    val api        = new CampaignApi(repo, translator, dumbCampaignEventRepository, mainCampaignService, uuidGen)
  }

  // name that one for version API
  val parameterApi         = new ParameterApi(zioJsonExtractor, parameterApiService14)
  // info is special and should be based on all api and version, but to avoid change at each tests/version, build
  // it with static values
  val infoApi              = {
    val infoVersion = ApiVersion(19, deprecated = true) ::
      ApiVersion(20, deprecated = false) ::
      Nil
    val schemas     = parameterApi.schemas.endpoints ++ InfoApi.endpoints ++ RestTestEndpoints.endpoints
    val endpoints   = schemas.flatMap(new RudderEndpointDispatcher(LiftApiProcessingLogger).withVersion(_, infoVersion))
    new InfoApi(infoVersion, endpoints)
  }
  val pluginsSystemService = InMemoryPluginService
    .make(
      Plugin(
        PluginId("auth-backends"),
        "authentication backends",
        "Add new authentication backends",
        Some("8.3.0-2.4.1"),
        PluginInstallStatus.Enabled,
        None,
        ParseVersion.parse("2.4.1").getOrElse(throw new Exception("bad version in test")),
        ParseVersion.parse("8.3.0").getOrElse(throw new Exception("bad version in test")),
        PluginType.Webapp,
        List(PluginError.LicenseNearExpirationError(1, ZonedDateTime.parse("2025-01-10T20:53:20Z"))),
        Some(
          PluginLicense(
            Licensee("test-licensee"),
            SoftwareId("test-softwareId"),
            MinVersion("0.0.0-0.0.0"),
            MaxVersion("99.99.0-99.99.0"),
            ZonedDateTime.parse("2025-01-10T20:53:20Z"),
            ZonedDateTime.parse("2025-01-10T20:53:20Z"),
            MaxNodes(Some(1_000_000)),
            Map.empty[String, String].toMap
          )
        )
      ) ::
      Plugin(
        PluginId("cve"),
        "cve",
        "Manage known vulnerabilities in system components",
        Some("8.3.0-2.10"),
        PluginInstallStatus.Enabled,
        None,
        ParseVersion.parse("2.10").getOrElse(throw new Exception("bad version in test")),
        ParseVersion.parse("8.3.0").getOrElse(throw new Exception("bad version in test")),
        PluginType.Webapp,
        List(
          PluginError.LicenseNeededError
        ),
        None
      ) ::
      Plugin(
        PluginId("zabbix"),
        "zabbix",
        "Integration with Zabbix (monitoring tool)",
        Some("8.3.0-2.1"),
        PluginInstallStatus.Uninstalled,
        None,
        ParseVersion.parse("2.1").getOrElse(throw new Exception("bad version in test")),
        ParseVersion.parse("8.3.0").getOrElse(throw new Exception("bad version in test")),
        PluginType.Integration,
        List.empty,
        None
      )
      :: Nil
    )
    .runNow

  val mockApiAccounts = new MockApiAccountService(userService)
  val apiAccountApi: ApiAccountApi = new ApiAccountApi(mockApiAccounts.service)

  val apiModules: List[LiftApiModuleProvider[? <: EndpointSchema & SortIndex]] = List(
    systemApi,
    parameterApi,
    new TechniqueApi(
      techniqueAPIService14,
      ncfTechniqueWriter,
      ncfTechniqueReader,
      mockTechniques.techniqueRepo,
      techniqueSerializer,
      uuidGen,
      userPropertyService,
      resourceFileService,
      mockGitRepo.configurationRepositoryRoot.pathAsString
    ),
    new DirectiveApi(
      zioJsonExtractor,
      uuidGen,
      directiveApiService14
    ),
    new RuleApi(zioJsonExtractor, ruleApiService14, uuidGen),
    new RulesInternalApi(ruleInternalApiService, ruleApiService14),
    new GroupsInternalApi(groupInternalApiService),
    new NodeApi(
      zioJsonExtractor,
      mockNodeGroups.propService,
      nodeApiService,
      userPropertyService,
      new NodeApiInheritedProperties(mockNodes.propRepo),
      uuidGen,
      DeleteMode.Erase
    ),
    new GroupsApi(
      mockNodeGroups.propService,
      zioJsonExtractor,
      uuidGen,
      userPropertyService,
      groupService14
    ),
    new SettingsApi(
      settingsService.configService,
      asyncDeploymentAgent,
      uuidGen,
      settingsService.policyServerManagementService,
      mockNodes.nodeFactRepo,
      zioJsonExtractor
    ),
    archiveAPIModule.api,
    campaignApiModule.api,
    new ComplianceApi(
      mockCompliance.complianceAPIService,
      mockDirectives.directiveRepo
    ),
    new UserManagementApiImpl(
      mockUserManagement.userRepo,
      mockUserManagement.userService,
      mockUserManagement.userManagementService,
      mockUserManagement.tenantRepo,
      () => mockUserManagement.providerRoleExtension,
      () => mockUserManagement.authBackendProviders
    ),
    apiAccountApi,
    infoApi,
    eventLogApi,
    new PluginInternalApi(pluginsSystemService),
    new InventoryApi(mockInventoryFileWatcher, mockInventoryDir)
  )

  val (rudderApi, liftRules) = TraitTestApiFromYamlFiles.buildLiftRules(apiModules, apiVersions, Some(userService))

  // RestHelpers
  liftRules.statelessDispatch.append(RestStatus)
  liftRules.statelessDispatch.append(sharedFilesApi)
  liftRules.statelessDispatch.append(
    new RestQuicksearch(
      quickSearchService,
      userService,
      linkUtil
    )
  )

  val baseTempDirectory = mockGitRepo.abstractRoot

  // a cleanup method that delete all test files
  def cleanup(): Unit = {

    FileUtils.deleteDirectory(mockGitRepo.abstractRoot.toJava)
    FileUtils.deleteDirectory(mockUserManagementTmpDir.toJava)

  }

}

object RestTestSetUp {

  sealed private trait RestTestEndpoints extends EnumEntry with EndpointSchema with GeneralApi with SortIndex      {
    override def dataContainer: Option[String] = None
  }
  private object RestTestEndpoints       extends Enum[RestTestEndpoints] with ApiModuleProvider[RestTestEndpoints] {

    object TestEndpoint extends RestTestEndpoints with StartsAtVersion19 with ZeroParam with SortIndex {
      val z: Int = implicitly[Line].value
      val description    = "Just an endpoint for tests"
      val (action, path) = GET / "test-endpoint"
      val authz: List[AuthorizationType] = AuthorizationType.Administration.Read :: Nil
    }

    object TestEndpointDuplicate extends RestTestEndpoints with StartsAtVersion19 with ZeroParam with SortIndex {
      val z: Int = implicitly[Line].value
      val description    = "Just an endpoint for tests : a duplicate which has the same name but another path"
      val (action, path) = GET / "test-endpoint-duplicate"
      val authz: List[AuthorizationType] = AuthorizationType.Administration.Read :: Nil

      override val name: String = TestEndpoint.name
    }

    def endpoints: List[RestTestEndpoints] = values.toList.sortBy(_.z)

    def values: IndexedSeq[RestTestEndpoints] = findValues
  }

  def newEnv: RestTestSetUp = {
    new RestTestSetUp()
  }
}

/*
 * Provides methods to mock & test REST requests programmatically using all our services.
 */
class RestTest(liftRules: LiftRules) {

  /*
   * Correctly build and scope mutable things to use the request in a safe
   * way in the context of LiftRules.
   */
  def doReq[T](mockReq: MockHttpServletRequest)(tests: Req => MatchResult[T]): MatchResult[T] = {
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
  def execRequestResponse[T](mockReq: MockHttpServletRequest)(tests: Box[LiftResponse] => MatchResult[T]): MatchResult[T] = {
    doReq(mockReq) { req =>
      // the test logic is taken from LiftServlet#doServices.
      // perhaps we should call directly that methods, but it need
      // much more set-up, and I don't know for sure *how* to set-up things.
      NamedPF
        .applyBox(req, LiftRules.statelessDispatch.toList)
        .map(_.apply() match {
          case Full(a) => Full(LiftRules.convertResponse((a, Nil, S.responseCookies, req)))
          case r       => r
        }) match {
        case Full(x) => tests(x)
        case eb: EmptyBox => tests(eb)
      }
    }
  }

  /*
   * Correctly build and scope mutable things to use the request in a safe
   * way in the context of LiftRules.
   */
  def doReqZioTest[T](mockReq: MockHttpServletRequest)(tests: Req => TestResult): TestResult = {
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
  def execRequestResponseZioTest[T](mockReq: MockHttpServletRequest)(tests: Box[LiftResponse] => TestResult): TestResult = {
    doReqZioTest(mockReq) { req =>
      // the test logic is taken from LiftServlet#doServices.
      // perhaps we should call directly that methods, but it needs
      // much more set-up, and I don't know for sure *how* to set-up things.
      NamedPF
        .applyBox(req, LiftRules.statelessDispatch.toList)
        .map(_.apply() match {
          case Full(a) => Full(LiftRules.convertResponse((a, Nil, S.responseCookies, req)))
          case r       => r ?~! "Error when executing the request in LiftRules.statelessDispatch.toList"
        }) match {
        case Full(x) => tests(x)
        case eb: EmptyBox => tests(eb)
      }
    }
  }

  private def mockRequest(path: String, method: String) = {
    val mockReq = new MockHttpServletRequest("http://localhost:8080")

    val (p, queryString) = {
      path.split('?').toList match {
        case Nil       => (path, "") // should not happen since we have at least path
        case h :: Nil  => (h, "")
        case h :: tail => (h, tail.mkString("&"))
      }
    }

    mockReq.method = method
    // parse
    mockReq.path = p
    if (method == "GET") {
      mockReq.queryString = queryString
    }
    mockReq
  }
  def GET(path: String): MockHttpServletRequest = mockRequest(path, "GET")
  def POST(path:   String): MockHttpServletRequest = mockRequest(path, "POST")
  def DELETE(path: String): MockHttpServletRequest = mockRequest(path, "DELETE")

  private def mockJsonRequest(path: String, method: String, data: JValue) = {
    val mockReq = mockRequest(path, method)

    mockReq.body = data.prettyRender.getBytes()
    mockReq.contentType = "application/json"
    mockReq
  }

  // String-based API alternative to lift-json
  private def mockJsonRequest(path: String, method: String, data: String) = {
    val mockReq = mockRequest(path, method)
    mockReq.body_=(data, "application/json")
    mockReq
  }

  def jsonPUT(path: String, json: JValue): MockHttpServletRequest = {
    mockJsonRequest(path, "PUT", json)
  }

  def jsonPUT(path: String, json: String): MockHttpServletRequest = {
    mockJsonRequest(path, "PUT", json)
  }

  def jsonPOST(path: String, json: JValue): MockHttpServletRequest = {
    mockJsonRequest(path, "POST", json)
  }

  def jsonPOST(path: String, json: String): MockHttpServletRequest = {
    mockJsonRequest(path, "POST", json)
  }

  // url encode the data for param name
  def binaryPOST(
      path:         String,
      paramName:    String,
      filename:     String,
      data:         Array[Byte],
      stringParams: Map[String, String] = Map()
  ): MockHttpServletRequest = {
    import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity
    val mockReq     = mockRequest(path, "POST")
    val filePart    = new FilePart(paramName, new ByteArrayPartSource(filename, data), null, StandardCharsets.UTF_8.name())
    val stringParts = stringParams.toList.map { case (n, v) => new StringPart(n, v, StandardCharsets.UTF_8.name()) }
    val parts       = new MultipartRequestEntity(Array(filePart) ++ stringParts, new HttpMethodParams())
    val out         = new ByteArrayOutputStream()
    parts.writeRequest(out)
    // be careful, liftweb does not parse header, you need to put content type in the variable
    mockReq.headers = Map(
      "Content-Type"   -> List(parts.getContentType),
      "Content-Length" -> List(parts.getContentLength.toString)
    ).toMap
    mockReq.contentType = parts.getContentType
    mockReq.body = out.toByteArray
    mockReq
  }

  // high level methods. Directly manipulate response
  def testGETResponse[T](path: String)(tests: Box[LiftResponse] => MatchResult[T]):    MatchResult[T] = {
    execRequestResponse(GET(path))(tests)
  }
  def testDELETEResponse[T](path: String)(tests: Box[LiftResponse] => MatchResult[T]): MatchResult[T] = {
    execRequestResponse(DELETE(path))(tests)
  }

  def testPUTResponse[T](path: String, json: JValue)(tests: Box[LiftResponse] => MatchResult[T]):  MatchResult[T] = {
    execRequestResponse(jsonPUT(path, json))(tests)
  }
  def testPOSTResponse[T](path: String, json: JValue)(tests: Box[LiftResponse] => MatchResult[T]): MatchResult[T] = {
    execRequestResponse(jsonPOST(path, json))(tests)
  }
  def testPUTResponse[T](path: String, json: String)(tests: Box[LiftResponse] => MatchResult[T]):  MatchResult[T] = {
    execRequestResponse(jsonPUT(path, json))(tests)
  }
  def testPOSTResponse[T](path: String, json: String)(tests: Box[LiftResponse] => MatchResult[T]): MatchResult[T] = {
    execRequestResponse(jsonPOST(path, json))(tests)
  }
  def testBinaryPOSTResponse[T](
      path:         String,
      paramName:    String,
      filename:     String,
      data:         Array[Byte],
      stringParams: Map[String, String] = Map()
  )(
      tests:        Box[LiftResponse] => MatchResult[T]
  ): MatchResult[T] = {
    execRequestResponse(binaryPOST(path, paramName, filename, data, stringParams))(tests)
  }
  def testEmptyPostResponse[T](path: String)(tests: Box[LiftResponse] => MatchResult[T]):          MatchResult[T] = {
    execRequestResponse(POST(path))(tests)
  }

  // Low level methods. You can build the answer by hand from there

  def testGET[T](path: String)(tests: Req => MatchResult[T]): MatchResult[T] = {
    doReq(GET(path))(tests)
  }

  def testDELETE[T](path: String)(tests: Req => MatchResult[T]): MatchResult[T] = {
    doReq(DELETE(path))(tests)
  }

  def testPUT[T](path: String, json: JValue)(tests: Req => MatchResult[T]):  MatchResult[T] = {
    doReq(jsonPUT(path, json))(tests)
  }
  def testPOST[T](path: String, json: JValue)(tests: Req => MatchResult[T]): MatchResult[T] = {
    doReq(jsonPOST(path, json))(tests)
  }

  // This is a method to call when testing POST endpoint without data to pass.
  // Ex: reload techniques endpoints which reload all techniques.
  // It has to be a POST because it executes an action on the server side even if it doesn't need additional data to do so.
  def testEmptyPost[T](path: String)(tests: Req => MatchResult[T]): MatchResult[T] = {
    doReq(POST(path))(tests)
  }
}
