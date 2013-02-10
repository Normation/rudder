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
import org.springframework.core.io.ClassPathResource
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
import com.normation.rudder.migration.ControlEventLogsMigration_10_2
import com.normation.rudder.migration.EventLogsMigration_10_2
import com.normation.rudder.migration.DefaultXmlEventLogMigration
import com.normation.rudder.migration.XmlMigration_10_2
import com.normation.rudder.migration.EventLogMigration_10_2
import net.liftweb.common._
import com.normation.rudder.repository.jdbc.SquerylConnectionProvider
import com.normation.rudder.repository.squeryl._
import com.normation.rudder.repository._
import com.normation.rudder.services.modification.ModificationService

/**
 * Spring configuration for services
 */
@Configuration
@Import(Array(classOf[PropertyPlaceholderConfig], classOf[AppConfigAuth]))
class AppConfig extends Loggable {

  @Value("${ldap.host}")
  var SERVER = ""

  @Value("${ldap.port}")
  var PORT = 389

  @Value("${ldap.authdn}")
  var AUTH_DN = ""
  @Value("${ldap.authpw}")
  var AUTH_PW = ""

  @Value("${ldap.inventories.accepted.basedn}")
  var ACCEPTED_INVENTORIES_DN = ""

  @Value("${ldap.inventories.pending.basedn}")
  var PENDING_INVENTORIES_DN = ""

  @Value("${ldap.inventories.removed.basedn}")
  var REMOVED_INVENTORIES_DN = ""

  @Value("${ldap.inventories.software.basedn}")
  var SOFTWARE_INVENTORIES_DN = ""

  @Value("${ldap.rudder.base}")
  var RUDDER_DN = ""

  @Value("${ldap.node.base}")
  var NODE_DN = ""

  @Value("${bin.emergency.stop}")
  val startStopBinPath = ""

  @Value("${history.inventories.rootdir}")
  var INVENTORIES_HISTORY_ROOT_DIR = ""

  @Value("${upload.root.directory}")
  var UPLOAD_ROOT_DIRECTORY = ""

  @Value("${base.url}")
  var BASE_URL = ""

  @Value("${rudder.dir.backup}")
  var backupFolder = ""

  @Value("${rudder.dir.dependencies}")
  var toolsFolder = ""

  @Value("${rudder.dir.uploaded.file.sharing}")
  var sharesFolder = ""

  @Value("${rudder.dir.lock}")
  var lockFolder = ""

  @Value("${rudder.dir.shared.files.folder}")
  var sharedFilesFolder = ""

  @Value("${rudder.dir.licensesFolder}")
  var licensesFolder = ""
  @Value("${rudder.endpoint.cmdb}")
  var cmdbEndpoint = ""
  @Value("${rudder.webdav.user}")
  var webdavUser = ""
  @Value("${rudder.webdav.password}")
  var webdavPassword = ""
  @Value("${rudder.community.port}")
  var communityPort = ""

  @Value("${rudder.community.checkpromises.command}")
  var communityCheckPromises = ""
  @Value("${rudder.nova.checkpromises.command}")
  var novaCheckPromises = ""

  @Value("${rudder.jdbc.driver}")
  var jdbcDriver = ""
  @Value("${rudder.jdbc.url}")
  var jdbcUrl = ""
  @Value("${rudder.jdbc.username}")
  var jdbcUsername = ""
  @Value("${rudder.jdbc.password}")
  var jdbcPassword = ""

  @Value("${rudder.dir.gitRoot}")
  var gitRoot = ""

  @Value("${rudder.dir.techniques}")
  var policyPackages = ""

  @Value("${rudder.batch.dyngroup.updateInterval}")
  var dyngroupUpdateInterval = 60 //one hour

  @Value("${rudder.batch.techniqueLibrary.updateInterval}")
  var ptlibUpdateInterval = 60 * 5 //five minutes

  @Value("${rudder.batch.reportscleaner.archive.TTL}")
  var reportCleanerArchiveTTL = AutomaticReportsCleaning.defaultArchiveTTL

  @Value("${rudder.batch.reportscleaner.delete.TTL}")
  var reportCleanerDeleteTTL = AutomaticReportsCleaning.defaultDeleteTTL

  @Value("${rudder.batch.reportscleaner.frequency}")
  var reportCleanerFrequency = AutomaticReportsCleaning.defaultDay

  @Value("${rudder.batch.databasecleaner.runtime.hour}")
  var reportCleanerRuntimeHour = AutomaticReportsCleaning.defaultHour

  @Value("${rudder.batch.databasecleaner.runtime.minute}")
  var reportCleanerRuntimeMinute = AutomaticReportsCleaning.defaultMinute

  @Value("${rudder.batch.databasecleaner.runtime.day}")
  var reportCleanerRuntimeDay = "sunday"

  @Value("${rudder.batch.reports.logInterval}")
  var reportLogInterval = 1 //one minute

