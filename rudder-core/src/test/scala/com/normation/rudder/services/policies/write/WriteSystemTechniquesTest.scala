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
import com.normation.rudder.services.policies.NodeConfigData
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
import com.normation.rudder.domain.licenses.NovaLicense
import org.specs2.specification.AfterEach




/**
 * Details of tests executed in each instances of
 * the test.
 * To see values for gitRoot, ptLib, etc, see at the end
 * of that file.
 */
@RunWith(classOf[JUnitRunner])
class WriteSystemTechniqueTest extends Specification with Loggable with ContentMatchers with AfterAll with AfterEach {

  //just a little sugar to stop hurting my eyes with new File(blablab, plop)
  implicit class PathString(root: String) {
    def /(child: String) = new File(root, child)
  }
  implicit class PathString2(root: File) {
    def /(child: String) = new File(root, child)
  }

  //////////// init ////////////
  val abstractRoot = "/tmp/test-rudder-config-repo"/System.currentTimeMillis.toString
  abstractRoot.mkdirs()
  // config-repo will also be the git root, as a normal rudder
  val configurationRepositoryRoot = abstractRoot/"configuration-repository"

  //initialize config-repo content from our test/resources source
  FileUtils.copyDirectory( new File("src/test/resources/configuration-repository") , configurationRepositoryRoot)
  val repo = new GitRepositoryProviderImpl(configurationRepositoryRoot.getAbsolutePath)


  val EXPECTED_SHARE = configurationRepositoryRoot/"expected-share"

  //where the "/var/rudder/share" file is for tests:
  val SHARE = abstractRoot/"share"

  val rootGeneratedPromisesDir = SHARE/"root"

