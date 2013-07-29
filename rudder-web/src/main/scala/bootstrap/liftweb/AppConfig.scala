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

package bootstrap.liftweb

import com.normation.inventory.domain._
import com.normation.inventory.services.core._
import com.normation.inventory.ldap.core._
import com.normation.inventory.services._
import com.normation.rudder.batch._
import com.normation.rudder.services.policies.ParameterizedValueLookupServiceImpl
import com.normation.rudder.services.nodes._
import com.normation.rudder.repository._
import com.normation.rudder.services.queries._
import com.normation.rudder.services.licenses._
import com.normation.rudder.services.servers._
import com.normation.rudder.services.system._
import com.normation.rudder.services.path._
import com.normation.rudder.services.policies._
import com.normation.rudder.services.reports._
import com.normation.rudder.domain.queries._
import bootstrap.liftweb.checks._
import com.normation.cfclerk.services._
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.{ Bean, Configuration, Import, ImportResource }
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.{ ApplicationContext, ApplicationContextAware }
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import com.normation.spring.ScalaApplicationContext
import com.normation.ldap.sdk._
import com.normation.rudder.domain._
import com.normation.rudder.web.services._
import com.normation.rudder.web.model._
import com.normation.utils.StringUuidGenerator
import com.normation.utils.StringUuidGeneratorImpl
import com.normation.rudder.repository.ldap._
import java.io.File
import org.joda.time.DateTime
import com.normation.rudder.services.eventlog._
import com.normation.cfclerk.xmlparsers._
import com.normation.cfclerk.services.impl._
import scala.collection.JavaConversions._
import com.normation.rudder.repository.ldap._
import com.normation.rudder.repository.xml._
import com.normation.rudder.repository.jdbc._
import com.normation.rudder.repository._
import net.liftweb.common.Loggable
import com.normation.rudder.services.servers.NodeConfigurationChangeDetectServiceImpl
import org.apache.commons.dbcp.BasicDataSource
import com.normation.rudder.services.eventlog.HistorizationServiceImpl
import com.normation.rudder.services.policies.DeployOnTechniqueCallback
import scala.xml.PrettyPrinter
import com.normation.rudder.services.marshalling._
import com.normation.utils.ScalaLock
import com.normation.rudder.web.rest._
import com.normation.rudder.services.user.TrivialPersonIdentService
import com.normation.rudder.services.eventlog.EventLogFactoryImpl
import com.normation.rudder.migration.ControlEventLogsMigration_2_3
import com.normation.rudder.migration.EventLogsMigration_2_3
import com.normation.rudder.migration.MigrationEventLogRepository
import com.normation.rudder.migration.EventLogMigration_2_3
import com.normation.rudder.migration.XmlMigration_2_3
import com.normation.rudder.web.services.UserPropertyService
import java.lang.IllegalArgumentException
import com.normation.rudder.domain.logger.ApplicationLogger
import logger.MigrationLogger
import com.normation.rudder.migration.DefaultXmlEventLogMigration
import net.liftweb.common._
import com.normation.rudder.repository.jdbc.SquerylConnectionProvider
import com.normation.rudder.repository.squeryl._
import com.normation.rudder.repository._
import com.normation.rudder.services.modification.ModificationService
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import scala.util.Try
import com.normation.rudder.repository.inmemory.InMemoryChangeRequestRepository
import com.normation.rudder.services.workflows.ChangeRequestService
import com.normation.rudder.services.workflows.ChangeRequestServiceImpl
import com.normation.rudder.services.workflows.NoWorkflowServiceImpl
import com.normation.cfclerk.xmlwriters.SectionSpecWriter
import com.normation.cfclerk.xmlwriters.SectionSpecWriterImpl
import com.normation.rudder.services.workflows.CommitAndDeployChangeRequestService
import com.normation.rudder.services.workflows.CommitAndDeployChangeRequestServiceImpl
import com.normation.rudder.services.modification.DiffServiceImpl
import com.normation.rudder.services.modification.DiffService
import com.normation.rudder.services.workflows.WorkflowService
import com.normation.rudder.services.user.PersonIdentService
import com.normation.rudder.services.workflows.TwoValidationStepsWorkflowServiceImpl
import com.normation.rudder.web.rest.RestExtractorService
import com.normation.rudder.web.rest.rule._
import com.normation.rudder.web.rest.directive._
import com.normation.rudder.web.rest.group._
import com.normation.rudder.web.rest.node._
import com.normation.rudder.api.ApiAccount
import com.normation.rudder.api.RoLDAPApiAccountRepository
import com.normation.rudder.api.WoApiAccountRepository
import com.normation.rudder.api.RoApiAccountRepository
import com.normation.rudder.api.WoLDAPApiAccountRepository
import com.normation.rudder.api.TokenGeneratorImpl
import com.normation.rudder.migration.XmlMigration_3_4
import com.normation.rudder.migration.ControlXmlFileFormatMigration_3_4
import com.normation.rudder.migration.EventLogMigration_3_4
import com.normation.rudder.migration.ChangeRequestsMigration_3_4
import com.normation.rudder.migration.ChangeRequestMigration_3_4
import com.normation.rudder.migration.EventLogsMigration_3_4
import com.normation.rudder.migration.EventLogsMigration_3_4
import com.normation.rudder.web.rest.parameter._

/**
 * Define a resource for configuration.
 * For now, config properties can only be loaded from either
 * a file in the classpath, or a file in the file system.
 */
sealed trait ConfigResource
final case class ClassPathResource(name: String) extends ConfigResource
final case class FileSystemResource(file: File) extends ConfigResource

/**
 * User defined configuration variable
 * (from properties file or alike)
 */
object RudderProperties {



  val JVM_CONFIG_FILE_KEY = "rudder.configFile"
  val DEFAULT_CONFIG_FILE_NAME = "configuration.properties"

  /**
   * Where to go to look for properties
   */
  val configResource = System.getProperty(JVM_CONFIG_FILE_KEY) match {
      case null | "" => //use default location in classpath
        ApplicationLogger.info("JVM property -D%s is not defined, use configuration file in classpath".format(JVM_CONFIG_FILE_KEY))
        ClassPathResource(DEFAULT_CONFIG_FILE_NAME)
      case x => //so, it should be a full path, check it
        val config = new File(x)
        if(config.exists && config.canRead) {
          ApplicationLogger.info("Use configuration file defined by JVM property -D%s : %s".format(JVM_CONFIG_FILE_KEY, config.getPath))
          FileSystemResource(config)
        } else {
          ApplicationLogger.error("Can not find configuration file specified by JVM property %s: %s ; abort".format(JVM_CONFIG_FILE_KEY, config.getPath))
          throw new javax.servlet.UnavailableException("Configuration file not found: %s".format(config.getPath))
        }
    }

  val config : Config = {
    configResource match {
      case ClassPathResource(name) => ConfigFactory.load(name)
      case FileSystemResource(file) => ConfigFactory.load(ConfigFactory.parseFile(file))
    }
  }
}

/**
 * Static initialization of Rudder services.
 * This is not a cake-pattern, more an ad-hoc replacement
 * for Spring AppConfig, which is so slow.
 */
object RudderConfig extends Loggable {
  import RudderProperties.config

  //
  // Public properties
  // Here, we define static nouns for all theses properties
  //

  private[this] val filteredPasswords = scala.collection.mutable.Buffer[String]()

