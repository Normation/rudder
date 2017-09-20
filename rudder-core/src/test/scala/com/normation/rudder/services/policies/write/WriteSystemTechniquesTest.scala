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

package com.normation.rudder.services.policies.write

import java.io.File
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueResourceId
import com.normation.cfclerk.domain.TechniqueTemplate
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.services.impl.GitRepositoryProviderImpl
import com.normation.cfclerk.services.impl.GitTechniqueReader
import com.normation.cfclerk.services.impl.SimpleGitRevisionProvider
import com.normation.cfclerk.services.impl.SystemVariableSpecServiceImpl
import com.normation.cfclerk.services.impl.TechniqueRepositoryImpl
import com.normation.cfclerk.xmlparsers.SectionSpecParser
import com.normation.cfclerk.xmlparsers.TechniqueParser
import com.normation.cfclerk.xmlparsers.VariableSpecParser
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.FullCompliance
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.reports.SyslogUDP
import com.normation.rudder.repository.xml.LicenseRepositoryXML
import com.normation.rudder.services.policies.BundleOrder
import com.normation.rudder.services.policies.RudderServerRole
import com.normation.rudder.services.policies.SystemVariableServiceImpl
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationLoggerImpl
import com.normation.rudder.services.policies.nodeconfig.ParameterForConfiguration
import com.normation.rudder.services.servers.PolicyServerManagementService
import com.normation.utils.StringUuidGeneratorImpl
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.io.FileLinesContent
import org.specs2.matcher.ContentMatchers
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.AfterAll
import org.specs2.text.LinesContent
import net.liftweb.common.Box
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.policies.FullOtherTarget
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.policies.AllTargetExceptPolicyServers
import com.normation.rudder.domain.policies.PolicyServerTarget
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.policies.FullGroupTarget
import com.normation.rudder.domain.policies.AllTarget
import com.normation.rudder.domain.policies.FullRuleTargetInfo
import com.normation.rudder.domain.licenses.CfeEnterpriseLicense
import com.normation.templates.FillTemplatesService
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyModeOverrides
import com.normation.BoxSpecMatcher
import com.normation.rudder.services.policies.NodeConfigData
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.cfclerk.domain.Variable
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TrackerVariable
import com.normation.inventory.domain.AgentType
import com.normation.rudder.services.policies.NodeConfigData.{root, node1, rootNodeConfig}

/**
 * Details of tests executed in each instances of
 * the test.
 * To see values for gitRoot, ptLib, etc, see at the end
 * of that file.
 */
object TestSystemData {
  //make tests more similar than default rudder install
  val hookIgnore = """.swp, ~, .bak,
 .cfnew   , .cfsaved  , .cfedited, .cfdisabled, .cfmoved,
 .dpkg-old, .dpkg-dist, .dpkg-new, .dpkg-tmp,
 .disable , .disabled , _disable , _disabled,
 .ucf-old , .ucf-dist , .ucf-new ,
 .rpmnew  , .rpmsave  , .rpmorig""".split(",").map( _.trim).toList

  //just a little sugar to stop hurting my eyes with new File(blablab, plop)
  implicit class PathString(root: String) {
    def /(child: String) = new File(root, child)
  }
  implicit class PathString2(root: File) {
    def /(child: String) = new File(root, child)
  }

  //////////// init ////////////
  val abstractRoot = new File("/tmp/test-rudder-config-repo-" + DateTime.now.toString())
  abstractRoot.mkdirs()
  // config-repo will also be the git root, as a normal rudder
  val configurationRepositoryRoot = abstractRoot/"configuration-repository"

  //initialize config-repo content from our test/resources source
  FileUtils.copyDirectory( new File("src/test/resources/configuration-repository") , configurationRepositoryRoot)
  val repo = new GitRepositoryProviderImpl(configurationRepositoryRoot.getAbsolutePath)

  val EXPECTED_SHARE = configurationRepositoryRoot/"expected-share"

  val variableSpecParser = new VariableSpecParser
  val systemVariableServiceSpec = new SystemVariableSpecServiceImpl()
  val policyParser: TechniqueParser = new TechniqueParser(
      variableSpecParser
    , new SectionSpecParser(variableSpecParser)
    , systemVariableServiceSpec
  )
  val reader = new GitTechniqueReader(
                policyParser
              , new SimpleGitRevisionProvider("refs/heads/master", repo)
              , repo
              , "metadata.xml"
              , "category.xml"
              , "expected_reports.csv"
              , Some("techniques")
              , "default-directive-names.conf"
            )

