/*
 *************************************************************************************
 * Copyright 2020 Normation SAS
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

package com.normation.rudder

import better.files._
import com.normation.GitVersion
import com.normation.box._
import com.normation.cfclerk.domain._
import com.normation.cfclerk.services.impl._
import com.normation.cfclerk.xmlparsers.SectionSpecParser
import com.normation.cfclerk.xmlparsers.TechniqueParser
import com.normation.cfclerk.xmlparsers.VariableSpecParser
import com.normation.errors._
import com.normation.errors.IOResult
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain._
import com.normation.inventory.domain.AgentType.CfeCommunity
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.ldap.core.InventoryDitService
import com.normation.inventory.ldap.core.InventoryDitServiceImpl
import com.normation.inventory.services.core.ReadOnlySoftwareDAO
import com.normation.rudder.MockNodes.allNodeFacts
import com.normation.rudder.campaigns.Campaign
import com.normation.rudder.campaigns.CampaignDetails
import com.normation.rudder.campaigns.CampaignEvent
import com.normation.rudder.campaigns.CampaignEventId
import com.normation.rudder.campaigns.CampaignEventRepository
import com.normation.rudder.campaigns.CampaignId
import com.normation.rudder.campaigns.CampaignInfo
import com.normation.rudder.campaigns.CampaignParsingInfo
import com.normation.rudder.campaigns.CampaignRepository
import com.normation.rudder.campaigns.CampaignSerializer
import com.normation.rudder.campaigns.CampaignStatusValue
import com.normation.rudder.campaigns.CampaignType
import com.normation.rudder.campaigns.DayTime
import com.normation.rudder.campaigns.Enabled
import com.normation.rudder.campaigns.Finished
import com.normation.rudder.campaigns.JSONTranslateCampaign
import com.normation.rudder.campaigns.MainCampaignService
import com.normation.rudder.campaigns.Monday
import com.normation.rudder.campaigns.Running
import com.normation.rudder.campaigns.Scheduled
import com.normation.rudder.campaigns.WeeklySchedule
import com.normation.rudder.configuration.ConfigurationRepositoryImpl
import com.normation.rudder.configuration.DirectiveRevisionRepository
import com.normation.rudder.configuration.GroupRevisionRepository
import com.normation.rudder.configuration.RuleRevisionRepository
import com.normation.rudder.domain.NodeDit
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.archives.ParameterArchiveId
import com.normation.rudder.domain.archives.RuleArchiveId
import com.normation.rudder.domain.nodes._
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.properties.AddGlobalParameterDiff
import com.normation.rudder.domain.properties.DeleteGlobalParameterDiff
import com.normation.rudder.domain.properties.GenericProperty.StringToConfigValue
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.GroupProperty
import com.normation.rudder.domain.properties.InheritMode
import com.normation.rudder.domain.properties.ModifyGlobalParameterDiff
import com.normation.rudder.domain.properties.PropertyProvider
import com.normation.rudder.domain.queries._
import com.normation.rudder.domain.queries.CriterionComposition
import com.normation.rudder.domain.reports.NodeModeConfig
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.CoreNodeFactRepository
import com.normation.rudder.facts.nodes.MockNodeFactFullInventoryRepositoryProxy
import com.normation.rudder.facts.nodes.NodeFact
import com.normation.rudder.facts.nodes.NodeFactStorage
import com.normation.rudder.facts.nodes.NodeInfoServiceProxy
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.facts.nodes.SelectFacts
import com.normation.rudder.facts.nodes.SoftDaoGetNodesbySofwareName
import com.normation.rudder.facts.nodes.StorageChangeEventDelete
import com.normation.rudder.facts.nodes.StorageChangeEventSave
import com.normation.rudder.facts.nodes.StorageChangeEventStatus
import com.normation.rudder.facts.nodes.WoFactNodeRepositoryProxy
import com.normation.rudder.git.GitFindUtils
import com.normation.rudder.git.GitRepositoryProviderImpl
import com.normation.rudder.git.GitRevisionProvider
import com.normation.rudder.git.SimpleGitRevisionProvider
import com.normation.rudder.migration.XmlEntityMigration
import com.normation.rudder.reports._
import com.normation.rudder.repository._
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.repository.WoRuleRepository
import com.normation.rudder.repository.xml.GitParseGroupLibrary
import com.normation.rudder.repository.xml.GitParseRules
import com.normation.rudder.repository.xml.GitParseTechniqueLibrary
import com.normation.rudder.repository.xml.TechniqueRevisionRepository
import com.normation.rudder.rule.category._
import com.normation.rudder.services.marshalling.NodeGroupCategoryUnserialisationImpl
import com.normation.rudder.services.marshalling.NodeGroupUnserialisationImpl
import com.normation.rudder.services.marshalling.RuleUnserialisationImpl
import com.normation.rudder.services.policies.NodeConfigData
import com.normation.rudder.services.policies.NodeConfiguration
import com.normation.rudder.services.policies.ParameterForConfiguration
import com.normation.rudder.services.policies.Policy
import com.normation.rudder.services.policies.SystemVariableServiceImpl
import com.normation.rudder.services.queries._
import com.normation.rudder.services.servers.AllowedNetwork
import com.normation.rudder.services.servers.FactListNewNodes
import com.normation.rudder.services.servers.NewNodeManager
import com.normation.rudder.services.servers.PolicyServerManagementService
import com.normation.rudder.services.servers.PolicyServers
import com.normation.rudder.services.servers.PolicyServersUpdateCommand
import com.normation.rudder.services.servers.RelaySynchronizationMethod.Classic
import com.normation.rudder.tenants.DefaultTenantService
import com.normation.utils.DateFormaterService
import com.normation.utils.StringUuidGeneratorImpl
import com.normation.zio._
import com.softwaremill.quicklens._
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.RDN
import com.unboundid.ldif.LDIFChangeRecord
import net.liftweb.common.Box
import net.liftweb.common.Full
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.lib.ObjectId
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import scala.annotation.tailrec
import scala.collection.immutable.{SortedMap => ISortedMap}
import scala.util.control.NonFatal
import scala.xml.Elem
import zio.{System => _, Tag => _, _}
import zio.json.jsonDiscriminator
import zio.json.jsonHint
import zio.stream.ZStream
import zio.syntax._

/*
 * Mock services for test, especially repositories, and provides
 * test data (nodes, directives, etc)
 */

object NoTags {
  def apply(): Tags = Tags(Set())
}

object MkTags {
  def apply(tags: (String, String)*): Tags = {
    Tags(tags.map { case (k, v) => Tag(TagName(k), TagValue(v)) }.toSet)
  }
}

object Diff {

  class DiffBetween[A](old: A, current: A) {
    def apply[B](path: A => B): Option[SimpleDiff[B]] = {
      if (path(old) == path(current)) None
      else Some(SimpleDiff(path(old), path(current)))
    }
  }

  def apply[A](old: A, current: A) = new DiffBetween(old, current)
}

// a global test actor
object TestActor {
  val actor: EventActor = EventActor("test user")
  def get = actor
}

object revisionRepo {
  import com.normation.GitVersion._

  val revisionsMap: Ref.Synchronized[Map[Revision, RevisionInfo]] = Ref.Synchronized.make(Map[Revision, RevisionInfo]()).runNow

  def getOpt(revision: Revision): IOResult[Option[RevisionInfo]] =
    revisionsMap.get.map(_.get(revision))

  def getAll: IOResult[Seq[RevisionInfo]] = {
    revisionsMap.get.map(_.valuesIterator.toList.sortBy(_.date.getMillis).toSeq)
  }

  def add(revisionInfo: RevisionInfo): IOResult[Unit] = {
    revisionsMap.updateZIO(m => (m + (revisionInfo.rev -> revisionInfo)).succeed)
  }
}

class MockGitConfigRepo(prefixTestResources: String = "", configRepoDirName: String = "configuration-repository") {

  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // set up root node configuration
  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  val abstractRoot: File = File("/tmp/test-rudder-mock-config-repo-" + DateTime.now.toString())
  abstractRoot.createDirectories()
  if (System.getProperty("tests.clean.tmp") != "false") {
    java.lang.Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
      override def run(): Unit = FileUtils.deleteDirectory(abstractRoot.toJava)
    }))
  }

  // config-repo will also be the git root, as a normal rudder
  val configurationRepositoryRoot: File = abstractRoot / configRepoDirName
  // initialize config-repo content from our rudder-code test/resources source

  NodeConfigData.copyConfigurationRepository(
    prefixTestResources + s"src/test/resources/${configRepoDirName}",
    configurationRepositoryRoot.toJava
  )

  val gitRepo: GitRepositoryProviderImpl = GitRepositoryProviderImpl.make(configurationRepositoryRoot.pathAsString).runNow

  // always return HEAD on master
  val revisionProvider: revisionProvider = new revisionProvider
  class revisionProvider extends GitRevisionProvider() {
    val refPath = "refs/heads/master"

    override def getAvailableRevTreeId: IOResult[ObjectId] = {
      GitFindUtils.findRevTreeFromRevString(gitRepo.db, refPath)
    }

    override def currentRevTreeId: IOResult[ObjectId] = {
      GitFindUtils.findRevTreeFromRevString(gitRepo.db, refPath)
    }

    override def setCurrentRevTreeId(id: ObjectId): IOResult[Unit] = {
      // nothing
      ZIO.unit
    }
  }
}

object MockTechniques {
  def apply(mockGitConfigRepo: MockGitConfigRepo) =
    new MockTechniques(mockGitConfigRepo.configurationRepositoryRoot, mockGitConfigRepo)
}

class MockTechniques(configurationRepositoryRoot: File, mockGit: MockGitConfigRepo) {
  val variableSpecParser        = new VariableSpecParser
  val systemVariableServiceSpec = new SystemVariableSpecServiceImpl()
  val techniqueParser: TechniqueParser = new TechniqueParser(
    variableSpecParser,
    new SectionSpecParser(variableSpecParser),
    systemVariableServiceSpec
  )
  val techniqueReader = new GitTechniqueReader(
    techniqueParser,
    new SimpleGitRevisionProvider("refs/heads/master", mockGit.gitRepo),
    mockGit.gitRepo,
    "metadata.xml",
    "category.xml",
    Some("techniques"),
    "default-directive-names.conf"
  )
  val stringUuidGen   = new StringUuidGeneratorImpl()

  val techniqueRepo = new TechniqueRepositoryImpl(techniqueReader, Seq(), stringUuidGen)

  val techniqueRevisionRepo: TechniqueRevisionRepository =
    new GitParseTechniqueLibrary(techniqueParser, mockGit.gitRepo, mockGit.revisionProvider, "techniques", "metadata.xml")
  val xmlEntityMigration:    XmlEntityMigration          = new XmlEntityMigration {
    override def getUpToDateXml(entity: Elem): Box[Elem] = Full(entity)
  }
  val ruleRevisionRepo:      RuleRevisionRepository      =
    new GitParseRules(new RuleUnserialisationImpl(), mockGit.gitRepo, xmlEntityMigration, "rules")

  ///////////////////////////  policyServer and systemVariables  ///////////////////////////

  val policyServerManagementService: PolicyServerManagementService = new PolicyServerManagementService() {
    override def getAllowedNetworks(policyServerId: NodeId): IOResult[List[AllowedNetwork]] = List(
      AllowedNetwork("192.168.49.0/24", "name")
    ).succeed
    override def getPolicyServers():                         IOResult[PolicyServers]        = ???

    override def savePolicyServers(policyServers: PolicyServers):  IOResult[PolicyServers]                     = ???
    override def getAllAllowedNetworks():                          IOResult[Map[NodeId, List[AllowedNetwork]]] = ???
    override def updatePolicyServers(
        commands: List[PolicyServersUpdateCommand],
        modId:    ModificationId,
        actor:    EventActor
    ): IOResult[PolicyServers] = ???
    override def setAllowedNetworks(
        policyServerId: NodeId,
        networks:       Seq[AllowedNetwork],
        modId:          ModificationId,
        actor:          EventActor
    ): IOResult[List[AllowedNetwork]] = ???
    override def updateAllowedNetworks(
        policyServerId: NodeId,
        addNetworks:    Seq[AllowedNetwork],
        deleteNetwork:  Seq[String],
        modId:          ModificationId,
        actor:          EventActor
    ): IOResult[List[AllowedNetwork]] = ???
    override def deleteRelaySystemObjects(policyServerId: NodeId): IOResult[Unit]                              = ???
  }

  val systemVariableService = new SystemVariableServiceImpl(
    systemVariableServiceSpec,
    policyServerManagementService,
    toolsFolder = "tools_folder",
    policyDistribCfenginePort = 5309,
    policyDistribHttpsPort = 443,
    sharedFilesFolder = "/var/rudder/configuration-repository/shared-files",
    webdavUser = "rudder",
    webdavPassword = "rudder",
    reportsDbUri = "jdbc:postgresql://localhost:5432/rudder",
    reportsDbUser = "rudder",
    reportsDbPassword = "secret",
    configurationRepository = configurationRepositoryRoot.pathAsString,
    serverVersion = "7.0.0", // denybadclocks is runtime properties

    getDenyBadClocks = () => Full(true),
    getSyncMethod = () => Full(Classic),
    getSyncPromises = () => Full(false),
    getSyncSharedFiles = () => Full(false), // TTLs are runtime properties too

    getModifiedFilesTtl = () => Full(30),
    getCfengineOutputsTtl = () => Full(7),
    getSendMetrics = () => Full(None),
    getReportProtocolDefault = () => Full(AgentReportingHTTPS)
  )

  val globalAgentRun:       AgentRunInterval     = AgentRunInterval(None, 5, 1, 0, 4)
  val globalComplianceMode: GlobalComplianceMode = GlobalComplianceMode(FullCompliance, 15)

  val globalSystemVariables: Map[String, Variable] = systemVariableService
    .getGlobalSystemVariables(globalAgentRun)
    .openOrThrowException("I should get global system variable in test!")
}

object TV {
  def apply(s: String): TechniqueVersion =
    TechniqueVersion.parse(s).getOrElse(throw new IllegalArgumentException(s"Cannot parse '${s}' as a technique version'"))
}

class MockDirectives(mockTechniques: MockTechniques) {

  object directives {

    /*
     * NOTICE:
     * Changes here are likely to need to be replicated in rudder-core: NodeCondigData.scala,
     * in class TestNodeConfiguration
     */

    val commonTechnique:                                                      Technique                   = techniqueRepos.unsafeGet(TechniqueId(TechniqueName("common"), TV("1.0")))
    def commonVariables(nodeId: NodeId, allNodeInfos: Map[NodeId, NodeInfo]): Map[String, VariableSpec#V] = {
      val spec = commonTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
      Seq(
        spec("OWNER").toVariable(Seq(allNodeInfos(nodeId).localAdministratorAccountName)),
        spec("UUID").toVariable(Seq(nodeId.value)),
        spec("POLICYSERVER_ID").toVariable(Seq(allNodeInfos(nodeId).policyServerId.value)),
        spec("POLICYSERVER_ADMIN").toVariable(
          Seq(allNodeInfos(allNodeInfos(nodeId).policyServerId).localAdministratorAccountName)
        ),
        spec("ALLOWEDNETWORK").toVariable(Seq(""))
      ).map(v => (v.spec.name, v)).toMap
    }
    val commonDirective:                                                      Directive                   = Directive(
      DirectiveId(DirectiveUid("common-root"), GitVersion.DEFAULT_REV),
      TV("1.0"),
      Map(
        ("OWNER", Seq("${rudder.node.admin}")),
        ("UUID", Seq("${rudder.node.id}")),
        ("POLICYSERVER_ID", Seq("${rudder.node.id}")),
        ("POLICYSERVER_ADMIN", Seq("${rudder.node.admin}"))
      ),
      "common-root",
      "",
      None,
      "",
      5,
      true,
      true // short desc / policyMode / long desc / prio / enabled / system
    )

