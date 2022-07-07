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

package bootstrap.liftweb.checks.migration

import com.normation.GitVersion
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.eventlog.EventActor
import com.normation.eventlog.EventLog
import com.normation.eventlog.EventLogFilter
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.NodeId
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.ldap.core.InventoryDitService
import com.normation.inventory.ldap.core.InventoryDitServiceImpl
import com.normation.inventory.ldap.core.InventoryMapper
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.RoLDAPConnection
import com.normation.rudder.configuration.ActiveDirective
import com.normation.rudder.configuration.ConfigurationRepository
import com.normation.rudder.domain.NodeDit
import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.domain.policies.ActiveTechniqueCategory
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.rudder.domain.policies.AddDirectiveDiff
import com.normation.rudder.domain.policies.AddRuleDiff
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.ModifyRuleDiff
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.git.GitArchiveId
import com.normation.rudder.git.GitPath
import com.normation.rudder.git.GitRepositoryProvider
import com.normation.rudder.repository.DirectiveNotArchived
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.GitActiveTechniqueArchiver
import com.normation.rudder.repository.GitActiveTechniqueCategoryArchiver
import com.normation.rudder.repository.GitDirectiveArchiver
import com.normation.rudder.repository.WoRuleRepository
import com.normation.rudder.repository.ldap.InitTestLDAPServer
import com.normation.rudder.repository.ldap.LDAPDiffMapper
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.rudder.repository.ldap.LDAPGitRevisionProvider
import com.normation.rudder.repository.ldap.RoLDAPDirectiveRepository
import com.normation.rudder.repository.ldap.RoLDAPNodeGroupRepository
import com.normation.rudder.repository.ldap.RoLDAPRuleRepository
import com.normation.rudder.repository.ldap.WoLDAPDirectiveRepository
import com.normation.rudder.repository.ldap.WoLDAPRuleRepository
import com.normation.rudder.repository.ldap.ZioTReentrantLock
import com.normation.rudder.repository.xml.GitParseTechniqueLibrary
import com.normation.rudder.services.eventlog.EventLogFactory
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.policies.NodeConfigData
import com.normation.rudder.services.policies.TechniqueAcceptationUpdater
import com.normation.rudder.services.policies.TestTechniqueRepo
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.rudder.services.queries.DefaultStringQueryParser
import com.normation.rudder.services.queries.JsonQueryLexer
import com.normation.rudder.services.servers.PolicyServerManagementServiceImpl
import com.normation.rudder.services.user.TrivialPersonIdentService
import com.normation.utils.StringUuidGeneratorImpl
import com.unboundid.ldap.sdk.DN
import com.normation.errors.IOResult
import com.normation.inventory.ldap.core.LDAPConstants.A_DESCRIPTION
import com.normation.inventory.ldap.core.LDAPConstants.A_NAME
import zio.syntax._
import zio._
import com.normation.zio._
import com.softwaremill.quicklens._
import com.unboundid.ldap.sdk.RDN
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.lib.PersonIdent
import org.joda.time.DateTime

import java.io.File
import scala.util.Try

/**
 * A simple test class to check that the demo data file is up to date
 * with the schema (there may still be a desynchronization if both
 * demo-data, test data and test schema for UnboundID are not synchronized
 * with OpenLDAP Schema).
 */
@RunWith(classOf[JUnitRunner])
class TestMigrateSystemTechniques7_0 extends Specification {
  sequential
  // initialize test environnement - long and painful //

  val schema = (
      "00-core" ::
      "01-pwpolicy" ::
      "04-rfc2307bis" ::
      "05-rfc4876" ::
      "099-0-inventory" ::
      "099-1-rudder" :: Nil) map { name =>
    // toURI is needed for https://issues.rudder.io/issues/19186
    this.getClass.getClassLoader.getResource("ldap-data/schema/"+name+".ldif").toURI.getPath
  }

  val bootstrapLDIFs = ("bootstrap-6_2.ldif" ::
                        "init-root-server-6_2.ldif" ::
                        "init-relay1-server-6_2.ldif" ::
                        Nil) map { name =>
    // toURI is needed for https://issues.rudder.io/issues/19186
    this.getClass.getClassLoader.getResource("ldap-data/"+name).toURI.getPath
  }