  val techniqueRepository = new TechniqueRepositoryImpl(reader, Seq(), new StringUuidGeneratorImpl())
  val licenseRepo = new LicenseRepositoryXML("we_don_t_have_license")
  val logNodeConfig = new NodeConfigurationLoggerImpl(abstractRoot + "/lognodes")
  val policyServerManagement = new PolicyServerManagementService() {
    override def setAuthorizedNetworks(policyServerId:NodeId, networks:Seq[String], modId: ModificationId, actor:EventActor) = ???
    override def getAuthorizedNetworks(policyServerId:NodeId) : Box[Seq[String]] = Full(List("192.168.49.0/24"))
  }
  val systemVariableService = new SystemVariableServiceImpl(
      systemVariableServiceSpec
    , policyServerManagement
    , toolsFolder              = "tools_folder"
    , cmdbEndPoint             = "http://localhost:8080/endpoint/upload/"
    , communityPort            = 5309
    , sharedFilesFolder        = "/var/rudder/configuration-repository/shared-files"
    , webdavUser               = "rudder"
    , webdavPassword           = "rudder"
    , reportsDbUri             = "rudder"
    , reportsDbUser            = "rudder"
    , syslogPort               = 514
    , configurationRepository  = configurationRepositoryRoot.getAbsolutePath
    , serverRoles              = Seq(
                                     RudderServerRole("rudder-ldap"                   , "rudder.server-roles.ldap")
                                   , RudderServerRole("rudder-inventory-endpoint"     , "rudder.server-roles.inventory-endpoint")
                                   , RudderServerRole("rudder-db"                     , "rudder.server-roles.db")
                                   , RudderServerRole("rudder-relay-top"              , "rudder.server-roles.relay-top")
                                   , RudderServerRole("rudder-web"                    , "rudder.server-roles.web")
                                   , RudderServerRole("rudder-relay-promises-only"    , "rudder.server-roles.relay-promises-only")
                                   , RudderServerRole("rudder-cfengine-mission-portal", "rudder.server-roles.cfengine-mission-portal")
                                 )

    //denybadclocks and skipIdentify are runtime properties
    , getDenyBadClocks         = () => Full(true)
    , getSkipIdentify          = () => Full(false)
    // TTLs are runtime properties too
    , getModifiedFilesTtl             = () => Full(30)
    , getCfengineOutputsTtl           = () => Full(7)
    , getStoreAllCentralizedLogsInFile= () => Full(true)
    , getSendMetrics                  = () => Full(None)
    , getSyslogProtocol               = () => Full(SyslogUDP)
  )

  lazy val writeAllAgentSpecificFiles = new WriteAllAgentSpecificFiles()
  val prepareTemplateVariable = new PrepareTemplateVariablesImpl(
      techniqueRepository
    , systemVariableServiceSpec
    , new BuildBundleSequence(systemVariableServiceSpec, writeAllAgentSpecificFiles)
  )


  /*
   * We parametrize the output of file writing with a sub-directory name,
   * so that we can keep each write in it's own directory for debug.
   */
  def getPromiseWritter(label: String) = {

    //where the "/var/rudder/share" file is for tests:
    val SHARE = abstractRoot/s"share-${label}"

    val rootGeneratedPromisesDir = SHARE/"root"

    val pathComputer = new PathComputerImpl(
        SHARE.getParent + "/"
      , SHARE.getName
      , abstractRoot.getAbsolutePath + "/backup"
      , rootGeneratedPromisesDir.getAbsolutePath // community will go under our share
      , "/" // we don't want to use entreprise agent root path
    )

    val promiseWritter = new Cf3PromisesFileWriterServiceImpl(
        techniqueRepository
      , pathComputer
      , logNodeConfig
      , prepareTemplateVariable
      , new FillTemplatesService()
      , writeAllAgentSpecificFiles
      , "/we-don-t-want-hooks-here"
      , hookIgnore
    )

    (rootGeneratedPromisesDir, promiseWritter)
  }

  //////////// end init ////////////

  //an utility class for filtering file lines given a regex,
  //used in the file content matcher
  case class RegexFileContent(regex: List[String]) extends LinesContent[File] {
    val patterns = regex.map(_.r.pattern)

    override def lines(f: File): Seq[String] = {
      FileLinesContent.lines(f).filter { line => !patterns.exists { _.matcher(line).matches() } }
    }

    override def name(f: File) = FileLinesContent.name(f)
  }

  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // set up root node configuration
  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


