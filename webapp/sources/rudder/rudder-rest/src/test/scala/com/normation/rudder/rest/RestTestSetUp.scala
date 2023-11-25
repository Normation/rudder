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

import com.normation.box._
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.VariableSpec
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.cfclerk.services.TechniquesLibraryUpdateNotification
import com.normation.cfclerk.services.UpdateTechniqueLibrary
import com.normation.errors.IOResult
import com.normation.eventlog.EventActor
import com.normation.eventlog.EventLog
import com.normation.eventlog.EventLogFilter
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.FullInventory
import com.normation.inventory.domain.NodeId
import com.normation.rudder._
import com.normation.rudder.api.{ApiAuthorization => ApiAuthz}
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.apidata.RestDataSerializerImpl
import com.normation.rudder.apidata.ZioJsonExtractor
import com.normation.rudder.batch._
import com.normation.rudder.batch.PolicyGenerationTrigger.AllGeneration
import com.normation.rudder.campaigns.CampaignSerializer
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyMode.Audit
import com.normation.rudder.domain.policies.PolicyMode.Enforce
import com.normation.rudder.domain.policies.PolicyModeOverrides
import com.normation.rudder.domain.policies.PolicyModeOverrides.Always
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.domain.reports.NodeExpectedReports
import com.normation.rudder.domain.reports.NodeModeConfig
import com.normation.rudder.domain.secret.Secret
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.NodeFact
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.facts.nodes.SelectFacts
import com.normation.rudder.git.GitArchiveId
import com.normation.rudder.git.GitCommitId
import com.normation.rudder.git.GitPath
import com.normation.rudder.hooks.HookEnvPairs
import com.normation.rudder.ncf.EditorTechniqueReader
import com.normation.rudder.ncf.ResourceFileService
import com.normation.rudder.ncf.TechniqueSerializer
import com.normation.rudder.ncf.TechniqueWriter
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.reports.execution.AgentRunWithNodeConfig
import com.normation.rudder.reports.execution.AgentRunWithoutCompliance
import com.normation.rudder.reports.execution.RoReportsExecutionRepository
import com.normation.rudder.repository._
import com.normation.rudder.rest.data.Creation
import com.normation.rudder.rest.data.Creation.CreationError
import com.normation.rudder.rest.data.NodeSetup
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
import com.normation.rudder.services.queries.DynGroupService
import com.normation.rudder.services.queries.DynGroupUpdaterServiceImpl
import com.normation.rudder.services.reports.CacheExpectedReportAction
import com.normation.rudder.services.servers.DeleteMode
import com.normation.rudder.services.system.DebugInfoScriptResult
import com.normation.rudder.services.system.DebugInfoService
import com.normation.rudder.services.user.PersonIdentService
import com.normation.rudder.services.workflows.CommitAndDeployChangeRequestService
import com.normation.rudder.services.workflows.CommitAndDeployChangeRequestServiceImpl
import com.normation.rudder.services.workflows.DefaultWorkflowLevel
import com.normation.rudder.services.workflows.NoWorkflowServiceImpl
import com.normation.rudder.web.model.DirectiveField
import com.normation.rudder.web.services.DirectiveEditorServiceImpl
import com.normation.rudder.web.services.DirectiveFieldFactory
import com.normation.rudder.web.services.Section2FieldService
import com.normation.rudder.web.services.StatelessUserPropertyService
import com.normation.rudder.web.services.Translator
import com.normation.utils.StringUuidGeneratorImpl
import com.normation.zio._
import doobie._
import java.nio.charset.StandardCharsets
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import net.liftweb.http.LiftResponse
import net.liftweb.http.LiftRules
import net.liftweb.http.LiftRulesMocker
import net.liftweb.http.Req
import net.liftweb.http.S
import net.liftweb.http.SHtml
import net.liftweb.json.JsonAST.JValue
import net.liftweb.mocks.MockHttpServletRequest
import net.liftweb.mockweb.MockWeb
import net.liftweb.util.NamedPF
import org.apache.commons.httpclient.methods.multipart.ByteArrayPartSource
import org.apache.commons.httpclient.methods.multipart.FilePart
import org.apache.commons.httpclient.methods.multipart.StringPart
import org.apache.commons.httpclient.params.HttpMethodParams
import org.apache.commons.io.FileUtils
import org.apache.commons.io.output.ByteArrayOutputStream
import org.eclipse.jgit.lib.PersonIdent
import org.joda.time.DateTime
import org.specs2.matcher.MatchResult
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.xml.Elem
import zio._
import zio.syntax._

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