  val root = NodeConfigData.root
  val relay1 = NodeConfigData.node1
    .modify(_.node.id.value).setTo("ba7e3ca5-a967-40d8-aa97-41a3ff450fd2")
    .modify(_.node.isPolicyServer).setTo(true)
    .modify(_.hostname).setTo("relay1.rudder.test")

  val nodeInfoService = new NodeInfoService {
    override def getAll(): IOResult[Map[NodeId, NodeInfo]] = List(root, relay1).map(x => (x.id, x)).toMap.succeed
    override def getNodeInfo(nodeId: NodeId): IOResult[Option[NodeInfo]] = ???
    override def getNodeInfos(nodeIds: Set[NodeId]): IOResult[Set[NodeInfo]] = ???
    override def getNumberOfManagedNodes: Int = ???
    override def getAllNodesIds(): IOResult[Set[NodeId]] = ???
    override def getAllNodes(): IOResult[Map[NodeId, Node]] = ???
    override def getAllSystemNodeIds(): IOResult[Seq[NodeId]] = ???
    override def getPendingNodeInfos(): IOResult[Map[NodeId, NodeInfo]] = ???
    override def getPendingNodeInfo(nodeId: NodeId): IOResult[Option[NodeInfo]] = ???
    override def getDeletedNodeInfos(): IOResult[Map[NodeId, NodeInfo]] = ???
    override def getDeletedNodeInfo(nodeId: NodeId): IOResult[Option[NodeInfo]] = ???
    override def getAllNodeInfos(): IOResult[Seq[NodeInfo]] = ???
  }

  val rudderDit = new RudderDit(new DN("ou=Rudder,cn=rudder-configuration") )

  val eventLogRepos = new EventLogRepository {
    override def saveEventLog(modId: ModificationId, eventLog: EventLog): IOResult[EventLog] = eventLog.succeed
    override def eventLogFactory: EventLogFactory = ???
    override def getEventLogByCriteria(criteria: Option[String], limit: Option[Int], orderBy: Option[String], extendedFilter: Option[String]): IOResult[Seq[EventLog]] = ???
    override def getEventLogById(id: Long): IOResult[EventLog] = ???
    override def getEventLogCount(criteria: Option[String], extendedFilter: Option[String]): IOResult[Long] = ???
    override def getEventLogByChangeRequest(changeRequest: ChangeRequestId, xpath: String, optLimit: Option[Int], orderBy: Option[String], eventTypeFilter: List[EventLogFilter]): IOResult[Vector[EventLog]] = ???
    override def getEventLogWithChangeRequest(id: Int): IOResult[Option[(EventLog, Option[ChangeRequestId])]] = ???
    override def getLastEventByChangeRequest(xpath: String, eventTypeFilter: List[EventLogFilter]): IOResult[Map[ChangeRequestId, EventLog]] = ???

    // the following implementation likely means that these methods should return UIO.unit
    override def saveAddDirective(modId: ModificationId, principal: EventActor, addDiff: AddDirectiveDiff, varsRootSectionSpec: SectionSpec, reason: Option[String]): IOResult[EventLog] = {
      ZIO.succeed(null)
    }
    override def saveModifyRule(modId: ModificationId, principal: EventActor, modifyDiff: ModifyRuleDiff, reason: Option[String]): IOResult[EventLog] = {
      ZIO.succeed(null)
    }

    override def saveAddRule(modId: ModificationId, principal: EventActor, addDiff: AddRuleDiff, reason: Option[String]): IOResult[EventLog] = {
      ZIO.succeed(null)
    }
  }

  val ldap = InitTestLDAPServer.newLdapConnectionProvider(schema, bootstrapLDIFs)
  val roLdap = ldap.asInstanceOf[LDAPConnectionProvider[RoLDAPConnection]]

  val policyServerService = new PolicyServerManagementServiceImpl(ldap, rudderDit, eventLogRepos)

  // technique repos & all
  val testEnv =  new TestTechniqueRepo(
      optGitRevisionProvider = Some((repo: GitRepositoryProvider) => new LDAPGitRevisionProvider(ldap, rudderDit, repo, "refs/heads/master"))
  )

