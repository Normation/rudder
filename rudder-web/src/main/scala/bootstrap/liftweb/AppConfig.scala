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

package bootstrap.liftweb

import com.normation.inventory.domain._
import com.normation.inventory.services.core._
import com.normation.inventory.ldap.core._
import com.normation.inventory.services._
import com.normation.rudder.batch._
import com.normation.rudder.services.nodes._
import com.normation.rudder.repository._
import com.normation.rudder.services.queries._
import com.normation.rudder.services.servers._
import com.normation.rudder.services.system._
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
import com.normation.rudder.services.eventlog.HistorizationServiceImpl
import com.normation.rudder.services.policies.DeployOnTechniqueCallback
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
import com.normation.cfclerk.xmlwriters.SectionSpecWriter
import com.normation.cfclerk.xmlwriters.SectionSpecWriterImpl
import com.normation.rudder.services.modification.DiffServiceImpl
import com.normation.rudder.services.modification.DiffService
import com.normation.rudder.services.user.PersonIdentService
import com.normation.rudder.services.workflows._
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
import com.normation.rudder.migration._
import com.normation.rudder.web.rest.parameter._
import com.normation.rudder.web.rest.changeRequest._
import com.normation.rudder.reports.execution._
import com.normation.rudder.reports.statusUpdate._
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.appconfig._
import com.normation.rudder.rule.category._
import com.normation.rudder.rule.category.GitRuleCategoryArchiverImpl
import com.normation.rudder.services.policies.nodeconfig._
import com.normation.rudder.reports.ComplianceModeService
import com.normation.rudder.reports.ComplianceModeServiceImpl
import com.normation.rudder.reports.AgentRunIntervalService
import com.normation.rudder.reports.AgentRunIntervalServiceImpl
import com.normation.rudder.web.rest.compliance.ComplianceAPI7
import com.normation.rudder.web.rest.compliance.ComplianceAPIService
import com.normation.rudder.services.policies.write.Cf3PromisesFileWriterServiceImpl
import com.normation.rudder.services.policies.write.PathComputerImpl
import com.normation.rudder.services.policies.write.PrepareTemplateVariablesImpl
import com.typesafe.config.ConfigException
import org.apache.commons.io.FileUtils

/**
 * Define a resource for configuration.
 * For now, config properties can only be loaded from either
 * a file in the classpath, or a file in the file system.
 */
sealed trait ConfigResource
final case class ClassPathResource(name: String) extends ConfigResource
final case class FileSystemResource(file: File) extends ConfigResource

