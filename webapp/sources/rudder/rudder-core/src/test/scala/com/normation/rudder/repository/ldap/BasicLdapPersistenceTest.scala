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
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.inventory.domain.NodeId
import com.normation.inventory.ldap.core.*
import com.normation.inventory.ldap.provisioning.NameAndVersionIdFinder
import com.normation.ldap.listener.InMemoryDsConnectionProvider
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.RoLDAPConnection
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.rudder.MockGitConfigRepo
import com.normation.rudder.MockTechniques
import com.normation.rudder.domain.NodeDit
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.queries.*
import com.normation.rudder.facts.nodes.CoreNodeFactRepository
import com.normation.rudder.facts.nodes.LdapNodeFactStorage
import com.normation.rudder.facts.nodes.SoftDaoGetNodesBySoftwareName
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.rudder.services.queries.JsonQueryLexer
import com.normation.rudder.services.queries.RawStringQueryParser
import com.normation.rudder.services.servers.InstanceIdGeneratorImpl
import com.normation.rudder.services.servers.InstanceIdService
import com.normation.rudder.tenants.*
import com.normation.utils.StringUuidGeneratorImpl
import com.normation.zio.*
import com.unboundid.ldap.sdk.DN
import org.joda.time.DateTime
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.Chunk

/**
 * Test read/write basic LDAP entities
 */
@RunWith(classOf[JUnitRunner])
class BasicLdapPersistenceTest extends Specification {

  val bootstrapLDIFs: List[String] = {
    ("ldap/bootstrap.ldif" :: "ldap-data/technique-library-data.ldif" :: "ldap-data/inventory-sample-data.ldif" :: Nil) map {
      name =>
        // toURI is needed for https://issues.rudder.io/issues/19186
        this.getClass.getClassLoader.getResource(name).toURI.getPath
    }
  }

  val ldap: InMemoryDsConnectionProvider[RoLDAPConnection & RwLDAPConnection] = {
    try {
      InitTestLDAPServer.newLdapConnectionProvider(InitTestLDAPServer.schemaLDIFs, bootstrapLDIFs)
    } catch {
      case ex =>
        ex.printStackTrace()
        throw ex
    }
  }

  // close your eyes for next two lines
  val ldapRo: LDAPConnectionProvider[RoLDAPConnection] = ldap.asInstanceOf[LDAPConnectionProvider[RoLDAPConnection]]
  val ldapRw: LDAPConnectionProvider[RwLDAPConnection] = ldap.asInstanceOf[LDAPConnectionProvider[RwLDAPConnection]]

  // for easier access in tests
  def testServer = ldap.server

  val acceptedDIT = new InventoryDit(
    new DN("ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration"),
    new DN("ou=Inventories,cn=rudder-configuration"),
    "test"
  )

  val removedDIT = new InventoryDit(
    new DN("ou=Removed Inventories,ou=Inventories,cn=rudder-configuration"),
    new DN("ou=Inventories,cn=rudder-configuration"),
    "test"
  )
  val pendingDIT = new InventoryDit(
    new DN("ou=Pending Inventories,ou=Inventories,cn=rudder-configuration"),
    new DN("ou=Inventories,cn=rudder-configuration"),
    "test"
  )
  val nodeDit    = new NodeDit(new DN("cn=rudder-configuration"))
  val rudderDit  = new RudderDit(new DN("ou=Rudder, cn=rudder-configuration"))

  lazy val inventoryDitService: InventoryDitService = new InventoryDitServiceImpl(pendingDIT, acceptedDIT, removedDIT)
  lazy val inventoryMapper = new InventoryMapper(inventoryDitService, pendingDIT, acceptedDIT, removedDIT)

  lazy val ditQueryDataImpl: DitQueryData                                            = {
    lazy val instanceUuidPath    = File.root / "opt" / "rudder" / "etc" / "instance-id"
    lazy val instanceIdGenerator = new InstanceIdGeneratorImpl()
    lazy val instanceIdService   = InstanceIdService.make(instanceUuidPath, instanceIdGenerator).runNow
    lazy val getSubGroupChoices  = {
      // yes there is a loop with groupRepository,
      // but it is (somehow) ok because it's only used lazily in nodeQueryData.
      new DefaultSubGroupComparatorRepository(groupRepository)
    }
    lazy val nodeQueryData: NodeQueryCriteriaData = new NodeQueryCriteriaData(
      () => getSubGroupChoices,
      instanceIdService
    )
    new DitQueryData(acceptedDIT, nodeDit, rudderDit, nodeQueryData)
  }
  lazy val cmdbQueryParser:  CmdbQueryParser & RawStringQueryParser & JsonQueryLexer =
    CmdbQueryParser.jsonRawParser(Map.empty[String, ObjectCriterion] ++ ditQueryDataImpl.criteriaMap)
  lazy val ldapMapper                  = new LDAPEntityMapper(rudderDit, nodeDit, cmdbQueryParser, inventoryMapper)
  lazy val ldapFullInventoryRepository = new FullInventoryRepositoryImpl(inventoryDitService, inventoryMapper, ldapRw, 100)
  lazy val softwareGet                 = new ReadOnlySoftwareDAOImpl(inventoryDitService, ldapRo, inventoryMapper)
  lazy val softwareSave                = new NameAndVersionIdFinder("check_name_and_version", ldapRo, inventoryMapper, acceptedDIT)

