/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

package com.normation.rudder.repository.ldap

import better.files.File
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.TechniqueName
import com.normation.errors.IOResult
import com.normation.eventlog.EventLog
import com.normation.eventlog.EventLogFilter
import com.normation.eventlog.EventLogRequest
import com.normation.eventlog.ModificationId
import com.normation.inventory.ldap.core.*
import com.normation.inventory.ldap.provisioning.NameAndVersionIdFinder
import com.normation.ldap.listener.InMemoryDsConnectionProvider
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.RoLDAPConnection
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.rudder.MockGitConfigRepo
import com.normation.rudder.MockTechniques
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.NodeDit
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.queries.*
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.facts.nodes.CoreNodeFactRepository
import com.normation.rudder.facts.nodes.LdapNodeFactStorage
import com.normation.rudder.facts.nodes.SoftDaoGetNodesBySoftwareName
import com.normation.rudder.git.GitArchiveId
import com.normation.rudder.git.GitCommitId
import com.normation.rudder.git.GitPath
import com.normation.rudder.ncf.eventlogs.EditorTechniqueXmlSerialisationImpl
import com.normation.rudder.repository.*
import com.normation.rudder.services.eventlog.*
import com.normation.rudder.services.marshalling.*
import com.normation.rudder.services.marshalling.GlobalParameterSerialisationImpl
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.rudder.services.queries.JsonQueryLexer
import com.normation.rudder.services.queries.RawStringQueryParser
import com.normation.rudder.services.servers.InstanceIdGeneratorImpl
import com.normation.rudder.services.servers.InstanceIdService
import com.normation.rudder.services.user.TrivialPersonIdentService
import com.normation.rudder.tenants.*
import com.normation.utils.StringUuidGeneratorImpl
import com.normation.zio.*
import com.unboundid.ldap.listener.InMemoryDirectoryServer
import com.unboundid.ldap.sdk.DN
import java.io
import java.time.Instant
import org.eclipse.jgit.lib.PersonIdent
import zio.*
import zio.syntax.*

/**
 * This trait factor out all the repositories set-up:
 * - nodes with nodeFactStorage / nodeFactRepo
 * - groups with groupRepo
 * - techniques & directives with directiveRepo
 * - rules with ruleRepo
 * - global properties with globalPropRepo
 */
trait SetupLdapRepositories {

  lazy val bootstrapLDIFs: List[String] = {
    ("ldap/bootstrap.ldif" :: "ldap-data/technique-library-data.ldif" :: "ldap-data/inventory-sample-data.ldif" :: Nil) map {
      name =>
        // toURI is needed for https://issues.rudder.io/issues/19186
        this.getClass.getClassLoader.getResource(name).toURI.getPath
    }
  }

  lazy val ldap: InMemoryDsConnectionProvider[RoLDAPConnection & RwLDAPConnection] = {
    try {
      InitTestLDAPServer.newLdapConnectionProvider(InitTestLDAPServer.schemaLDIFs, bootstrapLDIFs)
    } catch {
      case ex: Throwable => // to simplify debugging
        ex.printStackTrace()
        throw ex
    }
  }

  // close your eyes for next two lines
  lazy val roLdap: LDAPConnectionProvider[RoLDAPConnection] = ldap.asInstanceOf[LDAPConnectionProvider[RoLDAPConnection]]
  lazy val rwLdap: LDAPConnectionProvider[RwLDAPConnection] = ldap.asInstanceOf[LDAPConnectionProvider[RwLDAPConnection]]

  // for easier access in tests
  def testServer: InMemoryDirectoryServer = ldap.server

  lazy val acceptedDIT = new InventoryDit(
    new DN("ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration"),
    new DN("ou=Inventories,cn=rudder-configuration"),
    "test"
  )

  lazy val removedDIT = new InventoryDit(
    new DN("ou=Removed Inventories,ou=Inventories,cn=rudder-configuration"),
    new DN("ou=Inventories,cn=rudder-configuration"),
    "test"
  )
  lazy val pendingDIT = new InventoryDit(
    new DN("ou=Pending Inventories,ou=Inventories,cn=rudder-configuration"),
    new DN("ou=Inventories,cn=rudder-configuration"),
    "test"
  )
  lazy val nodeDit    = new NodeDit(new DN("cn=rudder-configuration"))
  lazy val rudderDit  = new RudderDit(new DN("ou=Rudder, cn=rudder-configuration"))