final case class AuthenticationMethods(name: String) {
  val path = s"applicationContext-security-auth-${name}.xml"
  val configFile = s"classpath:${path}"
  val springBean = s"${name}AuthenticationProvider"
}

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

  //some logic for the authentication providers
  val authenticationMethods: Seq[AuthenticationMethods] = {
    val names = try {
      //config.getString can't be null by contract
      config.getString("rudder.auth.provider").split(",").toSeq.map( _.trim).collect { case s if(s.size > 0) => s}
    } catch {
      //if the property is missing, use the default "file" value
      //it can be a migration.
      case ex: ConfigException.Missing =>  Seq("file")
    }

    //always add "rootAdmin" has the first method
    //and de-duplicate methods
    val auths = ("rootAdmin" +: names).distinct.map(AuthenticationMethods(_))

    //for each methods, check that the provider file is present, or log an error and
    //disable that provider
    auths.flatMap { a =>
      if(a.name == "rootAdmin") {
        Some(a)
      } else {
        //try to instantiate
        val cpr = new org.springframework.core.io.ClassPathResource(a.path)
        if(cpr.exists) {
          Some(a)
        } else {
          ApplicationLogger.error(s"The authentication provider '${a.name}' will not be loaded because the spring ressource file '${a.configFile}' was not found")
          None
        }
      }
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

  //the LDAP password used for authentication is not used here, but should not appear nonetheless
  filteredPasswords += "rudder.auth.ldap.connection.bind.password"

  //other values

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
  val RUDDER_CFENGINE_RELOAD_SERVER_COMMAND = config.getString("rudder.cfengine.reload.server.command")
  val RUDDER_JDBC_DRIVER = config.getString("rudder.jdbc.driver")
  val RUDDER_JDBC_URL = config.getString("rudder.jdbc.url")
  val RUDDER_JDBC_USERNAME = config.getString("rudder.jdbc.username")
  val RUDDER_JDBC_PASSWORD = config.getString("rudder.jdbc.password") ; filteredPasswords += "rudder.jdbc.password"
  val RUDDER_JDBC_MAX_POOL_SIZE = config.getInt("rudder.jdbc.maxPoolSize")
  val RUDDER_DIR_GITROOT = config.getString("rudder.dir.gitRoot")
  val RUDDER_DIR_TECHNIQUES = config.getString("rudder.dir.techniques")
  val RUDDER_BATCH_DYNGROUP_UPDATEINTERVAL = config.getInt("rudder.batch.dyngroup.updateInterval") //60 //one hour
  val RUDDER_BATCH_TECHNIQUELIBRARY_UPDATEINTERVAL = config.getInt("rudder.batch.techniqueLibrary.updateInterval") //60 * 5 //five minutes
  val RUDDER_BATCH_REPORTSCLEANER_ARCHIVE_TTL = config.getInt("rudder.batch.reportscleaner.archive.TTL") //AutomaticReportsCleaning.defaultArchiveTTL
  val RUDDER_BATCH_REPORTSCLEANER_DELETE_TTL = config.getInt("rudder.batch.reportscleaner.delete.TTL") //AutomaticReportsCleaning.defaultDeleteTTL
  val RUDDER_BATCH_REPORTSCLEANER_FREQUENCY = config.getString("rudder.batch.reportscleaner.frequency") //AutomaticReportsCleaning.defaultDay
  val RUDDER_BATCH_DATABASECLEANER_RUNTIME_HOUR = config.getInt("rudder.batch.databasecleaner.runtime.hour") //AutomaticReportsCleaning.defaultHour
  val RUDDER_BATCH_DATABASECLEANER_RUNTIME_MINUTE = config.getInt("rudder.batch.databasecleaner.runtime.minute") //AutomaticReportsCleaning.defaultMinute
  val RUDDER_BATCH_DATABASECLEANER_RUNTIME_DAY = config.getString("rudder.batch.databasecleaner.runtime.day") //"sunday"
  val RUDDER_BATCH_REPORTS_LOGINTERVAL = config.getInt("rudder.batch.reports.logInterval") //1 //one minute
  val RUDDER_TECHNIQUELIBRARY_GIT_REFS_PATH = config.getString("rudder.techniqueLibrary.git.refs.path")
  val RUDDER_AUTOARCHIVEITEMS = config.getBoolean("rudder.autoArchiveItems") //true
  val RUDDER_SYSLOG_PORT = config.getInt("rudder.syslog.port") //514
  val RUDDER_REPORTS_EXECUTION_MAX_DAYS = config.getInt("rudder.batch.storeAgentRunTimes.maxDays") // In days : 5
  val RUDDER_REPORTS_EXECUTION_INTERVAL = config.getInt("rudder.batch.storeAgentRunTimes.updateInterval") // In seconds : 5

  val BIN_EMERGENCY_STOP = config.getString("bin.emergency.stop")
  val HISTORY_INVENTORIES_ROOTDIR = config.getString("history.inventories.rootdir")
  val UPLOAD_ROOT_DIRECTORY = config.getString("upload.root.directory")

  //used in spring security "applicationContext-security.xml", be careful if you change its name
  val RUDDER_REST_ALLOWNONAUTHENTICATEDUSER = config.getBoolean("rudder.rest.allowNonAuthenticatedUser")

  val RUDDER_DEBUG_NODE_CONFIGURATION_PATH = config.getString("rudder.debug.nodeconfiguration.path")

  // Roles definitions
  val RUDDER_SERVER_ROLES = Seq(
      //each time, it's (role name, key in the config file)
      RudderServerRole("rudder-ldap", "rudder.server-roles.ldap")
    , RudderServerRole("rudder-inventory-endpoint", "rudder.server-roles.inventory-endpoint")
    , RudderServerRole("rudder-db", "rudder.server-roles.db")
    , RudderServerRole("rudder-relay-top", "rudder.server-roles.relay-top")
    , RudderServerRole("rudder-web", "rudder.server-roles.web")
    , RudderServerRole("rudder-relay-promises-only", "rudder.server-roles.relay-promises-only")
    , RudderServerRole("rudder-cfengine-mission-portal", "rudder.server-roles.cfengine-mission-portal")
  )

  val licensesConfiguration = "licenses.xml"
  val logentries = "logentries.xml"
  val prettyPrinter = new RudderPrettyPrinter(120, 2)
  val userLibraryDirectoryName = "directives"
  val groupLibraryDirectoryName = "groups"
  val rulesDirectoryName = "rules"
  val ruleCategoriesDirectoryName = "ruleCategories"
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
  val woNodeRepository: WoNodeRepository = woLdapNodeRepository
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
  lazy val asyncComplianceService : AsyncComplianceService = new AsyncComplianceService(reportingService)
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
  val checkTechniqueLibrary: CheckTechniqueLibrary = techniqueLibraryUpdater
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
  lazy val srvGrid = new SrvGrid(roAgentRunsRepository, asyncComplianceService)
  val findExpectedReportRepository : FindExpectedReportRepository = findExpectedRepo
  val historizationRepository : HistorizationRepository =  historizationJdbcRepository
  val roApiAccountRepository : RoApiAccountRepository = roLDAPApiAccountRepository
  val woApiAccountRepository : WoApiAccountRepository = woLDAPApiAccountRepository

  val roWorkflowRepository : RoWorkflowRepository = new RoWorkflowJdbcRepository(jdbcTemplate)
  val woWorkflowRepository : WoWorkflowRepository = new WoWorkflowJdbcRepository(jdbcTemplate, roWorkflowRepository)

  lazy val roAgentRunsRepository : RoReportsExecutionRepository = cachedAgentRunRepository
  lazy val woAgentRunsRepository : WoReportsExecutionRepository = cachedAgentRunRepository

  //all cache that need to be cleared are stored here
  lazy val clearableCache: Seq[CachedRepository] = Seq(
      cachedAgentRunRepository
    , recentChangesService
    , reportingServiceImpl
    , nodeInfoServiceImpl
  )

  val inMemoryChangeRequestRepository : InMemoryChangeRequestRepository = new InMemoryChangeRequestRepository
  val ldapInventoryMapper = inventoryMapper

  val roChangeRequestRepository : RoChangeRequestRepository = {
    //a runtime checking of the workflow to use
    new EitherRoChangeRequestRepository(
        configService.rudder_workflow_enabled
      , new RoChangeRequestJdbcRepository(
            jdbcTemplate
          , new ChangeRequestsMapper(changeRequestChangesUnserialisation)
        )
      , inMemoryChangeRequestRepository
    )
  }

  val woChangeRequestRepository : WoChangeRequestRepository = {
    //a runtime checking of the workflow to use
    new EitherWoChangeRequestRepository(
        configService.rudder_workflow_enabled
      , new WoChangeRequestJdbcRepository(
            jdbcTemplate
          , changeRequestChangesSerialisation
          , roChangeRequestRepository
        )
      , inMemoryChangeRequestRepository
    )
  }

  val roRuleCategoryRepository : RoRuleCategoryRepository = roLDAPRuleCategoryRepository
  val ruleCategoryService      : RuleCategoryService = new RuleCategoryService()
  val woRuleCategoryRepository : WoRuleCategoryRepository = woLDAPRuleCategoryRepository

  val changeRequestEventLogService : ChangeRequestEventLogService = new ChangeRequestEventLogServiceImpl(eventLogRepository)

  val xmlSerializer = XmlSerializerImpl(
      ruleSerialisation
    , directiveSerialisation
    , nodeGroupSerialisation
    , globalParameterSerialisation
    ,ruleCategorySerialisation
  )

  val xmlUnserializer = XmlUnserializerImpl(
      ruleUnserialisation
    , directiveUnserialisation
    , nodeGroupUnserialisation
    , globalParameterUnserialisation
    , ruleCategoryUnserialisation
  )
  val workflowEventLogService =    new WorkflowEventLogServiceImpl(eventLogRepository,uuidGen)
  val diffService: DiffService = new DiffServiceImpl()
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
      , configService.rudder_workflow_enabled
      , xmlSerializer
      , xmlUnserializer
      , sectionSpecParser
    )
  val asyncWorkflowInfo = new AsyncWorkflowInfo
  val workflowService: WorkflowService = {
    new EitherWorkflowService(
        configService.rudder_workflow_enabled
      , new TwoValidationStepsWorkflowServiceImpl(
            workflowEventLogService
          , commitAndDeployChangeRequest
          , roWorkflowRepository
          , woWorkflowRepository
          , asyncWorkflowInfo
          , configService.rudder_workflow_self_validation
          , configService.rudder_workflow_self_deployment
        )
      , new NoWorkflowServiceImpl(
            commitAndDeployChangeRequest
          , inMemoryChangeRequestRepository
        )
    )
  }

  val changeRequestService: ChangeRequestService = new ChangeRequestServiceImpl (
      roChangeRequestRepository
    , woChangeRequestRepository
    , changeRequestEventLogService
    , uuidGen
    , configService.rudder_workflow_enabled
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
      , workflowService
    )

  val tokenGenerator = new TokenGeneratorImpl(32)

  val restDeploy = new RestDeploy(asyncDeploymentAgentImpl, uuidGen)
  val restDyngroupReload = new RestDyngroupReload(dyngroupUpdaterBatch)
  val restTechniqueReload = new RestTechniqueReload(techniqueRepositoryImpl, uuidGen)
  val restArchiving = new RestArchiving(itemArchiveManagerImpl,personIdentServiceImpl, uuidGen)
  val restGetGitCommitAsZip = new RestGetGitCommitAsZip(gitRepo)
  val restApiAccounts = new RestApiAccounts(roApiAccountRepository,woApiAccountRepository,restExtractorService,tokenGenerator, uuidGen)
  val restDataSerializer = RestDataSerializerImpl(techniqueRepository,diffService)

  val ruleApiService2 =
    new RuleApiService2(
        roRuleRepository
      , woRuleRepository
      , uuidGen
      , asyncDeploymentAgent
      , changeRequestService
      , workflowService
      , restExtractorService
      , configService.rudder_workflow_enabled
      , restDataSerializer
    )

  val ruleApi2 =
    new RuleAPI2 (
        roRuleRepository
      , restExtractorService
      , ruleApiService2
    )

    val ruleApiService6 =
    new RuleApiService6 (
        roRuleCategoryRepository
      , roRuleRepository
      , woRuleCategoryRepository
      , ruleCategoryService
      , restDataSerializer
    )

  val ruleApi6 =
    new RuleAPI6 (
        ruleApiService6
      , ruleApi2
      , restExtractorService
      , uuidGen
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
      , configService.rudder_workflow_enabled
      , directiveEditorService
      , restDataSerializer
      , techniqueRepositoryImpl
    )

  val directiveApi2 =
    new DirectiveAPI2 (
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
      , configService.rudder_workflow_enabled
      , restDataSerializer
    )

  val groupApiService5 = new GroupApiService5 (groupApiService2)

  val groupApi2 =
    new GroupAPI2 (
        roNodeGroupRepository
      , restExtractorService
      , groupApiService2
    )

  val groupApi5 =
    new GroupAPI5 (
        restExtractorService
      , groupApi2
      , groupApiService5
    )

    val groupApiService6 =
    new GroupApiService6 (
        roNodeGroupRepository
      , woNodeGroupRepository
      , restDataSerializer
    )

  val groupApi6 =
    new GroupAPI6 (
        groupApiService6
      , groupApi5
      , restExtractorService
      , uuidGen
    )

    val nodeApiService2 =
      NodeApiService2 (
        newNodeManager
      , nodeInfoService
      , removeNodeService
      , uuidGen
      , restExtractorService
      , restDataSerializer
      , false
    )

  val nodeApi2 =
    NodeAPI2 (
        nodeApiService2
      , restExtractorService
    )

  val nodeApi4 = {
    val fixedApiService2 = nodeApiService2.copy(fixedTag = true)
    val fixedApi2 = nodeApi2.copy(apiV2 = fixedApiService2)
    new NodeAPI4 (
        fixedApi2
      , new NodeApiService4 (
            fullInventoryRepository
          , nodeInfoService
          , uuidGen
          , restExtractorService
          , restDataSerializer
        )
      , restExtractorService
    )
  }

  val nodeApi5 = {
    new NodeAPI5 (
        nodeApi4
      , new NodeApiService5(
            woNodeRepository
          , nodeInfoService
          , uuidGen
          , restExtractorService
        )
      , restExtractorService
    )
  }

  val nodeApi6 = {
    new NodeAPI6 (
        nodeApi5
      , new NodeApiService6(
            nodeInfoService
          , fullInventoryRepository
          , restExtractorService
          , restDataSerializer
          , queryProcessor
        )
      , restExtractorService
    )
  }

  val parameterApiService2 =
    new ParameterApiService2 (
        roLDAPParameterRepository
      , woLDAPParameterRepository
      , uuidGen
      , changeRequestService
      , workflowService
      , restExtractorService
      , configService.rudder_workflow_enabled
      , restDataSerializer
    )

  val parameterApi2 =
    new ParameterAPI2 (
        restExtractorService
      , parameterApiService2
    )

  val changeRequestApiService3 =
    new ChangeRequestAPIService3 (
        roChangeRequestRepository
      , woChangeRequestRepository
      , roWorkflowRepository
      , woWorkflowRepository
      , techniqueRepository
      , changeRequestService
      , workflowService
      , commitAndDeployChangeRequest
      , restExtractorService
      , restDataSerializer
      , configService.rudder_workflow_enabled
    )

  val changeRequestApi3 =
    new ChangeRequestAPI3 (
        restExtractorService
      , changeRequestApiService3
    )

  private[this] val complianceAPIService = new ComplianceAPIService(
          roRuleRepository
        , nodeInfoService
        , roNodeGroupRepository
        , reportingService
        , globalComplianceModeService.getGlobalComplianceMode _
      )

  val complianceApi6 = new ComplianceAPI7(restExtractorService, complianceAPIService, v6compatibility = true)
  val complianceApi7 = new ComplianceAPI7(restExtractorService, complianceAPIService)

  // First working version with support for rules, directives, nodes and global parameters
  val apiV2 : List[RestAPI] = ruleApi2 :: directiveApi2 :: groupApi2 :: nodeApi2 :: parameterApi2 :: Nil
  // Add change request support
  val apiV3 : List[RestAPI] = changeRequestApi3 :: apiV2
  // Add inventory support on nodes
  val apiV4 : List[RestAPI] = nodeApi4 :: apiV3.filter( _ != nodeApi2)
  // Allow empty query for groups, add key-values support on nodes
  val apiV5 : List[RestAPI] = nodeApi5 :: groupApi5 :: apiV4.filter( _ != nodeApi4).filter( _ != groupApi2)
  // Add compliance endpoint and filtering off node/rule/group results
  val apiV6 : List[RestAPI] = complianceApi6 :: nodeApi6 :: ruleApi6 :: groupApi6 :: apiV5.filter( _ != nodeApi5).filter( _ != ruleApi2).filter( _ != groupApi5)
  // apiv7 just add compatible changes on compliances, adding "level" option and "compliance mode" attribute in response
  val apiV7 = complianceApi7 :: apiV6.filter( _ != complianceApi6)

  val apis = {
    Map (
        //Rudder 2.7
        ( ApiVersion(2,true) -> apiV2 )
        //Rudder 2.8
      , ( ApiVersion(3,true) -> apiV3 )
        //Rudder 2.10
      , ( ApiVersion(4,true) -> apiV4 )
        //Rudder 3.0
      , ( ApiVersion(5,false) -> apiV5 )
        //Rudder 3.1
      , ( ApiVersion(6,false) -> apiV6 )
        //Rudder 3.2
      , ( ApiVersion(7,false) -> apiV7 )
    )
  }

  val apiDispatcher = APIDispatcher(apis, restExtractorService)

  lazy val configService: ReadConfigService with UpdateConfigService =
    new LDAPBasedConfigService(
        config
      , new LdapConfigRepository(rudderDit, rwLdap, ldapEntityMapper, eventLogRepository, stringUuidGenerator)
      , asyncWorkflowInfo
  )

  lazy val recentChangesService = new CachedNodeChangesServiceImpl(new NodeChangesServiceImpl(reportsRepository))

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
  private[this] lazy val ruleCategorySerialisation: RuleCategorySerialisation = new RuleCategorySerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
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
  private[this] lazy val propertySerialization: GlobalPropertySerialisation =
    new GlobalPropertySerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
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
    , propertySerialization
  )
  private[this] lazy val pathComputer = new PathComputerImpl(
      Constants.NODE_PROMISES_PARENT_DIR_BASE
    , Constants.NODE_PROMISES_PARENT_DIR
    , RUDDER_DIR_BACKUP
    , Constants.CFENGINE_COMMUNITY_PROMISES_PATH
    , Constants.CFENGINE_NOVA_PROMISES_PATH
  )
  private[this] lazy val baseUrlService: GetBaseUrlService = new DefaultBaseUrlService(BASE_URL)

  /*
   * For now, we don't want to query server other
   * than the accepted ones.
   */
  private[this] lazy val ditQueryDataImpl = new DitQueryData(acceptedNodesDitImpl, nodeDit)
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
  private[this] lazy val ruleCategoryUnserialisation = new RuleCategoryUnserialisationImpl
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

  private[this] lazy val entityMigration =
    new DefaultXmlEventLogMigration(
        xmlMigration_2_3
      , xmlMigration_3_4
      , xmlMigration_4_5
      , xmlMigration_5_6
    )
  private[this] lazy val xmlMigration_2_3 = new XmlMigration_2_3()
  private[this] lazy val xmlMigration_3_4 = new XmlMigration_3_4()
  private[this] lazy val xmlMigration_4_5 = new XmlMigration_4_5()
  private[this] lazy val xmlMigration_5_6 = new XmlMigration_5_6()

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
    new TechniqueParser(variableSpecParser,sectionSpecParser,systemVariableSpecService)
  }

  private[this] lazy val userPropertyServiceImpl = new StatelessUserPropertyService(
      configService.rudder_ui_changeMessage_enabled
    , configService.rudder_ui_changeMessage_mandatory
    , configService.rudder_ui_changeMessage_explanation
  )

  ////////////////////////////////////
  //  non pure services
  ////////////////////////////////////

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

    //create a demo default-directive-names.conf if none exists
    val defaultDirectiveNames = new File(RUDDER_DIR_TECHNIQUES, "default-directive-names.conf")
    if(!defaultDirectiveNames.exists) {
      FileUtils.writeStringToFile(defaultDirectiveNames, """
        |#
        |# This file contains the default name that a directive gets in Rudder UI creation pop-up.
        |# The file format is a simple key=value file, with key being the techniqueName
        |# or techniqueName/version and the value being the name to use.
        |# An empty value will lead to an empty default name.
        |# For a new Directive, we will try to lookup "TechniqueName/version" and if not
        |# available "TechniqueName" from this file. If neither key is available, the
        |# pop-up will use the actual Technique name as default.
        |# Don't forget to commit the file to have modifications seen by Rudder.
        |#
        |
        |# Default pattern for new directive from "userManagement" technique:
        |userManagement=User: <name> Login: <login>
        |# For userManagement version 2.0, prefer that pattern in new Directives:
        |userManagement/2.0: User 2.0 [LOGIN]
        |""".stripMargin)
    }

    val relativePath = RUDDER_DIR_TECHNIQUES.substring(gitSlash.size, RUDDER_DIR_TECHNIQUES.size)
    new GitTechniqueReader(
        techniqueParser
      , gitRevisionProviderImpl
      , gitRepo
      , "metadata.xml", "category.xml", "expected_reports.csv"
      , Some(relativePath)
      , "default-directive-names.conf"
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
    new InternalLDAPQueryProcessor(roLdap, acceptedNodesDitImpl, ditQueryDataImpl, ldapEntityMapper),
    nodeInfoServiceImpl
  )

  //we need a roLdap query checker for nodes in pending
  private[this] lazy val inventoryQueryChecker = new PendingNodesLDAPQueryChecker(new InternalLDAPQueryProcessor(roLdap, pendingNodesDitImpl, new DitQueryData(pendingNodesDitImpl, nodeDit), ldapEntityMapper))
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

  private[this] lazy val acceptHostnameAndIp: UnitAcceptInventory = new AcceptHostnameAndIp(
      "accept_new_server:check_hostname_unicity"
    , AcceptedInventory
    , queryProcessor
    , ditQueryDataImpl
    , psMngtService
  )

  private[this] lazy val historizeNodeStateOnChoice: UnitAcceptInventory with UnitRefuseInventory = new HistorizeNodeStateOnChoice(
      "accept_or_refuse_new_node:historize_inventory"
    , ldapFullInventoryRepository
    , diffRepos
    , PendingInventory
  )
  private[this] lazy val nodeGridImpl = new NodeGrid(ldapFullInventoryRepository, nodeInfoServiceImpl)

  private[this] lazy val modificationService = new ModificationService(logRepository,gitModificationRepository,itemArchiveManagerImpl,uuidGen)
  private[this] lazy val eventListDisplayerImpl = new EventListDisplayer(
      eventLogDetailsServiceImpl
    , logRepository
    , roLdapNodeGroupRepository
    , roLdapDirectiveRepository
    , nodeInfoServiceImpl
    , roLDAPRuleCategoryRepository
    , modificationService
    , personIdentServiceImpl
  )
  private[this] lazy val fileManagerImpl = new FileManager(UPLOAD_ROOT_DIRECTORY)
  private[this] lazy val databaseManagerImpl = new DatabaseManagerImpl(reportsRepositoryImpl, updateExpectedRepo)
  private[this] lazy val softwareInventoryDAO: ReadOnlySoftwareDAO = new ReadOnlySoftwareDAOImpl(inventoryDitService, roLdap, inventoryMapper)
  private[this] lazy val nodeSummaryServiceImpl = new NodeSummaryServiceImpl(inventoryDitService, inventoryMapper, roLdap)
  private[this] lazy val diffRepos: InventoryHistoryLogRepository =
    new InventoryHistoryLogRepository(HISTORY_INVENTORIES_ROOTDIR, new FullInventoryFileMarshalling(fullInventoryFromLdapEntries, inventoryMapper))