  private[this] lazy val acceptedNodesDitImpl: InventoryDit = new InventoryDit(DN("ou=Accepted Inventories", LDAP_INVENTORIES_BASEDN), LDAP_INVENTORIES_SOFTWARE_BASEDN, "Accepted inventories")
  def DN(rdn: String, parent: DN) = new DN(new RDN(rdn),  parent)
  private[this] lazy val LDAP_BASEDN = new DN("cn=rudder-configuration")
  private[this] lazy val LDAP_INVENTORIES_BASEDN = DN("ou=Inventories", LDAP_BASEDN)
  private[this] lazy val LDAP_INVENTORIES_SOFTWARE_BASEDN = LDAP_INVENTORIES_BASEDN
  private[this] lazy val queryParser = new CmdbQueryParser with DefaultStringQueryParser with JsonQueryLexer {
    override val criterionObjects = Map()
  }

  private[this] lazy val pendingNodesDitImpl: InventoryDit = new InventoryDit(DN("ou=Pending Inventories", LDAP_INVENTORIES_BASEDN), LDAP_INVENTORIES_SOFTWARE_BASEDN, "Pending inventories")
  private[this] lazy val removedNodesDitImpl                      = new InventoryDit(DN("ou=Removed Inventories", LDAP_INVENTORIES_BASEDN), LDAP_INVENTORIES_SOFTWARE_BASEDN,"Removed Servers")
  private[this] lazy val inventoryDitService: InventoryDitService = new InventoryDitServiceImpl(pendingNodesDitImpl, acceptedNodesDitImpl, removedNodesDitImpl)

  private[this] lazy val inventoryMapper: InventoryMapper = new InventoryMapper(inventoryDitService, pendingNodesDitImpl, acceptedNodesDitImpl, removedNodesDitImpl)
  private[this] lazy val nodeDitImpl: NodeDit             = new NodeDit(LDAP_BASEDN)
  private[this] lazy val uptLibReadWriteMutex    = new ZioTReentrantLock("directive-lock")
  private[this] lazy val ldapEntityMapper: LDAPEntityMapper = new LDAPEntityMapper(rudderDit, nodeDitImpl, acceptedNodesDitImpl, queryParser, inventoryMapper)
  private[this] lazy val ldapDiffMapper = new LDAPDiffMapper(ldapEntityMapper, queryParser)
  private[this] lazy val uuidGen = new StringUuidGeneratorImpl

  val archiver = new GitDirectiveArchiver with GitActiveTechniqueArchiver with GitActiveTechniqueCategoryArchiver  {
    override def archiveDirective(directive: Directive, ptName: TechniqueName, catIds: List[ActiveTechniqueCategoryId], variableRootSection: SectionSpec, gitCommit: Option[(ModificationId, PersonIdent, Option[String])]): IOResult[GitPath] = ???
    override def deleteDirective(directiveId: DirectiveUid, ptName: TechniqueName, catIds: List[ActiveTechniqueCategoryId], gitCommit: Option[(ModificationId, PersonIdent, Option[String])]): IOResult[GitPath] = ???
    override def archiveActiveTechnique(activeTechnique: ActiveTechnique, parents: List[ActiveTechniqueCategoryId], gitCommit: Option[(ModificationId, PersonIdent, Option[String])]): IOResult[(GitPath, Seq[DirectiveNotArchived])] = ???
    override def deleteActiveTechnique(ptName: TechniqueName, parents: List[ActiveTechniqueCategoryId], gitCommit: Option[(ModificationId, PersonIdent, Option[String])]): IOResult[GitPath] = ???
    override def moveActiveTechnique(activeTechnique: ActiveTechnique, oldParents: List[ActiveTechniqueCategoryId], newParents: List[ActiveTechniqueCategoryId], gitCommit: Option[(ModificationId, PersonIdent, Option[String])]): IOResult[(GitPath, Seq[DirectiveNotArchived])] = ???
    override def archiveActiveTechniqueCategory(uptc: ActiveTechniqueCategory, getParents: List[ActiveTechniqueCategoryId], gitCommit: Option[(ModificationId, PersonIdent, Option[String])]): IOResult[GitPath] = ???
    override def deleteActiveTechniqueCategory(uptcId: ActiveTechniqueCategoryId, getParents: List[ActiveTechniqueCategoryId], gitCommit: Option[(ModificationId, PersonIdent, Option[String])]): IOResult[GitPath] = ???
    override def moveActiveTechniqueCategory(uptc: ActiveTechniqueCategory, oldParents: List[ActiveTechniqueCategoryId], newParents: List[ActiveTechniqueCategoryId], gitCommit: Option[(ModificationId, PersonIdent, Option[String])]): IOResult[GitPath] = ???
    override def commitActiveTechniqueLibrary(modId: ModificationId, commiter: PersonIdent, reason: Option[String]): IOResult[GitArchiveId] = ???
    override def getTags(): IOResult[Map[DateTime, GitArchiveId]] = ???
    override def getItemDirectory: File = ???
  }
  private[this] lazy val personIdentServiceImpl = new TrivialPersonIdentService