  lazy val nodeFactStorage = new LdapNodeFactStorage(
    ldapRw,
    nodeDit,
    inventoryDitService,
    ldapMapper,
    inventoryMapper,
    new ZioTReentrantLock("node-lock"),
    ldapFullInventoryRepository,
    softwareGet,
    softwareSave,
    new StringUuidGeneratorImpl()
  )

  lazy val getNodesBySoftwareName = new SoftDaoGetNodesBySoftwareName(softwareGet)

  val tenantRepo:         TenantRepository = InMemoryTenantRepository.make(Nil).runNow
  lazy val tenantService: TenantService    = new DefaultTenantService()

  lazy val nodeFactRepo: CoreNodeFactRepository = {
    CoreNodeFactRepository.make(nodeFactStorage, getNodesBySoftwareName, tenantRepo, tenantService, Chunk(), Chunk()).runNow
  }

  lazy val groupRepository =
    new RoLDAPNodeGroupRepository(rudderDit, ldapRo, ldapMapper, nodeFactRepo, new ZioTReentrantLock("group-lock"))

  val mockGitRepo = new MockGitConfigRepo("")
  val mockTechniques: MockTechniques = MockTechniques(mockGitRepo)
  val directiveRepo = new RoLDAPDirectiveRepository(
    rudderDit,
    ldapRo,
    ldapMapper,
    mockTechniques.techniqueRepo,
    new ZioTReentrantLock("directive-lock")
  )

  val ruleRepo = new RoLDAPRuleRepository(rudderDit, ldapRo, ldapMapper, new ZioTReentrantLock("rule-lock"))

  sequential

  "reading a group with tenants should correctly deserialize it" >> {
    val id          = NodeGroupId(NodeGroupUid("test-group-node1"))
    val nodeCrit    = ditQueryDataImpl.criteriaMap("node")
    val expected    = NodeGroup(
      NodeGroupId(NodeGroupUid("test-group-node1")),
      "Only contains node1",
      "",
      properties = List(),
      query = Some(
        Query(
          QueryReturnType.NodeReturnType,
          CriterionComposition.And,
          ResultTransformation.Identity,
          List(
            CriterionLine(nodeCrit, nodeCrit.criteria(1), Equals, "node1")
          )
        )
      ),
      isDynamic = true,
      serverList = Set(NodeId("node1")),
      _isEnabled = true,
      isSystem = false,
      security = Some(SecurityTag(Chunk(TenantId("zoneA"))))
    )
    implicit val qc = QueryContext.testQC
    val res         = groupRepository.getNodeGroupOpt(id).runNow.map(_._1)

    res must beSome(beEqualTo(expected))
  }

  "reading a group category with a tenant should correctly deserialize it" >> {

    val catId    = NodeGroupCategoryId("category1")
    val res      = groupRepository.getGroupCategory(catId).runNow
    val expected =
      NodeGroupCategory(catId, "A group category", "", List(), List(), false, Some(SecurityTag(Chunk(TenantId("zoneA")))))

    res must beEqualTo(expected)
  }

  "reading an active technique with a tenant should correctly deserialize it" >> {

    val tech     = TechniqueName("user_defined_tech1")
    val res      = directiveRepo.getActiveTechnique(tech).runNow
    val expected = ActiveTechnique(
      ActiveTechniqueId(tech.value),
      tech,
      AcceptationDateTime(Map(TechniqueVersion.V1_0 -> DateTime.parse("2023-12-02T17:49:01.013Z"))),
      List(DirectiveUid("ce8aec6f-d371-4047-96d1-6b69ccdef9ae")),
      security = Some(SecurityTag(Chunk(TenantId("zoneA"))))
    )

    res must beSome(beEqualTo(expected))

  }

  "reading a directive with a tenant should correctly deserialize it" >> {

    val did = DirectiveUid("ce8aec6f-d371-4047-96d1-6b69ccdef9ae")

    val res      = directiveRepo.getDirective(did).runNow
    val expected = Directive(
      DirectiveId(did),
      TechniqueVersion.V1_0,
      Map("BD16C074-DEBC-433A-ADFC-3CB74FFFAD3F" -> Seq("")),
      "user_defined_tech1",
      "",
      policyMode = None,
      longDescription = "",
      priority = 5,
      _isEnabled = true,
      isSystem = false,
      security = Some(SecurityTag(Chunk(TenantId("zoneA"))))
    )

    res must beSome(beEqualTo(expected))
  }

  "reading a rule with a tenant should correctly deserialize it" >> {

    val did    = DirectiveId(DirectiveUid("ce8aec6f-d371-4047-96d1-6b69ccdef9ae"))
    val ruleId = RuleId((RuleUid("34323555-6b6b-4d07-b3bd-043df1239797")))

    val res      = ruleRepo.get(ruleId).runNow
    val expected = Rule(
      ruleId,
      "User rule",
      RuleCategoryId("rootRuleCategory"),
      Set(TargetExclusion(TargetUnion(Set(AllTarget)), TargetUnion(Set()))),
      Set(did),
      "",
      "",
      true,
      security = Some(SecurityTag(Chunk(TenantId("zoneA"))))
    )

    res must beEqualTo(expected)
  }

}