    val archiveTechnique: Technique =
      techniqueRepos.unsafeGet(TechniqueId(TechniqueName("test_import_export_archive"), TV("1.0")))
    val archiveDirective: Directive = Directive(
      DirectiveId(DirectiveUid("test_import_export_archive_directive"), GitVersion.DEFAULT_REV),
      TV("1.0"),
      Map(),
      "test_import_export_archive_directive",
      "",
      None,
      "",
      5,
      true,
      false
    )

    // we have one rule with several system technique for root server config

    def simpleServerPolicy(name: String) = {
      val technique = techniqueRepos.unsafeGet(TechniqueId(TechniqueName(s"${name}"), TV("1.0")))
      val directive = Directive(
        DirectiveId(DirectiveUid(s"${name}-root"), GitVersion.DEFAULT_REV),
        TV("1.0"),
        Map(),
        s"${name}-root",
        "",
        None,
        "",
        5,
        true,
        true // short desc / policyMode / long desc / prio / enabled / system
      )
      (technique, directive)
    }

    val (serverCommonTechnique, serverCommonDirective)         = simpleServerPolicy("server-common")
    val (serverApacheTechnique, serverApacheDirective)         = simpleServerPolicy("rudder-service-apache")
    val (serverPostgresqlTechnique, serverPostgresqlDirective) = simpleServerPolicy("rudder-service-postgresql")
    val (serverRelaydTechnique, serverRelaydDirective)         = simpleServerPolicy("rudder-service-relayd")
    val (serverSlapdTechnique, serverSlapdDirective)           = simpleServerPolicy("rudder-service-slapd")
    val (serverWebappTechnique, serverWebappDirective)         = simpleServerPolicy("rudder-service-webapp")

    val inventoryTechnique = techniqueRepos.unsafeGet(TechniqueId(TechniqueName("inventory"), TV("1.0")))
    val inventoryDirective: Directive = Directive(
      DirectiveId(DirectiveUid("inventory-all"), GitVersion.DEFAULT_REV),
      TV("1.0"),
      Map(),
      "Inventory",
      "",
      None,
      "",
      5,
      true,
      true // short desc / policyMode / long desc / prio / enabled / system
    )

    //
    // 4 user directives: clock management, rpm, package, a multi-policiy: fileTemplate, and a ncf one: Create_file
    //
    val clockTechnique = techniqueRepos.unsafeGet(TechniqueId(TechniqueName("clockConfiguration"), TV("3.0")))
    val clockDirective: Directive = Directive(
      DirectiveId(DirectiveUid("directive1"), GitVersion.DEFAULT_REV),
      TV("3.0"),
      Map(
        ("CLOCK_FQDNNTP", Seq("true")),
        ("CLOCK_HWSYNC_ENABLE", Seq("true")),
        ("CLOCK_NTPSERVERS", Seq("${rudder.param.ntpserver}")),
        ("CLOCK_SYNCSCHED", Seq("240")),
        ("CLOCK_TIMEZONE", Seq("dontchange"))
      ),
      "10. Clock Configuration",
      "",
      None,
      "",
      5,
      true,
      false // short desc / policyMode / long desc / prio / enabled / system
    )

    /*
     * A RPM Policy, which comes from 2 directives.
     * The second one contributes two packages.
     * It had a different value for the CHECK_INTERVAL, but
     * that variable is unique, so it get the first draft value all along.
     */
    val rpmTechnique = techniqueRepos.unsafeGet(TechniqueId(TechniqueName("rpmPackageInstallation"), TV("7.0")))
    val rpmDirective: Directive = Directive(
      DirectiveId(DirectiveUid("directive2"), GitVersion.DEFAULT_REV),
      TV("7.0"),
      Map(
        ("RPM_PACKAGE_CHECK_INTERVAL", Seq("5")),
        ("RPM_PACKAGE_POST_HOOK_COMMAND", Seq("")),
        ("RPM_PACKAGE_POST_HOOK_RUN", Seq("false")),
        ("RPM_PACKAGE_REDACTION", Seq("add")),
        ("RPM_PACKAGE_REDLIST", Seq("vim")),
        ("RPM_PACKAGE_VERSION", Seq("")),
        ("RPM_PACKAGE_VERSION_CRITERION", Seq("==")),
        ("RPM_PACKAGE_VERSION_DEFINITION", Seq("default"))
      ),
      "directive2",
      "",
      None,
      ""
    )

    // a directive with two iterations
    val pkgTechnique = techniqueRepos.unsafeGet(TechniqueId(TechniqueName("packageManagement"), TV("1.0")))
    val pkgDirective: Directive = Directive(
      DirectiveId(DirectiveUid("16617aa8-1f02-4e4a-87b6-d0bcdfb4019f"), GitVersion.DEFAULT_REV),
      TV("1.0"),
      Map(
        ("PACKAGE_LIST", Seq("htop", "jq")),
        ("PACKAGE_STATE", Seq("present", "present")),
        ("PACKAGE_VERSION", Seq("latest", "latest")),
        ("PACKAGE_VERSION_SPECIFIC", Seq("", "")),
        ("PACKAGE_ARCHITECTURE", Seq("default", "default")),
        ("PACKAGE_ARCHITECTURE_SPECIFIC", Seq("", "")),
        ("PACKAGE_MANAGER", Seq("default", "default")),
        ("PACKAGE_POST_HOOK_COMMAND", Seq("", ""))
      ),
      "directive 16617aa8-1f02-4e4a-87b6-d0bcdfb4019f",
      "",
      None,
      ""
    )

    val fileTemplateTechnique = techniqueRepos.unsafeGet(TechniqueId(TechniqueName("fileTemplate"), TV("1.0")))
    val fileTemplateDirecive1:  Directive = Directive(
      DirectiveId(DirectiveUid("e9a1a909-2490-4fc9-95c3-9d0aa01717c9"), GitVersion.DEFAULT_REV),
      TV("1.0"),
      Map(
        ("FILE_TEMPLATE_RAW_OR_NOT", Seq("Raw")),
        ("FILE_TEMPLATE_TEMPLATE", Seq("")),
        ("FILE_TEMPLATE_RAW_TEMPLATE", Seq("some content")),
        ("FILE_TEMPLATE_AGENT_DESTINATION_PATH", Seq("/tmp/destination.txt")),
        ("FILE_TEMPLATE_TEMPLATE_TYPE", Seq("mustache")),
        ("FILE_TEMPLATE_OWNER", Seq("root")),
        ("FILE_TEMPLATE_GROUP_OWNER", Seq("root")),
        ("FILE_TEMPLATE_PERMISSIONS", Seq("700")),
        ("FILE_TEMPLATE_PERSISTENT_POST_HOOK", Seq("false")),
        ("FILE_TEMPLATE_TEMPLATE_POST_HOOK_COMMAND", Seq(""))
      ),
      "directive e9a1a909-2490-4fc9-95c3-9d0aa01717c9",
      "",
      None,
      ""
    )
    val fileTemplateVariables2: Directive = Directive(
      DirectiveId(DirectiveUid("99f4ef91-537b-4e03-97bc-e65b447514cc"), GitVersion.DEFAULT_REV),
      TV("1.0"),
      Map(
        ("FILE_TEMPLATE_RAW_OR_NOT", Seq("Raw")),
        ("FILE_TEMPLATE_TEMPLATE", Seq("")),
        ("FILE_TEMPLATE_RAW_TEMPLATE", Seq("some content")),
        ("FILE_TEMPLATE_AGENT_DESTINATION_PATH", Seq("/tmp/other-destination.txt")),
        ("FILE_TEMPLATE_TEMPLATE_TYPE", Seq("mustache")),
        ("FILE_TEMPLATE_OWNER", Seq("root")),
        ("FILE_TEMPLATE_GROUP_OWNER", Seq("root")),
        ("FILE_TEMPLATE_PERMISSIONS", Seq("777")),
        ("FILE_TEMPLATE_PERSISTENT_POST_HOOK", Seq("true")),
        ("FILE_TEMPLATE_TEMPLATE_POST_HOOK_COMMAND", Seq("/bin/true"))
      ),
      "directive 99f4ef91-537b-4e03-97bc-e65b447514cc",
      "",
      None,
      "",
      _isEnabled = true
    )

    val ncf1Technique = techniqueRepos.unsafeGet(TechniqueId(TechniqueName("Create_file"), TV("1.0")))
    val ncf1Directive: Directive = Directive(
      DirectiveId(DirectiveUid("16d86a56-93ef-49aa-86b7-0d10102e4ea9"), GitVersion.DEFAULT_REV),
      TV("1.0"),
      Map(
        ("expectedReportKey Directory create", Seq("directory_create_/tmp/foo")),
        ("expectedReportKey File create", Seq("file_create_/tmp/foo/bar")),
        ("1AAACD71-C2D5-482C-BCFF-5EEE6F8DA9C2", Seq("\"foo"))
      ),
      "directive 16d86a56-93ef-49aa-86b7-0d10102e4ea9",
      "",
      None,
      ""
    )

    /**
      * test for multiple generation
      */
    val DIRECTIVE_NAME_COPY_GIT_FILE = "directive-copyGitFile"
    val copyGitFileTechnique         = techniqueRepos.unsafeGet(TechniqueId(TechniqueName("copyGitFile"), TV("2.3")))
    val copyGitFileDirective: Directive = Directive(
      DirectiveId(DirectiveUid("directive-copyGitFile"), GitVersion.DEFAULT_REV),
      TV("2.3"),
      Map(
        ("COPYFILE_NAME", Seq("file_name_0.json")),
        ("COPYFILE_EXCLUDE_INCLUDE_OPTION", Seq("none")),
        ("COPYFILE_EXCLUDE_INCLUDE", Seq("")),
        ("COPYFILE_DESTINATION", Seq("/tmp/destination_0.json")),
        ("COPYFILE_RECURSION", Seq("0")),
        ("COPYFILE_PURGE", Seq("false")),
        ("COPYFILE_COMPARE_METHOD", Seq("mtime")),
        ("COPYFILE_OWNER", Seq("root")),
        ("COPYFILE_GROUP", Seq("root")),
        ("COPYFILE_PERM", Seq("644")),
        ("COPYFILE_SUID", Seq("false")),
        ("COPYFILE_SGID", Seq("false")),
        ("COPYFILE_STICKY_FOLDER", Seq("false")),
        ("COPYFILE_POST_HOOK_RUN", Seq("true")),
        ("COPYFILE_POST_HOOK_COMMAND", Seq("/bin/echo Value_0.json"))
      ),
      "directive-copyGitFile",
      "",
      None,
      ""
    )

    /*
     * Test override order of generic-variable-definition.
     * We want to have to directive, directive1 and directive2.
     * directive1 is the default value and must be overriden by value in directive 2, which means that directive2 value must be
     * define after directive 1 value in generated "genericVariableDefinition.cf".
     * The semantic to achieve that is to use Priority: directive 1 has a higher (ie smaller int number) priority than directive 2.
     *
     * To be sure that we don't use rule/directive name order, we will make directive 2 sort name come before directive 1 sort name.
     *
     * BUT added subtilities: the final bundle name order that will be used is the most prioritary one, so that we keep the
     * global sorting logic between rules / directives.
     *
     * In summary: sorting directives that are merged into one is a different problem than sorting directives for the bundle sequence.
     */
    val gvdTechnique = techniqueRepos.unsafeGet(TechniqueId(TechniqueName("genericVariableDefinition"), TV("2.0")))
    val gvdDirective1: Directive = Directive(
      DirectiveId(DirectiveUid("gvd-directive1"), GitVersion.DEFAULT_REV),
      TV("2.0"),
      Map(
        ("GENERIC_VARIABLE_NAME", Seq("var1")),
        ("GENERIC_VARIABLE_CONTENT", Seq("value from gvd #1 should be first")) // the one to override
      ),
      "99. Generic Variable Def #1",
      "",
      None,
      "",
      0
    )
    val gvdDirective2: Directive = Directive(
      DirectiveId(DirectiveUid("gvd-directive2"), GitVersion.DEFAULT_REV),
      TV("2.0"),
      Map(
        ("GENERIC_VARIABLE_NAME", Seq("var2")),
        ("GENERIC_VARIABLE_CONTENT", Seq("value from gvd #2 should be second")) // the one to override
      ),
      "00. Generic Variable Def #2",
      "",
      None,
      "",
      10
    )

    val all = Map(
      (commonTechnique, commonDirective :: Nil),
      (inventoryTechnique, inventoryDirective :: Nil),
      (serverCommonTechnique, serverCommonDirective :: Nil),
      (serverApacheTechnique, serverApacheDirective :: Nil),
      (serverPostgresqlTechnique, serverPostgresqlDirective :: Nil),
      (serverRelaydTechnique, serverRelaydDirective :: Nil),
      (serverSlapdTechnique, serverSlapdDirective :: Nil),
      (serverWebappTechnique, serverWebappDirective :: Nil),
      (clockTechnique, clockDirective :: Nil),
      (commonTechnique, commonDirective :: Nil),
      (copyGitFileTechnique, copyGitFileDirective :: Nil),
      (fileTemplateTechnique, fileTemplateDirecive1 :: fileTemplateVariables2 :: Nil),
      (gvdTechnique, gvdDirective1 :: gvdDirective2 :: Nil),
      (ncf1Technique, ncf1Directive :: Nil),
      (pkgTechnique, pkgDirective :: Nil),
      (archiveTechnique, archiveDirective :: Nil),
      (rpmTechnique, rpmDirective :: Nil)
    )
  }

  val techniqueRepos = mockTechniques.techniqueRepo
  implicit class UnsafeGet(repo: TechniqueRepositoryImpl) {
    def unsafeGet(id: TechniqueId): Technique =
      repo.get(id).getOrElse(throw new RuntimeException(s"Bad init for test: technique '${id.debugString}' not found"))
  }

  val rootActiveTechniqueCategory: Ref.Synchronized[FullActiveTechniqueCategory] = Ref.Synchronized
    .make(
      FullActiveTechniqueCategory(
        id = ActiveTechniqueCategoryId("Active Techniques"),
        name = "Active Techniques",
        description =
          "This is the root category for active techniques. It contains subcategories, actives techniques and directives",
        subCategories = Nil,
        activeTechniques = Nil,
        isSystem = true
      )
    )
    .runNow

  def displayFullActiveTechniqueCategory(fatc: FullActiveTechniqueCategory, indent: String = ""): String = {
    val indent2 = indent + "  "

    def displayTech(t: FullActiveTechnique, indent3: String): String = {
      s"""${indent3}+ ${t.id.value}${t.directives.map(d => s"\n${indent3}  * ${d.id.uid.value}").mkString("")}""".stripMargin
    }

    s"""${indent}- ${fatc.id.value}
       |${fatc.activeTechniques.sortBy(_.id.value).map(t => displayTech(t, indent2)).mkString("", "\n", "")}
       |${fatc.subCategories
        .sortBy(_.id.value)
        .map(displayFullActiveTechniqueCategory(_, indent2))
        .mkString("", "\n", "")}""".stripMargin
  }