  lazy val gitParseTechniqueLibrary = new GitParseTechniqueLibrary(
        testEnv.draftParser
      , testEnv.repo
      , testEnv.gitRevisionProvider
      , "techniques"
      , "metadata.xml"
    )
  lazy val configurationRepository = new ConfigurationRepository {
    override def getTechnique(id: TechniqueId): IOResult[Option[Technique]] = testEnv.techniqueRepository.getLastTechniqueByName(id.name).succeed
    override def getDirective(id: DirectiveId): IOResult[Option[ActiveDirective]] = ???
    override def getDirectiveLibrary(ids: Set[DirectiveId]): IOResult[FullActiveTechniqueCategory] = ???
    override def getDirectiveRevision(uid: DirectiveUid): IOResult[List[GitVersion.RevisionInfo]] = ???
    override def getRule(id: RuleId): IOResult[Option[Rule]] = ???
  }
  private[this] lazy val roLdapDirectiveRepository = new RoLDAPDirectiveRepository(
        rudderDit, roLdap, ldapEntityMapper, testEnv.techniqueRepository, uptLibReadWriteMutex
  )
  val woLdapDirectiveRepository = new WoLDAPDirectiveRepository(
        roLdapDirectiveRepository,
        ldap,
        ldapDiffMapper,
        eventLogRepos,
        uuidGen,
        archiver,
        archiver,
        archiver,
        personIdentServiceImpl,
        false
      )

  testEnv.techniqueRepository.registerCallback(new TechniqueAcceptationUpdater(
      "UpdateAcceptationDatetime"
    , 50
    , roLdapDirectiveRepository
    , woLdapDirectiveRepository
    , testEnv.techniqueRepository
    , uuidGen
  ))

  private[this] lazy val groupLibReadWriteMutex  = new ZioTReentrantLock("group-lock")
  val roLdapNodeGroupRepository = new RoLDAPNodeGroupRepository(
      rudderDit, roLdap, ldapEntityMapper, groupLibReadWriteMutex
  )
  val ruleReadWriteMutex      = new ZioTReentrantLock("rule-lock")
  val roLdapRuleRepository                   = new RoLDAPRuleRepository(rudderDit, roLdap, ldapEntityMapper, ruleReadWriteMutex)
  val woLdapRuleRepository: WoRuleRepository = new WoLDAPRuleRepository(
      roLdapRuleRepository
    , ldap
    , ldapDiffMapper
    , roLdapNodeGroupRepository
    , eventLogRepos
    , null
    , personIdentServiceImpl
    , false
  )
  // end init test env //


  val checkMigrate = new CheckMigratedSystemTechniques(
      policyServerService
    , testEnv.repo
    , nodeInfoService
    , ldap
    , testEnv.techniqueRepository
    , testEnv.techniqueRepository
    , uuidGen
    , woLdapDirectiveRepository
    , woLdapRuleRepository
  )

  val addSpecialTargetAllPolicyServer = new CheckAddSpecialTargetAllPolicyServers(ldap)
  val modifyNameAndDescForClassicGroup = new CheckAddSpecialNodeGroupsDescription(ldap)


  // now, mv new-techniques in place of technique, git commit, update technique lib
//  org.slf4j.LoggerFactory.getLogger("techniques").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)
//  org.slf4j.LoggerFactory.getLogger("migration").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)
//  org.slf4j.LoggerFactory.getLogger("com.normation.rudder.repository").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)
//  org.slf4j.LoggerFactory.getLogger("com.normation.rudder.services.policies").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)


  sequential

  val numEntries = bootstrapLDIFs.foldLeft(0) { case (x,path) =>
    val reader = new com.unboundid.ldif.LDIFReader(path)
    var i = 0
    while(reader.readEntry != null) i += 1
    i + x
  }
  val dirDn = "techniqueCategoryId=Rudder Internal,techniqueCategoryId=Active Techniques,ou=Rudder,cn=rudder-configuration"
  val ruleDn = "ou=Rules,ou=Rudder,cn=rudder-configuration"
  val groupDn = "groupCategoryId=SystemGroups,groupCategoryId=GroupRoot,ou=Rudder,cn=rudder-configuration"

