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
import com.normation.eventlog.EventLogService
import com.normation.cfclerk.services._
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.{ Bean, Configuration, Import, ImportResource }
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.{ ApplicationContext, ApplicationContextAware }
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.io.ClassPathResource
import com.normation.spring.ScalaApplicationContext
import com.normation.ldap.sdk._
import com.normation.inventory.ldap.core._
import com.normation.inventory.services._
import com.normation.rudder.domain._
import com.normation.rudder.web.services._
import com.normation.rudder.web.model._
import com.normation.utils.StringUuidGenerator
import com.normation.utils.StringUuidGeneratorImpl
import com.normation.rudder.repository.ldap._
import java.io.File
import org.joda.time.DateTime
import com.normation.rudder.services.log._
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
import com.normation.rudder.services.log.HistorizationServiceImpl
import com.normation.rudder.services.policies.DeployOnPolicyTemplateCallback


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

  @Value("${ldap.inventories.software.basedn}")
  var SOFTWARE_INVENTORIES_DN = ""

  @Value("${ldap.rudder.base}")
  var RUDDER_DN = ""

  @Value("${ldap.node.base}")
  var NODE_DN = ""

  @Value("${bin.emergency.stop}")
  val startStopBinPath = ""

  @Value("${ldif.tracelog.rootdir}")
  var ldifLogPath = ""

  @Value("${history.inventories.rootdir}")
  var INVENTORIES_HISTORY_ROOT_DIR = ""

  @Value("${upload.root.directory}")
  var UPLOAD_ROOT_DIRECTORY = ""

  @Value("${policy.copyfile.destination.dir}")
  var POLICY_COPY_FILE_DEST_DIR = ""

  @Value("${base.url}")
  var BASE_URL = ""

  @Value("${rudder.dir.policies}")
  var baseFolder = ""
  @Value("${rudder.dir.backup}")
  var backupFolder = ""
  @Value("${rudder.dir.dependencies}")
  var toolsFolder = ""
  @Value("${rudder.dir.sharing}")
  var sharesFolder = ""
  @Value("${rudder.dir.lock}")
  var lockFolder = ""

  @Value("${rudder.dir.shared.files.folder}")
  var sharedFilesFolder = ""

    
  @Value("${rudder.dir.licensesFolder}")
  var licensesFolder = ""
  @Value("${rudder.endpoint.cmdb}")
  var cmdbEndpoint = ""
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

  @Value("${rudder.dir.config}")
  var configFolder = ""
  @Value("${rudder.dir.policyPackages}")
  var policyPackages = ""

  @Value("${rudder.batch.dyngroup.updateInterval}")
  var dyngroupUpdateInterval = 60 //one hour
  
  @Value("${rudder.batch.ptlib.updateInterval}")
  var ptlibUpdateInterval = 60 * 5 //five minutes

  @Value("${rudder.ptlib.git.refs.path}")
  var ptRefsPath = ""

  val licensesConfiguration = "licenses.xml"
  val logentries = "logentries.xml"

  // policy.xml parser
  @Bean
  def policyParser = {
    val variableSpecParser = new VariableSpecParser()
    new PolicyParser(variableSpecParser,new SectionSpecParser(variableSpecParser),new TmlParser,systemVariableSpecService)
  }

  @Bean
  def systemVariableSpecService = new SystemVariableSpecServiceImpl()

  @Bean
  def variableBuilderService: VariableBuilderService = new VariableBuilderServiceImpl()

  @Bean
  def ldapEntityMapper = new LDAPEntityMapper(rudderDit, nodeDit, acceptedServersDit, queryParser)

  @Bean
  def ldapNodeConfigurationMapper = new LDAPNodeConfigurationMapper(rudderDit, acceptedServersDit, systemVariableSpecService, policyPackageService, variableBuilderService, ldap)

  @Bean
  def serverRepository = new LDAPNodeConfigurationRepository(ldap, rudderDit, ldapNodeConfigurationMapper)

  @Bean
  def logRepository = new EventLogJdbcRepository(jdbcTemplate)

  @Bean
  def logService = new EventLogServiceImpl(logRepository)

  @Bean
  def inventoryLogEventService: InventoryEventLogService = new InventoryEventLogServiceImpl(logRepository)

  @Bean
  def licenseRepository = new LicenseRepositoryXML(licensesFolder + "/" + licensesConfiguration)

  @Bean
  def pathComputer = new PathComputerImpl(
    serverRepository,
    baseFolder,
    backupFolder)

  @Bean
  def gitRepo = new GitRepositoryProviderImpl(policyPackages)

  @Bean
  def policyPackagesReader: PolicyPackagesReader = new GitPolicyPackagesReader(
    policyParser, new LDAPGitRevisionProvider(ldap, rudderDit, gitRepo, ptRefsPath), gitRepo, "policy.xml", "category.xml")

  @Bean
  def systemVariableService: SystemVariableService = new SystemVariableServiceImpl(licenseRepository,
    new ParameterizedValueLookupServiceImpl(
      nodeInfoService,
      policyInstanceTargetService,
      ldapConfigurationRuleRepository,
      configurationRuleValService),
    systemVariableSpecService,
    nodeInfoService,
    baseFolder,
    toolsFolder,
    cmdbEndpoint,
    communityPort,
    sharedFilesFolder)

  @Bean
  def policyTranslator = new RudderPromiseWriterServiceImpl(
    policyPackageService,
    pathComputer,
    serverRepository,
    nodeInfoService,
    licenseRepository,
    reportingService,
    systemVariableSpecService,
    systemVariableService,
    baseFolder,
    backupFolder,
    toolsFolder,
    sharesFolder,
    cmdbEndpoint,
    communityPort,
    communityCheckPromises,
    novaCheckPromises)

  //must be here because of cirular dependency if in policyPackageService
  @Bean def policyTemplateAcceptationDatetimeUpdater: ReferenceLibraryUpdateNotification =
    new PolicyTemplateAcceptationDatetimeUpdater("UpdatePTAcceptationDatetime", ldapUserPolicyTemplateRepository)

  
  @Bean
  def policyPackageService = new PolicyPackageServiceImpl(
      policyPackagesReader
    , Seq(policyTemplateAcceptationDatetimeUpdater)
  )

  @Bean
  def serverService: NodeConfigurationService = new NodeConfigurationServiceImpl(
    policyTranslator,
    serverRepository,
    policyPackageService,
    logService,
    lockFolder)

  @Bean
  def licenseService: NovaLicenseService = new NovaLicenseServiceImpl(licenseRepository, serverRepository, licensesFolder)

  @Bean
  def reportingService: ReportingService = new ReportingServiceImpl(policyInstanceTargetService, 
      configurationExpectedRepo, reportsRepository, policyPackageService)

  @Bean
  def configurationExpectedRepo = new com.normation.rudder.repository.jdbc.ConfigurationExpectedReportsJdbcRepository(jdbcTemplate)

  @Bean
  def reportsRepository = new com.normation.rudder.repository.jdbc.ReportsJdbcRepository(jdbcTemplate)

  @Bean
  def dataSource = try {

    Class.forName(jdbcDriver);

    val pool = new BasicDataSource();
    pool.setDriverClassName(jdbcDriver)
    pool.setUrl(jdbcUrl)
    pool.setUsername(jdbcUsername)
    pool.setPassword(jdbcPassword)

    /* try to get the connection */
    val connection = pool.getConnection()
    connection.close()

    pool

  } catch {
    case e: Exception =>
      logger.error("Could not initialise the access to the database")
      throw e
  }

  @Bean
  def squerylInit = new SquerylInitializationService(dataSource)

  @Bean
  def jdbcTemplate = {
    val template = new org.springframework.jdbc.core.JdbcTemplate(dataSource)
    template
  }

  @Bean
  def historizationJdbcRepository = new HistorizationJdbcRepository(squerylInit)

  @Bean
  def baseUrlService: GetBaseUrlService = new DefaultBaseUrlService(BASE_URL)

  @Bean
  def startStopOrchestrator: StartStopOrchestrator = {
    if (!(new File(startStopBinPath)).exists)
      logger.error("The 'red button' program is not present at: '%s'. You will experience error when trying to use that functionnality".format(startStopBinPath))
    new SystemStartStopOrchestrator(startStopBinPath)
  }

  @Bean
  def acceptedServersDit: InventoryDit = new InventoryDit(ACCEPTED_INVENTORIES_DN, SOFTWARE_INVENTORIES_DN, "Accepted inventories")

  @Bean
  def pendingServersDit: InventoryDit = new InventoryDit(PENDING_INVENTORIES_DN, SOFTWARE_INVENTORIES_DN, "Pending inventories")

  @Bean
  def rudderDit: RudderDit = new RudderDit(RUDDER_DN)

  @Bean
  def nodeDit: NodeDit = new NodeDit(NODE_DN)

  @Bean
  def inventoryDitService: InventoryDitService = new InventoryDitServiceImpl(pendingServersDit, acceptedServersDit)

  @Bean
  def uuidGen: StringUuidGenerator = new StringUuidGeneratorImpl

  @Bean
  def ldap =
    new PooledSimpleAuthConnectionProvider(
      host = SERVER,
      port = PORT,
      authDn = AUTH_DN,
      authPw = AUTH_PW,
      poolSize = 5)

  ////////// LDAP Processing related thing //////////
  /*
   * For now, we don't want to query server other
   * than the accepted ones. 
   */
  @Bean
  def ditQueryData = new DitQueryData(acceptedServersDit)
  //query processor for accepted nodes
  @Bean
  def queryProcessor = new AccepetedNodesLDAPQueryProcessor(
    nodeDit,
    new InternalLDAPQueryProcessor(ldap, acceptedServersDit, ditQueryData, ldapEntityMapper))

  //onoy a LDAP query checker for nodes in pending
  @Bean
  def inventoryQueryChecker = new PendingNodesLDAPQueryChecker(new InternalLDAPQueryProcessor(ldap, pendingServersDit, new DitQueryData(pendingServersDit), ldapEntityMapper))

  @Bean
  def queryParser = new CmdbQueryParser with DefaultStringQueryParser with JsonQueryLexer {
    override val criterionObjects = Map[String, ObjectCriterion]() ++ ditQueryData.criteriaMap
  }

  @Bean
  def dynGroupService: DynGroupService = new DynGroupServiceImpl(rudderDit, ldap, ldapEntityMapper, inventoryQueryChecker)

  @Bean
  def policyInstanceTargetService: PolicyInstanceTargetService = new PolicyInstanceTargetServiceImpl(
    ldapNodeGroupRepository, nodeInfoService,
    Seq(
      new SpecialAllTargetUpits: UnitPolicyInstanceTargetService,
      new SpecialAllTargetExceptPolicyServersTargetUpits(nodeInfoService),
      new PolicyServerTargetUpits(nodeInfoService),
      new GroupTargetUpits(ldapNodeGroupRepository)),
    ldap,
    rudderDit,
    ldapEntityMapper)

  @Bean
  def inventoryMapper: InventoryMapper = new InventoryMapper(inventoryDitService, pendingServersDit, acceptedServersDit)

  @Bean
  def metaEntryManagement: LDAPFullInventoryRepository = new FullInventoryRepositoryImpl(inventoryDitService, inventoryMapper, ldap)
  @Bean
  def metaEntryService: FullInventoryFromLdapEntries = new FullInventoryFromLdapEntriesImpl(inventoryDitService, inventoryMapper)

  @Bean
  def unitRefuseGroup: UnitRefuseInventory = new RefuseGroups(
    "refuse_node:delete_id_in_groups",
    ldapNodeGroupRepository)

  @Bean
  def acceptInventory: UnitAcceptInventory with UnitRefuseInventory = new AcceptInventory(
    "accept_new_server:inventory",
    pendingServersDit,
    acceptedServersDit,
    metaEntryManagement)

  @Bean
  def acceptServerAndMachineInNodeOu: UnitAcceptInventory with UnitRefuseInventory = new AcceptFullInventoryInNodeOu(
    "accept_new_server:ou=node",
    nodeDit,
    ldap,
    ldapEntityMapper,
    PendingInventory)

  @Bean
  def acceptServerConfigurationRule: UnitAcceptInventory with UnitRefuseInventory = new AcceptServerConfigurationRule(
    "accept_new_server:add_system_configuration_rules",
    asyncDeploymentAgent,
    ldapNodeGroupRepository,
    serverRepository,
    AcceptedInventory)

  @Bean
  def addServerToDynGroup: UnitAcceptInventory with UnitRefuseInventory = new AddServerToDynGroup(
    "add_server_to_dyngroup",
    ldapNodeGroupRepository,
    dynGroupService,
    PendingInventory)

  @Bean
  def configurationRuleValService: ConfigurationRuleValService = new ConfigurationRuleValServiceImpl(
    ldapConfigurationRuleRepository,
    ldapPolicyInstanceRepository,
    policyPackageService,
    variableBuilderService)

  @Bean
  def psMngtService: PolicyServerManagementService = new PolicyServerManagementServiceImpl(
    ldapPolicyInstanceRepository, asyncDeploymentAgent)

  @Bean
  def historizationService = new HistorizationServiceImpl(
    historizationJdbcRepository,
    nodeInfoService,
    ldapNodeGroupRepository,
    ldapPolicyInstanceRepository,
    policyPackageService,
    ldapConfigurationRuleRepository)

  @Bean
  def asyncDeploymentAgent: AsyncDeploymentAgent = {
    val agent = new AsyncDeploymentAgent(new DeploymentServiceImpl(
      ldapConfigurationRuleRepository,
      configurationRuleValService,
      new ParameterizedValueLookupServiceImpl(
        nodeInfoService,
        policyInstanceTargetService,
        ldapConfigurationRuleRepository,
        configurationRuleValService),
      systemVariableService,
      policyInstanceTargetService,
      serverService,
      nodeInfoService,
      nodeConfigurationChangeDetectService,
      reportingService,
      historizationService), logService)
    policyPackageService.registerCallback(
        new DeployOnPolicyTemplateCallback("DeployOnPTLibUpdate", agent)
    )
    agent
  }
  
  @Bean
  def newServerManager: NewServerManager =
    new NewServerManagerImpl(
      ldap,
      pendingServersDit, acceptedServersDit,
      serverSummaryService,
      metaEntryManagement,
      //the sequence of unit process to accept a new inventory
      addServerToDynGroup ::
        acceptServerAndMachineInNodeOu ::
        acceptInventory ::
        acceptServerConfigurationRule ::
        Nil,
      //the sequence of unit process to refuse a new inventory
      unitRefuseGroup ::
        acceptServerAndMachineInNodeOu ::
        acceptInventory ::
        acceptServerConfigurationRule ::
        Nil)

  @Bean
  def nodeConfigurationChangeDetectService = new NodeConfigurationChangeDetectServiceImpl(ldapUserPolicyTemplateRepository)

  @Bean
  def serverGrid = new ServerGrid(metaEntryManagement)
  
  @Bean
  def srvGrid = new SrvGrid

  @Bean
  def fileManager = new FileManager(UPLOAD_ROOT_DIRECTORY)

  @Bean
  def softwareInventoryDAO: ReadOnlySoftwareDAO = new ReadOnlySoftwareDAOImpl(inventoryDitService, ldap, inventoryMapper)
  
  @Bean
  def serverSummaryService = new ServerSummaryServiceImpl(inventoryDitService, inventoryMapper, ldap)

  @Bean
  def diffRepos: InventoryHistoryLogRepository =
    new InventoryHistoryLogRepository(INVENTORIES_HISTORY_ROOT_DIR, new FullInventoryFileMarshalling(metaEntryService, inventoryMapper))

  @Bean
  def serverPolicyDiffService = new ServerPolicyDiffService

  @Bean 
  def ldapDiffMapper = new LDAPDiffMapper(ldapEntityMapper, queryParser)
  
  @Bean
  def ldapUserPolicyTemplateCategoryRepository = new LDAPUserPolicyTemplateCategoryRepository(rudderDit, ldap, ldapEntityMapper)
  
  @Bean
  def ldapUserPolicyTemplateRepository = new LDAPUserPolicyTemplateRepository(rudderDit, ldap, ldapEntityMapper, uuidGen, ldapUserPolicyTemplateCategoryRepository)
  
  @Bean
  def ldapPolicyInstanceRepository = new LDAPPolicyInstanceRepository(rudderDit, ldap, ldapEntityMapper, ldapDiffMapper, ldapUserPolicyTemplateRepository,policyPackageService,logRepository)
  
  @Bean
  def ldapConfigurationRuleRepository: ConfigurationRuleRepository = new LDAPConfigurationRuleRepository(
    rudderDit, nodeDit, ldap, ldapEntityMapper, ldapDiffMapper,
    ldapNodeGroupRepository,
    policyPackageService,logRepository)

  @Bean
  def ldapGroupCategoryRepository = new LDAPGroupCategoryRepository(rudderDit, ldap, ldapEntityMapper)
  @Bean
  def ldapNodeGroupRepository = new LDAPNodeGroupRepository(rudderDit, ldap, ldapEntityMapper, ldapDiffMapper, uuidGen, logRepository)

  @Bean 
  def eventLogDetailsService : EventLogDetailsService = new EventLogDetailsServiceImpl(
      queryParser
    , new PolicyInstanceUnserialisationImpl
    , new NodeGroupUnserialisationImpl(queryParser)
    , new ConfigurationRuleUnserialisationImpl
  )
  
  @Bean
  def nodeInfoService: NodeInfoService = new NodeInfoServiceImpl(nodeDit, rudderDit, acceptedServersDit, ldap, ldapEntityMapper)

  @Bean
  def dependencyAndDeletionService: DependencyAndDeletionService = new DependencyAndDeletionServiceImpl(
        ldap
      , rudderDit
      , ldapPolicyInstanceRepository
      , ldapUserPolicyTemplateRepository
      , ldapConfigurationRuleRepository
      , ldapNodeGroupRepository
      , ldapEntityMapper
      , policyInstanceTargetService
  )

  @Bean
  def quickSearchService: QuickSearchService = new QuickSearchServiceImpl(
    ldap, nodeDit, acceptedServersDit, ldapEntityMapper,
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
  def logDisplayer: LogDisplayer = new com.normation.rudder.web.services.LogDisplayer(reportsRepository, ldapPolicyInstanceRepository, ldapConfigurationRuleRepository)

  @Bean
  def dyngroupUpdaterBatch: UpdateDynamicGroups = new UpdateDynamicGroups(
      dynGroupService
    , new DynGroupUpdaterServiceImpl(ldapNodeGroupRepository, queryProcessor)
    , asyncDeploymentAgent
    , dyngroupUpdateInterval
  )

  @Bean
  def ptLibCron = new CheckPolicyTemplateLibrary(
      policyPackageService
    , asyncDeploymentAgent
    , ptlibUpdateInterval
  )
  
  @Bean
  def userSessionLogEvent = new UserSessionLogEvent(logRepository)

  @Bean
  def jsTreeUtilService = new JsTreeUtilService(
    ldapUserPolicyTemplateCategoryRepository, ldapUserPolicyTemplateRepository, ldapPolicyInstanceRepository, policyPackageService)

  /**
   * *************************************************
   * Bootstrap check actions
   * **************************************************
   */
  @Bean
  def allChecks = new SequentialImmediateBootStrapChecks(
    new CheckDIT(pendingServersDit, acceptedServersDit, rudderDit, ldap),
    new CheckPolicyBindings(),
    new CheckRootServerUnicity(serverRepository),
    new CheckSystemPolicyInstances(rudderDit, ldapConfigurationRuleRepository),
    new CheckInitUserTemplateLibrary(
      rudderDit, ldap, policyPackageService,
      ldapUserPolicyTemplateCategoryRepository, ldapUserPolicyTemplateRepository) //new CheckPolicyInstanceBusinessRules()
      )

  ////////// Policy Editor and web fields //////////

  import com.normation.rudder.web.model._
  import org.joda.time.format.DateTimeFormat
  import java.util.Locale
  import com.normation.cfclerk.domain._

  val frenchDateFormatter = DateTimeFormat.forPattern("dd/MM/yyyy").withLocale(Locale.FRANCE)
  val frenchTimeFormatter = DateTimeFormat.forPattern("kk:mm:ss").withLocale(Locale.FRANCE)

  object FieldFactoryImpl extends PolicyFieldFactory {
    //only one field

    override def forType(v: VariableSpec, id: String): PolicyField = {
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
  def policyEditorService: PolicyEditorService =
    new PolicyEditorServiceImpl(policyPackageService, section2FieldService)

  @Bean
  def reportDisplayer = new ReportDisplayer(ldapConfigurationRuleRepository, reportingService)

  ////////////////////// Snippet plugins & extension register //////////////////////
  import com.normation.plugins.{ SnippetExtensionRegister, SnippetExtensionRegisterImpl }
  @Bean
  def snippetExtensionRegister: SnippetExtensionRegister = new SnippetExtensionRegisterImpl()

}