  object directiveRepo extends RoDirectiveRepository with WoDirectiveRepository with DirectiveRevisionRepository {

    override def getDirectiveRevision(
        uid: DirectiveUid,
        rev: GitVersion.Revision
    ): IOResult[Option[(ActiveTechnique, Directive)]] = {
      rootActiveTechniqueCategory.get.map(_.allDirectives.get(DirectiveId(uid, rev)).map {
        case (fat, d) => (fat.toActiveTechnique(), d)
      })
    }

    override def getRevisions(uid: DirectiveUid): IOResult[List[GitVersion.RevisionInfo]] = {
      for {
        revs  <- rootActiveTechniqueCategory.get.map(_.allDirectives.keySet.toList.collect {
                   case DirectiveId(x, rev) if (x == uid) => rev
                 })
        infos <- ZIO.foreach(revs) { rev =>
                   revisionRepo.getOpt(rev).notOptional(s"Missing revision infos for revision for revision '${rev.value}'")
                 }
      } yield {
        infos
      }
    }

    override def getFullDirectiveLibrary(): IOResult[FullActiveTechniqueCategory] = rootActiveTechniqueCategory.get

    override def getDirective(uid: DirectiveUid): IOResult[Option[Directive]] = {
      getDirectiveRevision(uid, GitVersion.DEFAULT_REV).map(_.map(_._2))
    }

    override def getDirectiveWithContext(directiveId: DirectiveUid): IOResult[Option[(Technique, ActiveTechnique, Directive)]] = {
      rootActiveTechniqueCategory.get.map(_.allDirectives.get(DirectiveId(directiveId)).map {
        case (fat, d) =>
          (fat.techniques(d.techniqueVersion), fat.toActiveTechnique(), d)
      })
    }

    override def getActiveTechniqueAndDirective(id: DirectiveId): IOResult[Option[(ActiveTechnique, Directive)]] = {
      getDirectiveWithContext(id.uid).map(_.map { case (t, at, d) => (at, d) })
    }

    override def getDirectives(activeTechniqueId: ActiveTechniqueId, includeSystem: Boolean): IOResult[Seq[Directive]] = {
      val predicate = (d: Directive) => if (includeSystem) true else !d.isSystem
      rootActiveTechniqueCategory.get.map(_.allDirectives.collect { case (_, (_, d)) if (predicate(d)) => d }.toSeq)
    }

    override def getActiveTechniqueByCategory(
        includeSystem: Boolean
    ): IOResult[ISortedMap[List[ActiveTechniqueCategoryId], CategoryWithActiveTechniques]] = {
      implicit val ordering = ActiveTechniqueCategoryOrdering
      rootActiveTechniqueCategory.get.map(c => {
        ISortedMap.empty[List[ActiveTechniqueCategoryId], CategoryWithActiveTechniques] ++ c.fullIndex.map {
          case (path, fat) =>
            (
              path,
              CategoryWithActiveTechniques(fat.toActiveTechniqueCategory(), fat.activeTechniques.map(_.toActiveTechnique()).toSet)
            )
        }
      })
    }

    override def getActiveTechniqueByActiveTechnique(id: ActiveTechniqueId): IOResult[Option[ActiveTechnique]] = {
      rootActiveTechniqueCategory.get.map(_.allActiveTechniques.get(id).map(_.toActiveTechnique()))
    }

    override def getActiveTechnique(techniqueName: TechniqueName): IOResult[Option[ActiveTechnique]] = {
      rootActiveTechniqueCategory.get.map(
        _.allActiveTechniques.valuesIterator.find(_.techniqueName == techniqueName).map(_.toActiveTechnique())
      )
    }

    override def activeTechniqueBreadCrump(id: ActiveTechniqueId): IOResult[List[ActiveTechniqueCategory]] = {
      import cats.implicits._

      rootActiveTechniqueCategory.get.map(root => {
        root.fullIndex.find {
          case (path, fat) =>
            fat.activeTechniques.exists(_.id == id)
        } match {
          case Some((path, _)) =>
            path.traverse(id => root.allCategories.get(id)) match {
              case None    => Nil
              case Some(l) => l.map(_.toActiveTechniqueCategory())
            }
          case None            => Nil
        }
      })
    }

    override def getActiveTechniqueLibrary: IOResult[ActiveTechniqueCategory] = {
      rootActiveTechniqueCategory.get.map(_.toActiveTechniqueCategory())
    }

    override def getAllActiveTechniqueCategories(includeSystem: Boolean): IOResult[Seq[ActiveTechniqueCategory]] = {
      val predicate = (fat: FullActiveTechniqueCategory) => if (includeSystem) true else !fat.isSystem
      rootActiveTechniqueCategory.get.map {
        _.allCategories.values.collect {
          case c if (predicate(c)) =>
            c.toActiveTechniqueCategory()
        }.toSeq
      }
    }

    override def getActiveTechniqueCategory(id: ActiveTechniqueCategoryId): IOResult[Option[ActiveTechniqueCategory]] = {
      rootActiveTechniqueCategory.get.map(_.allCategories.get(id).map(_.toActiveTechniqueCategory()))
    }

    override def getParentActiveTechniqueCategory(id: ActiveTechniqueCategoryId): IOResult[ActiveTechniqueCategory] = {
      rootActiveTechniqueCategory.get.flatMap(root => {
        root.fullIndex.find(_._1.lastOption == Some(id)) match {
          case None            =>
            Inconsistency(s"Category not found: ${id.value}").fail
          case Some((path, _)) =>
            path
              .dropRight(1)
              .lastOption
              .flatMap(root.allCategories.get(_).map(_.toActiveTechniqueCategory()))
              .notOptional(s"Parent category of ${id.value} not found")
        }
      })
    }

    override def getParentsForActiveTechniqueCategory(id: ActiveTechniqueCategoryId): IOResult[List[ActiveTechniqueCategory]] =
      ???

    override def getParentsForActiveTechnique(id: ActiveTechniqueId): IOResult[ActiveTechniqueCategory] = ???

    override def containsDirective(id: ActiveTechniqueCategoryId): UIO[Boolean] = ???

    def buildDirectiveDiff(
        old:     Option[(SectionSpec, TechniqueName, Directive)],
        current: Directive
    ): Option[ModifyDirectiveDiff] = {
      (old, current) match {
        case (None, _)                     => None
        case (Some(t), c2) if (t._3 == c2) => None
        case (Some((spec, tn, c1)), c2)    =>
          val diff = Diff(c1, c2)
          Some(
            ModifyDirectiveDiff(
              tn,
              c2.id,
              c2.name,
              diff(_.name),
              diff(_.techniqueVersion),
              diff(d => SectionVal.directiveValToSectionVal(spec, d.parameters)),
              diff(_.shortDescription),
              diff(_.longDescription),
              diff(_.priority),
              diff(_.isEnabled),
              diff(_.isSystem),
              diff(_.policyMode),
              diff(_.tags)
            )
          )
      }
    }
    def saveGen(
        inActiveTechniqueId: ActiveTechniqueId,
        directive:           Directive
    ): ZIO[Any, RudderError, Option[ModifyDirectiveDiff]] = {
      rootActiveTechniqueCategory
        .modifyZIO(r => {
          r.saveDirective(inActiveTechniqueId, directive)
            .toIO
            .map(c => {
              // TODO: this is false, we should get root section spec for each directive/technique version
              (
                r.allDirectives
                  .get(DirectiveId(directive.id.uid, GitVersion.DEFAULT_REV))
                  .map(p => (p._1.techniques.head._2.rootSection, p._1.techniqueName, p._2)),
                c
              )
            })
        })
        .map(buildDirectiveDiff(_, directive))
    }
    override def saveDirective(
        inActiveTechniqueId: ActiveTechniqueId,
        directive:           Directive,
        modId:               ModificationId,
        actor:               EventActor,
        reason:              Option[String]
    ): IOResult[Option[DirectiveSaveDiff]] = {
      if (directive.isSystem) Inconsistency(s"Can not modify system directive '${directive.id}' here").fail
      else saveGen(inActiveTechniqueId, directive)
    }

    override def saveSystemDirective(
        inActiveTechniqueId: ActiveTechniqueId,
        directive:           Directive,
        modId:               ModificationId,
        actor:               EventActor,
        reason:              Option[String]
    ): IOResult[Option[DirectiveSaveDiff]] = {
      if (!directive.isSystem) Inconsistency(s"Can not modify non system directive '${directive.id}' here").fail
      else saveGen(inActiveTechniqueId, directive)
    }

    def deleteGen(id: DirectiveUid): ZIO[Any, Nothing, Option[DeleteDirectiveDiff]] = {
      // TODO: we should check if directive is system
      rootActiveTechniqueCategory
        .modifyZIO(r => (r.allDirectives.get(DirectiveId(id, GitVersion.DEFAULT_REV)), r.deleteDirective(id)).succeed)
        .map(_.map(p => DeleteDirectiveDiff(p._1.techniqueName, p._2)))
    }
    override def delete(
        id:     DirectiveUid,
        modId:  ModificationId,
        actor:  EventActor,
        reason: Option[String]
    ): IOResult[Option[DeleteDirectiveDiff]] = {
      deleteGen(id)
    }

    override def deleteSystemDirective(
        id:     DirectiveUid,
        modId:  ModificationId,
        actor:  EventActor,
        reason: Option[String]
    ): IOResult[Option[DeleteDirectiveDiff]] = {
      deleteGen(id)
    }

    override def addTechniqueInUserLibrary(
        categoryId:    ActiveTechniqueCategoryId,
        techniqueName: TechniqueName,
        versions:      Seq[TechniqueVersion],
        isSystem:      Boolean,
        modId:         ModificationId,
        actor:         EventActor,
        reason:        Option[String]
    ): IOResult[ActiveTechnique] = {
      val techs = techniqueRepos.getByName(techniqueName)
      for {
        all <-
          ZIO.foreach(versions)(v => techs.get(v).notOptional(s"Missing version '${v}' for technique '${techniqueName.value}'"))
        res <- rootActiveTechniqueCategory.modifyZIO { r =>
                 val root = r.addActiveTechnique(categoryId, techniqueName, all)
                 root.allCategories
                   .get(categoryId)
                   .flatMap(_.activeTechniques.find(_.id.value == techniqueName.value))
                   .notOptional(s"bug: active tech should be here")
                   .map(at => (at, root))
               }
      } yield {
        res.toActiveTechnique()
      }
    }

    override def move(
        id:            ActiveTechniqueId,
        newCategoryId: ActiveTechniqueCategoryId,
        modId:         ModificationId,
        actor:         EventActor,
        reason:        Option[String]
    ): IOResult[ActiveTechniqueId] = ???

    override def changeStatus(
        id:     ActiveTechniqueId,
        status: Boolean,
        modId:  ModificationId,
        actor:  EventActor,
        reason: Option[String]
    ): IOResult[ActiveTechniqueId] = ???

    override def setAcceptationDatetimes(
        id:        ActiveTechniqueId,
        datetimes: Map[TechniqueVersion, DateTime],
        modId:     ModificationId,
        actor:     EventActor,
        reason:    Option[String]
    ): IOResult[ActiveTechniqueId] = ???

    override def deleteActiveTechnique(
        id:     ActiveTechniqueId,
        modId:  ModificationId,
        actor:  EventActor,
        reason: Option[String]
    ): IOResult[ActiveTechniqueId] = ???

    override def addActiveTechniqueCategory(
        that:           ActiveTechniqueCategory,
        into:           ActiveTechniqueCategoryId,
        modificationId: ModificationId,
        actor:          EventActor,
        reason:         Option[String]
    ): IOResult[ActiveTechniqueCategory] = {
      rootActiveTechniqueCategory.updateAndGetZIO { root =>
        val full = FullActiveTechniqueCategory(
          that.id,
          that.name,
          that.description,
          that.children.flatMap(root.allCategories.get(_)),
          that.items.flatMap(root.allActiveTechniques.get(_)),
          that.isSystem
        )
        root.addActiveTechniqueCategory(into, full).succeed
      }.map(_.toActiveTechniqueCategory())
    }

    override def saveActiveTechniqueCategory(
        category:       ActiveTechniqueCategory,
        modificationId: ModificationId,
        actor:          EventActor,
        reason:         Option[String]
    ): IOResult[ActiveTechniqueCategory] = ???

    override def deleteCategory(
        id:             ActiveTechniqueCategoryId,
        modificationId: ModificationId,
        actor:          EventActor,
        reason:         Option[String],
        checkEmpty:     Boolean
    ): IOResult[ActiveTechniqueCategoryId] = ???

    override def move(
        categoryId:     ActiveTechniqueCategoryId,
        intoParent:     ActiveTechniqueCategoryId,
        optionNewName:  Option[ActiveTechniqueCategoryId],
        modificationId: ModificationId,
        actor:          EventActor,
        reason:         Option[String]
    ): IOResult[ActiveTechniqueCategoryId] = ???

  }

  val initDirectivesTree =
    new InitDirectivesTree(mockTechniques.techniqueRepo, directiveRepo, directiveRepo, new StringUuidGeneratorImpl())

  initDirectivesTree.copyReferenceLib(includeSystem = true)

  {
    val modId = ModificationId(s"init directives in lib")
    ZIO
      .foreachDiscard(directives.all) {
        case (t, list) =>
          val at = ActiveTechniqueId(t.id.name.value)
          ZIO.foreachDiscard(list) { d =>
            if (d.isSystem) {
              directiveRepo.saveSystemDirective(at, d, modId, TestActor.get, None)
            } else {
              directiveRepo.saveDirective(at, d, modId, TestActor.get, None)
            }
          }
      }
      .runNow
  }
}

class MockRules() {
  val t1: Long = System.currentTimeMillis()

  val rootRuleCategory: RuleCategory = RuleCategory(
    RuleCategoryId("rootRuleCategory"),
    "Rules",
    "This is the main category of Rules",
    RuleCategory(RuleCategoryId("category1"), "Category 1", "description of category 1", Nil) :: Nil,
    true
  )

  object ruleCategoryRepo extends RoRuleCategoryRepository with WoRuleCategoryRepository {

    import com.softwaremill.quicklens._

    // returns (parents, rule) if found
    def recGet(root: RuleCategory, id: RuleCategoryId): Option[(List[RuleCategory], RuleCategory)] = {
      if (root.id == id) Some((Nil, root))
      else {
        root.childs.foldLeft(Option.empty[(List[RuleCategory], RuleCategory)]) {
          case (found, cat) =>
            found match {
              case Some(x) => Some(x)
              case None    => recGet(cat, id).map { case (p, r) => (root :: p, r) }
            }
        }
      }
    }

    // apply modification for each rule
    @tailrec
    def recUpdate(toAdd: RuleCategory, parents: List[RuleCategory]): RuleCategory = {
      parents match {
        case Nil     => toAdd
        case p :: pp =>
          recUpdate(
            p.modify(_.childs).using(children => toAdd :: children.filterNot(_.id == toAdd.id)),
            pp
          )
      }
    }

    def inDelete(root: RuleCategory, id: RuleCategoryId): RuleCategory = {
      recGet(root, id) match {
        case Some((p :: pp, x)) => recUpdate(p.modify(_.childs).using(_.filterNot(_.id == id)), pp.reverse)
        case _                  => root
      }
    }