  val LDAP_HOST = config.getString("ldap.host")
  val LDAP_PORT = config.getInt("ldap.port")
  val LDAP_AUTHDN = config.getString("ldap.authdn")
  val LDAP_AUTHPW = config.getString("ldap.authpw") ; filteredPasswords += "ldap.authpw"
  val LDAP_INVENTORIES_ACCEPTED_BASEDN = config.getString("ldap.inventories.accepted.basedn")
  val LDAP_INVENTORIES_PENDING_BASEDN = config.getString("ldap.inventories.pending.basedn")
  val LDAP_INVENTORIES_REMOVED_BASEDN = config.getString("ldap.inventories.removed.basedn")
  val LDAP_INVENTORIES_SOFTWARE_BASEDN = config.getString("ldap.inventories.software.basedn")
  val LDAP_RUDDER_BASE = config.getString("ldap.rudder.base")
  val LDAP_NODE_BASE = config.getString("ldap.node.base")
  val RUDDER_DIR_BACKUP = config.getString("rudder.dir.backup")
  val RUDDER_DIR_DEPENDENCIES = config.getString("rudder.dir.dependencies")
  val RUDDER_DIR_UPLOADED_FILE_SHARING = config.getString("rudder.dir.uploaded.file.sharing")
  val RUDDER_DIR_LOCK = config.getString("rudder.dir.lock") //TODO no more used ?
  val RUDDER_DIR_SHARED_FILES_FOLDER = config.getString("rudder.dir.shared.files.folder")
  val RUDDER_DIR_LICENSESFOLDER = config.getString("rudder.dir.licensesFolder")
  val RUDDER_ENDPOINT_CMDB = config.getString("rudder.endpoint.cmdb")
  val RUDDER_WEBDAV_USER = config.getString("rudder.webdav.user")
  val RUDDER_WEBDAV_PASSWORD = config.getString("rudder.webdav.password") ; filteredPasswords += "rudder.webdav.password"
  val RUDDER_COMMUNITY_PORT = config.getInt("rudder.community.port")
  val RUDDER_COMMUNITY_CHECKPROMISES_COMMAND = config.getString("rudder.community.checkpromises.command")
  val RUDDER_NOVA_CHECKPROMISES_COMMAND = config.getString("rudder.nova.checkpromises.command")
  val RUDDER_JDBC_DRIVER = config.getString("rudder.jdbc.driver")
  val RUDDER_JDBC_URL = config.getString("rudder.jdbc.url")
  val RUDDER_JDBC_USERNAME = config.getString("rudder.jdbc.username")
  val RUDDER_JDBC_PASSWORD = config.getString("rudder.jdbc.password") ; filteredPasswords += "rudder.jdbc.password"
  val RUDDER_DIR_GITROOT = config.getString("rudder.dir.gitRoot")
  val RUDDER_DIR_TECHNIQUES = config.getString("rudder.dir.techniques")
  val RUDDER_BATCH_DYNGROUP_UPDATEINTERVAL = config.getInt("rudder.batch.dyngroup.updateInterval") //60 //one hour
  val RUDDER_BATCH_TECHNIQUELIBRARY_UPDATEINTERVAL = config.getInt("rudder.batch.techniqueLibrary.updateInterval") //60 * 5 //five minutes
  val reportCleanerArchiveTTL = AutomaticReportsCleaning.defaultArchiveTTL
  val RUDDER_BATCH__REPORTSCLEANER_DELETE_TTL = config.getInt("rudder.batch.reportscleaner.delete.TTL") //AutomaticReportsCleaning.defaultDeleteTTL
  val RUDDER_BATCH_REPORTSCLEANER_FREQUENCY = config.getString("rudder.batch.reportscleaner.frequency") //AutomaticReportsCleaning.defaultDay
  val RUDDER_BATCH_DATABASECLEANER_RUNTIME_HOUR = config.getInt("rudder.batch.databasecleaner.runtime.hour") //AutomaticReportsCleaning.defaultHour
  val RUDDER_BATCH_DATABASECLEANER_RUNTIME_MINUTE = config.getInt("rudder.batch.databasecleaner.runtime.minute") //AutomaticReportsCleaning.defaultMinute
  val RUDDER_BATCH_DATABASECLEANER_RUNTIME_DAY = config.getString("rudder.batch.databasecleaner.runtime.day") //"sunday"
  val RUDDER_BATCH_REPORTS_LOGINTERVAL = config.getInt("rudder.batch.reports.logInterval") //1 //one minute
  val RUDDER_TECHNIQUELIBRARY_GIT_REFS_PATH = config.getString("rudder.techniqueLibrary.git.refs.path")
  val RUDDER_AUTOARCHIVEITEMS = config.getBoolean("rudder.autoArchiveItems") //true
  val RUDDER_UI_CHANGEMESSAGE_ENABLED = config.getBoolean("rudder.ui.changeMessage.enabled") //false
  val RUDDER_UI_CHANGEMESSAGE_MANDATORY = config.getBoolean("rudder.ui.changeMessage.mandatory") //false
  val RUDDER_UI_CHANGEMESSAGE_EXPLANATION = config.getString("rudder.ui.changeMessage.explanation") //"Please enter a message explaining the reason for this change."
  val RUDDER_SYSLOG_PORT = config.getInt("rudder.syslog.port") //514

  val BIN_EMERGENCY_STOP = config.getString("bin.emergency.stop")
  val HISTORY_INVENTORIES_ROOTDIR = config.getString("history.inventories.rootdir")
  val UPLOAD_ROOT_DIRECTORY = config.getString("upload.root.directory")

  //used in spring security "applicationContext-security.xml", be careful if you change its name
  val RUDDER_REST_ALLOWNONAUTHENTICATEDUSER = config.getBoolean("rudder.rest.allowNonAuthenticatedUser")

  //workflows configuration
  val RUDDER_ENABLE_APPROVAL_WORKFLOWS = config.getBoolean("rudder.workflow.enabled") // false
  val RUDDER_ENABLE_SELF_VALIDATION    = config.getBoolean("rudder.workflow.self.validation") // false
  val RUDDER_ENABLE_SELF_DEPLOYMENT    = config.getBoolean("rudder.workflow.self.deployment") // true

  val licensesConfiguration = "licenses.xml"
  val logentries = "logentries.xml"
  val prettyPrinter = new PrettyPrinter(120, 2)
  val userLibraryDirectoryName = "directives"
  val groupLibraryDirectoryName = "groups"
  val rulesDirectoryName = "rules"
  val parametersDirectoryName = "parameters"

  //deprecated
  val BASE_URL = Try(config.getString("base.url")).getOrElse("")

  //
  // Theses services can be called from the outer worl/
  // They must be typed with there abstract interface, as
  // such service must not expose implementation details
  //

  val pendingNodesDit: InventoryDit = pendingNodesDitImpl
  val acceptedNodesDit: InventoryDit = acceptedNodesDitImpl
  val nodeDit: NodeDit = nodeDitImpl
  val rudderDit: RudderDit = rudderDitImpl
  val roLDAPConnectionProvider: LDAPConnectionProvider[RoLDAPConnection] = roLdap
  val roRuleRepository: RoRuleRepository = roLdapRuleRepository
  val woRuleRepository: WoRuleRepository = woLdapRuleRepository
  val roNodeGroupRepository: RoNodeGroupRepository = roLdapNodeGroupRepository
  val woNodeGroupRepository: WoNodeGroupRepository = woLdapNodeGroupRepository
  val techniqueRepository: TechniqueRepository = techniqueRepositoryImpl
  val updateTechniqueLibrary: UpdateTechniqueLibrary = techniqueRepositoryImpl
  val roDirectiveRepository: RoDirectiveRepository = roLdapDirectiveRepository
  val woDirectiveRepository: WoDirectiveRepository = woLdapDirectiveRepository
  val readOnlySoftwareDAO: ReadOnlySoftwareDAO = softwareInventoryDAO
  val eventLogRepository: EventLogRepository = logRepository
  val eventLogDetailsService: EventLogDetailsService = eventLogDetailsServiceImpl
  val reportingService: ReportingService = reportingServiceImpl
  val stringUuidGenerator: StringUuidGenerator = uuidGen
  val quickSearchService: QuickSearchService = quickSearchServiceImpl
  val cmdbQueryParser: CmdbQueryParser = queryParser
  val getBaseUrlService: GetBaseUrlService = baseUrlService
  val fileManager: FileManager = fileManagerImpl
  val startStopOrchestrator: StartStopOrchestrator = startStopOrchestratorImpl
  val inventoryHistoryLogRepository: InventoryHistoryLogRepository = diffRepos
  val inventoryEventLogService: InventoryEventLogService = inventoryLogEventServiceImpl
  val ruleApplicationStatus: RuleApplicationStatusService = ruleApplicationStatusImpl
  val newNodeManager: NewNodeManager = newNodeManagerImpl
  val nodeGrid: NodeGrid = nodeGridImpl
  val nodeSummaryService: NodeSummaryService = nodeSummaryServiceImpl
  val jsTreeUtilService: JsTreeUtilService = jsTreeUtilServiceImpl
  val directiveEditorService: DirectiveEditorService = directiveEditorServiceImpl
  val userPropertyService: UserPropertyService = userPropertyServiceImpl
  val eventListDisplayer: EventListDisplayer = eventListDisplayerImpl
  val asyncDeploymentAgent: AsyncDeploymentAgent = asyncDeploymentAgentImpl
  val policyServerManagementService: PolicyServerManagementService = psMngtService
  val updateDynamicGroups: UpdateDynamicGroups = dyngroupUpdaterBatch
  val databaseManager: DatabaseManager = databaseManagerImpl
  val automaticReportsCleaning: AutomaticReportsCleaning = dbCleaner
  val automaticReportLogger: AutomaticReportLogger = autoReportLogger
  val nodeConfigurationService: NodeConfigurationService = nodeConfigurationServiceImpl
  val removeNodeService: RemoveNodeService = removeNodeServiceImpl
  val nodeInfoService: NodeInfoService = nodeInfoServiceImpl
  val reportDisplayer: ReportDisplayer = reportDisplayerImpl
  val dependencyAndDeletionService: DependencyAndDeletionService =  dependencyAndDeletionServiceImpl
  val itemArchiveManager: ItemArchiveManager = itemArchiveManagerImpl
  val personIdentService: PersonIdentService = personIdentServiceImpl
  val gitRevisionProvider: GitRevisionProvider = gitRevisionProviderImpl
  val logDisplayer: LogDisplayer  = logDisplayerImpl
  val fullInventoryRepository: LDAPFullInventoryRepository = ldapFullInventoryRepository
  val acceptedNodeQueryProcessor: QueryProcessor = queryProcessor
  val categoryHierarchyDisplayer: CategoryHierarchyDisplayer = categoryHierarchyDisplayerImpl
  val dynGroupService: DynGroupService = dynGroupServiceImpl
  val ditQueryData: DitQueryData = ditQueryDataImpl
  val reportsRepository : ReportsRepository = reportsRepositoryImpl
  val eventLogDeploymentService: EventLogDeploymentService = eventLogDeploymentServiceImpl
  val allBootstrapChecks : BootstrapChecks = allChecks
  val srvGrid = new SrvGrid
  val expectedReportRepository : RuleExpectedReportsRepository = configurationExpectedRepo
  val historizationRepository : HistorizationRepository =  historizationJdbcRepository
  val roApiAccountRepository : RoApiAccountRepository = roLDAPApiAccountRepository
  val woApiAccountRepository : WoApiAccountRepository = woLDAPApiAccountRepository