//  private[this] lazy val serverPolicyDiffService = new NodeConfigurationDiffService

  private[this] lazy val personIdentServiceImpl = new TrivialPersonIdentService
//  private[this] lazy val ldapNodeConfigurationMapper = new LDAPNodeConfigurationMapper(rudderDitImpl, acceptedNodesDitImpl, systemVariableSpecService, techniqueRepositoryImpl, variableBuilderService, rwLdap)
//  private[this] lazy val ldapNodeConfigurationRepository = new LDAPNodeConfigurationRepository(rwLdap, rudderDitImpl, ldapNodeConfigurationMapper)

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
  private[this] lazy val gitRuleCategoryArchiver: GitRuleCategoryArchiver = new GitRuleCategoryArchiverImpl(
      gitRepo
    , new File(RUDDER_DIR_GITROOT)
    , ruleCategorySerialisation
    , ruleCategoriesDirectoryName
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

      techniqueRepositoryImpl.registerCallback(new SaveDirectivesOnTechniqueCallback("SaveDirectivesOnTechniqueCallback", 100, directiveEditorServiceImpl, roLdapDirectiveRepository, repo))

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

  private[this] lazy val woLdapNodeRepository: WoNodeRepository = new WoLDAPNodeRepository(
      nodeDitImpl
    , ldapEntityMapper
    , rwLdap
    , logRepository
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

  private[this] lazy val roLDAPRuleCategoryRepository = {
    new RoLDAPRuleCategoryRepository(
        rudderDitImpl
      , roLdap
      , ldapEntityMapper
      , groupLibReadWriteMutex
    )
  }
  private[this] lazy val woLDAPRuleCategoryRepository = {
    new WoLDAPRuleCategoryRepository(
        roLDAPRuleCategoryRepository
      , rwLdap
      , uuidGen
      , gitRuleCategoryArchiver
      , personIdentServiceImpl
      , RUDDER_AUTOARCHIVEITEMS
    )
  }

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
    , roLDAPRuleCategoryRepository
    , roLdapDirectiveRepository
    , roLdapNodeGroupRepository
    , roLDAPParameterRepository
    , woLDAPParameterRepository
    , gitRepo
    , gitRevisionProvider
    , gitRuleArchiver
    , gitRuleCategoryArchiver
    , gitActiveTechniqueCategoryArchiver
    , gitActiveTechniqueArchiver
    , gitNodeGroupArchiver
    , gitParameterArchiver
    , parseRules
    , ParseActiveTechniqueLibrary
    , parseGlobalParameter
    , parseRuleCategories
    , importTechniqueLibrary
    , parseGroupLibrary
    , importGroupLibrary
    , importRuleCategoryLibrary
    , logRepository
    , asyncDeploymentAgentImpl
    , gitModificationRepository
  )

  private[this] lazy val globalComplianceModeService : ComplianceModeService =
    new ComplianceModeServiceImpl(
        configService.rudder_compliance_mode_name _
      , configService.rudder_compliance_heartbeatPeriod
    )
  private[this] lazy val globalAgentRunService : AgentRunIntervalService =
    new AgentRunIntervalServiceImpl(
        nodeInfoServiceImpl
      , () => Full(configService.agent_run_interval)
      , configService.agent_run_start_hour
      , configService.agent_run_start_minute
      , configService.agent_run_splaytime
      , configService.rudder_compliance_heartbeatPeriod
    )

  private[this] lazy val systemVariableService: SystemVariableService = new SystemVariableServiceImpl(
      systemVariableSpecService
    , psMngtService
    , RUDDER_DIR_DEPENDENCIES
    , RUDDER_ENDPOINT_CMDB
    , RUDDER_COMMUNITY_PORT
    , RUDDER_DIR_SHARED_FILES_FOLDER
    , RUDDER_WEBDAV_USER
    , RUDDER_WEBDAV_PASSWORD
    , RUDDER_SYSLOG_PORT
    , RUDDER_DIR_GITROOT
    , RUDDER_SERVER_ROLES
    , configService.cfengine_server_denybadclocks _
    , configService.cfengine_server_skipidentify _
    , configService.cfengine_modified_files_ttl _
    , configService.cfengine_outputs_ttl _
    , configService.rudder_store_all_centralized_logs_in_file _
    , configService.send_server_metrics _
    , configService.rudder_syslog_protocol _
  )
  private[this] lazy val rudderCf3PromisesFileWriterService = new Cf3PromisesFileWriterServiceImpl(
      techniqueRepositoryImpl
    , pathComputer
    , new NodeConfigurationLoggerImpl(RUDDER_DEBUG_NODE_CONFIGURATION_PATH)
    , new PrepareTemplateVariablesImpl(techniqueRepositoryImpl, systemVariableSpecService)
    , RUDDER_COMMUNITY_CHECKPROMISES_COMMAND
    , RUDDER_NOVA_CHECKPROMISES_COMMAND
    , RUDDER_CFENGINE_RELOAD_SERVER_COMMAND
  )

  //must be here because of circular dependency if in techniqueRepository
  techniqueRepositoryImpl.registerCallback(new TechniqueAcceptationUpdater(
      "UpdatePTAcceptationDatetime"
    , 50
    , roLdapDirectiveRepository
    , woLdapDirectiveRepository
    , techniqueRepository
    , uuidGen
  ))

  private[this] lazy val techniqueRepositoryImpl = {
    val service = new TechniqueRepositoryImpl(
        techniqueReader,
      Seq(),
      uuidGen
    )
    service
  }
  private[this] lazy val interpolationCompiler = new InterpolatedValueCompilerImpl()
  private[this] lazy val ruleValService: RuleValService = new RuleValServiceImpl(interpolationCompiler)

  private[this] lazy val psMngtService: PolicyServerManagementService = new PolicyServerManagementServiceImpl(
    roLdapDirectiveRepository, woLdapDirectiveRepository)
  private[this] lazy val historizationService = new HistorizationServiceImpl(historizationJdbcRepository)

  private[this] lazy val asyncDeploymentAgentImpl: AsyncDeploymentAgent = {
    val deploymentService = {
      new PromiseGenerationServiceImpl(
          roLdapRuleRepository
        , woLdapRuleRepository
        , ruleValService
        , systemVariableService
        , nodeConfigurationServiceImpl
        , nodeInfoServiceImpl
        , licenseRepository
        , updateExpectedReports
        , historizationService
        , roNodeGroupRepository
        , roDirectiveRepository
        , ruleApplicationStatusImpl
        , roParameterServiceImpl
        , interpolationCompiler
        , ldapFullInventoryRepository
        , globalComplianceModeService
        , globalAgentRunService
        , reportingServiceImpl
        , rudderCf3PromisesFileWriterService
        , configService.agent_run_interval
        , configService.agent_run_splaytime
        , configService.agent_run_start_hour
        , configService.agent_run_start_minute
   )}
    val agent = new AsyncDeploymentAgent(
        deploymentService
      , eventLogDeploymentServiceImpl
      , deploymentStatusSerialisation)
    techniqueRepositoryImpl.registerCallback(
        new DeployOnTechniqueCallback("DeployOnPTLibUpdate", 1000, agent)
    )
    agent
  }

  private[this] lazy val newNodeManagerImpl = {
    //the sequence of unit process to accept a new inventory
    val unitAcceptors =
      historizeNodeStateOnChoice ::
      acceptNodeAndMachineInNodeOu ::
      acceptInventory ::
      acceptHostnameAndIp ::
      Nil

    //the sequence of unit process to refuse a new inventory
    val unitRefusors =
      historizeNodeStateOnChoice ::
      unitRefuseGroup ::
      acceptNodeAndMachineInNodeOu ::
      acceptInventory ::
      Nil

    new NewNodeManagerImpl(
        roLdap
      , pendingNodesDitImpl
      , acceptedNodesDitImpl
      , nodeSummaryServiceImpl
      , ldapFullInventoryRepository
      , unitAcceptors
      , unitRefusors
      , inventoryHistoryLogRepository
      , eventLogRepository
      , dyngroupUpdaterBatch
      , List(nodeInfoServiceImpl)
    )
  }

  private[this] lazy val nodeConfigurationServiceImpl: NodeConfigurationService = new NodeConfigurationServiceImpl(
      rudderCf3PromisesFileWriterService
    , new LdapNodeConfigurationCacheRepository(rudderDit, rwLdap)
  )
//  private[this] lazy val licenseService: NovaLicenseService = new NovaLicenseServiceImpl(licenseRepository, ldapNodeConfigurationRepository, RUDDER_DIR_LICENSESFOLDER)
  private[this] lazy val reportingServiceImpl = new CachedReportingServiceImpl(
      new ReportingServiceImpl(
          findExpectedRepo
        , reportsRepositoryImpl
        , roAgentRunsRepository
        , findExpectedRepo
        , globalAgentRunService
        , globalComplianceModeService.getGlobalComplianceMode _
      )
    , nodeInfoServiceImpl
  )

  private[this] lazy val updateExpectedReports = new ExpectedReportsUpdateImpl(
      updateExpectedRepo
    , updateExpectedRepo
  )
  private[this] lazy val pgIn = new PostgresqlInClause(70)
  private[this] lazy val findExpectedRepo = new FindExpectedReportsJdbcRepository(jdbcTemplate, pgIn)
  private[this] lazy val updateExpectedRepo = new UpdateExpectedReportsJdbcRepository(jdbcTemplate, transactionManager, findExpectedRepo, findExpectedRepo)
  private[this] lazy val reportsRepositoryImpl = new ReportsJdbcRepository(jdbcTemplate)
  private[this] lazy val dataSourceProvider = new RudderDatasourceProvider(RUDDER_JDBC_DRIVER, RUDDER_JDBC_URL, RUDDER_JDBC_USERNAME, RUDDER_JDBC_PASSWORD, RUDDER_JDBC_MAX_POOL_SIZE)
  private[this] lazy val squerylDatasourceProvider = new SquerylConnectionProvider(dataSourceProvider.datasource)
  private[this] lazy val jdbcTemplate = {
    val template = new org.springframework.jdbc.core.JdbcTemplate(dataSourceProvider.datasource)
    template
  }

  private[this] lazy val transactionManager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSourceProvider.datasource)

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
  private[this] lazy val parseRuleCategories : ParseRuleCategories = new GitParseRuleCategories(
      ruleCategoryUnserialisation
    , gitRepo
    , entityMigration
    , ruleCategoriesDirectoryName
  )
  private[this] lazy val importGroupLibrary : ImportGroupLibrary = new ImportGroupLibraryImpl(
     rudderDitImpl
   , rwLdap
   , ldapEntityMapper
   , groupLibReadWriteMutex
  )
  private[this] lazy val importRuleCategoryLibrary : ImportRuleCategoryLibrary = new ImportRuleCategoryLibraryImpl(
     rudderDitImpl
   , rwLdap
   , ldapEntityMapper
   , groupLibReadWriteMutex
  )
  private[this] lazy val eventLogDeploymentServiceImpl = new EventLogDeploymentService(logRepository, eventLogDetailsServiceImpl)
  private[this] lazy val nodeInfoServiceImpl = new NodeInfoServiceCachedImpl(
      roLdap
    , nodeDitImpl
    , acceptedNodesDitImpl
    , removedNodesDitImpl
    , ldapEntityMapper
  )
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
    Seq(
        LDAPConstants.A_NAME
      , LDAPConstants.A_NODE_UUID
      , LDAPConstants.A_MANUFACTURER
      , LDAPConstants.A_LIST_OF_IP
    ),
    //serverAttributes
    Seq(
        LDAPConstants.A_HOSTNAME
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
    , RUDDER_BATCH_REPORTSCLEANER_DELETE_TTL
    , RUDDER_BATCH_REPORTSCLEANER_ARCHIVE_TTL
    , cleanFrequency
  )}

  private[this] lazy val techniqueLibraryUpdater = new CheckTechniqueLibrary(
      techniqueRepositoryImpl
    , asyncDeploymentAgent
    , uuidGen
    , RUDDER_BATCH_TECHNIQUELIBRARY_UPDATEINTERVAL
  )

  private[this] lazy val jsTreeUtilServiceImpl = new JsTreeUtilService(roLdapDirectiveRepository, techniqueRepositoryImpl)
  private[this] lazy val removeNodeServiceImpl = new RemoveNodeServiceImpl(
        nodeDitImpl
      , rudderDitImpl
      , rwLdap
      , ldapEntityMapper
      , roLdapNodeGroupRepository
      , woLdapNodeGroupRepository
      , nodeInfoServiceImpl
      , ldapFullInventoryRepository
      , logRepository
      , nodeReadWriteMutex
      , nodeInfoServiceImpl
  )

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

  private[this] lazy val eventLogsMigration_4_5 = new EventLogsMigration_4_5(
      jdbcTemplate
    , new EventLogMigration_4_5(xmlMigration_4_5)
    , eventLogsMigration_3_4
  )

  private[this] lazy val controlXmlFileFormatMigration_4_5 = new ControlXmlFileFormatMigration_4_5(
      migrationEventLogRepository = migrationRepository
    , batchMigrators              = Seq(eventLogsMigration_4_5
                                      , new ChangeRequestsMigration_4_5(
                                          jdbcTemplate
                                        , new ChangeRequestMigration_4_5(xmlMigration_4_5)
                                      )
                                    )
    , previousMigrationController = Some(controlXmlFileFormatMigration_3_4)
  )

  private[this] lazy val eventLogsMigration_5_6 = new EventLogsMigration_5_6(
      jdbcTemplate
    , new EventLogMigration_5_6(xmlMigration_5_6)
    , eventLogsMigration_4_5
  )

  private[this] lazy val controlXmlFileFormatMigration_5_6 = new ControlXmlFileFormatMigration_5_6(
      migrationEventLogRepository = migrationRepository
    , batchMigrators              = Seq(eventLogsMigration_5_6
                                      , new ChangeRequestsMigration_5_6(
                                          jdbcTemplate
                                        , new ChangeRequestMigration_5_6(xmlMigration_5_6)
                                      )
                                    )
    , previousMigrationController = Some(controlXmlFileFormatMigration_4_5)
  )
  /**
   * *************************************************
   * Bootstrap check actions
   * **************************************************
   */

  private[this] lazy val ruleCategoriesDirectory = new File(new File(RUDDER_DIR_GITROOT),ruleCategoriesDirectoryName)

  private[this] lazy val allChecks = new SequentialImmediateBootStrapChecks(
      new CheckDIT(pendingNodesDitImpl, acceptedNodesDitImpl, removedNodesDitImpl, rudderDitImpl, rwLdap)
    , new CheckInitUserTemplateLibrary(
        rudderDitImpl, rwLdap, techniqueRepositoryImpl,
        roLdapDirectiveRepository, woLdapDirectiveRepository, uuidGen, asyncDeploymentAgentImpl) //new CheckDirectiveBusinessRules()
    , new CheckMigrationXmlFileFormat5_6(controlXmlFileFormatMigration_5_6)
    , new CheckInitXmlExport(itemArchiveManagerImpl, personIdentServiceImpl, uuidGen)
    , new CheckRootRuleCategoryExport (itemArchiveManager, ruleCategoriesDirectory,  personIdentServiceImpl, uuidGen)
    , new CheckMigrationDirectiveInterpolatedVariablesHaveRudderNamespace(roLdapDirectiveRepository, woLdapDirectiveRepository, uuidGen)
    // Check technique library reload needs to be achieved after modification in configuration (like migration of CFEngine variables)
    , new CheckTechniqueLibraryReload(
          techniqueRepositoryImpl
        , asyncDeploymentAgent
        , uuidGen
      )
    , new CheckSystemGroups (
          rudderDitImpl
        , roLdap
        , ldapEntityMapper
        , groupLibReadWriteMutex
        , woNodeGroupRepository
        , uuidGen
      )
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
        case predefinedField: PredefinedValuesVariableSpec => new ReadOnlyTextField(id)

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

  /*
   * Agent runs: we use a cache for them.
   */
  private[this] lazy val cachedAgentRunRepository = {
    val roRepo = new RoReportsExecutionJdbcRepository(jdbcTemplate, pgIn)
    new CachedReportsExecutionRepository(
        roRepo
      , new WoReportsExecutionSquerylRepository(squerylDatasourceProvider, roRepo )
    )
  }

  val updatesEntryJdbcRepository = new StatusUpdateSquerylRepository(squerylDatasourceProvider)

  val executionService = {
    val max   = if (RUDDER_REPORTS_EXECUTION_MAX_DAYS > 0) {
                  RUDDER_REPORTS_EXECUTION_MAX_DAYS
                } else {
                  logger.error("'rudder.aggregateReports.maxDays' property is not correctly set using 5 as default value, please check /opt/rudder/etc/rudder-web.properties")
                  5
                }

    new ReportsExecutionService(
      reportsRepository
    , woAgentRunsRepository
    , updatesEntryJdbcRepository
    , recentChangesService
    , reportingServiceImpl
    , max
    )
  }

 val aggregateReportScheduler = new FindNewReportsExecution(executionService,RUDDER_REPORTS_EXECUTION_INTERVAL)
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