  "The in memory LDAP directory" should {

    "correctly load and read back test-entries" in {

      ldap.server.countEntries === numEntries
    }

    /* We had none technique before, so all system technique are new:
     * - common
     * - inventory
     * - server-common
     * - rudder-service-relayd
     * - rudder-service-webapp
     * - rudder-service-apache
     * - rudder-service-slapd
     * - rudder-service-postgresql
     * but we already added in bootstrap ldap active technique for common and inventory,
     * so there's 2 ldap entry already counted
     */
    "have 6 more entries for new actives directives after tech lib reload" in {
      FileUtils.deleteDirectory(new File(testEnv.configurationRepositoryRoot, "techniques"))
      FileUtils.moveDirectory(new File(testEnv.configurationRepositoryRoot, "new-techniques"), new File(testEnv.configurationRepositoryRoot, "techniques"))
      testEnv.repo.git.add().addFilepattern(".").call()
      testEnv.repo.git.add().addFilepattern(".").setUpdate(true).call()

      testEnv.repo.git.commit().setAuthor("test", "test@test").setMessage("Init system techniques v7.0").call
      testEnv.techniqueRepository.update(ModificationId("test"), EventActor("test"), None)

      ldap.server.exportToLDIF("/tmp/ldif-after-tech-lib-update", false, false)

      ldap.server.countEntries === numEntries + 6
    }

  }

  "After updating to 7.2, migration of group names and description is required" in {
    (modifyNameAndDescForClassicGroup.checkMigrationNeeded().runNow must beTrue)
  }

  "If we migrate to new name and description of group 'all-nodes-with-cfengine-agent' and 'hasPolicyServer-root' should still be present" in {
    modifyNameAndDescForClassicGroup.checks()
    ldap.server.entryExists(modifyNameAndDescForClassicGroup.all_nodeGroupDN.toString()) must beTrue
    ldap.server.entryExists(modifyNameAndDescForClassicGroup.all_nodeGroupPolicyServerDN.toString()) must beTrue
  }

  "Group name of 'all-nodes-with-cfengine-agent' should have been modified" in {
    (Option(ldap.server.getEntry(s"nodeGroupId=all-nodes-with-cfengine-agent,${groupDn}")).map(_.getAttribute(A_NAME).getValue))
      .getOrElse(Nil) mustEqual modifyNameAndDescForClassicGroup.allNodeGroupNewName
  }

  "Group name of 'hasPolicyServer-root' should have been modified" in {
    (Option(ldap.server.getEntry(s"nodeGroupId=hasPolicyServer-root,${groupDn}")).map(_.getAttribute(A_NAME).getValue))
      .getOrElse(Nil) mustEqual modifyNameAndDescForClassicGroup.allNodeGroupPolicyServerNewName
  }

  "Group description of 'all-nodes-with-cfengine-agent' should have been modified" in {
    (Option(ldap.server.getEntry(s"nodeGroupId=all-nodes-with-cfengine-agent,${groupDn}")).map(_.getAttribute(A_DESCRIPTION).getValue))
      .getOrElse(Nil) mustEqual modifyNameAndDescForClassicGroup.allNodeGroupNewDescription
  }

  "Group description of 'hasPolicyServer-root' should have been modified" in {
    (Option(ldap.server.getEntry(s"nodeGroupId=hasPolicyServer-root,${groupDn}")).map(_.getAttribute(A_DESCRIPTION).getValue))
      .getOrElse(Nil) mustEqual modifyNameAndDescForClassicGroup.allNodeGroupPolicyServerNewDescription
  }

  "After migration, groups name and description should not be modified" in {
    (modifyNameAndDescForClassicGroup.checkMigrationNeeded().runNow must beFalse)
  }

  "When initialized with 6.2 data, we don't have the new special target" in {
    (ldap.server.entryExists(addSpecialTargetAllPolicyServer.all_policyServersDN.toString()) must beFalse) and
    (addSpecialTargetAllPolicyServer.checkMigrationNeeded().runNow must beTrue)
  }

  "If we migrate new special target, it is added" in {
    addSpecialTargetAllPolicyServer.checks()
    ldap.server.entryExists(addSpecialTargetAllPolicyServer.all_policyServersDN.toString()) must beTrue
  }