  val roWorkflowRepository : RoWorkflowRepository = new RoWorkflowJdbcRepository(jdbcTemplate)
  val woWorkflowRepository : WoWorkflowRepository = new WoWorkflowJdbcRepository(jdbcTemplate, roWorkflowRepository)


  val inMemoryChangeRequestRepository : InMemoryChangeRequestRepository = new InMemoryChangeRequestRepository

  val roChangeRequestRepository : RoChangeRequestRepository = RUDDER_ENABLE_APPROVAL_WORKFLOWS match {
      case false =>
        inMemoryChangeRequestRepository
      case true =>
        new RoChangeRequestJdbcRepository(
          jdbcTemplate
        , new ChangeRequestsMapper(changeRequestChangesUnserialisation))
    }

  val woChangeRequestRepository : WoChangeRequestRepository = RUDDER_ENABLE_APPROVAL_WORKFLOWS match {
    case false =>
      inMemoryChangeRequestRepository
    case true =>
      new WoChangeRequestJdbcRepository(
          jdbcTemplate
        , changeRequestChangesSerialisation
        , roChangeRequestRepository
        )
    }

  val changeRequestEventLogService : ChangeRequestEventLogService = new ChangeRequestEventLogServiceImpl(eventLogRepository)


  val workflowEventLogService =    new WorkflowEventLogServiceImpl(eventLogRepository,uuidGen)
  val diffService: DiffService = new DiffServiceImpl(roDirectiveRepository)
  val commitAndDeployChangeRequest : CommitAndDeployChangeRequestService =
    new CommitAndDeployChangeRequestServiceImpl(
        uuidGen
      , roChangeRequestRepository
      , woChangeRequestRepository
      , roDirectiveRepository
      , woDirectiveRepository
      , roNodeGroupRepository
      , woNodeGroupRepository
      , roRuleRepository
      , woRuleRepository
      , roLDAPParameterRepository
      , woLDAPParameterRepository
      , asyncDeploymentAgent
      , dependencyAndDeletionService
      , RUDDER_ENABLE_APPROVAL_WORKFLOWS
    )
  val asyncWorkflowInfo = new AsyncWorkflowInfo
  val workflowService: WorkflowService = RUDDER_ENABLE_APPROVAL_WORKFLOWS match {
    case true => new TwoValidationStepsWorkflowServiceImpl(
            workflowEventLogService
          , commitAndDeployChangeRequest
          , roWorkflowRepository
          , woWorkflowRepository
          , asyncWorkflowInfo
          , RUDDER_ENABLE_SELF_VALIDATION
          , RUDDER_ENABLE_SELF_DEPLOYMENT
        )
    case false => new NoWorkflowServiceImpl(
            commitAndDeployChangeRequest
          , inMemoryChangeRequestRepository
        )
  }
  val changeRequestService: ChangeRequestService = new ChangeRequestServiceImpl (
      roChangeRequestRepository
    , woChangeRequestRepository
    , changeRequestEventLogService
    , uuidGen
    , RUDDER_ENABLE_APPROVAL_WORKFLOWS
  )

  val roParameterService : RoParameterService = roParameterServiceImpl
  val woParameterService : WoParameterService = woParameterServiceImpl

  //////////////////////////////////////////////////////////////////////////////////////////
  ///////////////////////////////////////// REST ///////////////////////////////////////////
  //////////////////////////////////////////////////////////////////////////////////////////


  val restExtractorService =
    RestExtractorService (
        roRuleRepository
      , roDirectiveRepository
      , roNodeGroupRepository
      , techniqueRepository
      , queryParser
      , userPropertyService
    )

  val tokenGenerator = new TokenGeneratorImpl(32)

  val restDeploy = new RestDeploy(asyncDeploymentAgentImpl, uuidGen)
  val restDyngroupReload = new RestDyngroupReload(dyngroupUpdaterBatch)
  val restTechniqueReload = new RestTechniqueReload(techniqueRepositoryImpl, uuidGen)
  val restArchiving = new RestArchiving(itemArchiveManagerImpl,personIdentServiceImpl, uuidGen)
  val restGetGitCommitAsZip = new RestGetGitCommitAsZip(gitRepo)
  val restApiAccounts = new RestApiAccounts(roApiAccountRepository,woApiAccountRepository,restExtractorService,tokenGenerator, uuidGen)

  val ruleApiService2 =
    new RuleApiService2(
        roRuleRepository
      , woRuleRepository
      , uuidGen
      , asyncDeploymentAgent
      , changeRequestService
      , workflowService
      , restExtractorService
      , RUDDER_ENABLE_APPROVAL_WORKFLOWS
    )

  val ruleApi2 =
    new RuleAPI2 (
        roRuleRepository
      , restExtractorService
      , ruleApiService2
    )

  val latestRuleApi = new LatestRuleAPI (ruleApi2)

  val genericRuleApi =
    new RuleAPIHeaderVersion (
        roRuleRepository
      , restExtractorService
      , ruleApiService2
    )


   val directiveApiService2 =
    new DirectiveAPIService2 (
        roDirectiveRepository
      , woDirectiveRepository
      , uuidGen
      , asyncDeploymentAgent
      , changeRequestService
      , workflowService
      , restExtractorService
      , RUDDER_ENABLE_APPROVAL_WORKFLOWS
      , directiveEditorService
    )

  val directiveApi2 =
    new DirectiveAPI2 (
        roDirectiveRepository
      , restExtractorService
      , directiveApiService2
    )

  val latestDirectiveApi = new LatestDirectiveAPI (directiveApi2)

  val genericDirectiveApi =
    new DirectiveAPIHeaderVersion (
        roDirectiveRepository
      , restExtractorService
      , directiveApiService2
    )

  val groupApiService2 =
    new GroupApiService2 (
        roNodeGroupRepository
      , woNodeGroupRepository
      , uuidGen
      , asyncDeploymentAgent
      , changeRequestService
      , workflowService
      , restExtractorService
      , queryProcessor
      , RUDDER_ENABLE_APPROVAL_WORKFLOWS
    )

  val groupApi2 =
    new GroupAPI2 (
        roNodeGroupRepository
      , restExtractorService
      , groupApiService2
    )

  val latestGroupApi = new LatestGroupAPI (groupApi2)

  val genericGroupApi =
    new GroupAPIHeaderVersion (
        roNodeGroupRepository
      , restExtractorService
      , groupApiService2
    )

    val nodeApiService2 =
    new NodeApiService2 (
        newNodeManager
      , nodeInfoService
      , removeNodeService
      , uuidGen
      , restExtractorService
    )