  lazy val inventoryDitService: InventoryDitService = new InventoryDitServiceImpl(pendingDIT, acceptedDIT, removedDIT)
  lazy val inventoryMapper = new InventoryMapper(inventoryDitService, pendingDIT, acceptedDIT, removedDIT)

  lazy val ditQueryDataImpl: DitQueryData                                            = {
    lazy val instanceUuidPath    = File.root / "opt" / "rudder" / "etc" / "instance-id"
    lazy val instanceIdGenerator = new InstanceIdGeneratorImpl()
    lazy val instanceIdService   = InstanceIdService.make(instanceUuidPath, instanceIdGenerator).runNow
    lazy val getSubGroupChoices  = {
      // yes there is a loop with groupRepository,
      // but it is (somehow) ok because it's only used lazily in nodeQueryData.
      new DefaultSubGroupComparatorRepository(roGroupRepo)
    }
    lazy val nodeQueryData: NodeQueryCriteriaData = new NodeQueryCriteriaData(
      () => getSubGroupChoices,
      instanceIdService
    )
    new DitQueryData(acceptedDIT, nodeDit, rudderDit, nodeQueryData)
  }
  lazy val cmdbQueryParser:  CmdbQueryParser & RawStringQueryParser & JsonQueryLexer =
    CmdbQueryParser.jsonRawParser(Map.empty[String, ObjectCriterion] ++ ditQueryDataImpl.criteriaMap)
  lazy val ldapMapper     = new LDAPEntityMapper(rudderDit, nodeDit, cmdbQueryParser, inventoryMapper)
  lazy val ldapDiffMapper = new LDAPDiffMapper(ldapMapper, cmdbQueryParser)

  lazy val ldapFullInventoryRepository = new FullInventoryRepositoryImpl(inventoryDitService, inventoryMapper, rwLdap, 100)
  lazy val softwareGet                 = new ReadOnlySoftwareDAOImpl(inventoryDitService, roLdap, inventoryMapper)
  lazy val softwareSave                = new NameAndVersionIdFinder("check_name_and_version", roLdap, inventoryMapper, acceptedDIT)

  lazy val lockNode = new ZioTReentrantLock("node-lock")

  lazy val nodeFactStorage = new LdapNodeFactStorage(
    rwLdap,
    nodeDit,
    inventoryDitService,
    ldapMapper,
    inventoryMapper,
    lockNode,
    ldapFullInventoryRepository,
    softwareGet,
    softwareSave,
    new StringUuidGeneratorImpl()
  )

  lazy val getNodesBySoftwareName = new SoftDaoGetNodesBySoftwareName(softwareGet)

  lazy val tenantRepo:    InMemoryTenantService =
    InMemoryTenantService.make(TenantId("zoneA") :: TenantId("zoneB") :: Nil).runNow
  lazy val tenantService: TenantCheckLogic      = new DefaultTenantCheckLogic()

  lazy val nodeFactRepo: CoreNodeFactRepository = {
    CoreNodeFactRepository.make(nodeFactStorage, getNodesBySoftwareName, tenantRepo, tenantService, Chunk(), Chunk()).runNow
  }

  private def testGitArchiveId(commiter: PersonIdent) = GitArchiveId(GitPath("test"), GitCommitId("test"), commiter).succeed
  private def testGitPath = GitPath("test").succeed

  lazy val personIdent = new TrivialPersonIdentService

  lazy val roGroupRepo = {
    new RoLDAPNodeGroupRepository(
      rudderDit,
      roLdap,
      ldapMapper,
      nodeFactRepo,
      tenantService,
      tenantRepo,
      new ZioTReentrantLock("group-lock")
    )
  }

  lazy val woGroupRepo = new WoLDAPNodeGroupRepository(
    roGroupRepo,
    rwLdap,
    ldapDiffMapper,
    eventLogRepo,
    gitArchiver,
    personIdent,
    tenantService,
    tenantRepo,
    false
  )

  val mockGitRepo = new MockGitConfigRepo("")
  val mockTechniques: MockTechniques = MockTechniques(mockGitRepo)

  lazy val lockDirective   = new ZioTReentrantLock("directive-lock")
  lazy val roDirectiveRepo = new RoLDAPDirectiveRepository(
    rudderDit,
    roLdap,
    ldapMapper,
    mockTechniques.techniqueRepo,
    new ZioTReentrantLock("directive-lock")
  )