  "When initialized with 6.2 data, migration is needed" in {
    val oldEntries = List(
      ldap.server.entryExists(s"ruleId=root-DP,${ruleDn}")
    , ldap.server.entryExists(s"ruleId=ba7e3ca5-a967-40d8-aa97-41a3ff450fd2-DP,${ruleDn}")
    , ldap.server.entryExists(s"ruleId=server-roles,${ruleDn}")
    , ldap.server.entryExists(s"ruleId=root-DP,${ruleDn}")
    , ldap.server.entryExists(s"directiveId=common-root,activeTechniqueId=common,${dirDn}")
    , ldap.server.entryExists(s"directiveId=common-ba7e3ca5-a967-40d8-aa97-41a3ff450fd2,activeTechniqueId=common,${dirDn}")
    , ldap.server.entryExists(s"activeTechniqueId=distributePolicy,${dirDn}")
    , ldap.server.entryExists(s"ruleTarget=special:all_nodes_without_role,${groupDn}")
    , ldap.server.entryExists(s"ruleTarget=special:all_servers_with_role,${groupDn}")
    )

    oldEntries must contain(beTrue).foreach and
    (Option(ldap.server.getEntry(s"ruleId=hasPolicyServer-root,${ruleDn}")).map(_.getAttribute("directiveId").getValues.toList).getOrElse(Nil)
      must containTheSameElementsAs(List("common-root")))
    (Option(ldap.server.getEntry(s"ruleId=hasPolicyServer-ba7e3ca5-a967-40d8-aa97-41a3ff450fd2,${ruleDn}")).map(_.getAttribute("directiveId").getValues.toList).getOrElse(Nil)
      must containTheSameElementsAs(List("common-ba7e3ca5-a967-40d8-aa97-41a3ff450fd2")))
    checkMigrate.checkMigrationNeeded().runNow === true
  }

  "We can read all allowed network to migrate" in {
    val prog = for {
      _ <-checkMigrate.allowedNetworks6_x_7_0.createNewAllowedNetworks(List(relay1.id))
      x <- policyServerService.getPolicyServers()
    } yield (x.root.allowedNetworks ::: x.relays.flatMap(_.allowedNetworks)).map(_.inet)

    prog.runNow must containTheSameElementsAs(List("192.168.2.0/24", "192.168.3.0/24","192.168.1.0/24", "192.168.42.42"))
  }

  def checkNewEntries(prefix: String) = {// root policy server
    Try(ldap.server.assertEntriesExist(
        s"directiveId=common-hasPolicyServer-root,activeTechniqueId=common,${dirDn}"
      , s"directiveId=server-common-root,activeTechniqueId=server-common,${dirDn}"
      , s"directiveId=rudder-service-apache-root    ,activeTechniqueId=rudder-service-apache,${dirDn}"
      , s"directiveId=rudder-service-postgresql-root,activeTechniqueId=rudder-service-postgresql,${dirDn}"
      , s"directiveId=rudder-service-relayd-root    ,activeTechniqueId=rudder-service-relayd,${dirDn}"
      , s"directiveId=rudder-service-slapd-root     ,activeTechniqueId=rudder-service-slapd,${dirDn}"
      , s"directiveId=rudder-service-webapp-root    ,activeTechniqueId=rudder-service-webapp,${dirDn}"
      , s"ruleId=${prefix}hasPolicyServer-root,${ruleDn}"
      , s"ruleId=${prefix}policy-server-root,${ruleDn}"
    // server relay
      , s"directiveId=common-hasPolicyServer-ba7e3ca5-a967-40d8-aa97-41a3ff450fd2,activeTechniqueId=common,${dirDn}"
      , s"directiveId=server-common-ba7e3ca5-a967-40d8-aa97-41a3ff450fd2,activeTechniqueId=server-common,${dirDn}"
      , s"directiveId=rudder-service-relayd-ba7e3ca5-a967-40d8-aa97-41a3ff450fd2,activeTechniqueId=rudder-service-relayd,${dirDn}"
      , s"directiveId=rudder-service-apache-ba7e3ca5-a967-40d8-aa97-41a3ff450fd2,activeTechniqueId=rudder-service-apache,${dirDn}"
      , s"ruleId=${prefix}hasPolicyServer-ba7e3ca5-a967-40d8-aa97-41a3ff450fd2,${ruleDn}"
      , s"ruleId=${prefix}policy-server-ba7e3ca5-a967-40d8-aa97-41a3ff450fd2,${ruleDn}"
    )) must beSuccessfulTry and
    (Option(ldap.server.getEntry(s"ruleId=${prefix}hasPolicyServer-root,${ruleDn}")).map(_.getAttribute("directiveId").getValues.toList).getOrElse(Nil)
      must containTheSameElementsAs(List("common-hasPolicyServer-root"))) and
    (Option(ldap.server.getEntry(s"ruleId=${prefix}hasPolicyServer-ba7e3ca5-a967-40d8-aa97-41a3ff450fd2,${ruleDn}")).map(_.getAttribute("directiveId").getValues.toList).getOrElse(Nil)
      must containTheSameElementsAs(List("common-hasPolicyServer-ba7e3ca5-a967-40d8-aa97-41a3ff450fd2"))) and
    (Option(ldap.server.getEntry(s"ruleId=${prefix}policy-server-root,${ruleDn}")).map(_.getAttribute("directiveId").getValues.toList).getOrElse(Nil)
      must containTheSameElementsAs(List("server-common-root","rudder-service-apache-root","rudder-service-postgresql-root","rudder-service-relayd-root","rudder-service-slapd-root","rudder-service-webapp-root"))) and
    (Option(ldap.server.getEntry(s"ruleId=${prefix}policy-server-ba7e3ca5-a967-40d8-aa97-41a3ff450fd2,${ruleDn}")).map(_.getAttribute("directiveId").getValues.toList).getOrElse(Nil)
      must containTheSameElementsAs(List("server-common-ba7e3ca5-a967-40d8-aa97-41a3ff450fd2","rudder-service-apache-ba7e3ca5-a967-40d8-aa97-41a3ff450fd2","rudder-service-relayd-ba7e3ca5-a967-40d8-aa97-41a3ff450fd2")))
  }