  val nodeApi2 =
    new NodeAPI2 (
      nodeApiService2
    )

  val latestNodeApi = new LatestNodeAPI (nodeApi2)

  val genericNodeApi =
    new NodeAPIHeaderVersion (
      nodeApiService2
    )

  val parameterApiService2 =
    new ParameterApiService2 (
        roLDAPParameterRepository
      , woLDAPParameterRepository
      , uuidGen
      , changeRequestService
      , workflowService
      , restExtractorService
      , RUDDER_ENABLE_APPROVAL_WORKFLOWS
    )


  val parameterApi2 =
    new ParameterAPI2 (
        restExtractorService
      , parameterApiService2
    )

  val latestParameterApi = new LatestParameterAPI (parameterApi2)

  val genericParameterApi =
    new ParameterAPIHeaderVersion (
        restExtractorService
      , parameterApiService2
    )
  //////////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////////////////////////////////////////////////////////

  /**
   * A method to call to force initialisation of all object and services.
   * This is a good place to check boottime things, and throws
   * "application broken - can not start" exception
   *
   * Important: if that method is not called, RudderConfig will be
   * lazy and will only be initialised on the first call to one
   * of its (public) methods.
   */
  def init() : Unit = {
    import scala.collection.JavaConverters._
    val config = RudderProperties.config
    if(ApplicationLogger.isInfoEnabled) {
      //sort properties by key name
      val properties = config.entrySet.asScala.toSeq.sortBy( _.getKey ).map{ x =>
        //the log line: registered property: property_name=property_value
        s"registered property: ${x.getKey}=${if(filteredPasswords.contains(x.getKey)) "**********" else x.getValue.render}"
      }
      ApplicationLogger.info("List of registered properties:")
      properties.foreach { p =>
        ApplicationLogger.info(p)
      }
    }
  }


  //
  // Concrete implementation.
  // They are private to that object, and they can refer to other
  // private implementation as long as they conform to interface.
  //

  private[this] lazy val roLDAPApiAccountRepository = new RoLDAPApiAccountRepository(
      rudderDitImpl
    , roLdap
    , ldapEntityMapper
  )

  private[this] lazy val woLDAPApiAccountRepository = new WoLDAPApiAccountRepository(
      rudderDitImpl
    , rwLdap
    , ldapEntityMapper
    , ldapDiffMapper
    , logRepository
    , personIdentServiceImpl
  )

  private[this] lazy val ruleApplicationStatusImpl: RuleApplicationStatusService = new RuleApplicationStatusServiceImpl()
  private[this] lazy val acceptedNodesDitImpl: InventoryDit = new InventoryDit(LDAP_INVENTORIES_ACCEPTED_BASEDN, LDAP_INVENTORIES_SOFTWARE_BASEDN, "Accepted inventories")
  private[this] lazy val pendingNodesDitImpl: InventoryDit = new InventoryDit(LDAP_INVENTORIES_PENDING_BASEDN, LDAP_INVENTORIES_SOFTWARE_BASEDN, "Pending inventories")
  private[this] lazy val removedNodesDitImpl = new InventoryDit(LDAP_INVENTORIES_REMOVED_BASEDN,LDAP_INVENTORIES_SOFTWARE_BASEDN,"Removed Servers")
  private[this] lazy val rudderDitImpl: RudderDit = new RudderDit(LDAP_RUDDER_BASE)
  private[this] lazy val nodeDitImpl: NodeDit = new NodeDit(LDAP_NODE_BASE)
  private[this] lazy val inventoryDitService: InventoryDitService = new InventoryDitServiceImpl(pendingNodesDitImpl, acceptedNodesDitImpl,removedNodesDitImpl)
  private[this] lazy val uuidGen: StringUuidGenerator = new StringUuidGeneratorImpl
  private[this] lazy val systemVariableSpecService = new SystemVariableSpecServiceImpl()
  private[this] lazy val variableBuilderService: VariableBuilderService = new VariableBuilderServiceImpl()
  private[this] lazy val ldapEntityMapper = new LDAPEntityMapper(rudderDitImpl, nodeDitImpl, acceptedNodesDitImpl, queryParser)

  ///// items serializer - service that transforms items to XML /////
  private[this] lazy val ruleSerialisation: RuleSerialisation = new RuleSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
  private[this] lazy val rootSectionSerialisation : SectionSpecWriter = new SectionSpecWriterImpl()
  private[this] lazy val activeTechniqueCategorySerialisation: ActiveTechniqueCategorySerialisation =
    new ActiveTechniqueCategorySerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
  private[this] lazy val activeTechniqueSerialisation: ActiveTechniqueSerialisation =
    new ActiveTechniqueSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
  private[this] lazy val directiveSerialisation: DirectiveSerialisation =
    new DirectiveSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
  private[this] lazy val nodeGroupCategorySerialisation: NodeGroupCategorySerialisation =
    new NodeGroupCategorySerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
  private[this] lazy val nodeGroupSerialisation: NodeGroupSerialisation =
    new NodeGroupSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
  private[this] lazy val deploymentStatusSerialisation : DeploymentStatusSerialisation =
    new DeploymentStatusSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
  private[this] lazy val globalParameterSerialisation: GlobalParameterSerialisation =
    new GlobalParameterSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
  private[this] lazy val apiAccountSerialisation: APIAccountSerialisation =
    new APIAccountSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
  private[this] lazy val changeRequestChangesSerialisation : ChangeRequestChangesSerialisation =
    new ChangeRequestChangesSerialisationImpl(
        Constants.XML_CURRENT_FILE_FORMAT.toString
      , nodeGroupSerialisation
      , directiveSerialisation
      , ruleSerialisation
      , globalParameterSerialisation
      , techniqueRepositoryImpl
      , rootSectionSerialisation
    )
  private[this] lazy val eventLogFactory = new EventLogFactoryImpl(
      ruleSerialisation
    , directiveSerialisation
    , nodeGroupSerialisation
    , activeTechniqueSerialisation
    , globalParameterSerialisation
    , apiAccountSerialisation
  )
  private[this] lazy val pathComputer = new PathComputerImpl(RUDDER_DIR_BACKUP)
  private[this] lazy val baseUrlService: GetBaseUrlService = new DefaultBaseUrlService(BASE_URL)

  /*
   * For now, we don't want to query server other
   * than the accepted ones.
   */
  private[this] lazy val ditQueryDataImpl = new DitQueryData(acceptedNodesDitImpl)
  private[this] lazy val queryParser = new CmdbQueryParser with DefaultStringQueryParser with JsonQueryLexer {
    override val criterionObjects = Map[String, ObjectCriterion]() ++ ditQueryDataImpl.criteriaMap
  }
  private[this] lazy val inventoryMapper: InventoryMapper = new InventoryMapper(inventoryDitService, pendingNodesDitImpl, acceptedNodesDitImpl, removedNodesDitImpl)
  private[this] lazy val fullInventoryFromLdapEntries: FullInventoryFromLdapEntries = new FullInventoryFromLdapEntriesImpl(inventoryDitService, inventoryMapper)
  private[this] lazy val ldapDiffMapper = new LDAPDiffMapper(ldapEntityMapper, queryParser)


  private[this] lazy val activeTechniqueCategoryUnserialisation = new ActiveTechniqueCategoryUnserialisationImpl
  private[this] lazy val activeTechniqueUnserialisation = new ActiveTechniqueUnserialisationImpl
  private[this] lazy val directiveUnserialisation = new DirectiveUnserialisationImpl
  private[this] lazy val nodeGroupCategoryUnserialisation = new NodeGroupCategoryUnserialisationImpl
  private[this] lazy val nodeGroupUnserialisation = new NodeGroupUnserialisationImpl(queryParser)
  private[this] lazy val ruleUnserialisation = new RuleUnserialisationImpl
  private[this] lazy val globalParameterUnserialisation = new GlobalParameterUnserialisationImpl
  private[this] lazy val changeRequestChangesUnserialisation = new ChangeRequestChangesUnserialisationImpl(
      nodeGroupUnserialisation
    , directiveUnserialisation
    , ruleUnserialisation
    , globalParameterUnserialisation
    , techniqueRepository
    , sectionSpecParser
  )

  private[this] lazy val deploymentStatusUnserialisation = new DeploymentStatusUnserialisationImpl

  private[this] lazy val entityMigration = new DefaultXmlEventLogMigration(xmlMigration_2_3, xmlMigration_3_4)
  private[this] lazy val xmlMigration_2_3 = new XmlMigration_2_3()
  private[this] lazy val xmlMigration_3_4 = new XmlMigration_3_4()