    val categories: Ref.Synchronized[RuleCategory] = Ref.Synchronized.make(rootRuleCategory).runNow

    override def get(id: RuleCategoryId): IOResult[RuleCategory] = {
      categories.get.flatMap(c => recGet(c, id).map(_._2).notOptional(s"category with id '${id.value}' not found"))
    }
    override def getRootCategory():       IOResult[RuleCategory] = categories.get

    override def create(
        that:   RuleCategory,
        into:   RuleCategoryId,
        modId:  ModificationId,
        actor:  EventActor,
        reason: Option[String]
    ): IOResult[RuleCategory] = {
      categories
        .updateZIO(cats => {
          recGet(cats, into) match {
            case None                    => Inconsistency(s"Error: missing parent category '${into.value}'").fail
            case Some((parents, parent)) =>
              recGet(cats, that.id) match {
                case Some((pp, p)) => Inconsistency(s"Error: category already exists '${(p :: pp.reverse).map(_.id.value)}'").fail
                // create in parent
                case None          => recUpdate(that, parent :: parents.reverse).succeed
              }
          }
        })
        .map(_ => that)
    }

    override def updateAndMove(
        that:   RuleCategory,
        into:   RuleCategoryId,
        modId:  ModificationId,
        actor:  EventActor,
        reason: Option[String]
    ): IOResult[RuleCategory] = {
      categories
        .updateZIO(cats => {
          recGet(cats, that.id) match {
            case None    => Inconsistency(s"Category '${that.id.value}' not found, can't move it").fail
            case Some(_) => // ok, move it
              recGet(cats, into) match {
                case None          => Inconsistency(s"Parent category '${into.value}' not found, can't move ${that.id.value} into it").fail
                case Some((pp, p)) =>
                  val pp2 = pp.map(inDelete(_, that.id))
                  val p2  = inDelete(p, that.id)
                  recUpdate(
                    p2.modify(_.childs).using(children => that :: children.filterNot(_.id == that.id)),
                    pp2.reverse
                  ).succeed
              }
          }
        })
        .map(_ => that)
    }

    override def delete(
        category:   RuleCategoryId,
        modId:      ModificationId,
        actor:      EventActor,
        reason:     Option[String],
        checkEmpty: Boolean
    ): IOResult[RuleCategoryId] = {
      categories.updateZIO(cats => inDelete(cats, category).succeed).map(_ => category)
    }
  }

  object rules {

    implicit def str2ruleId(s: String): RuleId = RuleId(RuleUid(s))

    val commmonRule: Rule = Rule(
      "hasPolicyServer-root",
      "Rudder system policy: basic setup (common)",
      rootRuleCategory.id,
      Set(AllTarget),
      Set(DirectiveId(DirectiveUid("common-root"))),
      "common-root rule",
      "",
      true,
      true,
      NoTags() // long desc / enabled / system / tags
    )

    val serverRoleRule: Rule = Rule(
      "server-roles",
      "Rudder system policy: Server roles",
      rootRuleCategory.id,
      Set(AllTarget),
      Set(DirectiveId(DirectiveUid("Server Roles"))),
      "Server Roles rule",
      "",
      true,
      true,
      NoTags() // long desc / enabled / system / tags
    )

    val distributeRule: Rule = Rule(
      "root-DP",
      "distributePolicy",
      rootRuleCategory.id,
      Set(AllTarget),
      Set(DirectiveId(DirectiveUid("Distribute Policy"))),
      "Distribute Policy rule",
      "",
      true,
      true,
      NoTags() // long desc / enabled / system / tags
    )

    val inventoryAllRule: Rule = Rule(
      "inventory-all",
      "Rudder system policy: daily inventory",
      rootRuleCategory.id,
      Set(AllTarget),
      Set(DirectiveId(DirectiveUid("inventory-all"))),
      "Inventory all rule",
      "",
      true,
      true,
      NoTags() // long desc / enabled / system / tags
    )

    val clockRule: Rule = Rule(
      "rule1",
      "10. Global configuration for all nodes",
      rootRuleCategory.id,
      Set(AllTarget),
      Set(DirectiveId(DirectiveUid("directive1"))),
      "global config for all nodes",
      "",
      true,
      false,
      NoTags() // long desc / enabled / system / tags
    )

    val rpmRule: Rule = Rule(
      "rule2",
      "50. Deploy PLOP STACK",
      rootRuleCategory.id,
      Set(AllTarget),
      Set(DirectiveId(DirectiveUid("directive2"))),
      "global config for all nodes",
      "",
      true,
      false,
      NoTags() // long desc / enabled / system / tags
    )

    val defaultRule: Rule = Rule(
      "ff44fb97-b65e-43c4-b8c2-0df8d5e8549f",
      "60-rule-technique-std-lib",
      rootRuleCategory.id,
      Set(AllTarget),
      Set(
        DirectiveId(DirectiveUid("16617aa8-1f02-4e4a-87b6-d0bcdfb4019f")), // pkg

        DirectiveId(DirectiveUid("e9a1a909-2490-4fc9-95c3-9d0aa01717c9")), // fileTemplate1

        DirectiveId(DirectiveUid("99f4ef91-537b-4e03-97bc-e65b447514cc")) // fileTemplate2
      ),
      "default rule",
      "",
      true,
      false,
      NoTags() // long desc / enabled / system / tags
    )

    val copyDefaultRule: Rule = Rule(
      "ff44fb97-b65e-43c4-b8c2-000000000000",
      "99-rule-technique-std-lib",
      rootRuleCategory.id,
      Set(AllTarget),
      Set(
        DirectiveId(DirectiveUid("99f4ef91-537b-4e03-97bc-e65b447514cc")) // fileTemplate2
      ),
      "updated copy of default rule",
      "",
      true,
      false,
      NoTags()                                                            // long desc / enabled / system / tags
    )

    val ncfTechniqueRule: Rule = Rule(
      "208716db-2675-43b9-ab57-bfbab84346aa",
      "50-rule-technique-ncf",
      rootRuleCategory.id,
      Set(TargetExclusion(TargetUnion(Set(AllTarget)), TargetUnion(Set(PolicyServerTarget(NodeId("root")))))),
      Set(DirectiveId(DirectiveUid("16d86a56-93ef-49aa-86b7-0d10102e4ea9"))),
      "ncf technique rule",
      "",
      true,
      false, // long desc / enabled / system

      MkTags(("datacenter", "Paris"), ("serverType", "webserver"))
    )

    val copyGitFileRule: Rule = Rule(
      "rulecopyGitFile",
      "90-copy-git-file",
      rootRuleCategory.id,
      Set(GroupTarget(NodeGroupId(NodeGroupUid("1111f5d3-8c61-4d20-88a7-bb947705ba8a")))), // g1 in MockNodeGroups
      Set(DirectiveId(DirectiveUid("directive-copyGitFile"))),
      "ncf technique rule",
      "",
      true,
      false,
      NoTags()                                                                             // long desc / enabled / system / tags
    )

    val gvd1Rule: Rule = Rule(
      "gvd-rule1",
      "10. Test gvd ordering",
      rootRuleCategory.id,
      Set(AllTarget),
      Set(DirectiveId(DirectiveUid("gvd-directive1")), DirectiveId(DirectiveUid("gvd-directive2"))),
      "test gvd ordering rule",
      "",
      true,
      false,
      NoTags() // long desc / enabled / system / tags
    )

    val all: List[Rule] = List(
      commmonRule,
      serverRoleRule,
      distributeRule,
      inventoryAllRule,
      clockRule,
      rpmRule,
      defaultRule,
      copyDefaultRule,
      ncfTechniqueRule,
      copyGitFileRule
    )
  }

  object ruleRepo extends RoRuleRepository with WoRuleRepository {

    val rulesMap: Ref.Synchronized[Map[RuleId, Rule]] = Ref.Synchronized.make(rules.all.map(r => (r.id, r)).toMap).runNow

    val predicate: Boolean => (Rule => Boolean) = (includeSytem: Boolean) =>
      (r: Rule) => if (includeSytem) true else r.isSystem == false

    override def getOpt(ruleId: RuleId): IOResult[Option[Rule]] =
      rulesMap.get.map(_.get(ruleId))

    override def getAll(includeSytem: Boolean): IOResult[Seq[Rule]] = {
      rulesMap.get.map(_.valuesIterator.filter(predicate(includeSytem)).toSeq)
    }

    override def getIds(includeSytem: Boolean): IOResult[Set[RuleId]] = {
      rulesMap.get.map(_.valuesIterator.collect { case r if (predicate(includeSytem)(r)) => r.id }.toSet)
    }