  @Value("${rudder.techniqueLibrary.git.refs.path}")
  var ptRefsPath = ""

  @Value("${rudder.autoArchiveItems}")
  var autoArchiveItems : Boolean = true

  @Value("${rudder.autoDeployOnModification}")
  var autoDeployOnModification : Boolean = true

  @Value("${rudder.ui.changeMessage.enabled}")
  var reasonFieldEnabled = false

  @Value("${rudder.ui.changeMessage.mandatory}")
  var reasonFieldMandatory = false

  @Value("${rudder.ui.changeMessage.explanation}")
  var reasonFieldExplanation = "Please enter a message explaining the reason for this change."

  @Value("${rudder.syslog.port}")
  var syslogPort : Int = 514

  val licensesConfiguration = "licenses.xml"
  val logentries = "logentries.xml"

  val prettyPrinter = new PrettyPrinter(120, 2)


  val userLibraryDirectoryName = "directives"

  val groupLibraryDirectoryName = "groups"

  val rulesDirectoryName = "rules"


  ////////////////////////////////////  
  //  pure services / No I/O at all //
  ////////////////////////////////////
    //actually, we tolerate I/O during 
    //service set-up (ex: read a config file)

  @Bean
  def acceptedNodesDit: InventoryDit = new InventoryDit(ACCEPTED_INVENTORIES_DN, SOFTWARE_INVENTORIES_DN, "Accepted inventories")

  @Bean
  def pendingNodesDit: InventoryDit = new InventoryDit(PENDING_INVENTORIES_DN, SOFTWARE_INVENTORIES_DN, "Pending inventories")

  @Bean
  def removedNodesDit = new InventoryDit(REMOVED_INVENTORIES_DN,SOFTWARE_INVENTORIES_DN,"Removed Servers")

  @Bean
  def rudderDit: RudderDit = new RudderDit(RUDDER_DN)

  @Bean
  def nodeDit: NodeDit = new NodeDit(NODE_DN)

  @Bean
  def inventoryDitService: InventoryDitService = new InventoryDitServiceImpl(pendingNodesDit, acceptedNodesDit,removedNodesDit)

  @Bean
  def uuidGen: StringUuidGenerator = new StringUuidGeneratorImpl
  
  @Bean
  def systemVariableSpecService = new SystemVariableSpecServiceImpl()

  @Bean
  def variableBuilderService: VariableBuilderService = new VariableBuilderServiceImpl()

  @Bean
  def ldapEntityMapper = new LDAPEntityMapper(rudderDit, nodeDit, acceptedNodesDit, queryParser)

  
  
  ///// items serializer - service that transforms items to XML /////