  private[this] lazy val eventLogDetailsServiceImpl = new EventLogDetailsServiceImpl(
      queryParser
    , new DirectiveUnserialisationImpl
    , new NodeGroupUnserialisationImpl(queryParser)
    , new RuleUnserialisationImpl
    , new ActiveTechniqueUnserialisationImpl
    , new DeploymentStatusUnserialisationImpl
    , new GlobalParameterUnserialisationImpl
    , new ApiAccountUnserialisationImpl
  )

  //////////////////////////////////////////////////////////
  //  non pure services that could perhaps be
  //////////////////////////////////////////////////////////

  // => rwLdap is only used to repair an error, that could be repaired elsewhere.

  // => because of systemVariableSpecService
  // metadata.xml parser
  private[this] lazy val variableSpecParser = new VariableSpecParser
  private[this] lazy val sectionSpecParser = new SectionSpecParser(variableSpecParser)
  private[this] lazy val techniqueParser = {
    new TechniqueParser(variableSpecParser,sectionSpecParser,new Cf3PromisesFileTemplateParser,systemVariableSpecService)
  }

  ////////////////////////////////////
  //  non pure services
  ////////////////////////////////////
  private[this] lazy val userPropertyServiceImpl = {
    val opt = new ReasonsMessageInfo(RUDDER_UI_CHANGEMESSAGE_ENABLED, RUDDER_UI_CHANGEMESSAGE_MANDATORY, RUDDER_UI_CHANGEMESSAGE_EXPLANATION)
    new UserPropertyServiceImpl(opt)
  }

  ///// end /////
  private[this] lazy val logRepository = new EventLogJdbcRepository(jdbcTemplate,eventLogFactory)
  private[this] lazy val inventoryLogEventServiceImpl = new InventoryEventLogServiceImpl(logRepository)
  private[this] lazy val licenseRepository = new LicenseRepositoryXML(RUDDER_DIR_LICENSESFOLDER + "/" + licensesConfiguration)
  private[this] lazy val gitRepo = new GitRepositoryProviderImpl(RUDDER_DIR_GITROOT)
  private[this] lazy val gitRevisionProviderImpl = new LDAPGitRevisionProvider(rwLdap, rudderDitImpl, gitRepo, RUDDER_TECHNIQUELIBRARY_GIT_REFS_PATH)
  private[this] lazy val techniqueReader: TechniqueReader = {
    //find the relative path from gitRepo to the ptlib root
    val gitSlash = new File(RUDDER_DIR_GITROOT).getPath + "/"
    if(!RUDDER_DIR_TECHNIQUES.startsWith(gitSlash)) {
      ApplicationLogger.error("The Technique library root directory must be a sub-directory of '%s', but it is configured to be: '%s'".format(RUDDER_DIR_GITROOT, RUDDER_DIR_TECHNIQUES))
      throw new RuntimeException("The Technique library root directory must be a sub-directory of '%s', but it is configured to be: '%s'".format(RUDDER_DIR_GITROOT, RUDDER_DIR_TECHNIQUES))
    }
    val relativePath = RUDDER_DIR_TECHNIQUES.substring(gitSlash.size, RUDDER_DIR_TECHNIQUES.size)
    new GitTechniqueReader(
        techniqueParser
      , gitRevisionProviderImpl
      , gitRepo
      , "metadata.xml", "category.xml"
      , Some(relativePath)
    )
  }
  private[this] lazy val historizationJdbcRepository = new HistorizationJdbcRepository(squerylDatasourceProvider)
  private[this] lazy val startStopOrchestratorImpl: StartStopOrchestrator = {
    if (!(new File(BIN_EMERGENCY_STOP)).exists)
      ApplicationLogger.error("The 'red button' program is not present at: '%s'. You will experience error when trying to use that functionnality".format(BIN_EMERGENCY_STOP))
    new SystemStartStopOrchestrator(BIN_EMERGENCY_STOP)
  }

  private[this] lazy val roLdap =
    new ROPooledSimpleAuthConnectionProvider(
      host = LDAP_HOST,
      port = LDAP_PORT,
      authDn = LDAP_AUTHDN,
      authPw = LDAP_AUTHPW,
      poolSize = 2)
  private[this] lazy val rwLdap =
    new RWPooledSimpleAuthConnectionProvider(
      host = LDAP_HOST,
      port = LDAP_PORT,
      authDn = LDAP_AUTHDN,
      authPw = LDAP_AUTHPW,
      poolSize = 2)

  //query processor for accepted nodes
  private[this] lazy val queryProcessor = new AccepetedNodesLDAPQueryProcessor(
    nodeDitImpl,
    acceptedNodesDitImpl,
    new InternalLDAPQueryProcessor(roLdap, acceptedNodesDitImpl, ditQueryDataImpl, ldapEntityMapper))

  //we need a roLdap query checker for nodes in pending
  private[this] lazy val inventoryQueryChecker = new PendingNodesLDAPQueryChecker(new InternalLDAPQueryProcessor(roLdap, pendingNodesDitImpl, new DitQueryData(pendingNodesDitImpl), ldapEntityMapper))
  private[this] lazy val dynGroupServiceImpl = new DynGroupServiceImpl(rudderDitImpl, roLdap, ldapEntityMapper, inventoryQueryChecker)

  private[this] lazy val ldapFullInventoryRepository = new FullInventoryRepositoryImpl(inventoryDitService, inventoryMapper, rwLdap)
  private[this] lazy val unitRefuseGroup: UnitRefuseInventory = new RefuseGroups(
    "refuse_node:delete_id_in_groups",
    roLdapNodeGroupRepository, woLdapNodeGroupRepository)
  private[this] lazy val acceptInventory: UnitAcceptInventory with UnitRefuseInventory = new AcceptInventory(
    "accept_new_server:inventory",
    pendingNodesDitImpl,
    acceptedNodesDitImpl,
    ldapFullInventoryRepository)
  private[this] lazy val acceptNodeAndMachineInNodeOu: UnitAcceptInventory with UnitRefuseInventory = new AcceptFullInventoryInNodeOu(
    "accept_new_server:ou=node",
    nodeDitImpl,
    rwLdap,
    ldapEntityMapper,
    PendingInventory)
  private[this] lazy val acceptNodeRule: UnitAcceptInventory with UnitRefuseInventory = new AcceptNodeRule(
    "accept_new_server:add_system_configuration_rules",
    asyncDeploymentAgentImpl,
    roLdapNodeGroupRepository,
    woLdapNodeGroupRepository,
    AcceptedInventory)

  private[this] lazy val acceptHostnameAndIp: UnitAcceptInventory = new AcceptHostnameAndIp(
      "accept_new_server:check_hostname_unicity"
    , AcceptedInventory
    , queryProcessor
    , ditQueryDataImpl
    , psMngtService
  )

  private[this] lazy val addNodeToDynGroup: UnitAcceptInventory with UnitRefuseInventory = new AddNodeToDynGroup(
    "add_server_to_dyngroup",
    roLdapNodeGroupRepository,
    woLdapNodeGroupRepository,
    dynGroupServiceImpl,
    PendingInventory)
  private[this] lazy val historizeNodeStateOnChoice: UnitAcceptInventory with UnitRefuseInventory = new HistorizeNodeStateOnChoice(
      "accept_or_refuse_new_node:historize_inventory"
    , ldapFullInventoryRepository
    , diffRepos
    , PendingInventory
  )
  private[this] lazy val nodeConfigurationChangeDetectService = new NodeConfigurationChangeDetectServiceImpl()
  private[this] lazy val nodeGridImpl = new NodeGrid(ldapFullInventoryRepository)

  private[this] lazy val modificationService = new ModificationService(logRepository,gitModificationRepository,itemArchiveManagerImpl,uuidGen)
  private[this] lazy val eventListDisplayerImpl = new EventListDisplayer(eventLogDetailsServiceImpl, logRepository, roLdapNodeGroupRepository, roLdapDirectiveRepository, nodeInfoServiceImpl, modificationService, personIdentServiceImpl)
  private[this] lazy val fileManagerImpl = new FileManager(UPLOAD_ROOT_DIRECTORY)
  private[this] lazy val databaseManagerImpl = new DatabaseManagerImpl(reportsRepositoryImpl)
  private[this] lazy val softwareInventoryDAO: ReadOnlySoftwareDAO = new ReadOnlySoftwareDAOImpl(inventoryDitService, roLdap, inventoryMapper)
  private[this] lazy val nodeSummaryServiceImpl = new NodeSummaryServiceImpl(inventoryDitService, inventoryMapper, roLdap)
  private[this] lazy val diffRepos: InventoryHistoryLogRepository =
    new InventoryHistoryLogRepository(HISTORY_INVENTORIES_ROOTDIR, new FullInventoryFileMarshalling(fullInventoryFromLdapEntries, inventoryMapper))
  private[this] lazy val serverPolicyDiffService = new NodeConfigurationDiffService