/*
 * Mock everything needed to test rest API, ie almost a whole rudder.
 */
class RestTestSetUp {

  implicit val userService: UserService = new UserService {
    val user           = new User {
      val account                              = RudderAccount.User("test-user", "pass")
      def checkRights(auth: AuthorizationType) = true
      def getApiAuthz                          = ApiAuthz.allAuthz
    }
    val getCurrentUser = user
  }

  // Instantiate Service needed to feed System API constructor

  val fakeUpdatePTLibService = new UpdateTechniqueLibrary() {
    def update(modId: ModificationId, actor: EventActor, reason: Option[String]) = {
      Full(Map())
    }
    def registerCallback(callback: TechniquesLibraryUpdateNotification): Unit = {}
  }

  val mockGitRepo          = new MockGitConfigRepo("")
  val mockTechniques       = MockTechniques(mockGitRepo)
  val mockDirectives       = new MockDirectives(mockTechniques)
  val mockRules            = new MockRules()
  val mockNodes            = new MockNodes()
  val mockParameters       = new MockGlobalParam()
  val mockNodeGroups       = new MockNodeGroups(mockNodes)
  val mockLdapQueryParsing = new MockLdapQueryParsing(mockGitRepo, mockNodeGroups)
  val uuidGen              = new StringUuidGeneratorImpl()
  val mockConfigRepo       = new MockConfigRepo(mockTechniques, mockDirectives, mockRules, mockNodeGroups, mockLdapQueryParsing)

  val dynGroupUpdaterService =
    new DynGroupUpdaterServiceImpl(mockNodeGroups.groupsRepo, mockNodeGroups.groupsRepo, mockNodes.queryProcessor)

  object dynGroupService extends DynGroupService {
    override def getAllDynGroups():                Box[Seq[NodeGroup]] = {
      mockNodeGroups.groupsRepo
        .getFullGroupLibrary()
        .map(_.allGroups.collect {
          case (id, t) if (t.nodeGroup.isDynamic) => t.nodeGroup
        }.toSeq)
        .toBox
    }
    override def changesSince(lastTime: DateTime): Box[Boolean]        = Full(false)

    override def getAllDynGroupsWithandWithoutDependencies(): Box[(Seq[NodeGroupId], Seq[NodeGroupId])] = ???
  }