  //a test node - CFEngine
  val nodeId = NodeId("c8813416-316f-4307-9b6a-ca9c109a9fb0")
  val cfeNode = node1.copy(node = node1.node.copy(id = nodeId, name = nodeId.value))

  val allNodesInfo_rootOnly = Map(root.id -> root)
  val allNodesInfo_cfeNode = Map(root.id -> root, cfeNode.id -> cfeNode)

  //the group lib
  val emptyGroupLib = FullNodeGroupCategory(
      NodeGroupCategoryId("/")
    , "/"
    , "root of group categories"
    , List()
    , List()
    , true
  )

  val groupLib = emptyGroupLib.copy(
      targetInfos = List(
          FullRuleTargetInfo(
              FullGroupTarget(
                  GroupTarget(NodeGroupId("a-group-for-root-only"))
                , NodeGroup(NodeGroupId("a-group-for-root-only")
                    , "Serveurs [€ðŋ] cassés"
                    , "Liste de l'ensemble de serveurs cassés à réparer"
                    , None
                    , true
                    , Set(NodeId("root"))
                    , true
                    , false
                  )
              )
              , "Serveurs [€ðŋ] cassés"
              , "Liste de l'ensemble de serveurs cassés à réparer"
              , true
              , false
            )
        , FullRuleTargetInfo(
              FullOtherTarget(PolicyServerTarget(NodeId("root")))
            , "special:policyServer_root"
            , "The root policy server"
            , true
            , true
          )
        , FullRuleTargetInfo(
            FullOtherTarget(AllTargetExceptPolicyServers)
            , "special:all_exceptPolicyServers"
            , "All groups without policy servers"
            , true
            , true
          )
        , FullRuleTargetInfo(
            FullOtherTarget(AllTarget)
            , "special:all"
            , "All nodes"
            , true
            , true
          )
      )
  )

  val globalAgentRun = AgentRunInterval(None, 5, 1, 0, 4)
  val globalComplianceMode = GlobalComplianceMode(FullCompliance, 15)

  val globalSystemVariables = systemVariableService.getGlobalSystemVariables(globalAgentRun).openOrThrowException("I should get global system variable in test!")

  val noLicense = Map.empty[NodeId, CfeEnterpriseLicense]

  //
  //root has 4 system directive, let give them some variables
  //
  val commonTechnique = techniqueRepository.get(TechniqueId(TechniqueName("common"), TechniqueVersion("1.0"))).getOrElse(throw new RuntimeException("Bad init for test"))
  def commonVariables(nodeId: NodeId, allNodeInfos: Map[NodeId, NodeInfo]) = {
     val spec = commonTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
     Seq(
       spec("ALLOWEDNETWORK").toVariable(Seq("192.168.0.0/16"))
     , spec("OWNER").toVariable(Seq(allNodeInfos(nodeId).localAdministratorAccountName))
     , spec("UUID").toVariable(Seq(nodeId.value))
     , spec("POLICYSERVER_ID").toVariable(Seq(allNodeInfos(nodeId).policyServerId.value))
     , spec("POLICYSERVER").toVariable(Seq(allNodeInfos(allNodeInfos(nodeId).policyServerId).hostname))
     , spec("POLICYSERVER_ADMIN").toVariable(Seq(allNodeInfos(allNodeInfos(nodeId).policyServerId).localAdministratorAccountName))
     ).map(v => (v.spec.name, v)).toMap
  }

  
  def policy (
      id : Cf3PolicyDraftId
    , technique   : Technique
    , variableMap : Map[String, Variable]
    , tracker     : TrackerVariable
    , rule        : BundleOrder
    , directive   : BundleOrder
    , system      : Boolean = true
    , policyMode  : Option[PolicyMode] = None
  ) = {
    Cf3PolicyDraft(
        id
      , technique
      , DateTime.now
      , variableMap 
      , tracker
      , 0
      , system
      , policyMode
      , AgentType.CfeCommunity
      , 2
      , rule
      , directive
      , Set()
    )
  }
  