  private[this] lazy val personIdentServiceImpl = new TrivialPersonIdentService
  private[this] lazy val ldapNodeConfigurationMapper = new LDAPNodeConfigurationMapper(rudderDitImpl, acceptedNodesDitImpl, systemVariableSpecService, techniqueRepositoryImpl, variableBuilderService, rwLdap)
  private[this] lazy val ldapNodeConfigurationRepository = new LDAPNodeConfigurationRepository(rwLdap, rudderDitImpl, ldapNodeConfigurationMapper)

  private[this] lazy val roParameterServiceImpl = new RoParameterServiceImpl(roLDAPParameterRepository)
  private[this] lazy val woParameterServiceImpl = new WoParameterServiceImpl(roParameterServiceImpl, woLDAPParameterRepository, asyncDeploymentAgentImpl)

  ///// items archivers - services that allows to transform items to XML and save then on a Git FS /////
  private[this] lazy val gitModificationRepository = new GitModificationSquerylRepository(squerylDatasourceProvider)
  private[this] lazy val gitRuleArchiver: GitRuleArchiver = new GitRuleArchiverImpl(
      gitRepo
    , new File(RUDDER_DIR_GITROOT)
    , ruleSerialisation
    , rulesDirectoryName
    , prettyPrinter
    , gitModificationRepository
  )
  private[this] lazy val gitActiveTechniqueCategoryArchiver: GitActiveTechniqueCategoryArchiver = new GitActiveTechniqueCategoryArchiverImpl(
      gitRepo
    , new File(RUDDER_DIR_GITROOT)
    , activeTechniqueCategorySerialisation
    , userLibraryDirectoryName
    , prettyPrinter
    , gitModificationRepository
  )
  private[this] lazy val gitActiveTechniqueArchiver: GitActiveTechniqueArchiverImpl = new GitActiveTechniqueArchiverImpl(
      gitRepo
    , new File(RUDDER_DIR_GITROOT)
    , activeTechniqueSerialisation
    , userLibraryDirectoryName
    , prettyPrinter
    , gitModificationRepository
  )
  private[this] lazy val gitDirectiveArchiver: GitDirectiveArchiver = new GitDirectiveArchiverImpl(
      gitRepo
    , new File(RUDDER_DIR_GITROOT)
    , directiveSerialisation
    , userLibraryDirectoryName
    , prettyPrinter
    , gitModificationRepository
  )
  private[this] lazy val gitNodeGroupArchiver: GitNodeGroupArchiver = new GitNodeGroupArchiverImpl(
      gitRepo
    , new File(RUDDER_DIR_GITROOT)
    , nodeGroupSerialisation
    , nodeGroupCategorySerialisation
    , groupLibraryDirectoryName
    , prettyPrinter
    , gitModificationRepository
  )
  private[this] lazy val gitParameterArchiver: GitParameterArchiver = new GitParameterArchiverImpl(
      gitRepo
    , new File(RUDDER_DIR_GITROOT)
    , globalParameterSerialisation
    , parametersDirectoryName
    , prettyPrinter
    , gitModificationRepository
  )
  ////////////// MUTEX FOR rwLdap REPOS //////////////

  private[this] lazy val uptLibReadWriteMutex = ScalaLock.java2ScalaRWLock(new java.util.concurrent.locks.ReentrantReadWriteLock(true))
  private[this] lazy val groupLibReadWriteMutex = ScalaLock.java2ScalaRWLock(new java.util.concurrent.locks.ReentrantReadWriteLock(true))
  private[this] lazy val nodeReadWriteMutex = ScalaLock.java2ScalaRWLock(new java.util.concurrent.locks.ReentrantReadWriteLock(true))
  private[this] lazy val parameterReadWriteMutex = ScalaLock.java2ScalaRWLock(new java.util.concurrent.locks.ReentrantReadWriteLock(true))


  private[this] lazy val roLdapDirectiveRepository = new RoLDAPDirectiveRepository(
        rudderDitImpl, roLdap, ldapEntityMapper, techniqueRepositoryImpl, uptLibReadWriteMutex)
  private[this] lazy val woLdapDirectiveRepository = {{
      val repo = new WoLDAPDirectiveRepository(
          roLdapDirectiveRepository,
        rwLdap,
        ldapDiffMapper,
        logRepository,
        uuidGen,
        gitDirectiveArchiver,
        gitActiveTechniqueArchiver,
        gitActiveTechniqueCategoryArchiver,
        personIdentServiceImpl,
        RUDDER_AUTOARCHIVEITEMS
      )

      gitActiveTechniqueArchiver.uptModificationCallback += new UpdatePiOnActiveTechniqueEvent(
          gitDirectiveArchiver,
        techniqueRepositoryImpl,
        roLdapDirectiveRepository
      )

      repo
    }
  }
  private[this] lazy val roLdapRuleRepository = new RoLDAPRuleRepository(rudderDitImpl, roLdap, ldapEntityMapper)

  private[this] lazy val woLdapRuleRepository: WoRuleRepository = new WoLDAPRuleRepository(
      roLdapRuleRepository
    , rwLdap
    , ldapDiffMapper
    , roLdapNodeGroupRepository
    , logRepository
    , gitRuleArchiver
    , personIdentServiceImpl
    , RUDDER_AUTOARCHIVEITEMS
  )
  private[this] lazy val roLdapNodeGroupRepository = new RoLDAPNodeGroupRepository(
      rudderDitImpl, roLdap, ldapEntityMapper, groupLibReadWriteMutex
  )
  private[this] lazy val woLdapNodeGroupRepository = new WoLDAPNodeGroupRepository(
      roLdapNodeGroupRepository
    , rwLdap
    , ldapDiffMapper
    , uuidGen
    , logRepository
    , gitNodeGroupArchiver
    , personIdentServiceImpl
    , RUDDER_AUTOARCHIVEITEMS
  )

  private[this] lazy val roLDAPParameterRepository = new RoLDAPParameterRepository(
      rudderDitImpl, roLdap, ldapEntityMapper, parameterReadWriteMutex
  )
  private[this] lazy val woLDAPParameterRepository = new WoLDAPParameterRepository(
      roLDAPParameterRepository
    , rwLdap
    , ldapDiffMapper
    , logRepository
    , gitParameterArchiver
    , personIdentServiceImpl
    , RUDDER_AUTOARCHIVEITEMS
  )

  private[this] lazy val itemArchiveManagerImpl = new ItemArchiveManagerImpl(
      roLdapRuleRepository
    , woLdapRuleRepository
    , roLdapDirectiveRepository
    , roLdapNodeGroupRepository
    , roLDAPParameterRepository
    , woLDAPParameterRepository
    , gitRepo
    , gitRevisionProvider
    , gitRuleArchiver
    , gitActiveTechniqueCategoryArchiver
    , gitActiveTechniqueArchiver
    , gitNodeGroupArchiver
    , gitParameterArchiver
    , parseRules
    , ParseActiveTechniqueLibrary
    , parseGlobalParameter
    , importTechniqueLibrary
    , parseGroupLibrary
    , importGroupLibrary
    , logRepository
    , asyncDeploymentAgentImpl
    , gitModificationRepository
  )
  private[this] lazy val parameterizedValueLookupService: ParameterizedValueLookupService =
    new ParameterizedValueLookupServiceImpl(ruleValService)

  private[this] lazy val systemVariableService: SystemVariableService = new SystemVariableServiceImpl(
      licenseRepository
    , parameterizedValueLookupService
    , systemVariableSpecService
    , RUDDER_DIR_DEPENDENCIES
    , RUDDER_ENDPOINT_CMDB
    , RUDDER_COMMUNITY_PORT
    , RUDDER_DIR_SHARED_FILES_FOLDER
    , RUDDER_WEBDAV_USER
    , RUDDER_WEBDAV_PASSWORD
    , RUDDER_SYSLOG_PORT
  )
  private[this] lazy val rudderCf3PromisesFileWriterService = new RudderCf3PromisesFileWriterServiceImpl(
    techniqueRepositoryImpl,
    pathComputer,
    ldapNodeConfigurationRepository,
    nodeInfoServiceImpl,
    licenseRepository,
    reportingServiceImpl,
    systemVariableSpecService,
    systemVariableService,
    RUDDER_DIR_DEPENDENCIES,
    RUDDER_DIR_UPLOADED_FILE_SHARING,
    RUDDER_ENDPOINT_CMDB,
    RUDDER_COMMUNITY_PORT,
    RUDDER_COMMUNITY_CHECKPROMISES_COMMAND,
    RUDDER_NOVA_CHECKPROMISES_COMMAND)