  val deploymentStatusSerialisation = new DeploymentStatusSerialisation {
    override def serialise(deploymentStatus: CurrentDeploymentStatus): Elem = <test/>
  }
  val eventLogRepo                  = new EventLogRepository {
    override def saveEventLog(modId: ModificationId, eventLog: EventLog): IOResult[EventLog] = eventLog.succeed

    override def eventLogFactory:                                                                EventLogFactory                                       = ???
    override def getEventLogByCriteria(
        criteria:       Option[Fragment],
        limit:          Option[Int],
        orderBy:        List[Fragment],
        extendedFilter: Option[Fragment]
    ): IOResult[Seq[EventLog]] = ???
    override def getEventLogById(id: Long):                                                      IOResult[EventLog]                                    = ???
    override def getEventLogCount(criteria: Option[Fragment], extendedFilter: Option[Fragment]): IOResult[Long]                                        = ???
    override def getEventLogByChangeRequest(
        changeRequest:   ChangeRequestId,
        xpath:           String,
        optLimit:        Option[Int],
        orderBy:         Option[String],
        eventTypeFilter: List[EventLogFilter]
    ): IOResult[Vector[EventLog]] = ???
    override def getEventLogWithChangeRequest(id: Int):                                          IOResult[Option[(EventLog, Option[ChangeRequestId])]] = ???
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
  val eventLogger                   = new EventLogDeploymentService(eventLogRepo, null) {
    override def getLastDeployement(): Box[CurrentDeploymentStatus] = Full(NoStatus)
  }
  val policyGeneration              = new PromiseGenerationService {
    override def deploy():                                                                     Box[Set[NodeId]]                        = Full(Set())
    override def getAllNodeInfos():                                                            Box[Map[NodeId, NodeInfo]]              = ???
    override def getDirectiveLibrary(ids: Set[DirectiveId]):                                   Box[FullActiveTechniqueCategory]        = ???
    override def getGroupLibrary():                                                            Box[FullNodeGroupCategory]              = ???
    override def getAllGlobalParameters:                                                       Box[Seq[GlobalParameter]]               = ???
    override def getGlobalComplianceMode():                                                    Box[GlobalComplianceMode]               = ???
    override def getGlobalAgentRun():                                                          Box[AgentRunInterval]                   = ???
    override def getScriptEngineEnabled:                                                       () => Box[FeatureSwitch]                = ???
    override def getGlobalPolicyMode:                                                          () => Box[GlobalPolicyMode]             = ???
    override def getComputeDynGroups:                                                          () => Box[Boolean]                      = ???
    override def getMaxParallelism:                                                            () => Box[String]                       = ???
    override def getJsTimeout:                                                                 () => Box[Int]                          = ???
    override def getGenerationContinueOnError:                                                 () => Box[Boolean]                      = ???
    override def writeCertificatesPem(allNodeInfos: Map[NodeId, NodeInfo]):                    Unit                                    = ???
    override def triggerNodeGroupUpdate():                                                     Box[Unit]                               = ???
    override def beforeDeploymentSync(generationTime: DateTime):                               Box[Unit]                               = ???
    override def HOOKS_D:                                                                      String                                  = ???
    override def HOOKS_IGNORE_SUFFIXES:                                                        List[String]                            = ???
    override def UPDATED_NODE_IDS_PATH:                                                        String                                  = ???
    override def GENERATION_FAILURE_MSG_PATH:                                                  String                                  = ???
    override def getAppliedRuleIds(
        rules:        Seq[Rule],
        groupLib:     FullNodeGroupCategory,
        directiveLib: FullActiveTechniqueCategory,
        allNodeInfos: Map[NodeId, NodeInfo]
    ): Set[RuleId] = ???
    override def findDependantRules():                                                         Box[Seq[Rule]]                          = ???
    override def buildRuleVals(
        activesRules: Set[RuleId],
        rules:        Seq[Rule],
        directiveLib: FullActiveTechniqueCategory,
        groupLib:     FullNodeGroupCategory,
        allNodeInfos: Map[NodeId, NodeInfo]
    ): Box[Seq[RuleVal]] = ???
    override def getNodeContexts(
        nodeIds:              Set[NodeId],
        allNodeInfos:         Map[NodeId, NodeInfo],
        allGroups:            FullNodeGroupCategory,
        globalParameters:     List[GlobalParameter],
        globalAgentRun:       AgentRunInterval,
        globalComplianceMode: ComplianceMode,
        globalPolicyMode:     GlobalPolicyMode
    ): Box[NodesContextResult] = ???
    override def getFilteredTechnique():                                                       Map[NodeId, List[TechniqueName]]        = ???
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
    override def forgetOtherNodeConfigurationState(keep: Set[NodeId]):                         Box[Set[NodeId]]                        = ???
    override def getNodeConfigurationHash():                                                   Box[Map[NodeId, NodeConfigurationHash]] = ???
    override def getNodesConfigVersion(
        allNodeConfigs: Map[NodeId, NodeConfiguration],
        hashes:         Map[NodeId, NodeConfigurationHash],
        generationTime: DateTime
    ): Map[NodeId, NodeConfigId] = ???
    override def writeNodeConfigurations(
        rootNodeId:       NodeId,
        updated:          Map[NodeId, NodeConfigId],
        allNodeConfig:    Map[NodeId, NodeConfiguration],
        allNodeInfos:     Map[NodeId, NodeInfo],
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
    override def saveExpectedReports(expectedReports: List[NodeExpectedReports]):              Box[Seq[NodeExpectedReports]]           = ???
    override def runPreHooks(generationTime: DateTime, systemEnv: HookEnvPairs):               Box[Unit]                               = ???
    override def runPostHooks(
        generationTime:    DateTime,
        endTime:           DateTime,
        idToConfiguration: Map[NodeId, NodeInfo],
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
    override def invalidateComplianceCache(actions: Seq[(NodeId, CacheExpectedReportAction)]): IOResult[Unit]                          = ???
  }
  val bootGuard                     = (for {
    p <- Promise.make[Nothing, Unit]
    _ <- p.succeed(())
  } yield p).runNow
  val asyncDeploymentAgent          = new AsyncDeploymentActor(
    policyGeneration,
    eventLogger,
    deploymentStatusSerialisation,
    () => Duration("0s").succeed,
    () => AllGeneration.succeed,
    bootGuard
  )

  val findDependencies  = new FindDependencies { // never find any dependencies
    override def findRulesForDirective(id: DirectiveUid): IOResult[Seq[Rule]] = Nil.succeed
    override def findRulesForTarget(target: RuleTarget):  IOResult[Seq[Rule]] = Nil.succeed
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
  val restExtractorService = RestExtractorService(
    mockRules.ruleRepo,
    mockDirectives.directiveRepo,
    null, // roNodeGroupRepository

    mockTechniques.techniqueRepo,
    mockLdapQueryParsing.queryParser, // queryParser

    new StatelessUserPropertyService(() => false.succeed, () => false.succeed, () => "".succeed),
    workflowLevelService,
    uuidGen,
    null
  )

  val zioJsonExtractor = new ZioJsonExtractor(mockLdapQueryParsing.queryParser)

  val restDataSerializer = RestDataSerializerImpl(
    mockTechniques.techniqueRepo,
    null // diffService
  )

  // TODO
  // all other apis

  class FakeClearCacheService extends ClearCacheService {
    override def action(actor: EventActor)                                           = null
    override def clearNodeConfigurationCache(storeEvent: Boolean, actor: EventActor) = null
  }

  val fakeNotArchivedElements =
    NotArchivedElements(Seq[CategoryNotArchived](), Seq[ActiveTechniqueNotArchived](), Seq[DirectiveNotArchived]())
  val fakePersonIdent         = new PersonIdent("test-user", "test.user@normation.com")
  val fakeGitCommitId         = GitCommitId("6d6b2ceb46adeecd845ad0c0812fee07e2727104")
  val fakeGitArchiveId        = GitArchiveId(GitPath("fake/git/path"), fakeGitCommitId, fakePersonIdent)

  class FakeItemArchiveManager extends ItemArchiveManager {
    override def exportAll(
        commiter:      PersonIdent,
        modId:         ModificationId,
        actor:         EventActor,
        reason:        Option[String],
        includeSystem: Boolean
    ) = ZIO.succeed((fakeGitArchiveId, fakeNotArchivedElements))
    override def exportRules(
        commiter:      PersonIdent,
        modId:         ModificationId,
        actor:         EventActor,
        reason:        Option[String],
        includeSystem: Boolean
    ) = ZIO.succeed(fakeGitArchiveId)
    override def exportTechniqueLibrary(
        commiter:      PersonIdent,
        modId:         ModificationId,
        actor:         EventActor,
        reason:        Option[String],
        includeSystem: Boolean
    ) = ZIO.succeed((fakeGitArchiveId, fakeNotArchivedElements))
    override def exportGroupLibrary(
        commiter:      PersonIdent,
        modId:         ModificationId,
        actor:         EventActor,
        reason:        Option[String],
        includeSystem: Boolean
    ) = ZIO.succeed(fakeGitArchiveId)
    override def exportParameters(
        commiter:      PersonIdent,
        modId:         ModificationId,
        actor:         EventActor,
        reason:        Option[String],
        includeSystem: Boolean
    ) = ZIO.succeed(fakeGitArchiveId)
    override def importAll(
        archiveId:     GitCommitId,
        commiter:      PersonIdent,
        modId:         ModificationId,
        actor:         EventActor,
        reason:        Option[String],
        includeSystem: Boolean
    ) = ZIO.succeed(fakeGitCommitId)
    override def importRules(
        archiveId:     GitCommitId,
        commiter:      PersonIdent,
        modId:         ModificationId,
        actor:         EventActor,
        reason:        Option[String],
        includeSystem: Boolean
    ) = ZIO.succeed(fakeGitCommitId)
    override def importTechniqueLibrary(
        archiveId:     GitCommitId,
        commiter:      PersonIdent,
        modId:         ModificationId,
        actor:         EventActor,
        reason:        Option[String],
        includeSystem: Boolean
    ) = ZIO.succeed(fakeGitCommitId)
    override def importGroupLibrary(
        archiveId:     GitCommitId,
        commiter:      PersonIdent,
        modId:         ModificationId,
        actor:         EventActor,
        reason:        Option[String],
        includeSystem: Boolean
    ) = ZIO.succeed(fakeGitCommitId)
    override def importParameters(
        archiveId:     GitCommitId,
        commiter:      PersonIdent,
        modId:         ModificationId,
        actor:         EventActor,
        reason:        Option[String],
        includeSystem: Boolean
    ) = ZIO.succeed(fakeGitCommitId)
    override def rollback(
        archiveId:        GitCommitId,
        commiter:         PersonIdent,
        modId:            ModificationId,
        actor:            EventActor,
        reason:           Option[String],
        rollbackedEvents: Seq[EventLog],
        target:           EventLog,
        rollbackType:     String,
        includeSystem:    Boolean
    ) = ZIO.succeed(fakeGitCommitId)

    /**
      * These methods are called by the Archive API to get the git archives.
      * The API then provides the logic to transform the Box[Map][DateTime, GitArchiveId] into JSON
      * Here, we want to make these methods returning fake archives for testing the API logic.
      */
    val fakeArchives                     = Map[DateTime, GitArchiveId](
      new DateTime(42) -> fakeGitArchiveId
    )
    override def getFullArchiveTags      = ZIO.succeed(fakeArchives)
    override def getGroupLibraryTags     = ZIO.succeed(fakeArchives)
    override def getTechniqueLibraryTags = ZIO.succeed(fakeArchives)
    override def getRulesTags            = ZIO.succeed(fakeArchives)
    override def getParametersTags       = ZIO.succeed(fakeArchives)
  }
  val fakeItemArchiveManager = new FakeItemArchiveManager
  val fakeClearCacheService   = new FakeClearCacheService
  val fakePersonIndentService = new PersonIdentService {
    override def getPersonIdentOrDefault(username: String) = ZIO.succeed(fakePersonIdent)
  }
  val fakeScriptLauncher      = new DebugInfoService {
    override def launch() = DebugInfoScriptResult("test", new Array[Byte](42)).succeed
  }

  val fakeUpdateDynamicGroups = {
    new UpdateDynamicGroups(dynGroupService, dynGroupUpdaterService, asyncDeploymentAgent, uuidGen, 1, () => Full("1")) {
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
      new CheckFileDescriptorLimit(mockNodes.nodeInfoService)
    )
  )
  val fakeHcNotifService     = new HealthcheckNotificationService(fakeHealthcheckService, 5.minute)
  val apiService13           = new SystemApiService13(
    fakeHealthcheckService,
    fakeHcNotifService,
    restDataSerializer,
    null
  )

  val ruleApiService2 = new RuleApiService2(
    mockRules.ruleRepo,
    mockRules.ruleRepo,
    uuidGen,
    asyncDeploymentAgent,
    workflowLevelService,
    restExtractorService,
    restDataSerializer
  )

  val ruleCategoryService = new RuleCategoryService()
  val ruleApiService6     = new RuleApiService6(
    mockRules.ruleCategoryRepo,
    mockRules.ruleRepo,
    mockRules.ruleCategoryRepo,
    restDataSerializer
  )
  val ruleApiService14    = new RuleApiService14(
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
    mockNodes.nodeInfoService,
    () => GlobalPolicyMode(Enforce, Always).succeed,
    new RuleApplicationStatusServiceImpl()
  )

  val fieldFactory           = new DirectiveFieldFactory {
    override def forType(fieldType: VariableSpec, id: String): DirectiveField = default(id)
    override def default(withId: String):                      DirectiveField = new DirectiveField {
      self =>
      type ValueType = String
      def manifest               = manifestOf[String]
      lazy val id                = withId
      def name                   = id
      override val uniqueFieldId = Full(id)
      protected var _x: String = getDefaultValue
      def validate    = Nil
      def validations = Nil
      def setFilter   = Nil
      def parseClient(s: String):                              Unit                   = if (null == s) _x = "" else _x = s
      def toClient:                                            String                 = if (null == _x) "" else _x
      def getPossibleValues(filters: (ValueType => Boolean)*): Option[Set[ValueType]] = None // not supported in the general cases
      def getDefaultValue = ""
      def get             = _x
      def set(x: String)  = { if (null == x) _x = "" else _x = x; _x }
      def toForm          = Full(SHtml.textarea("", s => parseClient(s)))
    }
  }
  val directiveEditorService = new DirectiveEditorServiceImpl(
    mockConfigRepo.configurationRepository,
    new Section2FieldService(fieldFactory, Translator.defaultTranslators)
  )
  val directiveApiService2   = {
    new DirectiveApiService2(
      mockDirectives.directiveRepo,
      mockDirectives.directiveRepo,
      uuidGen,
      asyncDeploymentAgent,
      workflowLevelService,
      restExtractorService,
      directiveEditorService,
      restDataSerializer,
      mockTechniques.techniqueRepo
    )
  }

  val directiveApiService14 = {
    new DirectiveApiService14(
      mockDirectives.directiveRepo,
      mockConfigRepo.configurationRepository,
      mockDirectives.directiveRepo,
      uuidGen,
      asyncDeploymentAgent,
      workflowLevelService,
      directiveEditorService,
      restDataSerializer,
      mockTechniques.techniqueRepo
    )
  }

  val techniqueAPIService6 = new TechniqueAPIService6(
    mockDirectives.directiveRepo,
    restDataSerializer
  )

  val techniqueAPIService14 = new TechniqueAPIService14(
    mockDirectives.directiveRepo,
    mockTechniques.techniqueRevisionRepo,
    null,
    null,
    null,
    null
  )

  val systemApi        = new SystemApi(restExtractorService, apiService11, apiService13, "5.0", "5.0.0", "some time")
  val authzToken       = AuthzToken(EventActor("fakeToken"))
  val systemStatusPath = "api" + systemApi.Status.schema.path

  val softDao                      = mockNodes.softwareDao
  val roReportsExecutionRepository = new RoReportsExecutionRepository {
    override def getNodesLastRun(nodeIds: Set[NodeId]): IOResult[Map[NodeId, Option[AgentRunWithNodeConfig]]] =
      Map.empty[NodeId, Option[AgentRunWithNodeConfig]].succeed

    def getNodesAndUncomputedCompliance(): IOResult[Map[NodeId, Option[AgentRunWithNodeConfig]]] = ???

    def getUnprocessedRuns(): IOResult[Seq[AgentRunWithoutCompliance]] = ???
  }

  val nodeApiService = new NodeApiService(
    null,
    mockNodes.nodeFactRepo,
    null,
    null,
    roReportsExecutionRepository,
    null,
    uuidGen,
    null,
    null,
    null,
    mockNodes.nodeInfoService,
    mockNodes.newNodeManager,
    null,
    restExtractorService,
    restDataSerializer,
    null,
    mockNodes.queryProcessor,
    null,
    () => Full(GlobalPolicyMode(Audit, PolicyModeOverrides.Always)),
    "relay"
  ) {
    implicit val testCC: ChangeContext = {
      ChangeContext(
        ModificationId(uuidGen.newUuid),
        EventActor("test"),
        DateTime.now(),
        None,
        None,
        QueryContext.testQC.nodePerms
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
        .updateInventory(inventory, None)(cc)
        .mapBoth(
          err => CreationError.OnSaveInventory(s"Error when saving node: ${err.fullMsg}"),
          _ => inventory.node.main.id
        )
    }

    override def saveRudderNode(id: NodeId, setup: NodeSetup): IO[Creation.CreationError, NodeId] = {
      (for {
        n <- mockNodes.nodeFactRepo.get(id).notOptional(s"Can not merge node: missing")
        n2 = CoreNodeFact.updateNode(n, mergeNodeSetup(n.toNode, setup))
        _ <- mockNodes.nodeFactRepo.save(NodeFact.fromMinimal(n2))(testCC, SelectFacts.none)
      } yield {
        n.id
      }).mapError(err => CreationError.OnSaveInventory(err.fullMsg))
    }
  }

  val parameterApiService2  = new ParameterApiService2(
    mockParameters.paramsRepo,
    mockParameters.paramsRepo,
    uuidGen,
    workflowLevelService,
    restExtractorService,
    restDataSerializer
  )
  val parameterApiService14 = new ParameterApiService14(
    mockParameters.paramsRepo,
    mockParameters.paramsRepo,
    uuidGen,
    workflowLevelService
  )

  val groupService2               = new GroupApiService2(
    mockNodeGroups.groupsRepo,
    mockNodeGroups.groupsRepo,
    uuidGen,
    asyncDeploymentAgent,
    workflowLevelService,
    restExtractorService,
    mockNodes.queryProcessor,
    restDataSerializer
  )
  val groupService6               = new GroupApiService6(mockNodeGroups.groupsRepo, mockNodeGroups.groupsRepo, restDataSerializer)
  val groupService14              = new GroupApiService14(
    mockNodeGroups.groupsRepo,
    mockNodeGroups.groupsRepo,
    mockParameters.paramsRepo,
    uuidGen,
    asyncDeploymentAgent,
    workflowLevelService,
    restExtractorService,
    mockLdapQueryParsing.queryParser,
    mockNodes.queryProcessor,
    restDataSerializer
  )
  val groupApiInheritedProperties = new GroupApiInheritedProperties(mockNodeGroups.groupsRepo, mockParameters.paramsRepo)
  val ncfTechniqueWriter:  TechniqueWriter       = null
  val ncfTechniqueReader:  EditorTechniqueReader = null
  val techniqueRepository: TechniqueRepository   = null
  val techniqueSerializer: TechniqueSerializer   = null
  val resourceFileService: ResourceFileService   = null
  val settingsService = new MockSettings(workflowLevelService, new AsyncWorkflowInfo())

  object archiveAPIModule {
    val archiveBuilderService = new ZipArchiveBuilderService(
      new FileArchiveNameService(),
      mockConfigRepo.configurationRepository,
      mockTechniques.techniqueRevisionRepo
    )

    // archive name in a Ref to make it simple to change in tests
    val rootDirName      = Ref.make("archive").runNow
    val zipArchiveReader = new ZipArchiveReaderImpl(mockLdapQueryParsing.queryParser, mockTechniques.techniqueParser)
    // a mock save archive that stores result in a ref
    object archiveSaver   extends SaveArchiveService  {
      val base = Ref.make(Option.empty[(PolicyArchive, MergePolicy)]).runNow
      override def save(archive: PolicyArchive, mergePolicy: MergePolicy, actor: EventActor): IOResult[Unit] = {
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
    import mockCampaign._
    val api        = new CampaignApi(repo, translator, dumbCampaignEventRepository, mainCampaignService, restExtractorService, uuidGen)
  }

  val apiModules = List(
    systemApi,
    new ParameterApi(restExtractorService, zioJsonExtractor, parameterApiService2, parameterApiService14),
    new TechniqueApi(
      restExtractorService,
      techniqueAPIService6,
      techniqueAPIService14,
      ncfTechniqueWriter,
      ncfTechniqueReader,
      techniqueRepository,
      techniqueSerializer,
      uuidGen,
      resourceFileService,
      mockGitRepo.configurationRepositoryRoot.pathAsString
    ),
    new DirectiveApi(
      mockDirectives.directiveRepo,
      restExtractorService,
      zioJsonExtractor,
      uuidGen,
      directiveApiService2,
      directiveApiService14
    ),
    new RuleApi(restExtractorService, zioJsonExtractor, ruleApiService2, ruleApiService6, ruleApiService14, uuidGen),
    new NodeApi(
      restExtractorService,
      restDataSerializer,
      nodeApiService,
      null,
      uuidGen,
      DeleteMode.Erase
    ),
    new GroupsApi(
      mockNodeGroups.groupsRepo,
      restExtractorService,
      zioJsonExtractor,
      uuidGen,
      groupService2,
      groupService6,
      groupService14,
      groupApiInheritedProperties
    ),
    new SettingsApi(
      restExtractorService,
      settingsService.configService,
      asyncDeploymentAgent,
      uuidGen,
      settingsService.policyServerManagementService,
      mockNodes.nodeInfoService
    ),
    archiveAPIModule.api,
    campaignApiModule.api
  )

  val apiVersions            =
    ApiVersion(14, true) :: ApiVersion(15, true) :: ApiVersion(16, true) :: ApiVersion(17, true) :: ApiVersion(18, false) :: Nil
  val (rudderApi, liftRules) = TraitTestApiFromYamlFiles.buildLiftRules(apiModules, apiVersions, Some(userService))

  liftRules.statelessDispatch.append(RestStatus)

  val baseTempDirectory = mockGitRepo.abstractRoot

  /*
   * We will commit some revisions for packageManagement technique:
   * - init commit: what is on repos
   * - commit 1: change metadata section (impact on directive details, but not on variables)
   * - commit 2: revert to initial state
   */
  def updatePackageManagementRevision(): Unit = {
    val metadata = mockGitRepo.configurationRepositoryRoot / "techniques/applications/packageManagement/1.0/metadata.xml"

    val orig = metadata.contentAsString(StandardCharsets.UTF_8)
    val mod  = orig.replaceAll("""name="Package version" """, """name="AN OTHER REVISION OF TECHNIQUE PACKAGE MANAGEMENT" """)

    metadata.write(mod)
    mockGitRepo.gitRepo.git.add().setUpdate(true).addFilepattern(".").call()
    mockGitRepo.gitRepo.git.commit().setMessage("new revision of packageManagement/1.0 technique").call()
    metadata.write(orig)
    mockGitRepo.gitRepo.git.add().setUpdate(true).addFilepattern(".").call()
    mockGitRepo.gitRepo.git.commit().setMessage("revert to original content for packageManagement/1.0 technique").call()
  }

  // a cleanup method that delete all test files
  def cleanup(): Unit = {

    FileUtils.deleteDirectory(mockGitRepo.abstractRoot.toJava)

  }

}

object RestTestSetUp {
  def newEnv = {
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
  def execRequestResponse[T](mockReq: MockHttpServletRequest)(tests: Box[LiftResponse] => MatchResult[T]) = {
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

  private[this] def mockRequest(path: String, method: String) = {
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
  def GET(path: String)                                       = mockRequest(path, "GET")
  def POST(path: String)                                      = mockRequest(path, "POST")
  def DELETE(path: String)                                    = mockRequest(path, "DELETE")

  private[this] def mockJsonRequest(path: String, method: String, data: JValue) = {
    val mockReq = mockRequest(path, method)
    mockReq.body = data
    mockReq
  }

  def jsonPUT(path: String, json: JValue) = {
    mockJsonRequest(path, "PUT", json)
  }

  def jsonPOST(path: String, json: JValue) = {
    mockJsonRequest(path, "POST", json)
  }

  // url encode the data for param name
  def binaryPOST(
      path:         String,
      paramName:    String,
      filename:     String,
      data:         Array[Byte],
      stringParams: Map[String, String] = Map()
  ) = {
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
    )
    mockReq.contentType = parts.getContentType
    mockReq.body = out.toByteArray
    mockReq
  }

  // high level methods. Directly manipulate response
  def testGETResponse[T](path: String)(tests: Box[LiftResponse] => MatchResult[T])    = {
    execRequestResponse(GET(path))(tests)
  }
  def testDELETEResponse[T](path: String)(tests: Box[LiftResponse] => MatchResult[T]) = {
    execRequestResponse(DELETE(path))(tests)
  }

  def testPUTResponse[T](path: String, json: JValue)(tests: Box[LiftResponse] => MatchResult[T])  = {
    execRequestResponse(jsonPUT(path, json))(tests)
  }
  def testPOSTResponse[T](path: String, json: JValue)(tests: Box[LiftResponse] => MatchResult[T]) = {
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
  ) = {
    execRequestResponse(binaryPOST(path, paramName, filename, data, stringParams))(tests)
  }
  def testEmptyPostResponse[T](path: String)(tests: Box[LiftResponse] => MatchResult[T]): MatchResult[T] = {
    execRequestResponse(POST(path))(tests)
  }

  // Low level methods. You can build the answer by hand from there

  def testGET[T](path: String)(tests: Req => MatchResult[T]) = {
    doReq(GET(path))(tests)
  }

  def testDELETE[T](path: String)(tests: Req => MatchResult[T]) = {
    doReq(DELETE(path))(tests)
  }

  def testPUT[T](path: String, json: JValue)(tests: Req => MatchResult[T])  = {
    doReq(jsonPUT(path, json))(tests)
  }
  def testPOST[T](path: String, json: JValue)(tests: Req => MatchResult[T]) = {
    doReq(jsonPOST(path, json))(tests)
  }

  // This is a method to call when testing POST endpoint without data to pass.
  // Ex: reload techniques endpoints which reload all techniques.
  // It has to be a POST because it executes an action on the server side even if it doesn't need additional data to do so.
  def testEmptyPost[T](path: String)(tests: Req => MatchResult[T]): MatchResult[T] = {
    doReq(POST(path))(tests)
  }
}