  @Bean
  def ruleSerialisation: RuleSerialisation =
    new RuleSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)

  @Bean
  def activeTechniqueCategorySerialisation: ActiveTechniqueCategorySerialisation =
    new ActiveTechniqueCategorySerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)

  @Bean
  def activeTechniqueSerialisation: ActiveTechniqueSerialisation =
    new ActiveTechniqueSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)

  @Bean
  def directiveSerialisation: DirectiveSerialisation =
    new DirectiveSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)

  @Bean
  def nodeGroupCategorySerialisation: NodeGroupCategorySerialisation =
    new NodeGroupCategorySerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)

  @Bean
  def nodeGroupSerialisation: NodeGroupSerialisation =
    new NodeGroupSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)

  @Bean
  def deploymentStatusSerialisation : DeploymentStatusSerialisation =
    new DeploymentStatusSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)


  @Bean
  def eventLogFactory = new EventLogFactoryImpl(
    ruleSerialisation,
    directiveSerialisation, 
    nodeGroupSerialisation, 
    activeTechniqueSerialisation)
  
  @Bean
  def pathComputer = new PathComputerImpl(
    ldapNodeConfigurationRepository,
    backupFolder)
  
  @Bean
  def baseUrlService: GetBaseUrlService = new DefaultBaseUrlService(BASE_URL)

  /*
   * For now, we don't want to query server other
   * than the accepted ones. 
   */
  @Bean
  def ditQueryData = new DitQueryData(acceptedNodesDit)

  @Bean
  def queryParser = new CmdbQueryParser with DefaultStringQueryParser with JsonQueryLexer {
    override val criterionObjects = Map[String, ObjectCriterion]() ++ ditQueryData.criteriaMap
  }

  @Bean
  def inventoryMapper: InventoryMapper = new InventoryMapper(inventoryDitService, pendingNodesDit, acceptedNodesDit, removedNodesDit)

  @Bean
  def fullInventoryFromLdapEntries: FullInventoryFromLdapEntries = new FullInventoryFromLdapEntriesImpl(inventoryDitService, inventoryMapper)
    
  @Bean
  def srvGrid = new SrvGrid
  
  @Bean 
  def ldapDiffMapper = new LDAPDiffMapper(ldapEntityMapper, queryParser)

  

  @Bean 
  def activeTechniqueCategoryUnserialisation = new ActiveTechniqueCategoryUnserialisationImpl

  @Bean
  def activeTechniqueUnserialisation = new ActiveTechniqueUnserialisationImpl
  
  @Bean
  def directiveUnserialisation = new DirectiveUnserialisationImpl
  
  @Bean
  def nodeGroupCategoryUnserialisation = new NodeGroupCategoryUnserialisationImpl
    
  @Bean
  def nodeGroupUnserialisation = new NodeGroupUnserialisationImpl(queryParser)
  
  @Bean
  def ruleUnserialisation = new RuleUnserialisationImpl
 
  @Bean
  def deploymentStatusUnserialisation = new DeploymentStatusUnserialisationImpl

  @Bean
  def xmlMigration_2_3 = new XmlMigration_2_3()
  
  @Bean
  def xmlMigration_10_2 = new XmlMigration_10_2()
  
  @Bean
  def entityMigration = new DefaultXmlEventLogMigration(xmlMigration_10_2, xmlMigration_2_3)
  
  
  @Bean 
  def eventLogDetailsService : EventLogDetailsService = new EventLogDetailsServiceImpl(
      queryParser
    , new DirectiveUnserialisationImpl
    , new NodeGroupUnserialisationImpl(queryParser)
    , new RuleUnserialisationImpl
    , new ActiveTechniqueUnserialisationImpl
    , new DeploymentStatusUnserialisationImpl
  )
  
  //////////////////////////////////////////////////////////
  //  non pure services that could perhaps be
  //////////////////////////////////////////////////////////

  // => rwLdap is only used to repair an error, that could be repaired elsewhere. 
  @Bean
  def ldapNodeConfigurationMapper = new LDAPNodeConfigurationMapper(rudderDit, acceptedNodesDit, systemVariableSpecService, techniqueRepository, variableBuilderService, rwLdap)

  // => because of systemVariableSpecService
  // metadata.xml parser
  @Bean
  def techniqueParser = {
    val variableSpecParser = new VariableSpecParser()
    new TechniqueParser(variableSpecParser,new SectionSpecParser(variableSpecParser),new Cf3PromisesFileTemplateParser,systemVariableSpecService)
  }
  
  
  
  
  ////////////////////////////////////  
  //  non pure services 
  ////////////////////////////////////  

  @Bean
  def userPropertyService = {
    val opt = new ReasonsMessageInfo(reasonFieldEnabled, reasonFieldMandatory, 
        reasonFieldExplanation)
    new UserPropertyServiceImpl(opt)
  }

  @Bean
  def ldapNodeConfigurationRepository = new LDAPNodeConfigurationRepository(rwLdap, rudderDit, ldapNodeConfigurationMapper)


  ///// items archivers - services that allows to transform items to XML and save then on a Git FS /////
  @Bean
  def gitModificationRepository = new GitModificationSquerylRepository(squerylDatasourceProvider)

  @Bean
  def gitRuleArchiver: GitRuleArchiver = new GitRuleArchiverImpl(
      gitRepo
    , new File(gitRoot)
    , ruleSerialisation
    , rulesDirectoryName
    , prettyPrinter
    , gitModificationRepository
  )

  @Bean
  def gitActiveTechniqueCategoryArchiver: GitActiveTechniqueCategoryArchiver = new GitActiveTechniqueCategoryArchiverImpl(
      gitRepo
    , new File(gitRoot)
    , activeTechniqueCategorySerialisation
    , userLibraryDirectoryName
    , prettyPrinter
    , gitModificationRepository
  )

  @Bean
  def gitActiveTechniqueArchiver: GitActiveTechniqueArchiverImpl = new GitActiveTechniqueArchiverImpl(
      gitRepo
    , new File(gitRoot)
    , activeTechniqueSerialisation
    , userLibraryDirectoryName
    , prettyPrinter
    , gitModificationRepository
  )

  @Bean
  def gitDirectiveArchiver: GitDirectiveArchiver = new GitDirectiveArchiverImpl(
      gitRepo
    , new File(gitRoot)
    , directiveSerialisation
    , userLibraryDirectoryName
    , prettyPrinter
    , gitModificationRepository
  )

  @Bean
  def gitNodeGroupArchiver: GitNodeGroupArchiver = new GitNodeGroupArchiverImpl(
      gitRepo
    , new File(gitRoot)
    , nodeGroupSerialisation
    , nodeGroupCategorySerialisation
    , groupLibraryDirectoryName
    , prettyPrinter
    , gitModificationRepository
  )

  @Bean
  def itemArchiveManager = new ItemArchiveManagerImpl(
      roLdapRuleRepository
    , rwLdapRuleRepository
    , roLdapDirectiveRepository
    , roLdapNodeGroupRepository
    , gitRepo
    , gitRevisionProvider
    , gitRuleArchiver
    , gitActiveTechniqueCategoryArchiver
    , gitActiveTechniqueArchiver
    , gitNodeGroupArchiver
    , parseRules
    , ParseActiveTechniqueLibrary
    , importTechniqueLibrary
    , parseGroupLibrary
    , importGroupLibrary
    , logRepository
    , asyncDeploymentAgent
    , gitModificationRepository
  )

  ///// end /////

  @Bean
  def logRepository = new EventLogJdbcRepository(jdbcTemplate,eventLogFactory)

  @Bean
  def inventoryLogEventService: InventoryEventLogService = new InventoryEventLogServiceImpl(logRepository)

  @Bean
  def licenseRepository = new LicenseRepositoryXML(licensesFolder + "/" + licensesConfiguration)

  @Bean
  def gitRepo = new GitRepositoryProviderImpl(gitRoot)

  @Bean
  def gitRevisionProvider = new LDAPGitRevisionProvider(rwLdap, rudderDit, gitRepo, ptRefsPath)

  @Bean
  def techniqueReader: TechniqueReader = {
    //find the relative path from gitRepo to the ptlib root
    val gitSlash = new File(gitRoot).getPath + "/"
    if(!policyPackages.startsWith(gitSlash)) {
      ApplicationLogger.error("The Technique library root directory must be a sub-directory of '%s', but it is configured to be: '%s'".format(gitRoot, policyPackages))
      throw new RuntimeException("The Technique library root directory must be a sub-directory of '%s', but it is configured to be: '%s'".format(gitRoot, policyPackages))
    }
    val relativePath = policyPackages.substring(gitSlash.size, policyPackages.size)
    new GitTechniqueReader(
        techniqueParser
      , gitRevisionProvider
      , gitRepo
      , "metadata.xml", "category.xml"
      , Some(relativePath)
    )
  }

  @Bean
  def systemVariableService: SystemVariableService = new SystemVariableServiceImpl(
      licenseRepository
    , new ParameterizedValueLookupServiceImpl(
        nodeInfoService
      , ruleTargetService
      , roLdapRuleRepository
      , ruleValService
      )
    , systemVariableSpecService
    , nodeInfoService
    , toolsFolder
    , cmdbEndpoint
    , communityPort
    , sharedFilesFolder
    , webdavUser
    , webdavPassword
    , syslogPort
  )

  @Bean
  def rudderCf3PromisesFileWriterService = new RudderCf3PromisesFileWriterServiceImpl(
    techniqueRepository,
    pathComputer,
    ldapNodeConfigurationRepository,
    nodeInfoService,
    licenseRepository,
    reportingService,
    systemVariableSpecService,
    systemVariableService,
    toolsFolder,
    sharesFolder,
    cmdbEndpoint,
    communityPort,
    communityCheckPromises,
    novaCheckPromises)

  //must be here because of cirular dependency if in techniqueRepository
  @Bean def techniqueAcceptationDatetimeUpdater: TechniquesLibraryUpdateNotification = {
    val callback = new TechniqueAcceptationDatetimeUpdater("UpdatePTAcceptationDatetime", roLdapDirectiveRepository, rwLdapDirectiveRepository)
    techniqueRepository.registerCallback(callback)
    callback
  }

  @Bean
  def techniqueRepository = {
    val service = new TechniqueRepositoryImpl(
        techniqueReader
      , Seq()
      , uuidGen
    )
    service.registerCallback(new LogEventOnTechniqueReloadCallback("LogEventOnPTLibUpdate", logRepository))
    service
  }

  @Bean
  def nodeConfigurationService: NodeConfigurationService = new NodeConfigurationServiceImpl(
    rudderCf3PromisesFileWriterService,
    ldapNodeConfigurationRepository,
    techniqueRepository,
    lockFolder)

  @Bean
  def licenseService: NovaLicenseService = new NovaLicenseServiceImpl(licenseRepository, ldapNodeConfigurationRepository, licensesFolder)

  @Bean
  def reportingService: ReportingService = new ReportingServiceImpl(ruleTargetService,
      configurationExpectedRepo, reportsRepository, techniqueRepository)

  @Bean
  def configurationExpectedRepo = new com.normation.rudder.repository.jdbc.RuleExpectedReportsJdbcRepository(jdbcTemplate)

  @Bean
  def reportsRepository = new com.normation.rudder.repository.jdbc.ReportsJdbcRepository(jdbcTemplate)

  @Bean
  def dataSourceProvider = new RudderDatasourceProvider(jdbcDriver, jdbcUrl, jdbcUsername, jdbcPassword)

  @Bean
  def squerylDatasourceProvider = new SquerylConnectionProvider(dataSourceProvider.datasource)

  @Bean
  def jdbcTemplate = {
    val template = new org.springframework.jdbc.core.JdbcTemplate(dataSourceProvider.datasource)
    template
  }

  @Bean
  def historizationJdbcRepository = new HistorizationJdbcRepository(squerylDatasourceProvider)

  @Bean
  def startStopOrchestrator: StartStopOrchestrator = {
    if (!(new File(startStopBinPath)).exists)
      ApplicationLogger.error("The 'red button' program is not present at: '%s'. You will experience error when trying to use that functionnality".format(startStopBinPath))
    new SystemStartStopOrchestrator(startStopBinPath)
  }


  @Bean
  def roLdap =
    new ROPooledSimpleAuthConnectionProvider(
      host = SERVER,
      port = PORT,
      authDn = AUTH_DN,
      authPw = AUTH_PW,
      poolSize = 2)

  @Bean
  def rwLdap =
    new RWPooledSimpleAuthConnectionProvider(
      host = SERVER,
      port = PORT,
      authDn = AUTH_DN,
      authPw = AUTH_PW,
      poolSize = 2)

  //query processor for accepted nodes
  @Bean
  def queryProcessor = new AccepetedNodesLDAPQueryProcessor(
    nodeDit,
    new InternalLDAPQueryProcessor(roLdap, acceptedNodesDit, ditQueryData, ldapEntityMapper))

  //we need a roLdap query checker for nodes in pending
  @Bean
  def inventoryQueryChecker = new PendingNodesLDAPQueryChecker(new InternalLDAPQueryProcessor(roLdap, pendingNodesDit, new DitQueryData(pendingNodesDit), ldapEntityMapper))

  @Bean
  def dynGroupService: DynGroupService = new DynGroupServiceImpl(rudderDit, roLdap, ldapEntityMapper, inventoryQueryChecker)

  @Bean
  def ruleTargetService: RuleTargetService = new RuleTargetServiceImpl(
    roLdapNodeGroupRepository, nodeInfoService,
    Seq(
      new SpecialAllTargetUpits: UnitRuleTargetService,
      new SpecialAllTargetExceptPolicyServersTargetUpits(nodeInfoService),
      new PolicyServerTargetUpits(nodeInfoService),
      new GroupTargetUpits(roLdapNodeGroupRepository)),
    roLdap,
    rudderDit,
    ldapEntityMapper)

  @Bean
  def ldapFullInventoryRepository = new FullInventoryRepositoryImpl(inventoryDitService, inventoryMapper, rwLdap)
  
  @Bean
  def unitRefuseGroup: UnitRefuseInventory = new RefuseGroups(
    "refuse_node:delete_id_in_groups",
    roLdapNodeGroupRepository, rwLdapNodeGroupRepository)

  @Bean
  def acceptInventory: UnitAcceptInventory with UnitRefuseInventory = new AcceptInventory(
    "accept_new_server:inventory",
    pendingNodesDit,
    acceptedNodesDit,
    ldapFullInventoryRepository)

  @Bean
  def acceptNodeAndMachineInNodeOu: UnitAcceptInventory with UnitRefuseInventory = new AcceptFullInventoryInNodeOu(
    "accept_new_server:ou=node",
    nodeDit,
    rwLdap,
    ldapEntityMapper,
    PendingInventory)

  @Bean
  def acceptNodeRule: UnitAcceptInventory with UnitRefuseInventory = new AcceptNodeRule(
    "accept_new_server:add_system_configuration_rules",
    asyncDeploymentAgent,
    roLdapNodeGroupRepository,
    rwLdapNodeGroupRepository,
    ldapNodeConfigurationRepository,
    AcceptedInventory)

  @Bean
  def acceptHostnameAndIp: UnitAcceptInventory = new AcceptHostnameAndIp(
    "accept_new_server:check_hostname_unicity",
    AcceptedInventory,
    queryProcessor,
    ditQueryData
  )

  @Bean
  def addNodeToDynGroup: UnitAcceptInventory with UnitRefuseInventory = new AddNodeToDynGroup(
    "add_server_to_dyngroup",
    roLdapNodeGroupRepository,
    rwLdapNodeGroupRepository,
    dynGroupService,
    PendingInventory)

  @Bean
  def historizeNodeStateOnChoice: UnitAcceptInventory with UnitRefuseInventory = new HistorizeNodeStateOnChoice(
      "accept_or_refuse_new_node:historize_inventory"
    , ldapFullInventoryRepository
    , diffRepos
    , PendingInventory
  )

  @Bean
  def ruleValService: RuleValService = new RuleValServiceImpl(
    roLdapRuleRepository,
    roLdapDirectiveRepository,
    techniqueRepository,
    variableBuilderService)

  @Bean
  def psMngtService: PolicyServerManagementService = new PolicyServerManagementServiceImpl(
    roLdapDirectiveRepository, rwLdapDirectiveRepository, asyncDeploymentAgent)

  @Bean
  def historizationService = new HistorizationServiceImpl(
    historizationJdbcRepository,
    nodeInfoService,
    roLdapNodeGroupRepository,
    roLdapDirectiveRepository,
    techniqueRepository,
    roLdapRuleRepository)

  @Bean
  def asyncDeploymentAgent: AsyncDeploymentAgent = {
    val agent = new AsyncDeploymentAgent(new DeploymentServiceImpl(
          roLdapRuleRepository,
          rwLdapRuleRepository,
          ruleValService,
          new ParameterizedValueLookupServiceImpl(
            nodeInfoService,
            ruleTargetService,
            roLdapRuleRepository,
            ruleValService),
          systemVariableService,
          ruleTargetService,
          nodeConfigurationService,
          nodeInfoService,
          nodeConfigurationChangeDetectService,
          reportingService,
          historizationService)
      , eventLogDeploymentService
      , autoDeployOnModification
      , deploymentStatusSerialisation)
    techniqueRepository.registerCallback(
        new DeployOnTechniqueCallback("DeployOnPTLibUpdate", agent)
    )
    agent
  }

  @Bean
  def newNodeManager: NewNodeManager =
    new NewNodeManagerImpl(
      roLdap,
      pendingNodesDit, acceptedNodesDit,
      serverSummaryService,
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

  @Bean
  def nodeConfigurationChangeDetectService = new NodeConfigurationChangeDetectServiceImpl(roLdapDirectiveRepository)

  @Bean
  def serverGrid = new NodeGrid(ldapFullInventoryRepository)


  @Bean
  def modificationService = new ModificationService(logRepository,gitModificationRepository,itemArchiveManager,uuidGen)
  @Bean
  def eventListDisplayer = new EventListDisplayer(eventLogDetailsService, logRepository, roLdapNodeGroupRepository, roLdapDirectiveRepository, nodeInfoService,modificationService,personIdentService)

  @Bean
  def fileManager = new FileManager(UPLOAD_ROOT_DIRECTORY)

  @Bean
  def databaseManager = new DatabaseManagerImpl(reportsRepository)

  @Bean
  def softwareInventoryDAO: ReadOnlySoftwareDAO = new ReadOnlySoftwareDAOImpl(inventoryDitService, roLdap, inventoryMapper)

  @Bean
  def serverSummaryService = new NodeSummaryServiceImpl(inventoryDitService, inventoryMapper, roLdap)

  @Bean
  def diffRepos: InventoryHistoryLogRepository =
    new InventoryHistoryLogRepository(INVENTORIES_HISTORY_ROOT_DIR, new FullInventoryFileMarshalling(fullInventoryFromLdapEntries, inventoryMapper))

  @Bean
  def serverPolicyDiffService = new NodeConfigurationDiffService

  @Bean //trivial definition of the person ident service
  def personIdentService = new TrivialPersonIdentService

  ////////////// MUTEX FOR Ldap REPOS //////////////

  val uptLibReadWriteMutex = ScalaLock.java2ScalaRWLock(new java.util.concurrent.locks.ReentrantReadWriteLock(true))

  val groupLibReadWriteMutex = ScalaLock.java2ScalaRWLock(new java.util.concurrent.locks.ReentrantReadWriteLock(true))

  val nodeReadWriteMutex = ScalaLock.java2ScalaRWLock(new java.util.concurrent.locks.ReentrantReadWriteLock(true))



  @Bean
  def roLdapDirectiveRepository = new RoLDAPDirectiveRepository(
        rudderDit, roLdap, ldapEntityMapper, techniqueRepository, uptLibReadWriteMutex)

  @Bean
  def rwLdapDirectiveRepository = {
    val repo = new WoLDAPDirectiveRepository(
        roLdapDirectiveRepository
      , rwLdap
      , ldapDiffMapper
      , logRepository
      , uuidGen
      , gitDirectiveArchiver
      , gitActiveTechniqueArchiver
      , gitActiveTechniqueCategoryArchiver
      , personIdentService
      , autoArchiveItems
    )

    gitActiveTechniqueArchiver.uptModificationCallback += new UpdatePiOnActiveTechniqueEvent(
        gitDirectiveArchiver
      , techniqueRepository
      , roLdapDirectiveRepository
    )

    repo
  }

  @Bean
  def roLdapRuleRepository = new RoLDAPRuleRepository(rudderDit, roLdap, ldapEntityMapper)

  @Bean
  def rwLdapRuleRepository: WoRuleRepository = new WoLDAPRuleRepository(
      roLdapRuleRepository
    , rwLdap
    , ldapDiffMapper
    , roLdapNodeGroupRepository
    , logRepository
    , gitRuleArchiver
    , personIdentService
    , autoArchiveItems
  )

  @Bean
  def roLdapNodeGroupRepository = new RoLDAPNodeGroupRepository(
      rudderDit, roLdap, ldapEntityMapper, groupLibReadWriteMutex
  )

  @Bean
  def rwLdapNodeGroupRepository = new WoLDAPNodeGroupRepository(
      roLdapNodeGroupRepository
    , rwLdap 
    , ldapDiffMapper
    , uuidGen
    , logRepository
    , gitNodeGroupArchiver
    , personIdentService
    , autoArchiveItems
  )

  @Bean
  def parseRules : ParseRules = new GitParseRules(
      ruleUnserialisation
    , gitRepo
    , entityMigration
    , rulesDirectoryName
  )

  @Bean
  def ParseActiveTechniqueLibrary : ParseActiveTechniqueLibrary = new GitParseActiveTechniqueLibrary(
      activeTechniqueCategoryUnserialisation
    , activeTechniqueUnserialisation
    , directiveUnserialisation
    , gitRepo
    , entityMigration
    , userLibraryDirectoryName
  )

  @Bean
  def importTechniqueLibrary : ImportTechniqueLibrary = new ImportTechniqueLibraryImpl(
     rudderDit
   , rwLdap
   , ldapEntityMapper
   , uptLibReadWriteMutex
  )

  @Bean
  def parseGroupLibrary : ParseGroupLibrary = new GitParseGroupLibrary(
      nodeGroupCategoryUnserialisation
    , nodeGroupUnserialisation
    , gitRepo
    , entityMigration
    , groupLibraryDirectoryName
  )

  @Bean
  def importGroupLibrary : ImportGroupLibrary = new ImportGroupLibraryImpl(
     rudderDit
   , rwLdap
   , ldapEntityMapper
   , groupLibReadWriteMutex
  )

  @Bean
  def eventLogDeploymentService = new EventLogDeploymentService(logRepository, eventLogDetailsService)

  @Bean
  def nodeInfoService: NodeInfoService = new NodeInfoServiceImpl(nodeDit, rudderDit, acceptedNodesDit, roLdap, ldapEntityMapper)

  @Bean
  def dependencyAndDeletionService: DependencyAndDeletionService = new DependencyAndDeletionServiceImpl(
        roLdap
      , rudderDit
      , roLdapDirectiveRepository
      , rwLdapDirectiveRepository
      , rwLdapRuleRepository
      , rwLdapNodeGroupRepository
      , ldapEntityMapper
      , ruleTargetService
  )

  @Bean
  def quickSearchService: QuickSearchService = new QuickSearchServiceImpl(
    roLdap, nodeDit, acceptedNodesDit, ldapEntityMapper,
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

  @Bean
  def logDisplayer: LogDisplayer = new LogDisplayer(reportsRepository, roLdapDirectiveRepository, roLdapRuleRepository)

  @Bean
  def categoryHierarchyDisplayer: CategoryHierarchyDisplayer = new CategoryHierarchyDisplayer(roLdapNodeGroupRepository)

  @Bean
  def dyngroupUpdaterBatch: UpdateDynamicGroups = new UpdateDynamicGroups(
      dynGroupService
    , new DynGroupUpdaterServiceImpl(roLdapNodeGroupRepository, rwLdapNodeGroupRepository, queryProcessor)
    , asyncDeploymentAgent
    , uuidGen
    , dyngroupUpdateInterval
  )


  @Bean
  def dbCleaner: AutomaticReportsCleaning = {
    val cleanFrequency = AutomaticReportsCleaning.buildFrequency(
        reportCleanerFrequency
      , reportCleanerRuntimeMinute
      , reportCleanerRuntimeHour
      , reportCleanerRuntimeDay) match {
      case Full(freq) => freq
      case eb:EmptyBox => val fail = eb ?~! "automatic reports cleaner is not correct"
        val exceptionMsg = "configuration file (/opt/rudder/etc/rudder-webapp.conf) is not correctly set, cause is %s".format(fail.msg)
        throw new RuntimeException(exceptionMsg)
    }
    
    new AutomaticReportsCleaning(
      databaseManager
    , reportCleanerDeleteTTL
    , reportCleanerArchiveTTL
    , cleanFrequency
  )}
    
    
  @Bean
  def ptLibCron = new CheckTechniqueLibrary(
      techniqueRepository
    , asyncDeploymentAgent
    , uuidGen
    , ptlibUpdateInterval
  )

  @Bean
  def userSessionLogEvent = new UserSessionLogEvent(logRepository, uuidGen)

  @Bean
  def jsTreeUtilService = new JsTreeUtilService(roLdapDirectiveRepository, techniqueRepository)

  @Bean
  def removeNodeService = new RemoveNodeServiceImpl(
        nodeDit
      , rudderDit
      , rwLdap
      , ldapEntityMapper
      , roLdapNodeGroupRepository
      , rwLdapNodeGroupRepository
      , nodeConfigurationService
      , nodeInfoService
      , ldapFullInventoryRepository
      , logRepository
      , nodeReadWriteMutex)

  /**
   * Event log migration
   */



  @Bean
  def eventLogsMigration_10_2 = new EventLogsMigration_10_2(
      jdbcTemplate      = jdbcTemplate
    , eventLogMigration = new EventLogMigration_10_2(xmlMigration_10_2)
    , errorLogger       = MigrationLogger(2).defaultErrorLogger
    , successLogger     = MigrationLogger(2).defaultSuccessLogger
    , batchSize         = 1000
   )

  @Bean
  def eventLogsMigration_10_2_Management = new ControlEventLogsMigration_10_2(
      migrationEventLogRepository = new MigrationEventLogRepository(squerylDatasourceProvider)
    , eventLogsMigration_10_2
  )

  @Bean
  def eventLogsMigration_2_3 = new EventLogsMigration_2_3(
      jdbcTemplate      = jdbcTemplate
    , eventLogMigration = new EventLogMigration_2_3(xmlMigration_2_3)
    , eventLogsMigration_10_2 = eventLogsMigration_10_2
    , errorLogger       = MigrationLogger(3).defaultErrorLogger
    , successLogger     = MigrationLogger(3).defaultSuccessLogger
    , batchSize         = 1000
   )

  @Bean
  def eventLogsMigration_2_3_Management = new ControlEventLogsMigration_2_3(
          migrationEventLogRepository = new MigrationEventLogRepository(squerylDatasourceProvider)
        , eventLogsMigration_2_3
        , eventLogsMigration_10_2_Management
      )

  /**
   * *************************************************
   * Bootstrap check actions
   * **************************************************
   */
  @Bean
  def allChecks = new SequentialImmediateBootStrapChecks(
      new CheckDIT(pendingNodesDit, acceptedNodesDit, removedNodesDit, rudderDit, rwLdap)
    , new CheckRootNodeUnicity(ldapNodeConfigurationRepository)
    , new CheckSystemDirectives(rudderDit, roLdapRuleRepository)
    , new CheckInitUserTemplateLibrary(
        rudderDit, rwLdap, techniqueRepository,
        roLdapDirectiveRepository, rwLdapDirectiveRepository, uuidGen) //new CheckDirectiveBusinessRules()
    , new CheckMigrationEventLog2_3(eventLogsMigration_2_3_Management)
    , new CheckInitXmlExport(itemArchiveManager, personIdentService, uuidGen)
    , new CheckMigrationDirectiveInterpolatedVariablesHaveRudderNamespace(roLdapDirectiveRepository, rwLdapDirectiveRepository, uuidGen)
  )


  //////////////////////////////////////////////////////////////////////////////////////////
  ///////////////////////////////////////// REST ///////////////////////////////////////////
  //////////////////////////////////////////////////////////////////////////////////////////


  @Bean
  def restDeploy = new RestDeploy(asyncDeploymentAgent, uuidGen)

  @Bean
  def restDyngroup = new RestDyngroupReload(dyngroupUpdaterBatch)

  @Bean
  def restDptLibReload = new RestTechniqueReload(techniqueRepository, uuidGen)

  @Bean
  def restArchiving = new RestArchiving(itemArchiveManager,personIdentService, uuidGen)

  @Bean
  def restZipArchiving = new RestGetGitCommitAsZip(gitRepo)

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
        case input: InputVariableSpec => v.constraint.typeName.toLowerCase match {
          case str: String if str.startsWith(prefixSize) => new InputSizeField(id, str.substring(prefixSize.size))
          case "uploadedfile" => new UploadedFileField(UPLOAD_ROOT_DIRECTORY)(id)
          case "destinationfullpath" => default(id)
          case "date" => new DateField(frenchDateFormatter)(id)
          case "time" => new TimeField(frenchTimeFormatter)(id)
          case "perm" => new FilePermsField(id)
          case "boolean" => new CheckboxField(id)
          case "textarea" => new TextareaField(id)
          case _ => default(id)
        }
        case _ =>
          logger.error("Unexpected case : variable %s should not be displayed. Only select1, select or input can be displayed.".format(v.name))
          default(id)
      }
    }

    override def default(id: String) = new TextField(id)
  }

  @Bean def section2FieldService: Section2FieldService = {
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

  @Bean
  def directiveEditorService: DirectiveEditorService =
    new DirectiveEditorServiceImpl(techniqueRepository, section2FieldService)

  @Bean
  def reportDisplayer = new ReportDisplayer(
      roLdapRuleRepository
    , roLdapDirectiveRepository
    , reportingService
    , techniqueRepository)

  @Bean
  def propertyRepository = new RudderPropertiesSquerylRepository(
      squerylDatasourceProvider
    , reportsRepository )

  @Bean
  def automaticReportLogger = new AutomaticReportLogger(
      propertyRepository
    , reportsRepository
    , roLdapRuleRepository
    , roLdapDirectiveRepository
    , nodeInfoService
    , reportLogInterval )

  ////////////////////// Snippet plugins & extension register //////////////////////
  import com.normation.plugins.{ SnippetExtensionRegister, SnippetExtensionRegisterImpl }
  @Bean
  def snippetExtensionRegister: SnippetExtensionRegister = new SnippetExtensionRegisterImpl()

}