  lazy val woDirectiveRepo = new WoLDAPDirectiveRepository(
    roDirectiveRepo,
    rwLdap,
    ldapDiffMapper,
    eventLogRepo,
    gitArchiver,
    gitArchiver,
    gitArchiver,
    personIdent,
    false
  )

  lazy val lockRule   = new ZioTReentrantLock("rule-lock")
  lazy val roRuleRepo = new RoLDAPRuleRepository(rudderDit, roLdap, ldapMapper, lockRule)

  lazy val lockProperty         = new ZioTReentrantLock("property-lock")
  lazy val roGlobalPropertyRepo = new RoLDAPParameterRepository(
    rudderDit,
    roLdap,
    ldapMapper,
    lockProperty
  )

  lazy val woGlobalPropertyRepo = new WoLDAPParameterRepository(
    roGlobalPropertyRepo,
    rwLdap,
    ldapDiffMapper,
    eventLogRepo,
    gitArchiver,
    personIdent,
    false
  )

  // event log
  lazy val eventLogRepo = new EventLogRepository {
    override def eventLogFactory: EventLogFactory = new EventLogFactoryImpl(
      new RuleSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString),
      new DirectiveSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString),
      new NodeGroupSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString),
      new ActiveTechniqueSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString),
      new GlobalParameterSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString),
      new APIAccountSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString),
      new GlobalPropertySerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString),
      new SecretSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString),
      new EditorTechniqueXmlSerialisationImpl(Constants.XML_CURRENT_FILE_FORMAT.toString)
    )

    override def saveEventLog(modId: ModificationId, eventLog: EventLog): IOResult[EventLog] = eventLog.succeed
    override def getEventLogByCriteria(
        criteria:       Option[doobie.Fragment],
        limit:          Option[Int],
        orderBy:        List[doobie.Fragment],
        extendedFilter: Option[doobie.Fragment]
    ): IOResult[Seq[EventLog]] = ???
    override def getEventLogById(id: Long): IOResult[EventLog] = ???

    override def getEventLogByChangeRequest(
        changeRequest:   ChangeRequestId,
        xpath:           String,
        optLimit:        Option[Int],
        orderBy:         Option[String],
        eventTypeFilter: List[EventLogFilter]
    ): IOResult[Vector[EventLog]] = ???

    override def getEventLogWithChangeRequest(id: Int): IOResult[Option[(EventLog, Option[ChangeRequestId])]] = ???

    override def getLastEventByChangeRequest(
        xpath:           String,
        eventTypeFilter: List[EventLogFilter]
    ): IOResult[Map[ChangeRequestId, EventLog]] = ???

    override def getEventLogByCriteria(filter: Option[EventLogRequest]): IOResult[Seq[EventLog]] = ???

    override def getEventLogCount(filter: Option[EventLogRequest]): IOResult[Long] = ???
  }

  // git archiver are not used because of the guard, still need to be declared.
  val gitArchiver = new GitRuleArchiver
    with GitDirectiveArchiver with GitParameterArchiver with GitNodeGroupArchiver with GitActiveTechniqueArchiver
    with GitActiveTechniqueCategoryArchiver {

    override def archiveActiveTechnique(
        activeTechnique: ActiveTechnique,
        parents:         List[ActiveTechniqueCategoryId],
        gitCommit:       Option[(ModificationId, PersonIdent, Option[String])]
    ): IOResult[(GitPath, Seq[DirectiveNotArchived])] = testGitPath.map((_, Nil))

    override def deleteActiveTechnique(
        ptName:    TechniqueName,
        parents:   List[ActiveTechniqueCategoryId],
        gitCommit: Option[(ModificationId, PersonIdent, Option[String])]
    ): IOResult[GitPath] = testGitPath

    override def moveActiveTechnique(
        activeTechnique: ActiveTechnique,
        oldParents:      List[ActiveTechniqueCategoryId],
        newParents:      List[ActiveTechniqueCategoryId],
        gitCommit:       Option[(ModificationId, PersonIdent, Option[String])]
    ): IOResult[(GitPath, Seq[DirectiveNotArchived])] = testGitPath.map((_, Nil))

    override def archiveActiveTechniqueCategory(
        uptc:       ActiveTechniqueCategory,
        getParents: List[ActiveTechniqueCategoryId],
        gitCommit:  Option[(ModificationId, PersonIdent, Option[String])]
    ): IOResult[GitPath] = testGitPath

    override def deleteActiveTechniqueCategory(
        uptcId:     ActiveTechniqueCategoryId,
        getParents: List[ActiveTechniqueCategoryId],
        gitCommit:  Option[(ModificationId, PersonIdent, Option[String])]
    ): IOResult[GitPath] = testGitPath

    override def moveActiveTechniqueCategory(
        uptc:       ActiveTechniqueCategory,
        oldParents: List[ActiveTechniqueCategoryId],
        newParents: List[ActiveTechniqueCategoryId],
        gitCommit:  Option[(ModificationId, PersonIdent, Option[String])]
    ): IOResult[GitPath] = testGitPath

    override def commitActiveTechniqueLibrary(
        modId:    ModificationId,
        commiter: PersonIdent,
        reason:   Option[String]
    ): IOResult[GitArchiveId] = testGitArchiveId(commiter)

    override def archiveRule(rule: Rule, gitCommit: Option[(ModificationId, PersonIdent, Option[String])]): IOResult[GitPath] =
      testGitPath

    override def commitRules(modId: ModificationId, commiter: PersonIdent, reason: Option[String]): IOResult[GitArchiveId] =
      testGitArchiveId(commiter)

    override def deleteRule(
        ruleId:      RuleId,
        gitCommitCr: Option[(ModificationId, PersonIdent, Option[String])]
    ): IOResult[GitPath] = testGitPath

    override def archiveDirective(
        directive:           Directive,
        ptName:              TechniqueName,
        catIds:              List[ActiveTechniqueCategoryId],
        variableRootSection: SectionSpec,
        gitCommit:           Option[(ModificationId, PersonIdent, Option[String])]
    ): IOResult[GitPath] = testGitPath

    override def deleteDirective(
        directiveId: DirectiveUid,
        ptName:      TechniqueName,
        catIds:      List[ActiveTechniqueCategoryId],
        gitCommit:   Option[(ModificationId, PersonIdent, Option[String])]
    ): IOResult[GitPath] = testGitPath

    override def archiveParameter(
        parameter: GlobalParameter,
        gitCommit: Option[(ModificationId, PersonIdent, Option[String])]
    ): IOResult[GitPath] = testGitPath

    override def commitParameters(modId: ModificationId, commiter: PersonIdent, reason: Option[String]): IOResult[GitArchiveId] =
      testGitArchiveId(commiter)

    override def deleteParameter(
        parameterName: String,
        gitCommit:     Option[(ModificationId, PersonIdent, Option[String])]
    ): IOResult[GitPath] = testGitPath

    override def archiveNodeGroupCategory(
        groupCat:   NodeGroupCategory,
        getParents: List[NodeGroupCategoryId],
        gitCommit:  Option[(ModificationId, PersonIdent, Option[String])]
    ): IOResult[GitPath] = testGitPath

    override def deleteNodeGroupCategory(
        groupCatId: NodeGroupCategoryId,
        getParents: List[NodeGroupCategoryId],
        gitCommit:  Option[(ModificationId, PersonIdent, Option[String])]
    ): IOResult[GitPath] = testGitPath

    override def moveNodeGroupCategory(
        groupCat:   NodeGroupCategory,
        oldParents: List[NodeGroupCategoryId],
        newParents: List[NodeGroupCategoryId],
        gitCommit:  Option[(ModificationId, PersonIdent, Option[String])]
    ): IOResult[GitPath] = testGitPath

    override def getItemDirectory: io.File = java.nio.file.Path.of("/tmp").toFile

    override def commitGroupLibrary(
        modId:    ModificationId,
        commiter: PersonIdent,
        reason:   Option[String]
    ): IOResult[GitArchiveId] = testGitArchiveId(commiter)

    override def getTags(): IOResult[Map[Instant, GitArchiveId]] = Map().succeed

    override def archiveNodeGroup(
        nodeGroup: NodeGroup,
        parents:   List[NodeGroupCategoryId],
        gitCommit: Option[(ModificationId, PersonIdent, Option[String])]
    ): IOResult[GitPath] = testGitPath

    override def deleteNodeGroup(
        nodeGroup: NodeGroupId,
        parents:   List[NodeGroupCategoryId],
        gitCommit: Option[(ModificationId, PersonIdent, Option[String])]
    ): IOResult[GitPath] = testGitPath

    override def moveNodeGroup(
        nodeGroup:  NodeGroup,
        oldParents: List[NodeGroupCategoryId],
        newParents: List[NodeGroupCategoryId],
        gitCommit:  Option[(ModificationId, PersonIdent, Option[String])]
    ): IOResult[GitPath] = testGitPath
  }

}