  //must be here because of cirular dependency if in techniqueRepository
  techniqueRepositoryImpl.registerCallback(new TechniqueAcceptationDatetimeUpdater("UpdatePTAcceptationDatetime", roLdapDirectiveRepository, woLdapDirectiveRepository))

  private[this] lazy val techniqueRepositoryImpl = {
    val service = new TechniqueRepositoryImpl(
        techniqueReader,
      Seq(),
      uuidGen
    )
    service.registerCallback(new LogEventOnTechniqueReloadCallback("LogEventOnPTLibUpdate", logRepository))
    service
  }
  private[this] lazy val ruleValService: RuleValService = new RuleValServiceImpl(variableBuilderService)

  private[this] lazy val psMngtService: PolicyServerManagementService = new PolicyServerManagementServiceImpl(
    roLdapDirectiveRepository, woLdapDirectiveRepository, asyncDeploymentAgentImpl)
  private[this] lazy val historizationService = new HistorizationServiceImpl(historizationJdbcRepository)

  private[this] lazy val asyncDeploymentAgentImpl: AsyncDeploymentAgent = {
    val agent = new AsyncDeploymentAgent(new DeploymentServiceImpl(
          roLdapRuleRepository,
          woLdapRuleRepository,
          ruleValService,
          parameterizedValueLookupService,
          systemVariableService,
          nodeConfigurationServiceImpl,
          nodeInfoServiceImpl,
          nodeConfigurationChangeDetectService,
          reportingServiceImpl,
          historizationService,
          roNodeGroupRepository,
          roDirectiveRepository,
          ruleApplicationStatusImpl,
          roParameterServiceImpl)
      , eventLogDeploymentServiceImpl
      , deploymentStatusSerialisation)
    techniqueRepositoryImpl.registerCallback(
        new DeployOnTechniqueCallback("DeployOnPTLibUpdate", agent)
    )
    agent
  }
  private[this] lazy val newNodeManagerImpl =
    new NewNodeManagerImpl(
      roLdap,
      pendingNodesDitImpl, acceptedNodesDitImpl,
      nodeSummaryServiceImpl,
      ldapFullInventoryRepository,
      //the sequence of unit process to accept a new inventory
      historizeNodeStateOnChoice ::
      addNodeToDynGroup ::
      acceptNodeAndMachineInNodeOu ::
      acceptInventory ::
      acceptNodeRule ::
      acceptHostnameAndIp ::
      Nil,
      //the sequence of unit process to refuse a new inventory
      historizeNodeStateOnChoice ::
      unitRefuseGroup ::
      acceptNodeAndMachineInNodeOu ::
      acceptInventory ::
      acceptNodeRule ::
      Nil
  )
  private[this] lazy val nodeConfigurationServiceImpl: NodeConfigurationService = new NodeConfigurationServiceImpl(
    rudderCf3PromisesFileWriterService,
    ldapNodeConfigurationRepository,
    techniqueRepositoryImpl)
  private[this] lazy val licenseService: NovaLicenseService = new NovaLicenseServiceImpl(licenseRepository, ldapNodeConfigurationRepository, RUDDER_DIR_LICENSESFOLDER)
  private[this] lazy val reportingServiceImpl = new ReportingServiceImpl(configurationExpectedRepo, reportsRepositoryImpl, techniqueRepositoryImpl)
  private[this] lazy val configurationExpectedRepo = new com.normation.rudder.repository.jdbc.RuleExpectedReportsJdbcRepository(jdbcTemplate)
  private[this] lazy val reportsRepositoryImpl = new com.normation.rudder.repository.jdbc.ReportsJdbcRepository(jdbcTemplate)
  private[this] lazy val dataSourceProvider = new RudderDatasourceProvider(RUDDER_JDBC_DRIVER, RUDDER_JDBC_URL, RUDDER_JDBC_USERNAME, RUDDER_JDBC_PASSWORD)
  private[this] lazy val squerylDatasourceProvider = new SquerylConnectionProvider(dataSourceProvider.datasource)
  private[this] lazy val jdbcTemplate = {
    val template = new org.springframework.jdbc.core.JdbcTemplate(dataSourceProvider.datasource)
    template
  }

  private[this] lazy val parseRules : ParseRules = new GitParseRules(
      ruleUnserialisation
    , gitRepo
    , entityMigration
    , rulesDirectoryName
  )
  private[this] lazy val ParseActiveTechniqueLibrary : ParseActiveTechniqueLibrary = new GitParseActiveTechniqueLibrary(
      activeTechniqueCategoryUnserialisation
    , activeTechniqueUnserialisation
    , directiveUnserialisation
    , gitRepo
    , entityMigration
    , userLibraryDirectoryName
  )
  private[this] lazy val importTechniqueLibrary : ImportTechniqueLibrary = new ImportTechniqueLibraryImpl(
     rudderDitImpl
   , rwLdap
   , ldapEntityMapper
   , uptLibReadWriteMutex
  )
  private[this] lazy val parseGroupLibrary : ParseGroupLibrary = new GitParseGroupLibrary(
      nodeGroupCategoryUnserialisation
    , nodeGroupUnserialisation
    , gitRepo
    , entityMigration
    , groupLibraryDirectoryName
  )
  private[this] lazy val parseGlobalParameter : ParseGlobalParameters = new GitParseGlobalParameters(
      globalParameterUnserialisation
    , gitRepo
    , entityMigration
    , parametersDirectoryName
  )
  private[this] lazy val importGroupLibrary : ImportGroupLibrary = new ImportGroupLibraryImpl(
     rudderDitImpl
   , rwLdap
   , ldapEntityMapper
   , groupLibReadWriteMutex
  )
  private[this] lazy val eventLogDeploymentServiceImpl = new EventLogDeploymentService(logRepository, eventLogDetailsServiceImpl)
  private[this] lazy val nodeInfoServiceImpl: NodeInfoService = new NodeInfoServiceImpl(nodeDitImpl, rudderDitImpl, acceptedNodesDitImpl, roLdap, ldapEntityMapper)
  private[this] lazy val dependencyAndDeletionServiceImpl: DependencyAndDeletionService = new DependencyAndDeletionServiceImpl(
        roLdap
      , rudderDitImpl
      , roLdapDirectiveRepository
      , woLdapDirectiveRepository
      , woLdapRuleRepository
      , woLdapNodeGroupRepository
      , ldapEntityMapper
  )
  private[this] lazy val quickSearchServiceImpl = new QuickSearchServiceImpl(
    roLdap, nodeDitImpl, acceptedNodesDitImpl, ldapEntityMapper,
    //nodeAttributes
    Seq(LDAPConstants.A_NAME, LDAPConstants.A_NODE_UUID),
    //serverAttributes
    Seq(
        LDAPConstants.A_HOSTNAME
      , LDAPConstants.A_LIST_OF_IP
      , LDAPConstants.A_OS_NAME
      , LDAPConstants.A_OS_FULL_NAME
      , LDAPConstants.A_OS_VERSION
      , LDAPConstants.A_OS_SERVICE_PACK
      , LDAPConstants.A_OS_KERNEL_VERSION
    ))
  private[this] lazy val logDisplayerImpl: LogDisplayer = new LogDisplayer(reportsRepositoryImpl, roLdapDirectiveRepository, roLdapRuleRepository)
  private[this] lazy val categoryHierarchyDisplayerImpl: CategoryHierarchyDisplayer = new CategoryHierarchyDisplayer()
  private[this] lazy val dyngroupUpdaterBatch: UpdateDynamicGroups = new UpdateDynamicGroups(
      dynGroupServiceImpl
    , new DynGroupUpdaterServiceImpl(roLdapNodeGroupRepository, woLdapNodeGroupRepository, queryProcessor)
    , asyncDeploymentAgentImpl
    , uuidGen
    , RUDDER_BATCH_DYNGROUP_UPDATEINTERVAL
  )