  def common(nodeId: NodeId, allNodeInfos: Map[NodeId, NodeInfo]) = policy(
      Cf3PolicyDraftId(RuleId("hasPolicyServer-root"), DirectiveId("common-root"))
    , commonTechnique
    , commonVariables(nodeId, allNodeInfos)
    , commonTechnique.trackerVariableSpec.toVariable(Seq())
    , BundleOrder("Rudder system policy: basic setup (common)")
    , BundleOrder("Common")
    
  )

  val rolesTechnique = techniqueRepository.get(TechniqueId(TechniqueName("server-roles"), TechniqueVersion("1.0"))).getOrElse(throw new RuntimeException("Bad init for test"))
  val rolesVariables = {
     val spec = commonTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
     Seq(
       spec("ALLOWEDNETWORK").toVariable(Seq("192.168.0.0/16"))
     ).map(v => (v.spec.name, v)).toMap
  }

  val serverRole = policy(
      Cf3PolicyDraftId(RuleId("server-roles"), DirectiveId("server-roles-directive"))
    , rolesTechnique
    , rolesVariables
    , rolesTechnique.trackerVariableSpec.toVariable(Seq())
    , BundleOrder("Rudder system policy: Server roles")
    , BundleOrder("Server Roles")
  )

  val distributeTechnique = techniqueRepository.get(TechniqueId(TechniqueName("distributePolicy"), TechniqueVersion("1.0"))).getOrElse(throw new RuntimeException("Bad init for test"))
  val distributeVariables = {
     val spec = commonTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
     Seq(
       spec("ALLOWEDNETWORK").toVariable(Seq("192.168.0.0/16"))
     ).map(v => (v.spec.name, v)).toMap
  }
  val distributePolicy = policy(
      Cf3PolicyDraftId(RuleId("root-DP"), DirectiveId("root-distributePolicy"))
    , distributeTechnique
    , distributeVariables
    , distributeTechnique.trackerVariableSpec.toVariable(Seq())
    , BundleOrder("distributePolicy")
    , BundleOrder("Distribute Policy")
  )

  val inventoryTechnique = techniqueRepository.get(TechniqueId(TechniqueName("inventory"), TechniqueVersion("1.0"))).getOrElse(throw new RuntimeException("Bad init for test"))
  val inventoryVariables = {
     val spec = commonTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
     Seq(
       spec("ALLOWEDNETWORK").toVariable(Seq("192.168.0.0/16"))
     ).map(v => (v.spec.name, v)).toMap
  }
  val inventoryAll = policy(
      Cf3PolicyDraftId(RuleId("inventory-all"), DirectiveId("inventory-all"))
    , inventoryTechnique
    , inventoryVariables
    , inventoryTechnique.trackerVariableSpec.toVariable(Seq())
    , BundleOrder("Rudder system policy: daily inventory")
    , BundleOrder("Inventory")
  )

  //
  // Three user directives: clock management, rpm, package and a ncf one: Create_file
  //
  lazy val clockTechnique = techniqueRepository.get(TechniqueId(TechniqueName("clockConfiguration"), TechniqueVersion("3.0"))).getOrElse(throw new RuntimeException("Bad init for test"))
  lazy val clockVariables = {
     val spec = clockTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
     Seq(
         spec("CLOCK_FQDNNTP").toVariable(Seq("true"))
       , spec("CLOCK_HWSYNC_ENABLE").toVariable(Seq("true"))
       , spec("CLOCK_NTPSERVERS").toVariable(Seq("pool.ntp.org"))
       , spec("CLOCK_SYNCSCHED").toVariable(Seq("240"))
       , spec("CLOCK_TIMEZONE").toVariable(Seq("dontchange"))
     ).map(v => (v.spec.name, v)).toMap
  }
  lazy val clock = policy(
      Cf3PolicyDraftId(RuleId("rule1"), DirectiveId("directive1"))
    , clockTechnique
    , clockVariables
    , clockTechnique.trackerVariableSpec.toVariable(Seq())
    , BundleOrder("10. Global configuration for all nodes")
    , BundleOrder("10. Clock Configuration")
    , false
    , Some(PolicyMode.Enforce)
  )