  "We can create new config objects root policy server and relay1" in {

    checkMigrate.migrateConfigs6_x_7_0.createNewConfigsAll(List(NodeId("root"), NodeId("ba7e3ca5-a967-40d8-aa97-41a3ff450fd2"))).runNow

    checkNewEntries("new_")
  }

  "We can finish migration and clean up and all new entries STILL exists and rules are migrated" in {

    checkMigrate.migrateConfigs6_x_7_0.finalMoveAndCleanAll(List(NodeId("root"), NodeId("ba7e3ca5-a967-40d8-aa97-41a3ff450fd2"))).runNow

    // hasPolicyServer-${nodeId} exists but it's the new one
    val oldEntries = List(
      ldap.server.entryExists(s"ruleId=root-DP,${ruleDn}")
    , ldap.server.entryExists(s"ruleId=ba7e3ca5-a967-40d8-aa97-41a3ff450fd2-DP,${ruleDn}")
    , ldap.server.entryExists(s"ruleId=server-roles,${ruleDn}")
    , ldap.server.entryExists(s"ruleId=root-DP,${ruleDn}")
    , ldap.server.entryExists(s"directiveId=common-root,activeTechniqueId=common,${dirDn}")
    , ldap.server.entryExists(s"directiveId=common-ba7e3ca5-a967-40d8-aa97-41a3ff450fd2,activeTechniqueId=common,${dirDn}")
    , ldap.server.entryExists(s"activeTechniqueId=distributePolicy,${dirDn}")
    , ldap.server.entryExists(s"ruleTarget=special:all_nodes_without_role,${groupDn}")
    , ldap.server.entryExists(s"ruleTarget=special:all_servers_with_role,${groupDn}")
    )

    oldEntries must contain(beFalse).foreach and
    checkNewEntries("") and
    (Option(ldap.server.getEntry(s"ruleId=44444444-02fd-43d0-aab7-28460a91347b,${ruleDn}")).map(_.getAttribute("ruleTarget").getValues.toList).getOrElse(Nil)
      must containTheSameElementsAs(List("""{"include":{"or":["special:all_exceptPolicyServers"]},"exclude":{"or":["special:all_policyServers"]}}"""))) and
    (Option(ldap.server.getEntry(s"ruleId=55555555-02fd-43d0-aab7-28460a91347b,${ruleDn}")).map(_.getAttribute("ruleTarget").getValues.toList).getOrElse(Nil)
      must containTheSameElementsAs(List("""special:all_exceptPolicyServers""")))
  }

  "After all migration, migration is not needed anymore" in {
    checkMigrate.checkMigrationNeeded().runNow === false
  }
}