  private[this] lazy val dbCleaner: AutomaticReportsCleaning = {
    val cleanFrequency = AutomaticReportsCleaning.buildFrequency(
        RUDDER_BATCH_REPORTSCLEANER_FREQUENCY
      , RUDDER_BATCH_DATABASECLEANER_RUNTIME_MINUTE
      , RUDDER_BATCH_DATABASECLEANER_RUNTIME_HOUR
      , RUDDER_BATCH_DATABASECLEANER_RUNTIME_DAY) match {
      case Full(freq) => freq
      case eb:EmptyBox => val fail = eb ?~! "automatic reports cleaner is not correct"
        val exceptionMsg = "configuration file (/opt/rudder/etc/rudder-webapp.conf) is not correctly set, cause is %s".format(fail.msg)
        throw new RuntimeException(exceptionMsg)
    }

    new AutomaticReportsCleaning(
      databaseManagerImpl
    , RUDDER_BATCH__REPORTSCLEANER_DELETE_TTL
    , reportCleanerArchiveTTL
    , cleanFrequency
  )}

  @Bean
  def techniqueLibraryUpdater = new CheckTechniqueLibrary(
      techniqueRepositoryImpl
    , asyncDeploymentAgent
    , uuidGen
    , RUDDER_BATCH_TECHNIQUELIBRARY_UPDATEINTERVAL
  )
  private[this] lazy val userSessionLogEvent = new UserSessionLogEvent(logRepository, uuidGen)
  private[this] lazy val jsTreeUtilServiceImpl = new JsTreeUtilService(roLdapDirectiveRepository, techniqueRepositoryImpl)
  private[this] lazy val removeNodeServiceImpl = new RemoveNodeServiceImpl(
        nodeDitImpl
      , rudderDitImpl
      , rwLdap
      , ldapEntityMapper
      , roLdapNodeGroupRepository
      , woLdapNodeGroupRepository
      , nodeConfigurationServiceImpl
      , nodeInfoServiceImpl
      , ldapFullInventoryRepository
      , logRepository
      , nodeReadWriteMutex)

  /**
   * Event log migration
   */


  private[this] lazy val migrationRepository = new MigrationEventLogRepository(squerylDatasourceProvider)

  private[this] lazy val eventLogsMigration_2_3 = new EventLogsMigration_2_3(
      jdbcTemplate        = jdbcTemplate
    , individualMigration = new EventLogMigration_2_3(xmlMigration_2_3)
    , batchSize           = 1000
  )

  private[this] lazy val eventLogsMigration_2_3_Management = new ControlEventLogsMigration_2_3(
          migrationEventLogRepository = migrationRepository
        , Seq(eventLogsMigration_2_3)
  )

  private[this] lazy val eventLogsMigration_3_4 = new EventLogsMigration_3_4(
      jdbcTemplate
    , new EventLogMigration_3_4(xmlMigration_3_4)
    , eventLogsMigration_2_3
  )

  private[this] lazy val controlXmlFileFormatMigration_3_4 = new ControlXmlFileFormatMigration_3_4(
      migrationEventLogRepository = migrationRepository
    , batchMigrators              = Seq(eventLogsMigration_3_4
                                      , new ChangeRequestsMigration_3_4(
                                          jdbcTemplate
                                        , new ChangeRequestMigration_3_4(xmlMigration_3_4)
                                      )
                                    )
    , previousMigrationController = Some(eventLogsMigration_2_3_Management)
  )

  /**
   * *************************************************
   * Bootstrap check actions
   * **************************************************
   */
  private[this] lazy val allChecks = new SequentialImmediateBootStrapChecks(
      new CheckDIT(pendingNodesDitImpl, acceptedNodesDitImpl, removedNodesDitImpl, rudderDitImpl, rwLdap)
    , new CheckRootNodeUnicity(ldapNodeConfigurationRepository)
    , new CheckSystemDirectives(rudderDitImpl, roLdapRuleRepository)
    , new CheckInitUserTemplateLibrary(
        rudderDitImpl, rwLdap, techniqueRepositoryImpl,
        roLdapDirectiveRepository, woLdapDirectiveRepository, uuidGen) //new CheckDirectiveBusinessRules()
    , new CheckMigrationXmlFileFormat3_4(controlXmlFileFormatMigration_3_4)
    , new CheckInitXmlExport(itemArchiveManagerImpl, personIdentServiceImpl, uuidGen)
    , new CheckMigrationDirectiveInterpolatedVariablesHaveRudderNamespace(roLdapDirectiveRepository, woLdapDirectiveRepository, uuidGen)
    , new CheckDotInGenericCFEngineVariableDef(roLdapDirectiveRepository, woLdapDirectiveRepository, uuidGen)
  )



  //////////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////// Directive Editor and web fields //////////////////////////////
  //////////////////////////////////////////////////////////////////////////////////////////

  import com.normation.rudder.web.model._
  import org.joda.time.format.DateTimeFormat
  import java.util.Locale
  import com.normation.cfclerk.domain._

  val frenchDateFormatter = DateTimeFormat.forPattern("dd/MM/yyyy").withLocale(Locale.FRANCE)
  val frenchTimeFormatter = DateTimeFormat.forPattern("kk:mm:ss").withLocale(Locale.FRANCE)

  object FieldFactoryImpl extends DirectiveFieldFactory {
    //only one field

    override def forType(v: VariableSpec, id: String): DirectiveField = {
      val prefixSize = "size-"
      v match {
        case selectOne: SelectOneVariableSpec => new SelectOneField(id, selectOne.valueslabels)
        case select: SelectVariableSpec => new SelectField(id, select.valueslabels)
        case input: InputVariableSpec => v.constraint.typeName match {
          case str: SizeVType => new InputSizeField(id, str.name.substring(prefixSize.size))
          case UploadedFileVType => new UploadedFileField(UPLOAD_ROOT_DIRECTORY)(id)
          case DestinationPathVType => default(id)
          case DateVType(r) => new DateField(frenchDateFormatter)(id)
          case TimeVType(r) => new TimeField(frenchTimeFormatter)(id)
          case PermVType => new FilePermsField(id)
          case BooleanVType => new CheckboxField(id)
          case TextareaVType(r) => new TextareaField(id)
          case PasswordVType(algos) => new PasswordField(id, input.constraint.mayBeEmpty, algos)
          case _ => default(id)
        }
        case _ =>
          logger.error("Unexpected case : variable %s should not be displayed. Only select1, select or input can be displayed.".format(v.name))
          default(id)
      }
    }

    override def default(id: String) = new TextField(id)
  }

  private[this] lazy val section2FieldService: Section2FieldService = {
      def translators = {
        val t = new Translators()
        t.add(StringTranslator)
        t.add(new DateTimeTranslator(frenchDateFormatter, frenchTimeFormatter)) //TODO: how that can be session dependent ?
        t.add(FilePermsTranslator)
        t.add(FileTranslator)
        t.add(DestinationFileTranslator)
        t.add(SelectFieldTranslator)
        t
      }
    new Section2FieldService(FieldFactoryImpl, translators)
  }
  private[this] lazy val directiveEditorServiceImpl: DirectiveEditorService =
    new DirectiveEditorServiceImpl(techniqueRepositoryImpl, section2FieldService)
  private[this] lazy val reportDisplayerImpl = new ReportDisplayer(
      roLdapRuleRepository
    , roLdapDirectiveRepository
    , reportingServiceImpl
    , techniqueRepositoryImpl)
  private[this] lazy val propertyRepository = new RudderPropertiesSquerylRepository(
      squerylDatasourceProvider
    , reportsRepository )
  private[this] lazy val autoReportLogger = new AutomaticReportLogger(
      propertyRepository
    , reportsRepositoryImpl
    , roLdapRuleRepository
    , roLdapDirectiveRepository
    , nodeInfoServiceImpl
    , RUDDER_BATCH_REPORTS_LOGINTERVAL )

//  ////////////////////// Snippet plugins & extension register //////////////////////
//  import com.normation.plugins.{ SnippetExtensionRegister, SnippetExtensionRegisterImpl }
//  private[this] lazy val snippetExtensionRegister: SnippetExtensionRegister = new SnippetExtensionRegisterImpl()

}


/**
 * Spring configuration for services
 */
@Configuration
@Import(Array(classOf[AppConfigAuth]))
class AppConfig extends Loggable {


  ////////////////////// Snippet plugins & extension register //////////////////////
  import com.normation.plugins.{ SnippetExtensionRegister, SnippetExtensionRegisterImpl }
  @Bean
  def snippetExtensionRegister: SnippetExtensionRegister = new SnippetExtensionRegisterImpl()

}