  lazy val rpmTechnique = techniqueRepository.get(TechniqueId(TechniqueName("rpmPackageInstallation"), TechniqueVersion("7.0"))).getOrElse(throw new RuntimeException("Bad init for test"))
  lazy val rpmVariables = {
     val spec = rpmTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
     Seq(
         spec("RPM_PACKAGE_CHECK_INTERVAL").toVariable(Seq("5"))
       , spec("RPM_PACKAGE_POST_HOOK_COMMAND").toVariable(Seq(""))
       , spec("RPM_PACKAGE_POST_HOOK_RUN").toVariable(Seq("false"))
       , spec("RPM_PACKAGE_REDACTION").toVariable(Seq("add"))
       , spec("RPM_PACKAGE_REDLIST").toVariable(Seq("plop"))
       , spec("RPM_PACKAGE_VERSION").toVariable(Seq(""))
       , spec("RPM_PACKAGE_VERSION_CRITERION").toVariable(Seq("=="))
       , spec("RPM_PACKAGE_VERSION_DEFINITION").toVariable(Seq("default"))
     ).map(v => (v.spec.name, v)).toMap
  }
  lazy val rpm = policy(
      Cf3PolicyDraftId(RuleId("rule2"), DirectiveId("directive2"))
    , rpmTechnique
    , rpmVariables
    , rpmTechnique.trackerVariableSpec.toVariable(Seq())
    , BundleOrder("50. Deploy PLOP STACK")
    , BundleOrder("20. Install PLOP STACK main rpm")
    , false
    , Some(PolicyMode.Audit)
  )

  lazy val pkgTechnique = techniqueRepository.get(TechniqueId(TechniqueName("packageManagement"), TechniqueVersion("1.0"))).getOrElse(throw new RuntimeException("Bad init for test"))
  lazy val pkgVariables = {
     val spec = pkgTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
     Seq(
         spec("PACKAGE_LIST").toVariable(Seq("htop"))
       , spec("PACKAGE_STATE").toVariable(Seq("present"))
       , spec("PACKAGE_VERSION").toVariable(Seq("latest"))
       , spec("PACKAGE_VERSION_SPECIFIC").toVariable(Seq(""))
       , spec("PACKAGE_ARCHITECTURE").toVariable(Seq("default"))
       , spec("PACKAGE_ARCHITECTURE_SPECIFIC").toVariable(Seq(""))
       , spec("PACKAGE_MANAGER").toVariable(Seq("default"))
       , spec("PACKAGE_POST_HOOK_COMMAND").toVariable(Seq(""))
     ).map(v => (v.spec.name, v)).toMap
  }
  lazy val pkg = policy(
      Cf3PolicyDraftId(RuleId("ff44fb97-b65e-43c4-b8c2-0df8d5e8549f"), DirectiveId("16617aa8-1f02-4e4a-87b6-d0bcdfb4019f"))
    , pkgTechnique
    , pkgVariables
    , pkgTechnique.trackerVariableSpec.toVariable(Seq())
    , BundleOrder("60-rule-technique-std-lib")
    , BundleOrder("Package management.")
    , false
    , Some(PolicyMode.Enforce)
  )


  val ncf1Technique = techniqueRepository.get(TechniqueId(TechniqueName("Create_file"), TechniqueVersion("1.0"))).getOrElse(throw new RuntimeException("Bad init for test"))
  val ncf1Variables = {
     val spec = ncf1Technique.getAllVariableSpecs.map(s => (s.name, s)).toMap
     Seq(
         spec("expectedReportKey Directory create").toVariable(Seq("directory_create_/tmp/foo"))
       , spec("expectedReportKey File create").toVariable(Seq("file_create_/tmp/foo/bar"))
     ).map(v => (v.spec.name, v)).toMap
  }
  val ncf1 = policy(
      Cf3PolicyDraftId(RuleId("208716db-2675-43b9-ab57-bfbab84346aa"), DirectiveId("16d86a56-93ef-49aa-86b7-0d10102e4ea9"))
    , ncf1Technique
    , ncf1Variables
    , ncf1Technique.trackerVariableSpec.toVariable(Seq())
    , BundleOrder("50-rule-technique-ncf")
    , BundleOrder("Create a file")
    , false
    , Some(PolicyMode.Enforce)
  )

  //////////////

  // Allows override in policy mode, but default to audit
  val globalPolicyMode = GlobalPolicyMode(PolicyMode.Audit, PolicyModeOverrides.Always)

  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // actual tests
  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def getSystemVars(nodeInfo: NodeInfo, allNodeInfos: Map[NodeId, NodeInfo], allGroups: FullNodeGroupCategory) = {
    systemVariableService.getSystemVariables(
                         nodeInfo, allNodeInfos, allGroups, noLicense
                       , globalSystemVariables, globalAgentRun, globalComplianceMode
                     ).openOrThrowException("I should get system variable in tests")
  }