  val variableSpecParser = new VariableSpecParser
  val policyParser: TechniqueParser = new TechniqueParser(
      variableSpecParser,
      new SectionSpecParser(variableSpecParser),
      new SystemVariableSpecServiceImpl
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
  val pathComputer = new PathComputerImpl(
      SHARE.getParent
    , SHARE.getName
    , abstractRoot.getAbsolutePath + "/backup"
    , rootGeneratedPromisesDir.getAbsolutePath // community will go under our share
    , "/" // we don't want to use that one
  )
  val licenseRepo = new LicenseRepositoryXML("we_don_t_have_license")
  val logNodeConfig = new NodeConfigurationLoggerImpl(abstractRoot + "/lognodes")
  val policyServerManagement = new PolicyServerManagementService() {
    override def setAuthorizedNetworks(policyServerId:NodeId, networks:Seq[String], modId: ModificationId, actor:EventActor) = ???
    override def getAuthorizedNetworks(policyServerId:NodeId) : Box[Seq[String]] = Full(List("192.168.49.0/24"))
  }
  val systemVariableServiceSpec = new SystemVariableSpecServiceImpl()
  val systemVariableService = new SystemVariableServiceImpl(
      systemVariableServiceSpec
    , policyServerManagement
    , toolsFolder              = "tools_folder"
    , cmdbEndPoint             = "http://localhost:8080/endpoint/upload/"
    , communityPort            = 5309
    , sharedFilesFolder        = "/var/rudder/configuration-repository/shared-files"
    , webdavUser               = "rudder"
    , webdavPassword           = "rudder"
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

  val prepareTemplateVariable = new PrepareTemplateVariablesImpl(
      techniqueRepository
    , systemVariableServiceSpec
  )

  val promiseWritter = new Cf3PromisesFileWriterServiceImpl(
      techniqueRepository
    , pathComputer
    , logNodeConfig
    , prepareTemplateVariable
    , "/bin/true", "/bin/true", "/bin/true"
  )


  //////////// end init ////////////

  //////////// set-up auto test cleaning ////////////
  override def afterAll(): Unit = {
    logger.info("Deleting directory " + abstractRoot.getAbsoluteFile)
    FileUtils.deleteDirectory(abstractRoot)
  }
  override def after: Unit = {
    logger.info("Deleting directory " + rootGeneratedPromisesDir.getAbsoluteFile)
    FileUtils.deleteDirectory(rootGeneratedPromisesDir)
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



  //an utility class for filtering file lines given a regex,
  //used in the file content matcher
  case class RegexFileContent(regex: List[String]) extends LinesContent[File] {
    val patterns = regex.map(_.r.pattern)

    override def lines(f: File): Seq[String] = {
      FileLinesContent.lines(f).filter { line => !patterns.exists { _.matcher(line).matches() } }
    }

    override def name(f: File) = FileLinesContent.name(f)
  }

  /*
   * put regex for line you don't want to be compared for difference
   */
  def ignoreSomeLinesMatcher(regex: List[String]): LinesPairComparisonMatcher[File, File] = {
    LinesPairComparisonMatcher[File, File]()(RegexFileContent(regex), RegexFileContent(regex))
  }

  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // set up root node configuration
  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  import com.normation.rudder.services.policies.NodeConfigData.{root, rootNodeConfig}

  // only root in our "all nodes"
  val allNodesInfo = Map(root.id -> root)


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
                  GroupTarget(NodeGroupId("c8813416-316f-4307-9b6a-ca9c109a9fb0"))
                , NodeGroup(NodeGroupId("c8813416-316f-4307-9b6a-ca9c109a9fb0")
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

  val noLicense = Map.empty[NodeId, NovaLicense]

  //
  //root has 4 system directive, let give them some variables
  //
  val commonTechnique = techniqueRepository.get(TechniqueId(TechniqueName("common"), TechniqueVersion("1.0"))).getOrElse(throw new RuntimeException("Bad init for test"))
  val commonVariables = {
     val spec = commonTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
     Seq(
       spec("ALLOWEDNETWORK").toVariable(Seq("192.168.0.0/16"))
     , spec("OWNER").toVariable(Seq(root.localAdministratorAccountName))
     , spec("UUID").toVariable(Seq(root.id.value))
     , spec("POLICYSERVER_ID").toVariable(Seq(root.policyServerId.value))
     , spec("POLICYSERVER").toVariable(Seq(allNodesInfo(root.policyServerId).hostname))
     , spec("POLICYSERVER_ADMIN").toVariable(Seq(allNodesInfo(root.policyServerId).localAdministratorAccountName))
     ).map(v => (v.spec.name, v)).toMap
  }

  val common = Cf3PolicyDraft(
      id              = Cf3PolicyDraftId(RuleId("hasPolicyServer-root"), DirectiveId("common-root"))
    , technique       = commonTechnique
    , variableMap     = commonVariables
    , trackerVariable = commonTechnique.trackerVariableSpec.toVariable(Seq())
    , priority        = 0
    , serial          = 2
    , modificationDate= DateTime.now
    , ruleOrder       = BundleOrder("Rudder system policy: basic setup (common)")
    , directiveOrder  = BundleOrder("Common")
    , overrides       = Set()
  )

  val rolesTechnique = techniqueRepository.get(TechniqueId(TechniqueName("server-roles"), TechniqueVersion("1.0"))).getOrElse(throw new RuntimeException("Bad init for test"))
  val rolesVariables = {
     val spec = commonTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
     Seq(
       spec("ALLOWEDNETWORK").toVariable(Seq("192.168.0.0/16"))
     ).map(v => (v.spec.name, v)).toMap
  }

  val serverRole = Cf3PolicyDraft(
      id              = Cf3PolicyDraftId(RuleId("server-roles"), DirectiveId("server-roles-directive"))
    , technique       = rolesTechnique
    , variableMap     = rolesVariables
    , trackerVariable = rolesTechnique.trackerVariableSpec.toVariable(Seq())
    , priority        = 0
    , serial          = 2
    , modificationDate= DateTime.now
    , ruleOrder       = BundleOrder("Rudder system policy: Server roles")
    , directiveOrder  = BundleOrder("Server Roles")
    , overrides       = Set()
  )

  val distributeTechnique = techniqueRepository.get(TechniqueId(TechniqueName("distributePolicy"), TechniqueVersion("1.0"))).getOrElse(throw new RuntimeException("Bad init for test"))
  val distributeVariables = {
     val spec = commonTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
     Seq(
       spec("ALLOWEDNETWORK").toVariable(Seq("192.168.0.0/16"))
     ).map(v => (v.spec.name, v)).toMap
  }
  val distributePolicy = Cf3PolicyDraft(
      id              = Cf3PolicyDraftId(RuleId("root-DP"), DirectiveId("root-distributePolicy"))
    , technique       = distributeTechnique
    , variableMap     = distributeVariables
    , trackerVariable = distributeTechnique.trackerVariableSpec.toVariable(Seq())
    , priority        = 0
    , serial          = 2
    , modificationDate= DateTime.now
    , ruleOrder       = BundleOrder("distributePolicy")
    , directiveOrder  = BundleOrder("Distribute Policy")
    , overrides       = Set()
  )

  val inventoryTechnique = techniqueRepository.get(TechniqueId(TechniqueName("inventory"), TechniqueVersion("1.0"))).getOrElse(throw new RuntimeException("Bad init for test"))
  val inventoryVariables = {
     val spec = commonTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
     Seq(
       spec("ALLOWEDNETWORK").toVariable(Seq("192.168.0.0/16"))
     ).map(v => (v.spec.name, v)).toMap
  }
  val inventoryAll = Cf3PolicyDraft(
      id              = Cf3PolicyDraftId(RuleId("inventory-all"), DirectiveId("inventory-all"))
    , technique       = inventoryTechnique
    , variableMap     = inventoryVariables
    , trackerVariable = inventoryTechnique.trackerVariableSpec.toVariable(Seq())
    , priority        = 0
    , serial          = 2
    , modificationDate= DateTime.now
    , ruleOrder       = BundleOrder("Rudder system policy: daily inventory")
    , directiveOrder  = BundleOrder("Inventory")
    , overrides       = Set()
  )


  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // actual tests
  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  sequential

  "The test configuration-repository" should {
    "exists, and have a techniques/system sub-dir" in {
      val promiseFile = configurationRepositoryRoot/"techniques/system/common/1.0/promises.st"
      promiseFile.exists === true
    }
  }

  "A root node, with no node connected and defauls installation" should {
    "correctly write the expected promises files" in {

      // system variables for root
      val systemVariables = systemVariableService.getSystemVariables(
          root, allNodesInfo, groupLib, noLicense
        , globalSystemVariables, globalAgentRun, globalComplianceMode
      ).openOrThrowException("I should get system variable in tests")

      // the root node configuration
      val rnc = rootNodeConfig.copy(
          policyDrafts = Set(common, serverRole, distributePolicy, inventoryAll)
        , nodeContext  = systemVariables
        , parameters   = Set(ParameterForConfiguration(ParameterName("rudder_file_edit_header"), "### Managed by Rudder, edit with care ###"))
      )


      // Actually write the promise files for the root node
      promiseWritter.writeTemplate(root.id, Set(root.id), Map(root.id -> rnc), Map(root.id -> NodeConfigId("root-cfg-id")), Map())

      /*
       * And compare them with expected, modulo the configId and the name
       * of the (temp) directory where we wrote them
       */
      rootGeneratedPromisesDir must haveSameFilesAs(EXPECTED_SHARE/"root-default-install")
        .withFilter { f => f.getName != "rudder_promises_generated" }
        .withMatcher(ignoreSomeLinesMatcher(
             """.*rudder_node_config_id" string => .*"""
          :: """.*string => "/.*/configuration-repository.*"""
          :: Nil
        ))
    }
  }


  "rudder-group.st template" should {

    "correctly write nothing (no quotes) when no groups" in {

      // system variables for root
      val systemVariables = systemVariableService.getSystemVariables(
          root, allNodesInfo, emptyGroupLib, noLicense
        , globalSystemVariables, globalAgentRun, globalComplianceMode
      ).openOrThrowException("I should get system variable in tests")

      // the root node configuration
      val rnc = rootNodeConfig.copy(
          policyDrafts = Set(common, serverRole, distributePolicy, inventoryAll)
        , nodeContext  = systemVariables
        , parameters   = Set(ParameterForConfiguration(ParameterName("rudder_file_edit_header"), "### Managed by Rudder, edit with care ###"))
      )


      // Actually write the promise files for the root node
      promiseWritter.writeTemplate(root.id, Set(root.id), Map(root.id -> rnc), Map(root.id -> NodeConfigId("root-cfg-id")), Map())

      rootGeneratedPromisesDir/"common/1.0/rudder-groups.cf" must haveSameLinesAs(EXPECTED_SHARE/"test-rudder-groups/no-group.cf")
    }

    "correctly write the classes and by_uuid array when groups" in {

      // make a group lib without "all" target so that it's actually different than the full one,
      // so that we don't have false positive due to bad directory cleaning
      val groupLib2 = groupLib.copy(targetInfos = groupLib.targetInfos.take(groupLib.targetInfos.size - 1))

      // system variables for root
      val systemVariables = systemVariableService.getSystemVariables(
          root, allNodesInfo, groupLib2, noLicense
        , globalSystemVariables, globalAgentRun, globalComplianceMode
      ).openOrThrowException("I should get system variable in tests")

      // the root node configuration
      val rnc = rootNodeConfig.copy(
          policyDrafts = Set(common, serverRole, distributePolicy, inventoryAll)
        , nodeContext  = systemVariables
        , parameters   = Set(ParameterForConfiguration(ParameterName("rudder_file_edit_header"), "### Managed by Rudder, edit with care ###"))
      )

      // Actually write the promise files for the root node
      promiseWritter.writeTemplate(root.id, Set(root.id), Map(root.id -> rnc), Map(root.id -> NodeConfigId("root-cfg-id")), Map())

      rootGeneratedPromisesDir/"common/1.0/rudder-groups.cf" must haveSameLinesAs(EXPECTED_SHARE/"test-rudder-groups/some-groups.cf")
    }
  }
}