    override def create(rule: Rule, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[AddRuleDiff] = {
      rulesMap
        .updateZIO(rules => {
          rules.get(rule.id) match {
            case Some(_) =>
              Inconsistency(s"rule already exists: ${rule.id.serialize}").fail
            case None    =>
              (rules + (rule.id -> rule)).succeed
          }
        })
        .map(_ => AddRuleDiff(rule))
    }

    def buildRuleDiff(old: Rule, current: Rule): Option[ModifyRuleDiff] = {
      if (old == current) None
      else {
        val diff = Diff(old, current)
        Some(
          ModifyRuleDiff(
            current.id,
            current.name,
            diff(_.name),
            None,
            diff(_.targets),
            diff(_.directiveIds),
            diff(_.shortDescription),
            diff(_.longDescription),
            None, // mod reasons ?

            diff(_.isEnabledStatus),
            diff(_.isSystem),
            diff(_.categoryId),
            diff(_.tags.tags)
          )
        )
      }
    }

    override def update(
        rule:   Rule,
        modId:  ModificationId,
        actor:  EventActor,
        reason: Option[String]
    ): IOResult[Option[ModifyRuleDiff]] = {
      rulesMap
        .modifyZIO(rules => {
          rules.get(rule.id) match {
            case Some(r) if (r.isSystem) =>
              Inconsistency(s"rule is system (can't be updated here): ${rule.id.serialize}").fail
            case Some(r)                 =>
              (r, (rules + (rule.id -> rule))).succeed
            case None                    =>
              Inconsistency(s"rule does not exist: ${rule.id.serialize}").fail
          }
        })
        .map(buildRuleDiff(_, rule))
    }

    override def updateSystem(
        rule:   Rule,
        modId:  ModificationId,
        actor:  EventActor,
        reason: Option[String]
    ): IOResult[Option[ModifyRuleDiff]] = {
      rulesMap
        .modifyZIO(rules => {
          rules.get(rule.id) match {
            case Some(r) if (r.isSystem) =>
              (r, (rules + (rule.id -> rule))).succeed
            case Some(r)                 =>
              Inconsistency(s"rule is not system (can't be updated here): ${rule.id.serialize}").fail
            case None                    =>
              Inconsistency(s"rule does not exist: ${rule.id.serialize}").fail
          }
        })
        .map(buildRuleDiff(_, rule))
    }

    override def delete(
        id:     RuleId,
        modId:  ModificationId,
        actor:  EventActor,
        reason: Option[String]
    ): IOResult[DeleteRuleDiff] = {
      rulesMap
        .modifyZIO(rules => {
          rules.get(id) match {
            case Some(r) if (r.isSystem) =>
              Inconsistency(s"rule is system (can't be deleted here): ${id.serialize}").fail
            case Some(r)                 =>
              val m = (rules - id)
              (r, m).succeed
            case None                    =>
              Inconsistency(s"rule does not exist: ${id.serialize}").fail
          }
        })
        .map(DeleteRuleDiff(_))
    }

    override def deleteSystemRule(
        id:     RuleId,
        modId:  ModificationId,
        actor:  EventActor,
        reason: Option[String]
    ): IOResult[DeleteRuleDiff] = {
      rulesMap
        .modifyZIO(rules => {
          rules.get(id) match {
            case Some(r) if (r.isSystem) =>
              val m = (rules - id)
              (r, m).succeed
            case Some(r)                 =>
              Inconsistency(s"rule is not system (can't be deleted here): ${id.serialize}").fail
            case None                    =>
              Inconsistency(s"rule does not exist: ${id.serialize}").fail
          }
        })
        .map(DeleteRuleDiff(_))
    }

    override def swapRules(newRules: Seq[Rule]): IOResult[RuleArchiveId] = {
      // we need to keep system rules in old map, and filter out them in new one
      rulesMap.updateZIO { rules =>
        val systems         = rules.valuesIterator.filter(_.isSystem)
        val newRulesUpdated = newRules.filterNot(_.isSystem) ++ systems
        newRulesUpdated.map(r => (r.id, r)).toMap.succeed
      }.map(_ => RuleArchiveId(s"swap at ${DateTime.now().toString(ISODateTimeFormat.dateTime())}"))
    }

    override def deleteSavedRuleArchiveId(saveId: RuleArchiveId): IOResult[Unit] = ZIO.unit

    override def load(rule: Rule, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[Unit] = ???

    override def unload(ruleId: RuleId, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[Unit] = ???
  }
}

class MockConfigRepo(
    mockTechniques:       MockTechniques,
    mockDirectives:       MockDirectives,
    mockRules:            MockRules,
    mockNodeGroups:       MockNodeGroups,
    mockLdapQueryParsing: MockLdapQueryParsing
) {
  val configurationRepository = new ConfigurationRepositoryImpl(
    mockDirectives.directiveRepo,
    mockTechniques.techniqueRepo,
    mockRules.ruleRepo,
    mockNodeGroups.groupsRepo,
    mockDirectives.directiveRepo,
    mockTechniques.techniqueRevisionRepo,
    mockTechniques.ruleRevisionRepo,
    mockLdapQueryParsing.groupRevisionRepo
  )
}

class MockGlobalParam() {
  import com.normation.rudder.domain.properties.GenericProperty._

  val mode: InheritMode = {
    import com.normation.rudder.domain.properties.InheritMode._
    InheritMode(ObjectMode.Override, ArrayMode.Prepend, StringMode.Append)
  }

  val stringParam: GlobalParameter              =
    GlobalParameter("stringParam", GitVersion.DEFAULT_REV, "some string".toConfigValue, None, "a simple string param", None)
  // json: the key will be sorted alpha-num by Config lib; array value order is kept.
  val jsonParam:   GlobalParameter              = GlobalParameter
    .parse(
      "jsonParam",
      GitVersion.DEFAULT_REV,
      """{ "string":"a string", "array": [1, 3, 2], "json": { "var2":"val2", "var1":"val1"} }""",
      None,
      "a simple string param",
      None
    )
    .getOrElse(throw new RuntimeException("error in mock jsonParam"))
  val modeParam:   GlobalParameter              =
    GlobalParameter("modeParam", GitVersion.DEFAULT_REV, "some string".toConfigValue, Some(mode), "a simple string param", None)
  val systemParam: GlobalParameter              = GlobalParameter(
    "systemParam",
    GitVersion.DEFAULT_REV,
    "some string".toConfigValue,
    None,
    "a simple string param",
    Some(PropertyProvider.systemPropertyProvider)
  )
  val all:         Map[String, GlobalParameter] = List(stringParam, jsonParam, modeParam, systemParam).map(p => (p.name, p)).toMap

  val paramsRepo: paramsRepo = new paramsRepo
  class paramsRepo extends RoParameterRepository with WoParameterRepository {

    val paramsMap: Ref.Synchronized[Map[String, GlobalParameter]] =
      Ref.Synchronized.make[Map[String, GlobalParameter]](all).runNow

    override def getGlobalParameter(parameterName: String): IOResult[Option[GlobalParameter]] = {
      paramsMap.get.map(_.get(parameterName))
    }

    override def getAllGlobalParameters(): IOResult[Seq[GlobalParameter]] = {
      paramsMap.get.map(_.valuesIterator.toSeq)
    }

    override def saveParameter(
        parameter: GlobalParameter,
        modId:     ModificationId,
        actor:     EventActor,
        reason:    Option[String]
    ): IOResult[AddGlobalParameterDiff] = {
      paramsMap
        .updateZIO(params => {
          params.get(parameter.name) match {
            case Some(_) =>
              Inconsistency(s"parameter already exists: ${parameter.name}").fail
            case None    =>
              (params + (parameter.name -> parameter)).succeed
          }
        })
        .map(_ => AddGlobalParameterDiff(parameter))
    }

    override def updateParameter(
        parameter: GlobalParameter,
        modId:     ModificationId,
        actor:     EventActor,
        reason:    Option[String]
    ): IOResult[Option[ModifyGlobalParameterDiff]] = {
      paramsMap
        .modifyZIO(params => {
          params.get(parameter.name) match {
            case Some(old) =>
              (old, (params + (parameter.name -> parameter))).succeed
            case None      =>
              Inconsistency(s"param does not exist: ${parameter.name}").fail
          }
        })
        .map { old =>
          val diff = Diff(old, parameter)
          Some(
            ModifyGlobalParameterDiff(parameter.name, diff(_.value), diff(_.description), diff(_.provider), diff(_.inheritMode))
          )
        }
    }

    override def delete(
        parameterName: String,
        provider:      Option[PropertyProvider],
        modId:         ModificationId,
        actor:         EventActor,
        reason:        Option[String]
    ): IOResult[Option[DeleteGlobalParameterDiff]] = {
      paramsMap.modifyZIO(params => {
        params.get(parameterName) match {
          case Some(r) =>
            val m = (params - parameterName)
            (Some(DeleteGlobalParameterDiff(r)), m).succeed
          case None    =>
            (None, params).succeed
        }
      })
    }

    override def swapParameters(newParameters: Seq[GlobalParameter]): IOResult[ParameterArchiveId] = {
      paramsMap.set(newParameters.map(p => (p.name, p)).toMap) *> ParameterArchiveId("mock repo implementation").succeed
    }

    override def deleteSavedParametersArchiveId(saveId: ParameterArchiveId): IOResult[Unit] = {
      ZIO.unit
    }

  }
}

// internal storage format for node repository
final case class NodeDetails(info: NodeInfo, nInv: NodeInventory, mInv: Option[MachineInventory])
final case class NodeBase(
    pending:  Map[NodeId, NodeDetails],
    accepted: Map[NodeId, NodeDetails],
    deleted:  Map[NodeId, NodeDetails]
)

object MockNodes {
  val softwares: List[Software] = List(
    Software(SoftwareUuid("s00"), name = Some("s00"), version = Some(new Version("1.0"))),
    Software(SoftwareUuid("s01"), name = Some("s01"), version = Some(new Version("1.0"))),
    Software(SoftwareUuid("s02"), name = Some("s02"), version = Some(new Version("1.0"))),
    Software(SoftwareUuid("s03"), name = Some("s03"), version = Some(new Version("1.0"))),
    Software(SoftwareUuid("s04"), name = Some("s04"), version = Some(new Version("1.0"))),
    Software(SoftwareUuid("s05"), name = Some("s05"), version = Some(new Version("1.0"))),
    Software(SoftwareUuid("s06"), name = Some("s06"), version = Some(new Version("1.0"))),
    Software(SoftwareUuid("s07"), name = Some("s07"), version = Some(new Version("1.0"))),
    Software(SoftwareUuid("s08"), name = Some("s08"), version = Some(new Version("1.0"))),
    Software(SoftwareUuid("s09"), name = Some("s09"), version = Some(new Version("1.0"))),
    Software(SoftwareUuid("s10"), name = Some("s10"), version = Some(new Version("1.0"))),
    Software(SoftwareUuid("s11"), name = Some("s11"), version = Some(new Version("1.0"))),
    Software(SoftwareUuid("s12"), name = Some("s12"), version = Some(new Version("1.0"))),
    Software(SoftwareUuid("s13"), name = Some("s13"), version = Some(new Version("1.0")))
  )

  val softwareUpdates: List[SoftwareUpdate] = {
    val d0  = "2022-01-01-00:00:00Z"
    val id0 = "RHSA-2020-4566"
    val id1 = "CVE-2021-4034"
    List(
      SoftwareUpdate(
        "s00",
        Some("2.15.6~RC1"),
        Some("x86_64"),
        Some("yum"),
        SoftwareUpdateKind.Defect,
        None,
        Some("Some explanation"),
        Some(SoftwareUpdateSeverity.Critical),
        JsonSerializers.parseSoftwareUpdateDateTime(d0).toOption,
        Some(List(id0, id1))
      ),
      SoftwareUpdate(
        "s01",
        Some("1-23-RELEASE-1"),
        Some("x86_64"),
        Some("apt"),
        SoftwareUpdateKind.None,
        Some("default-repo"),
        None,
        None,
        None,
        None
      ), // we can have several time the same app

      SoftwareUpdate(
        "s01",
        Some("1-24-RELEASE-64"),
        Some("x86_64"),
        Some("apt"),
        SoftwareUpdateKind.Security,
        Some("security-backports"),
        None,
        Some(SoftwareUpdateSeverity.Other("backport")),
        None,
        Some(List(id1))
      )
    )
  }

  // a valid, not used pub key
  // cfengine key hash is: 081cf3aac62624ebbc83be7e23cb104d
  val PUBKEY =
    """-----BEGIN RSA PUBLIC KEY-----
MIIBCAKCAQEAlntroa72gD50MehPoyp6mRS5fzZpsZEHu42vq9KKxbqSsjfUmxnT
Rsi8CDvBt7DApIc7W1g0eJ6AsOfV7CEh3ooiyL/fC9SGATyDg5TjYPJZn3MPUktg
YBzTd1MMyZL6zcLmIpQBH6XHkH7Do/RxFRtaSyicLxiO3H3wapH20TnkUvEpV5Qh
zUkNM8vHZuu3m1FgLrK5NCN7BtoGWgeyVJvBMbWww5hS15IkCRuBkAOK/+h8xe2f
hMQjrt9gW2qJpxZyFoPuMsWFIaX4wrN7Y8ZiN37U2q1G11tv2oQlJTQeiYaUnTX4
z5VEb9yx2KikbWyChM1Akp82AV5BzqE80QIBIw==
-----END RSA PUBLIC KEY-----"""

  val emptyNodeReportingConfiguration: ReportingConfiguration = ReportingConfiguration(None, None, None)

  val id1: NodeId = NodeId("node1")
  val hostname1 = "node1.localhost"
  val admin1    = "root"
  val id2: NodeId = NodeId("node2")
  val hostname2 = "node2.localhost"
  val rootId: NodeId = NodeId("root")
  val rootHostname = "server.rudder.local"
  val rootAdmin    = "root"

  val rootNode: Node     = Node(
    rootId,
    "root",
    "",
    NodeState.Enabled,
    true,
    true,
    DateTime.parse("2021-01-30T01:20+01:00"),
    emptyNodeReportingConfiguration,
    Nil,
    Some(PolicyMode.Enforce),
    None
  )
  val root:     NodeInfo = NodeInfo(
    rootNode,
    rootHostname,
    Some(MachineInfo(MachineUuid("machine1"), VirtualMachineType(VmType.VirtualBox), None, None)),
    Linux(Debian, "Stretch", new Version("9.4"), None, new Version("4.5")),
    List("127.0.0.1", "192.168.0.100"),
    DateTime.parse("2021-01-30T01:20+01:00"),
    UndefinedKey,
    Seq(AgentInfo(CfeCommunity, Some(AgentVersion("7.0.0")), PublicKey(PUBKEY), Set())),
    rootId,
    rootAdmin,
    None,
    None,
    Some(NodeTimezone("UTC", "+00"))
  )

  val rootInventory: NodeInventory = NodeInventory(
    NodeSummary(
      root.id,
      AcceptedInventory,
      root.localAdministratorAccountName,
      root.hostname,
      root.osDetails,
      root.id,
      root.keyStatus
    ),
    name = None,
    description = None,
    ram = Some(MemorySize(100000)),
    swap = Some(MemorySize(1000000)),
    inventoryDate = None,
    receiveDate = DateFormaterService.parseDate("2021-01-31T03:05:00+01:00").toOption,
    archDescription = Some("x86_64"),
    lastLoggedUser = None,
    lastLoggedUserTime = None,
    agents = Seq(),
    serverIps = Seq(),
    machineId = None, // if we want several ids, we would have to ass an "alternate machine" field

    softwareIds = softwares.take(7).map(_.id),
    softwareUpdates = softwareUpdates,
    accounts = Seq("root", "httpd"),
    environmentVariables = Seq(EnvironmentVariable("THE_VAR", Some("THE_VAR value!"))),
    processes = Seq(Process(54432, Some("/bin/true"), Some(34.5f), Some(4235))),
    vms = Seq(),
    networks = Seq(
      Network(
        "enp0s3",
        None,
        InetAddressUtils.getAddressByName("10.0.2.15").toSeq,
        speed = Some("1000"),
        status = Some("Up")
      )
    ),
    fileSystems = Seq(
      FileSystem(
        "/",
        Some("ext4"),
        freeSpace = Some(MemorySize(12076449792L)),
        totalSpace = Some(MemorySize(55076449792L))
      )
    )
  )

  val node1Node: Node = Node(
    id1,
    "node1",
    "",
    NodeState.Enabled,
    false,
    false,
    DateTime.parse("2021-01-30T01:20+01:00"),
    emptyNodeReportingConfiguration,
    Nil,
    Some(PolicyMode.Enforce),
    None
  )

  val node1: NodeInfo = NodeInfo(
    node1Node,
    hostname1,
    Some(MachineInfo(MachineUuid("machine1"), VirtualMachineType(VmType.VirtualBox), None, None)),
    Linux(Debian, "Buster", new Version("10.6"), None, new Version("4.19")),
    List("192.168.0.10"),
    DateTime.parse("2021-01-30T01:20+01:00"),
    UndefinedKey,
    Seq(AgentInfo(CfeCommunity, Some(AgentVersion("7.0.0")), PublicKey(PUBKEY), Set())),
    rootId,
    admin1,
    None,
    Some(MemorySize(1460132)),
    Some(NodeTimezone("UTC", "+00"))
  )

  val nodeInventory1: NodeInventory = NodeInventory(
    NodeSummary(
      node1.id,
      AcceptedInventory,
      node1.localAdministratorAccountName,
      node1.hostname,
      node1.osDetails,
      root.id,
      node1.keyStatus
    ),
    name = None,
    description = None,
    ram = Some(MemorySize(1460132)),
    swap = Some(MemorySize(1000000)),
    inventoryDate = None,
    receiveDate = None,
    archDescription = None,
    lastLoggedUser = None,
    lastLoggedUserTime = None,
    agents = Seq(),
    serverIps = Seq(),
    machineId = None, // if we want several ids, we would have to ass an "alternate machine" field

    softwareIds = softwares.drop(5).take(10).map(_.id),
    softwareUpdates = softwareUpdates,
    accounts = Seq("root", "httpd"),
    environmentVariables = Seq(EnvironmentVariable("THE_VAR", Some("THE_VAR value!"))),
    processes = Seq(Process(54432, Some("/bin/true"), Some(34.5f), Some(4235))),
    vms = Seq(),
    networks = Seq(
      Network(
        "enp0s3",
        None,
        InetAddressUtils.getAddressByName("10.0.2.15").toSeq,
        speed = Some("1000"),
        status = Some("Up")
      )
    ),
    fileSystems = Seq(
      FileSystem(
        "/",
        Some("ext4"),
        freeSpace = Some(MemorySize(12076449792L)),
        totalSpace = Some(MemorySize(55076449792L))
      )
    )
  )

  // node1 us a relay
  val node2Node:      Node          = node1Node.copy(id = id2, name = id2.value)
  val node2:          NodeInfo      = node1.copy(node = node2Node, hostname = hostname2, policyServerId = root.id)
  val nodeInventory2: NodeInventory = {
    import com.softwaremill.quicklens._
    nodeInventory1
      .copy()
      .modify(_.main)
      .setTo(
        NodeSummary(
          node2.id,
          AcceptedInventory,
          node2.localAdministratorAccountName,
          node2.hostname,
          node2.osDetails,
          root.id,
          node2.keyStatus
        )
      )
      .modify(_.softwareUpdates)
      .setTo(Nil)
  }

  val dscNode1Node: Node = Node(
    NodeId("node-dsc"),
    "node-dsc",
    "",
    NodeState.Enabled,
    true,
    true, // is relay server

    DateTime.parse("2021-01-30T01:20+01:00"),
    emptyNodeReportingConfiguration,
    Nil,
    None,
    None
  )

  val dscNode1: NodeInfo = NodeInfo(
    dscNode1Node,
    "node-dsc.localhost",
    Some(MachineInfo(MachineUuid("machine1"), VirtualMachineType(VmType.VirtualBox), None, None)),
    Windows(Windows2012, "Windows 2012 youpla boom", new Version("2012"), Some("sp1"), new Version("win-kernel-2012")),
    List("192.168.0.5"),
    DateTime.parse("2021-01-30T01:20+01:00"),
    UndefinedKey,
    Seq(AgentInfo(AgentType.Dsc, Some(AgentVersion("7.0.0")), Certificate("windows-node-dsc-certificate"), Set())),
    rootId,
    admin1,
    None,
    Some(MemorySize(1460132)),
    None
  )

  val dscInventory1: NodeInventory = NodeInventory(
    NodeSummary(
      dscNode1.id,
      AcceptedInventory,
      dscNode1.localAdministratorAccountName,
      dscNode1.hostname,
      dscNode1.osDetails,
      dscNode1.policyServerId,
      dscNode1.keyStatus
    ),
    name = None,
    description = None,
    ram = None,
    swap = None,
    inventoryDate = None,
    receiveDate = None,
    archDescription = None,
    lastLoggedUser = None,
    lastLoggedUserTime = None,
    agents = Seq(),
    serverIps = Seq(),
    machineId = None, // if we want several ids, we would have to ass an "alternate machine" field

    softwareIds = softwares.drop(5).take(7).map(_.id),
    accounts = Seq(),
    environmentVariables = Seq(EnvironmentVariable("THE_VAR", Some("THE_VAR value!"))),
    processes = Seq(),
    vms = Seq(),
    networks = Seq(),
    fileSystems = Seq()
  )

  val allNodesInfo:       Map[NodeId, NodeInfo] = Map(rootId -> root, node1.id -> node1, node2.id -> node2)
  val allNodeFacts:       Map[NodeId, NodeFact] = Map(
    rootId      -> NodeFact.fromCompat(root, Right(FullInventory(rootInventory, None)), softwares.take(7)),
    node1.id    -> NodeFact.fromCompat(node1, Right(FullInventory(nodeInventory1, None)), softwares.drop(5).take(10)),
    node2.id    -> NodeFact.fromCompat(node2, Right(FullInventory(nodeInventory2, None)), softwares.drop(5).take(10)),
    dscNode1.id -> NodeFact.fromCompat(dscNode1, Right(FullInventory(dscInventory1, None)), softwares.drop(5).take(7))
  )
  val defaultModesConfig: NodeModeConfig        = NodeModeConfig(
    globalComplianceMode = GlobalComplianceMode(FullCompliance, 30),
    nodeHeartbeatPeriod = None,
    globalAgentRun = AgentRunInterval(None, 5, 0, 0, 0),
    nodeAgentRun = None,
    globalPolicyMode = GlobalPolicyMode(PolicyMode.Enforce, PolicyModeOverrides.Always),
    nodePolicyMode = None
  )

  val rootNodeConfig: NodeConfiguration = NodeConfiguration(
    nodeInfo = root,
    modesConfig = defaultModesConfig,
    runHooks = List(),
    policies = List[Policy](),
    nodeContext = Map[String, Variable](),
    parameters = Set[ParameterForConfiguration](),
    isRootServer = true
  )

  val node1NodeConfig: NodeConfiguration = NodeConfiguration(
    nodeInfo = node1,
    modesConfig = defaultModesConfig,
    runHooks = List(),
    policies = List[Policy](),
    nodeContext = Map[String, Variable](),
    parameters = Set[ParameterForConfiguration](),
    isRootServer = false
  )

  val node2NodeConfig: NodeConfiguration = NodeConfiguration(
    nodeInfo = node2,
    modesConfig = defaultModesConfig,
    runHooks = List(),
    policies = List[Policy](),
    nodeContext = Map[String, Variable](),
    parameters = Set[ParameterForConfiguration](),
    isRootServer = false
  )

  /**
   * Some more nodes
   */
  val nodeIds: Set[NodeId] = (
    for {
      i <- 0 to 10
    } yield {
      NodeId(s"${i}")
    }
  ).toSet

  def newNode(id: NodeId): Node =
    Node(id, "", "", NodeState.Enabled, false, false, DateTime.now, ReportingConfiguration(None, None, None), Nil, None, None)

  val nodes: Map[NodeId, NodeInfo] = (
    Set(root, node1, node2) ++ nodeIds.map { id =>
      NodeInfo(
        newNode(id),
        s"Node-${id}",
        None,
        Linux(Debian, "Jessie", new Version("7.0"), None, new Version("3.2")),
        Nil,
        DateTime.now,
        UndefinedKey,
        Seq(AgentInfo(CfeCommunity, None, PublicKey("rsa public key"), Set())),
        NodeId("root"),
        "",
        None,
        None,
        None
      )
    }
  ).map(n => (n.id, n)).toMap

}

class MockNodes() {
  val t2: Long = System.currentTimeMillis()

  object nodeFactStorage extends NodeFactStorage {
    val nodeFactBase: Ref[Map[NodeId, NodeFact]] = Ref.make(allNodeFacts).runNow

    override def save(nodeFact: NodeFact)(implicit attrs: SelectFacts = SelectFacts.all): IOResult[StorageChangeEventSave] = {
      nodeFactBase.modify { b =>
        val opt     = b.get(nodeFact.id)
        val updated = SelectFacts.merge(nodeFact, opt)
        (
          opt match {
            case Some(n) =>
              StorageChangeEventSave.Updated(n, updated, attrs)
            case None    =>
              StorageChangeEventSave.Created(nodeFact, attrs)
          },
          b + (nodeFact.id -> updated)
        )
      }
    }

    override def changeStatus(nodeId: NodeId, status: InventoryStatus): IOResult[StorageChangeEventStatus] = {
      nodeFactBase.modify(base => {
        base.get(nodeId) match {
          case Some(n) =>
            (StorageChangeEventStatus.Done(nodeId), base + (n.id -> n.modify(_.rudderSettings.status).setTo(status)))
          case None    => (StorageChangeEventStatus.Noop(nodeId), base)
        }
      })
    }

    override def delete(nodeId: NodeId)(implicit attrs: SelectFacts): IOResult[StorageChangeEventDelete] = {
      nodeFactBase.modify(b => {
        b.get(nodeId) match {
          case Some(n) => (StorageChangeEventDelete.Deleted(n, attrs), b.removed(nodeId))
          case None    => (StorageChangeEventDelete.Noop(nodeId), b)
        }
      })
    }

    def get(nodeId: NodeId, status: InventoryStatus)(implicit attrs: SelectFacts): IOResult[Option[NodeFact]] = {
      nodeFactBase.get.map(_.get(nodeId) match {
        case Some(n) if (n.rudderSettings.status == status) => Some(SelectFacts.mask(n))
        case _                                              => None
      })
    }

    override def getPending(nodeId: NodeId)(implicit attrs: SelectFacts): IOResult[Option[NodeFact]] = {
      get(nodeId, PendingInventory)
    }

    override def getAccepted(nodeId: NodeId)(implicit attrs: SelectFacts): IOResult[Option[NodeFact]] = {
      get(nodeId, AcceptedInventory)
    }

    def getAll(status: InventoryStatus)(implicit attrs: SelectFacts): IOStream[NodeFact] = {
      ZStream
        .fromZIO(
          nodeFactBase.get.map(base => {
            ZStream.fromIterable(base.collect {
              case (_, f) if (f.rudderSettings.status == status) => SelectFacts.mask(f)
            })
          })
        )
        .flatten
    }

    override def getAllPending()(implicit attrs: SelectFacts): IOStream[NodeFact] = {
      getAll(PendingInventory)
    }

    override def getAllAccepted()(implicit attrs: SelectFacts): IOStream[NodeFact] = {
      getAll(AcceptedInventory)
    }
  }

  object softwareDao extends ReadOnlySoftwareDAO {
    implicit val qc: QueryContext = QueryContext.todoQC

    val softRef: Ref.Synchronized[Map[SoftwareUuid, Software]] =
      Ref.Synchronized.make(MockNodes.softwares.map(s => (s.id, s)).toMap).runNow

    override def getSoftware(ids: Seq[SoftwareUuid]): IOResult[Seq[Software]] = {
      softRef.get.map(_.map(_._2).toList)
    }

    override def getSoftwareByNode(nodeIds: Set[NodeId], status: InventoryStatus): IOResult[Map[NodeId, Seq[Software]]] = {
      for {
        facts <- nodeFactRepo.slowGetAllCompat(status, SelectFacts.softwareOnly).runCollect
      } yield {
        (facts.collect {
          case f if (nodeIds.contains(f.id)) =>
            (
              f.id,
              f.software.map(_.toSoftware)
            )
        }).toMap
      }
    }

    override def getAllSoftwareIds(): IOResult[Set[SoftwareUuid]] = softRef.get.map(_.keySet)

    override def getSoftwaresForAllNodes(): IOResult[Set[SoftwareUuid]] = {
      ???
    }

    def getNodesbySofwareName(softName: String): IOResult[List[(NodeId, Software)]] = {
      for {
        facts <- nodeFactRepo.slowGetAllCompat(AcceptedInventory, SelectFacts.softwareOnly).runCollect
      } yield {
        facts.collect {
          case f if (f.software.map(_.name).contains(f.id)) =>
            (
              f.id,
              f.software
                .find(_.name == softName)
                .getOrElse(throw new IllegalArgumentException("for test - we just check it's here"))
                .toSoftware
            )
        }.toList
      }
    }
  }

  val getNodesbySofwareName = new SoftDaoGetNodesbySofwareName(softwareDao)

  val tenantService = DefaultTenantService.make(Nil).runNow

  val nodeFactRepo: CoreNodeFactRepository =
    CoreNodeFactRepository.make(nodeFactStorage, getNodesbySofwareName, tenantService, Chunk()).runNow

  object queryProcessor extends QueryProcessor {

    import cats.implicits._
    import com.normation.inventory.ldap.core.LDAPConstants._
    import com.normation.rudder.domain.RudderLDAPConstants._

    // return the value to corresponding to the given object/attribute
    def buildValues(objectName: String, attribute: String): PureResult[NodeFact => List[String]] = {
      objectName match {
        case OC_NODE =>
          attribute match {
            case "OS"                 => Right((n: NodeFact) => List(n.os.os.name))
            case A_NODE_UUID          => Right((n: NodeFact) => List(n.id.value))
            case A_HOSTNAME           => Right((n: NodeFact) => List(n.fqdn))
            case A_OS_NAME            => Right((n: NodeFact) => List(n.os.os.name))
            case A_OS_FULL_NAME       => Right((n: NodeFact) => List(n.os.fullName))
            case A_OS_VERSION         => Right((n: NodeFact) => List(n.os.version.value))
            case A_OS_SERVICE_PACK    => Right((n: NodeFact) => n.os.servicePack.toList)
            case A_OS_KERNEL_VERSION  => Right((n: NodeFact) => List(n.os.kernelVersion.value))
            case A_ARCH               => Right((n: NodeFact) => n.archDescription.toList)
            case A_STATE              => Right((n: NodeFact) => List(n.rudderSettings.state.name))
            case A_OS_RAM             => Right((n: NodeFact) => n.ram.map(_.size.toString).toList)
            case A_OS_SWAP            => Right((n: NodeFact) => n.swap.map(_.size.toString).toList)
            case A_AGENTS_NAME        => Right((n: NodeFact) => List(n.rudderAgent.agentType.id))
            case A_ACCOUNT            => Right((n: NodeFact) => n.accounts.toList)
            case A_LIST_OF_IP         => Right((n: NodeFact) => n.ipAddresses.map(_.inet).toList)
            case A_ROOT_USER          => Right((n: NodeFact) => List(n.rudderAgent.user))
            case A_INVENTORY_DATE     =>
              Right((n: NodeFact) =>
                List(n.lastInventoryDate.getOrElse(n.factProcessedDate).toString(ISODateTimeFormat.dateTimeNoMillis()))
              )
            case A_POLICY_SERVER_UUID => Right((n: NodeFact) => List(n.rudderSettings.policyServerId.value))
            case _                    => Left(Inconsistency(s"object '${objectName}' doesn't have attribute '${attribute}'"))
          }
        case x       => Left(Unexpected(s"Case value '${x}' for query processor not yet implemented in test, see `MockServices.scala`"))
      }
    }

    def compare(nodeValues: List[String], comparator: CriterionComparator, expectedValue: String): PureResult[Boolean] = {
      comparator match {
        case Exists    => Right(nodeValues.nonEmpty)
        case NotExists => Right(nodeValues.isEmpty)
        case Equals    => Right(nodeValues.exists(_ == expectedValue))
        case NotEquals => Right(nodeValues.nonEmpty && nodeValues.forall(_ != expectedValue))
        case Regex     =>
          try {
            val p = expectedValue.r.pattern
            Right(nodeValues.exists(p.matcher(_).matches()))
          } catch {
            case NonFatal(ex) => Left(Unexpected(s"Error in regex comparator in test: ${ex.getMessage}"))
          }
        case x         => Left(Unexpected(s"Comparator '${x} / ${expectedValue}' not yet implemented in test, see `MockServices.scala`"))
      }
    }

    def filterForLine(line: CriterionLine, nodes: List[NodeFact]): PureResult[List[NodeFact]] = {
      for {
        values   <- buildValues(line.objectType.objectType, line.attribute.name)
        matching <- nodes.traverse(n => compare(values(n), line.comparator, line.value).map(if (_) Some(n) else None))
      } yield {
        matching.flatten
      }
    }

    def filterForLines(
        lines:   List[CriterionLine],
        combine: CriterionComposition,
        nodes:   List[NodeFact]
    ): PureResult[List[NodeFact]] = {
      for {
        byLine <- lines.traverse(filterForLine(_, nodes))
      } yield {
        combine match {
          case And =>
            (nodes :: byLine).reduce((a, b) => a.intersect(b))
          case Or  =>
            (Nil :: byLine).reduce((a, b) => a ++ b)
        }
      }
    }

    override def process(query: Query): Box[Seq[NodeId]] = {
      for {
        nodes    <- nodeFactStorage.nodeFactBase.get
        matching <- filterForLines(query.criteria, query.composition, nodes.map(_._2).toList).toIO
      } yield {
        matching.map(_.id).toSeq
      }
    }.toBox

    override def processOnlyId(query: Query): Box[Seq[NodeId]] = process(query).map(_.toSeq)
  }

  val nodeInfoService         = new NodeInfoServiceProxy(nodeFactRepo)
  val fullInventoryRepository = new MockNodeFactFullInventoryRepositoryProxy(nodeFactRepo)
  val woNodeRepository        = new WoFactNodeRepositoryProxy(nodeFactRepo)

  object newNodeManager extends NewNodeManager {
    implicit val qc: QueryContext = QueryContext.todoQC

    val list = new FactListNewNodes(nodeFactRepo)

    override def listNewNodes()(implicit qc: QueryContext): IOResult[Seq[CoreNodeFact]] = list.listNewNodes()

    override def accept(id: NodeId)(implicit cc: ChangeContext): IOResult[CoreNodeFact] = {
      nodeFactRepo.changeStatus(id, AcceptedInventory) *>
      nodeFactRepo
        .getCompat(id, AcceptedInventory)
        .notOptional(s"Accepted node '${id.value}' is missing")
    }

    override def refuse(id: NodeId)(implicit cc: ChangeContext): IOResult[CoreNodeFact] = {
      nodeFactRepo
        .getCompat(id, PendingInventory)
        .flatMap {
          case None    => Inconsistency(s"node not found").fail
          case Some(x) => nodeFactRepo.delete(id) *> x.succeed
        }
    }

    override def acceptAll(ids: Seq[NodeId])(implicit cc: ChangeContext): IOResult[Seq[CoreNodeFact]] = {
      ZIO.foreach(ids)(accept)
    }

    override def refuseAll(ids: Seq[NodeId])(implicit cc: ChangeContext): IOResult[Seq[CoreNodeFact]] = {
      ZIO.foreach(ids)(refuse)
    }
  }
}

class MockNodeGroups(nodesRepo: MockNodes) {

  object groupsRepo extends RoNodeGroupRepository with WoNodeGroupRepository {

    implicit val ordering: NodeGroupCategoryOrdering.type = com.normation.rudder.repository.NodeGroupCategoryOrdering

    val categories: Ref.Synchronized[FullNodeGroupCategory] = Ref.Synchronized
      .make(FullNodeGroupCategory(NodeGroupCategoryId("GroupRoot"), "GroupRoot", "root of group categories", Nil, Nil, true))
      .runNow

    override def getFullGroupLibrary(): IOResult[FullNodeGroupCategory] = categories.get

    override def getNodeGroupOpt(id: NodeGroupId):      IOResult[Option[(NodeGroup, NodeGroupCategoryId)]] = {
      categories.get.map(root => {
        ((root.allGroups.get(id), root.categoryByGroupId.get(id)) match {
          case (Some(g), Some(c)) => Some((g.nodeGroup, c))
          case _                  => None
        })
      })
    }
    override def getNodeGroupCategory(id: NodeGroupId): IOResult[NodeGroupCategory]                        = {
      for {
        root <- categories.get
        cid  <- root.categoryByGroupId.get(id).notOptional(s"Category for group '${id.serialize}' not found")
        cat  <- root.allCategories.get(cid).map(_.toNodeGroupCategory).notOptional(s"Category '${cid.value}' not found")
      } yield cat
    }
    override def getAll():                              IOResult[Seq[NodeGroup]]                           = categories.get.map(_.allGroups.values.map(_.nodeGroup).toSeq)
    override def getAllByIds(ids: Seq[NodeGroupId]):    IOResult[Seq[NodeGroup]]                           = {
      categories.get.map(_.allGroups.values.map(_.nodeGroup).filter(g => ids.contains(g.id)).toSeq)
    }

    override def getAllNodeIds(): IOResult[Map[NodeGroupId, Set[NodeId]]] =
      categories.get.map(_.allGroups.values.map(_.nodeGroup).map(g => (g.id, g.serverList)).toMap)

    override def getAllNodeIdsChunk(): IOResult[Map[NodeGroupId, Chunk[NodeId]]] =
      categories.get.map(_.allGroups.values.map(_.nodeGroup).map(g => (g.id, Chunk.fromIterable(g.serverList))).toMap)

    override def getGroupsByCategory(
        includeSystem: Boolean
    ): IOResult[ISortedMap[List[NodeGroupCategoryId], CategoryAndNodeGroup]] = {
      def getChildren(
          parents: List[NodeGroupCategoryId],
          root:    FullNodeGroupCategory
      ): ISortedMap[List[NodeGroupCategoryId], CategoryAndNodeGroup] = {
        val c = ISortedMap(
          (root.id :: parents, CategoryAndNodeGroup(root.toNodeGroupCategory, root.ownGroups.values.map(_.nodeGroup).toSet))
        )
        root.subCategories.foldLeft(c) { case (current, n) => current ++ getChildren(root.id :: parents, n) }
      }
      val c = categories.get.map { root =>
        val all = getChildren(Nil, root)
        if (includeSystem) all
        else {
          all.filterNot(_._2.category.isSystem)
        }
      }
      c
    }

    override def getCategoryHierarchy: IOResult[ISortedMap[List[NodeGroupCategoryId], NodeGroupCategory]] = {
      getGroupsByCategory(true).map(
        _.map { case (k, v) => (k, v.category) }
      )
    }

    override def findGroupWithAnyMember(nodeIds: Seq[NodeId]): IOResult[Seq[NodeGroupId]] = {
      categories.get.map { root =>
        root.allGroups.collect {
          case (_, c) if (c.nodeGroup.serverList.exists(s => nodeIds.contains(s))) => c.nodeGroup.id
        }.toSeq
      }
    }

    override def findGroupWithAllMember(nodeIds: Seq[NodeId]): IOResult[Seq[NodeGroupId]] = {
      categories.get.map { root =>
        root.allGroups.collect {
          case (_, c) if (nodeIds.forall(s => c.nodeGroup.serverList.contains(s))) => c.nodeGroup.id
        }.toSeq
      }
    }

    override def getRootCategoryPure(): IOResult[NodeGroupCategory] = categories.get.map(_.toNodeGroupCategory)
    override def getRootCategory():     NodeGroupCategory           = getRootCategoryPure().runNow

    override def getAllGroupCategories(includeSystem: Boolean): IOResult[Seq[NodeGroupCategory]] = {
      categories.get
        .map(_.allCategories.values.collect {
          case c if (!c.isSystem || c.isSystem && includeSystem) => c.toNodeGroupCategory
        })
        .map(_.toSeq)
    }

    override def getGroupCategory(id: NodeGroupCategoryId): IOResult[NodeGroupCategory] = {
      categories.get.flatMap(_.allCategories.get(id).notOptional(s"Category '${id.value}' not found").map(_.toNodeGroupCategory))
    }

    override def getParentGroupCategory(id: NodeGroupCategoryId): IOResult[NodeGroupCategory] = {
      categories.get.flatMap { root =>
        root.parentCategories.get(id).notOptional(s"Parent of category '${id.value}' not found").map(_.toNodeGroupCategory)
      }
    }

    // get parents from nearest to root
    def recGetParent(root: FullNodeGroupCategory, id: NodeGroupCategoryId): List[FullNodeGroupCategory] = {
      root.parentCategories.get(id) match {
        case Some(cat) => cat :: recGetParent(root, cat.id)
        case None      => Nil
      }
    }

    override def getParents_NodeGroupCategory(id: NodeGroupCategoryId): IOResult[List[NodeGroupCategory]] = {
      categories.get.map(recGetParent(_, id).map(_.toNodeGroupCategory))
    }

    override def getAllNonSystemCategories(): IOResult[Seq[NodeGroupCategory]] = getAllGroupCategories(false)

    // returns (parents, group) if found
    def recGetCat(
        root: FullNodeGroupCategory,
        id:   NodeGroupCategoryId
    ): Option[(List[FullNodeGroupCategory], FullNodeGroupCategory)] = {
      if (root.id == id) Some((Nil, root))
      else {
        root.subCategories.foldLeft(Option.empty[(List[FullNodeGroupCategory], FullNodeGroupCategory)]) {
          case (found, cat) =>
            found match {
              case Some(x) => Some(x)
              case None    => recGetCat(cat, id).map { case (p, r) => (root :: p, r) }
            }
        }
      }
    }

    // Add `toAdd` at the bottom of the list (which goes from direct parent to root),
    // and modify children category along the way.
    // So, if there is a non-empty list, head is direct parent: replace toAdd in children, then recurse
    @tailrec
    def recUpdateCat(toAdd: FullNodeGroupCategory, parents: List[FullNodeGroupCategory]): FullNodeGroupCategory = {
      import com.softwaremill.quicklens._
      parents match {
        case Nil     => toAdd
        case p :: pp =>
          recUpdateCat(
            p.modify(_.subCategories).using(children => toAdd :: children.filterNot(_.id == toAdd.id)),
            pp
          )
      }
    }

    def inDeleteCat(root: FullNodeGroupCategory, id: NodeGroupCategoryId): FullNodeGroupCategory = {
      import com.softwaremill.quicklens._
      recGetCat(root, id) match {
        case Some((p :: pp, x)) => recUpdateCat(p.modify(_.subCategories).using(_.filterNot(_.id == id)), pp.reverse)
        case _                  => root
      }
    }

    // only used in relay plugin
    override def createPolicyServerTarget(
        target: PolicyServerTarget,
        modId:  ModificationId,
        actor:  EventActor,
        reason: Option[String]
    ): IOResult[LDIFChangeRecord] = ???

    // create group into Some(cat) (group must not exists) or update group (into=None, group must exists)
    def createOrUpdate(group: NodeGroup, into: Option[NodeGroupCategoryId]): IOResult[Option[NodeGroup]] = {
      categories.modifyZIO(root => {
        for {
          catId <- into match {
                     case Some(catId) =>
                       root.allGroups.get(group.id) match {
                         case Some(n) => Inconsistency(s"Group with id '${n.nodeGroup.id.serialize}' already exists'").fail
                         case None    => catId.succeed
                       }
                     case None        =>
                       root.categoryByGroupId.get(group.id) match {
                         case None     => Inconsistency(s"Group '${group.id.serialize}' not found").fail
                         case Some(id) => id.succeed
                       }
                   }
          cat   <- root.allCategories.get(catId) match {
                     case None      => Inconsistency(s"Category '${catId.value}' not found").fail
                     case Some(cat) => cat.succeed
                   }
          // previous group, for diff
          old    = cat.ownGroups.get(group.id).map(_.nodeGroup)
        } yield {
          val t       = FullRuleTargetInfo(
            FullGroupTarget(GroupTarget(group.id), group),
            group.name,
            group.description,
            group.isEnabled,
            group.isSystem
          )
          import com.softwaremill.quicklens._
          val c       =
            cat.modify(_.targetInfos).using(children => t :: children.filterNot(_.target.target.target == t.target.target.target))
          val parents = recGetParent(root, c.id)
          (old, recUpdateCat(c, parents))
        }
      })
    }

    override def create(
        group: NodeGroup,
        into:  NodeGroupCategoryId,
        modId: ModificationId,
        actor: EventActor,
        why:   Option[String]
    ): IOResult[AddNodeGroupDiff] = {
      createOrUpdate(group, Some(into)).flatMap {
        case None    => AddNodeGroupDiff(group).succeed
        case Some(_) => Inconsistency(s"Group '${group.id.serialize}' was present'").fail
      }
    }

    override def update(
        group:          NodeGroup,
        modId:          ModificationId,
        actor:          EventActor,
        whyDescription: Option[String]
    ): IOResult[Option[ModifyNodeGroupDiff]] = {
      createOrUpdate(group, None).flatMap {
        case None      => Inconsistency(s"Group '${group.id.serialize}' was missing").fail
        case Some(old) =>
          val diff = Diff(old, group)
          Some(
            ModifyNodeGroupDiff(
              group.id,
              group.name,
              diff(_.name),
              diff(_.description),
              diff(_.properties),
              diff(_.query),
              diff(_.isDynamic),
              diff(_.serverList),
              diff(_.isEnabled),
              diff(_.isSystem)
            )
          ).succeed
      }
    }

    override def delete(
        id:             NodeGroupId,
        modId:          ModificationId,
        actor:          EventActor,
        whyDescription: Option[String]
    ): IOResult[DeleteNodeGroupDiff] = {
      def recDelete(id: NodeGroupId, current: FullNodeGroupCategory): FullNodeGroupCategory = {
        current.copy(
          targetInfos = current.targetInfos.filterNot(_.toTargetInfo.target.target == s"group:${id.serialize}"),
          subCategories = current.subCategories.map(recDelete(id, _))
        )
      }
      categories
        .modifyZIO(root => {
          root.allGroups.get(id) match {
            case None    => Inconsistency(s"Group already deleted").fail
            case Some(g) => (g, recDelete(id, root)).succeed
          }
        })
        .map(g => DeleteNodeGroupDiff(g.nodeGroup))
    }
    override def delete(
        id:             NodeGroupCategoryId,
        modificationId: ModificationId,
        actor:          EventActor,
        reason:         Option[String],
        checkEmpty:     Boolean
    ): IOResult[NodeGroupCategoryId] = {
      def recDelete(id: NodeGroupCategoryId, current: FullNodeGroupCategory): FullNodeGroupCategory = {
        current.copy(subCategories = current.subCategories.filterNot(_.id == id).map(c => recDelete(id, c)))
      }
      categories
        .updateZIO(root => {
          root.allCategories.get(id) match {
            case None      => root.succeed
            case Some(cat) => recDelete(id, root).succeed
          }
        })
        .map(_ => id)
    }

    override def updateDiffNodes(
        group:          NodeGroupId,
        add:            List[NodeId],
        delete:         List[NodeId],
        modId:          ModificationId,
        actor:          EventActor,
        whyDescription: Option[String]
    ): IOResult[Option[ModifyNodeGroupDiff]] = ???

    override def updateSystemGroup(
        group:  NodeGroup,
        modId:  ModificationId,
        actor:  EventActor,
        reason: Option[String]
    ): IOResult[Option[ModifyNodeGroupDiff]] = ???

    override def updateDynGroupNodes(
        group:          NodeGroup,
        modId:          ModificationId,
        actor:          EventActor,
        whyDescription: Option[String]
    ): IOResult[Option[ModifyNodeGroupDiff]] = ???

    override def move(
        group:          NodeGroupId,
        containerId:    NodeGroupCategoryId,
        modId:          ModificationId,
        actor:          EventActor,
        whyDescription: Option[String]
    ): IOResult[Option[ModifyNodeGroupDiff]] = ???

    override def deletePolicyServerTarget(policyServer: PolicyServerTarget): IOResult[PolicyServerTarget] = ???

    def updateCategory(
        t:    FullNodeGroupCategory,
        into: FullNodeGroupCategory,
        root: FullNodeGroupCategory
    ): FullNodeGroupCategory = {
      import com.softwaremill.quicklens._
      val c       = into.modify(_.subCategories).using(children => t :: children.filterNot(_.id == t.id))
      val parents = recGetParent(root, c.id)
      recUpdateCat(c, parents)
    }

    override def addGroupCategorytoCategory(
        that:           NodeGroupCategory,
        into:           NodeGroupCategoryId,
        modificationId: ModificationId,
        actor:          EventActor,
        reason:         Option[String]
    ): IOResult[NodeGroupCategory] = {
      categories
        .updateZIO(root => {
          for {
            cat <- root.allCategories.get(into).notOptional(s"Missing target parent category '${into.value}'")
          } yield {
            val t = FullNodeGroupCategory(that.id, that.name, that.description, Nil, Nil, that.isSystem)
            updateCategory(t, cat, root)
          }
        })
        .map(_ => that)
    }

    override def saveGroupCategory(
        category:       NodeGroupCategory,
        modificationId: ModificationId,
        actor:          EventActor,
        reason:         Option[String]
    ): IOResult[NodeGroupCategory] = {
      categories
        .updateZIO(root => {
          for {
            cat <- root.parentCategories.get(category.id).notOptional(s"Missing target parent category of '${category.id.value}'")
          } yield {
            val t = FullNodeGroupCategory(category.id, category.name, category.description, Nil, Nil, category.isSystem)
            updateCategory(t, cat, root)
          }
        })
        .map(_ => category)
    }

    override def saveGroupCategory(
        category:       NodeGroupCategory,
        containerId:    NodeGroupCategoryId,
        modificationId: ModificationId,
        actor:          EventActor,
        reason:         Option[String]
    ): IOResult[NodeGroupCategory] = {
      categories
        .updateZIO(root => {
          for {
            cat <- root.allCategories.get(containerId).notOptional(s"Missing target parent category '${containerId.value}'")
          } yield {
            val t = FullNodeGroupCategory(category.id, category.name, category.description, Nil, Nil, category.isSystem)
            updateCategory(t, cat, root)
          }
        })
        .map(_ => category)
    }
  }

  // data
  val g0props: List[GroupProperty] = List(
    GroupProperty(
      "stringParam", // inherited from global param

      GitVersion.DEFAULT_REV,
      "string".toConfigValue,
      Some(InheritMode.parseString("map").getOrElse(null)),
      Some(PropertyProvider("datasources"))
    ),
    GroupProperty
      .parse(
        "jsonParam",
        GitVersion.DEFAULT_REV,
        """{ "group":"string", "array": [5,6], "json": { "g1":"g1"} }""",
        None,
        None
      )
      .getOrElse(null) // for test
  )

  val g0:     NodeGroup                     = NodeGroup(
    NodeGroupId(NodeGroupUid("0000f5d3-8c61-4d20-88a7-bb947705ba8a")),
    "Real nodes",
    "",
    g0props,
    None,
    false,
    Set(MockNodes.rootId, MockNodes.node1.id, MockNodes.node2.id),
    true
  )
  val g1:     NodeGroup                     =
    NodeGroup(NodeGroupId(NodeGroupUid("1111f5d3-8c61-4d20-88a7-bb947705ba8a")), "Empty group", "", Nil, None, false, Set(), true)
  val g2:     NodeGroup                     = NodeGroup(
    NodeGroupId(NodeGroupUid("2222f5d3-8c61-4d20-88a7-bb947705ba8a")),
    "only root",
    "",
    Nil,
    None,
    false,
    Set(NodeId("root")),
    true
  )
  val g3:     NodeGroup                     = NodeGroup(
    NodeGroupId(NodeGroupUid("3333f5d3-8c61-4d20-88a7-bb947705ba8a")),
    "Even nodes",
    "",
    Nil,
    None,
    false,
    MockNodes.nodeIds.filter(_.value.toInt % 2 == 0),
    true
  )
  val g4:     NodeGroup                     = NodeGroup(
    NodeGroupId(NodeGroupUid("4444f5d3-8c61-4d20-88a7-bb947705ba8a")),
    "Odd nodes",
    "",
    Nil,
    None,
    false,
    MockNodes.nodeIds.filter(_.value.toInt % 2 != 0),
    true
  )
  val g5:     NodeGroup                     = NodeGroup(
    NodeGroupId(NodeGroupUid("5555f5d3-8c61-4d20-88a7-bb947705ba8a")),
    "Nodes id divided by 3",
    "",
    Nil,
    None,
    false,
    MockNodes.nodeIds.filter(_.value.toInt % 3 == 0),
    true
  )
  val g6:     NodeGroup                     = NodeGroup(
    NodeGroupId(NodeGroupUid("6666f5d3-8c61-4d20-88a7-bb947705ba8a")),
    "Nodes id divided by 5",
    "",
    Nil,
    None,
    false,
    MockNodes.nodeIds.filter(_.value.toInt % 5 == 0),
    true
  )
  val groups: Set[(NodeGroupId, NodeGroup)] = Set(g0, g1, g2, g3, g4, g5, g6).map(g => (g.id, g))

  val groupsTargets: Set[(GroupTarget, NodeGroup)] = groups.map { case (id, g) => (GroupTarget(g.id), g) }

  val groupsTargetInfos: Map[NodeGroupId, FullRuleTargetInfo] = (groupsTargets
    .map(gt => {
      (
        gt._1.groupId,
        FullRuleTargetInfo(
          FullGroupTarget(gt._1, gt._2),
          "",
          "",
          true,
          false
        )
      )
    }))
    .toMap

  val groupLib: FullNodeGroupCategory = FullNodeGroupCategory(
    NodeGroupCategoryId("GroupRoot"),
    "GroupRoot",
    "root of group categories",
    List(
      FullNodeGroupCategory(
        NodeGroupCategoryId("category1"),
        "category 1",
        "the first category",
        Nil,
        Nil,
        false
      )
    ),
    List(
      FullRuleTargetInfo(
        FullGroupTarget(
          GroupTarget(NodeGroupId(NodeGroupUid("a-group-for-root-only"))),
          NodeGroup(
            NodeGroupId(NodeGroupUid("a-group-for-root-only")),
            "Serveurs [€ðŋ] cassés",
            "Liste de l'ensemble de serveurs cassés à réparer",
            Nil,
            None,
            true,
            Set(NodeId("root")),
            true,
            false
          )
        ),
        "Serveurs [€ðŋ] cassés",
        "Liste de l'ensemble de serveurs cassés à réparer",
        true,
        false
      ),
      FullRuleTargetInfo(
        FullOtherTarget(PolicyServerTarget(NodeId("root"))),
        "special:policyServer_root",
        "The root policy server",
        true,
        true
      ),
      FullRuleTargetInfo(
        FullOtherTarget(AllTargetExceptPolicyServers),
        "special:all_exceptPolicyServers",
        "All groups without policy servers",
        true,
        true
      ),
      FullRuleTargetInfo(
        FullOtherTarget(AllTarget),
        "special:all",
        "All nodes",
        true,
        true
      )
    ) ++ groupsTargetInfos.valuesIterator,
    true
  )

  // init with full lib
  groupsRepo.categories.set(groupLib).runNow

}

class MockLdapQueryParsing(mockGit: MockGitConfigRepo, mockNodeGroups: MockNodeGroups) {
  ///// query parsing ////
  def DN(rdn: String, parent: DN) = new DN(new RDN(rdn), parent)
  val LDAP_BASEDN                 = new DN("cn=rudder-configuration")
  val LDAP_INVENTORIES_BASEDN: DN = DN("ou=Inventories", LDAP_BASEDN)
  val LDAP_INVENTORIES_SOFTWARE_BASEDN = LDAP_INVENTORIES_BASEDN

  val acceptedNodesDitImpl: InventoryDit = new InventoryDit(
    DN("ou=Accepted Inventories", LDAP_INVENTORIES_BASEDN),
    LDAP_INVENTORIES_SOFTWARE_BASEDN,
    "Accepted inventories"
  )
  val pendingNodesDitImpl:  InventoryDit = new InventoryDit(
    DN("ou=Pending Inventories", LDAP_INVENTORIES_BASEDN),
    LDAP_INVENTORIES_SOFTWARE_BASEDN,
    "Pending inventories"
  )
  val removedNodesDitImpl =
    new InventoryDit(DN("ou=Removed Inventories", LDAP_INVENTORIES_BASEDN), LDAP_INVENTORIES_SOFTWARE_BASEDN, "Removed Servers")
  val rudderDit           = new RudderDit(DN("ou=Rudder", LDAP_BASEDN))
  val nodeDit             = new NodeDit(LDAP_BASEDN)
  val inventoryDitService: InventoryDitService =
    new InventoryDitServiceImpl(pendingNodesDitImpl, acceptedNodesDitImpl, removedNodesDitImpl)
  val getSubGroupChoices = new DefaultSubGroupComparatorRepository(mockNodeGroups.groupsRepo)
  val nodeQueryData      = new NodeQueryCriteriaData(() => getSubGroupChoices)
  val ditQueryDataImpl   = new DitQueryData(acceptedNodesDitImpl, nodeDit, rudderDit, nodeQueryData)
  val queryParser: CmdbQueryParser with DefaultStringQueryParser with JsonQueryLexer = new CmdbQueryParser
    with DefaultStringQueryParser with JsonQueryLexer {
    override val criterionObjects = Map[String, ObjectCriterion]() ++ ditQueryDataImpl.criteriaMap
  }

  val xmlEntityMigration: XmlEntityMigration      = new XmlEntityMigration {
    override def getUpToDateXml(entity: Elem): Box[Elem] = Full(entity)
  }
  val groupRevisionRepo:  GroupRevisionRepository = new GitParseGroupLibrary(
    new NodeGroupCategoryUnserialisationImpl(),
    new NodeGroupUnserialisationImpl(new CmdbQueryParser {
      override def parse(query: StringQuery): Box[Query]       = ???
      override def lex(query: String):        Box[StringQuery] = ???
    }),
    mockGit.gitRepo,
    xmlEntityMigration,
    "groups"
  )

  // update g0 (0000f5d3-8c61-4d20-88a7-bb947705ba8a) with a real query
  val qs: String = {
    """
      |{"select":"nodeAndPolicyServer",
      |"composition":"Or",
      |"where":[
      | {"objectType":"node","attribute":"nodeId","comparator":"eq","value":"node1"},
      | {"objectType":"node","attribute":"nodeId","comparator":"eq","value":"node2"},
      | {"objectType":"node","attribute":"nodeId","comparator":"eq","value":"root"}
      |]}""".stripMargin
  }
  (for {
    res      <- mockNodeGroups.groupsRepo.getNodeGroup(NodeGroupId(NodeGroupUid("0000f5d3-8c61-4d20-88a7-bb947705ba8a")))
    (g0, cat) = res
    q        <- queryParser.apply(qs).toIO
    g         = g0.copy(query = Some(q))
    _        <- mockNodeGroups.groupsRepo.update(g, ModificationId("init query of g0"), TestActor.get, None)
  } yield ()).runNow

}

// It would be much simpler if the root classes were concrete, parameterized with a A type:
// case class Campaign[A](info: CampaignInfo, details: A) // or even info inlined
object DumbCampaignType extends CampaignType("dumb-campaign")

final case class DumbCampaignDetails(name: String) extends CampaignDetails

@jsonDiscriminator("campaignType")
sealed trait DumbCampaignTrait extends Campaign

@jsonHint(DumbCampaignType.value)
final case class DumbCampaign(info: CampaignInfo, details: DumbCampaignDetails) extends DumbCampaignTrait {
  val campaignType = DumbCampaignType
  val version      = 1
  def copyWithId(newId: CampaignId): Campaign = this.copy(info = info.copy(id = newId))

}

class MockCampaign() {

  val campaignSerializer = new CampaignSerializer()

  // init item: one campaign, with a finished event, one running, one scheduled
  val c0: DumbCampaign  = DumbCampaign(
    CampaignInfo(
      CampaignId("c0"),
      "first campaign",
      "a test campaign present when rudder boot",
      Enabled,
      WeeklySchedule(DayTime(Monday, 3, 42), DayTime(Monday, 4, 42))
    ),
    DumbCampaignDetails("campaign #0")
  )
  val e0: CampaignEvent =
    CampaignEvent(CampaignEventId("e0"), c0.info.id, "campaign #0", Finished, new DateTime(0), new DateTime(1), DumbCampaignType)

  object repo extends CampaignRepository {
    val items: Ref[Map[CampaignId, DumbCampaignTrait]] = Ref.make(Map[CampaignId, DumbCampaignTrait]((c0.info.id -> c0))).runNow

    override def getAll(typeFilter: List[CampaignType], statusFilter: List[CampaignStatusValue]): IOResult[List[Campaign]] = {
      for {
        campaigns <- items.get.map(_.valuesIterator.toList)
      } yield {
        Campaign.filter(campaigns, typeFilter, statusFilter)
      }
    }

    override def get(id: CampaignId): IOResult[Campaign] =
      items.get.map(_.get(id)).notOptional(s"Missing campaign with id '${id.serialize}''")
    override def save(c: Campaign):   IOResult[Campaign] = {
      c match {
        case x: DumbCampaignTrait => items.update(_ + (x.info.id -> x)) *> c.succeed
        case _ => Inconsistency("Unknown campaign type").fail
      }
    }

    def delete(id: CampaignId): IOResult[CampaignId] = {
      items.update(_ - id) *> id.succeed
    }
  }

  object dumbCampaignTranslator extends JSONTranslateCampaign {
    import com.normation.rudder.campaigns.CampaignSerializer._
    import zio.json._
    implicit val dumbCampaignDetailsDecoder: JsonDecoder[DumbCampaignDetails] = DeriveJsonDecoder.gen
    implicit val dumbCampaignDecoder:        JsonDecoder[DumbCampaignTrait]   = DeriveJsonDecoder.gen
    implicit val dumbCampaignDetailsEncoder: JsonEncoder[DumbCampaignDetails] = DeriveJsonEncoder.gen
    implicit val dumbCampaignEncoder:        JsonEncoder[DumbCampaignTrait]   = DeriveJsonEncoder.gen

    def read(): PartialFunction[(String, CampaignParsingInfo), IOResult[Campaign]] = {
      case (s, CampaignParsingInfo(DumbCampaignType, 1)) => s.fromJson[DumbCampaignTrait].toIO
    }

    def getRawJson(): PartialFunction[Campaign, IOResult[zio.json.ast.Json]] = { case c: DumbCampaignTrait => c.toJsonAST.toIO }

    def campaignType(): PartialFunction[String, CampaignType] = { case DumbCampaignType.value => DumbCampaignType }
  }

  object dumbCampaignEventRepository extends CampaignEventRepository {
    val items: Ref[Map[CampaignEventId, CampaignEvent]] = Ref.make(Map[CampaignEventId, CampaignEvent]((e0.id -> e0))).runNow

    def isActive(e: CampaignEvent): Boolean = {
      e.state == Scheduled || e.state == Running
    }

    def get(id: CampaignEventId):            IOResult[Option[CampaignEvent]] = {
      items.get.map(_.get(id))
    }
    def saveCampaignEvent(c: CampaignEvent): IOResult[CampaignEvent]         = {
      for {
        _ <- items.update(map => map + ((c.id, c)))
      } yield c
    }

    def getWithCriteria(
        states:       List[String],
        campaignType: List[CampaignType],
        campaignId:   Option[CampaignId],
        limit:        Option[Int],
        offset:       Option[Int],
        afterDate:    Option[DateTime],
        beforeDate:   Option[DateTime],
        order:        Option[String],
        asc:          Option[String]
    ): IOResult[List[CampaignEvent]] = {

      val allEvents            = items.get.map(_.values.toList)
      val campaignIdFiltered   = campaignId match {
        case None     => allEvents
        case Some(id) => allEvents.map(_.filter(_.campaignId == id))
      }
      val campaignTypeFiltered = campaignType match {
        case Nil => campaignIdFiltered
        case l   => campaignIdFiltered.map(_.filter(c => l.contains(c.campaignType)))
      }
      val stateFiltered        = states match {
        case Nil => campaignTypeFiltered
        case s   => campaignTypeFiltered.map(_.filter(ev => states.contains(s)))
      }

      val afterDateFiltered  = afterDate match {
        case None     => stateFiltered
        case Some(id) => stateFiltered.map(_.filter(_.end.isAfter(id)))
      }
      val beforeDateFiltered = beforeDate match {
        case None     => afterDateFiltered
        case Some(id) => afterDateFiltered.map(_.filter(_.start.isBefore(id)))
      }

      val ordered = order match {
        case Some("endDate" | "end")                =>
          beforeDateFiltered.map(
            _.sortWith((a, b) => {
              asc match {
                case Some("asc") => a.end.isBefore(b.end)
                case _           => a.end.isAfter(b.end)
              }
            })
          )
        case Some("startDate" | "start") | None | _ =>
          beforeDateFiltered.map(
            _.sortWith((a, b) => {
              asc match {
                case Some("asc") => a.start.isBefore(b.start)
                case _           => a.start.isAfter(b.start)
              }
            })
          )
      }
      (offset, limit) match {
        case (Some(offset), Some(limit)) =>
          ordered.map(_.drop(offset).take(limit))
        case (None, Some(limit))         =>
          ordered.map(_.take(limit))
        case (Some(offset), None)        =>
          ordered.map(_.drop(offset))
        case (None, None)                =>
          ordered
      }
    }

    def numberOfEventsByCampaign(campaignId: CampaignId): IOResult[Int] = items.get.map(_.size)

    def deleteEvent(
        id:           Option[CampaignEventId],
        states:       List[String],
        campaignType: Option[CampaignType],
        campaignId:   Option[CampaignId],
        afterDate:    Option[DateTime],
        beforeDate:   Option[DateTime]
    ): IOResult[Unit] = {

      val eventIdFiltered: CampaignEvent => Boolean = id match {
        case None     => (_ => true)
        case Some(id) => (ev => ev.id != id)
      }

      val campaignIdFiltered: CampaignEvent => Boolean = campaignId match {
        case None     => eventIdFiltered
        case Some(id) => ev => eventIdFiltered(ev) || ev.campaignId != id
      }

      val campaignTypeFiltered: CampaignEvent => Boolean = campaignType match {
        case None     => campaignIdFiltered
        case Some(id) => ev => campaignIdFiltered(ev) && ev.campaignType != id
      }

      val stateFiltered: CampaignEvent => Boolean = states match {
        case Nil => campaignTypeFiltered
        case s   => ev => campaignTypeFiltered(ev) && !states.contains(s)
      }

      val afterDateFiltered:  CampaignEvent => Boolean = afterDate match {
        case None     => stateFiltered
        case Some(id) => ev => stateFiltered(ev) && ev.end.isAfter(id)
      }
      val beforeDateFiltered: CampaignEvent => Boolean = beforeDate match {
        case None     => afterDateFiltered
        case Some(id) => ev => afterDateFiltered(ev) && ev.start.isBefore(id)
      }
      for {
        i      <- items.get.map(_.values)
        newList = i.filter(beforeDateFiltered)
        _      <- items.set(newList.map(ce => (ce.id, ce)).toMap)
      } yield {
        ()
      }
    }
  }

  val mainCampaignService = new MainCampaignService(dumbCampaignEventRepository, repo, new StringUuidGeneratorImpl(), 0, 0)

}