  /// For root, we are using the same system variable and base root node config
  // the root node configuration
  val baseRootNodeConfig = rootNodeConfig.copy(
      policyDrafts = Set(common(root.id, allNodesInfo_rootOnly), serverRole, distributePolicy, inventoryAll)
    , nodeContext  = getSystemVars(root, allNodesInfo_rootOnly, groupLib)
    , parameters   = Set(ParameterForConfiguration(ParameterName("rudder_file_edit_header"), "### Managed by Rudder, edit with care ###"))
  )

  val cfeNodeConfig = NodeConfigData.node1NodeConfig.copy(
      nodeInfo   = cfeNode
    , parameters = Set(ParameterForConfiguration(ParameterName("rudder_file_edit_header"), "### Managed by Rudder, edit with care ###"))
  )


  // A global list of files to ignore because variable content (like timestamp)
  def filterGeneratedFile(f: File): Boolean = {
    f.getName match {
        case "rudder_promises_generated" | "rudder-promises-generated" => false
        case _                                                         => true
    }
  }

  //write a config



}

trait TechniquesTest extends Specification with Loggable with BoxSpecMatcher with ContentMatchers with AfterAll {
  
  import TestSystemData._
   /*
   * put regex for line you don't want to be compared for difference
   */
  def ignoreSomeLinesMatcher(regex: List[String]): LinesPairComparisonMatcher[File, File] = {
    LinesPairComparisonMatcher[File, File]()(RegexFileContent(regex), RegexFileContent(regex))
  }

  //////////// set-up auto test cleaning ////////////
  override def afterAll(): Unit = {
    if(System.getProperty("tests.clean.tmp") != "false") {
      logger.info("Deleting directory " + abstractRoot.getAbsolutePath)
      FileUtils.deleteDirectory(abstractRoot)
    }
  }

  //////////// end set-up ////////////

  //utility to assert the content of a resource equals some string
  def assertResourceContent(id: TechniqueResourceId, isTemplate: Boolean, expectedContent: String) = {
    val ext = if(isTemplate) Some(TechniqueTemplate.templateExtension) else None
    reader.getResourceContent(id, ext) {
        case None => ko("Can not open an InputStream for " + id.toString)
        case Some(is) => IOUtils.toString(is) === expectedContent
      }
  }
  
  def compareWith(path: File, expectedPath: String, ignoreRegex: List[String] = Nil) = {
    /*
     * And compare them with expected, modulo the configId and the name
     * of the (temp) directory where we wrote them
     */
    path must haveSameFilesAs(EXPECTED_SHARE/expectedPath)
      .withFilter ( filterGeneratedFile _ )
      .withMatcher(ignoreSomeLinesMatcher(
           """.*rudder_node_config_id" string => .*"""
        :: """.*string => "/.*/configuration-repository.*"""
        :: """.*/test-rudder-config-repo-.*""" //config repos path with timestamp
        :: ignoreRegex
      ))
  }
  
}

@RunWith(classOf[JUnitRunner])
class WriteSystemTechniquesTest extends TechniquesTest{
  import TestSystemData._
  sequential

  "The test configuration-repository" should {
    "exists, and have a techniques/system sub-dir" in {
      val promiseFile = configurationRepositoryRoot/"techniques/system/common/1.0/promises.st"
      promiseFile.exists === true
    }
  }

  "A root node, with no node connected" should {
    def writeNodeConfigWithUserDirectives(promiseWritter: PolicyWriterService, userDrafts: Cf3PolicyDraft*) = {
      val rnc = baseRootNodeConfig.copy(
          policyDrafts = baseRootNodeConfig.policyDrafts ++ userDrafts
      )

      // Actually write the promise files for the root node
      promiseWritter.writeTemplate(root.id, Set(root.id), Map(root.id -> rnc), Map(root.id -> NodeConfigId("root-cfg-id")), Map(), globalPolicyMode, DateTime.now)
    }

    "correctly write the expected promises files with defauls installation" in {
      val (rootPath, writter) = getPromiseWritter("root-default")
      (writeNodeConfigWithUserDirectives(writter) mustFull) and
      compareWith(rootPath, "root-default-install")
    }

    "correctly write the expected promises files when 2 directives configured" in {
      val (rootPath, writter) = getPromiseWritter("root-2-directives")
      (writeNodeConfigWithUserDirectives(writter, clock, rpm) mustFull) and
      compareWith(rootPath, "root-with-two-directives",
           """.*rudder_common_report\("ntpConfiguration".*@@.*"""  //clock reports
        :: """.*add:default:==:.*"""                               //rpm reports
        :: Nil
      )
    }
  }

  "rudder-group.st template" should {

    def getRootNodeConfig(groupLib: FullNodeGroupCategory) = {
      // system variables for root
      val systemVariables = systemVariableService.getSystemVariables(
          root, allNodesInfo_rootOnly, groupLib, noLicense
        , globalSystemVariables, globalAgentRun, globalComplianceMode
      ).openOrThrowException("I should get system variable in tests")

      // the root node configuration
      rootNodeConfig.copy(
          policyDrafts = Set(common(root.id, allNodesInfo_rootOnly), serverRole, distributePolicy, inventoryAll)
        , nodeContext  = systemVariables
        , parameters   = Set(ParameterForConfiguration(ParameterName("rudder_file_edit_header"), "### Managed by Rudder, edit with care ###"))
      )

    }

    "correctly write nothing (no quotes) when no groups" in {
      val (rootPath, writter) = getPromiseWritter("group-1")

      // Actually write the promise files for the root node
      writter.writeTemplate(
          root.id
        , Set(root.id)
        , Map(root.id -> getRootNodeConfig(emptyGroupLib))
        , Map(root.id -> NodeConfigId("root-cfg-id"))
        , Map()
        , globalPolicyMode
        , DateTime.now
      ).openOrThrowException("Can not write template!")

      rootPath/"common/1.0/rudder-groups.cf" must haveSameLinesAs(EXPECTED_SHARE/"test-rudder-groups/no-group.cf")
    }

    "correctly write the classes and by_uuid array when groups" in {

      // make a group lib without "all" target so that it's actually different than the full one,
      // so that we don't have false positive due to bad directory cleaning
      val groupLib2 = groupLib.copy(targetInfos = groupLib.targetInfos.take(groupLib.targetInfos.size - 1))
      val rnc = getRootNodeConfig(groupLib2)

      // Actually write the promise files for the root node
      val (rootPath, writter) = getPromiseWritter("group-2")

      writter.writeTemplate(root.id, Set(root.id), Map(root.id -> rnc), Map(root.id -> NodeConfigId("root-cfg-id")), Map(), globalPolicyMode, DateTime.now).openOrThrowException("Can not write template!")

      rootPath/"common/1.0/rudder-groups.cf" must haveSameLinesAs(EXPECTED_SHARE/"test-rudder-groups/some-groups.cf")
    }
  }

  "A CFEngine node, with two directives" should {

    val rnc = rootNodeConfig.copy(
        policyDrafts = Set(common(root.id, allNodesInfo_cfeNode), serverRole, distributePolicy, inventoryAll)
      , nodeContext  = getSystemVars(root, allNodesInfo_cfeNode, groupLib)
      , parameters   = Set(ParameterForConfiguration(ParameterName("rudder_file_edit_header"), "### Managed by Rudder, edit with care ###"))
    )

    val cfeNC = cfeNodeConfig.copy(
        nodeInfo     = cfeNode
      , policyDrafts = Set(common(cfeNode.id, allNodesInfo_cfeNode), inventoryAll, pkg, ncf1)
      , nodeContext  = getSystemVars(cfeNode, allNodesInfo_cfeNode, groupLib)
    )

    "correctly get the expected policy files" in {
      val (rootPath, writter) = getPromiseWritter("cfe-node")
      // Actually write the promise files for the root node
      val writen = writter.writeTemplate(
            root.id
          , Set(root.id, cfeNode.id)
          , Map(root.id -> rnc, cfeNode.id -> cfeNC)
          , Map(root.id -> NodeConfigId("root-cfg-id"), cfeNode.id -> NodeConfigId("cfe-node-cfg-id"))
          , Map(), globalPolicyMode, DateTime.now
      )

      (writen mustFull) and
      compareWith(rootPath.getParentFile/cfeNode.id.value, "node-cfe-with-two-directives",
           """.*rudder_common_report\("ntpConfiguration".*@@.*"""  //clock reports
        :: """.*add:default:==:.*"""                               //rpm reports
        :: Nil
      )
    }
  }
}
