/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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

package com.normation.rudder.repository.xml

import com.normation.GitVersion
import com.normation.GitVersion.Revision
import com.normation.GitVersion.RevisionInfo
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.EventLogDetails
import com.normation.inventory.domain.Version
import com.normation.rudder.MockDirectives
import com.normation.rudder.MockGitConfigRepo
import com.normation.rudder.MockGlobalParam
import com.normation.rudder.MockNodeGroups
import com.normation.rudder.MockNodes
import com.normation.rudder.MockRules
import com.normation.rudder.MockTechniques
import com.normation.rudder.MockTenants
import com.normation.rudder.configuration.GroupAndCat
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.eventlog.*
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.properties.GenericProperty.StringToConfigValue
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.Visibility
import com.normation.rudder.git.GitCommitId
import com.normation.rudder.git.GitRepositoryProvider
import com.normation.rudder.ncf.BundleName
import com.normation.rudder.ncf.EditorTechnique
import com.normation.rudder.ncf.TechniqueWriter
import com.normation.rudder.ncf.eventlogs.*
import com.normation.rudder.ncf.yaml.YamlTechniqueSerializer
import com.normation.rudder.repository.*
import com.normation.rudder.tenants.ChangeContext
import com.normation.rudder.tenants.QueryContext
import java.time.Instant
import org.junit.runner.RunWith
import scala.xml.Elem
import zio.*
import zio.syntax.*
import zio.test.*
import zio.test.junit.ZTestJUnitRunner

/*
 * Test the per-item rollback: reverting an addition is a deletion, reverting
 * a deletion or a modification is a restore of the archived item.
 *
 * For directives, groups, parameters and rules, the in-memory
 * repositories from MockServices are used (no git).
 *
 * Editor techniques need the `technique.yml` committed in the test configuration repository
 */
@RunWith(classOf[ZTestJUnitRunner])
class ItemRollbackRepositoryImplTest extends ZIOSpecDefault {

  import ItemRollbackRepositoryImplTest.*
  import ItemRollbackRepositoryImplTest.given

  override def spec: Spec[Any, Any] = suite("rollbackOneItem")(
    suite("directive")(
      test("reverting an addition deletes the directive") {
        for {
          _      <- givenDirective(archivedDirective)
          before <- directiveRepo.getDirective(directiveUid)
          _      <- itemRollbackRepository(parseActiveTechniqueLibrary = directiveArchive(directiveUid))
                      .rollbackOneItem(commitId, AddDirective(eventDetails(directiveXml(directiveUid))))
          after  <- directiveRepo.getDirective(directiveUid)
        } yield assertTrue(before.isDefined, after.isEmpty)
      },
      test("reverting a deletion saves the archived directive back") {
        for {
          _      <- givenNoDirective
          before <- directiveRepo.getDirective(directiveUid)
          _      <- itemRollbackRepository(parseActiveTechniqueLibrary = directiveArchive(directiveUid))
                      .rollbackOneItem(commitId, DeleteDirective(eventDetails(directiveXml(directiveUid))))
          after  <- directiveRepo.getDirective(directiveUid)
        } yield assertTrue(before.isEmpty, after.exists(_.name == archivedName))
      },
      test("reverting a modification restores the archived directive") {
        for {
          _      <- givenDirective(archivedDirective.copy(name = modifiedName))
          before <- directiveRepo.getDirective(directiveUid)
          _      <- itemRollbackRepository(parseActiveTechniqueLibrary = directiveArchive(directiveUid))
                      .rollbackOneItem(commitId, ModifyDirective(eventDetails(directiveXml(directiveUid))))
          after  <- directiveRepo.getDirective(directiveUid)
        } yield assertTrue(before.exists(_.name == modifiedName), after.exists(_.name == archivedName))
      }
    ),
    suite("node group")(
      test("reverting an addition deletes the group") {
        for {
          _      <- givenGroup(archivedGroup)
          before <- groupsRepo.getNodeGroupOpt(groupId)
          _      <- itemRollbackRepository(parseGroupLibrary = groupArchive(groupId))
                      .rollbackOneItem(commitId, AddNodeGroup(eventDetails(groupXml(groupId))))
          after  <- groupsRepo.getNodeGroupOpt(groupId)
        } yield assertTrue(before.isDefined, after.isEmpty)
      },
      test("reverting a deletion creates the archived group back") {
        for {
          _      <- givenNoGroup
          before <- groupsRepo.getNodeGroupOpt(groupId)
          _      <- itemRollbackRepository(parseGroupLibrary = groupArchive(groupId))
                      .rollbackOneItem(commitId, DeleteNodeGroup(eventDetails(groupXml(groupId))))
          after  <- groupsRepo.getNodeGroupOpt(groupId)
        } yield assertTrue(before.isEmpty, after.exists(_._1.name == archivedName))
      },
      test("reverting a modification updates the group to the archived one") {
        for {
          _      <- givenGroup(archivedGroup.copy(name = modifiedName))
          before <- groupsRepo.getNodeGroupOpt(groupId)
          _      <- itemRollbackRepository(parseGroupLibrary = groupArchive(groupId))
                      .rollbackOneItem(commitId, ModifyNodeGroup(eventDetails(groupXml(groupId))))
          after  <- groupsRepo.getNodeGroupOpt(groupId)
        } yield assertTrue(before.exists(_._1.name == modifiedName), after.exists(_._1.name == archivedName))
      }
    ),
    suite("global parameter")(
      test("reverting an addition deletes the parameter") {
        for {
          _      <- givenParameter(archivedParameter(parameterName))
          before <- paramsRepo.getGlobalParameter(parameterName)
          _      <- itemRollbackRepository(parseGlobalParameters = parameterArchive(parameterName))
                      .rollbackOneItem(commitId, AddGlobalParameter(eventDetails(parameterXml(parameterName))))
          after  <- paramsRepo.getGlobalParameter(parameterName)
        } yield assertTrue(before.isDefined, after.isEmpty)
      },
      test("reverting a deletion saves the archived parameter back") {
        for {
          _      <- givenNoParameter
          before <- paramsRepo.getGlobalParameter(parameterName)
          _      <- itemRollbackRepository(parseGlobalParameters = parameterArchive(parameterName))
                      .rollbackOneItem(commitId, DeleteGlobalParameter(eventDetails(parameterXml(parameterName))))
          after  <- paramsRepo.getGlobalParameter(parameterName)
        } yield assertTrue(before.isEmpty, after.exists(_.description == archivedName))
      },
      test("reverting a modification updates the parameter to the archived one") {
        for {
          _      <- givenParameter(parameterWith(parameterName, modifiedName))
          before <- paramsRepo.getGlobalParameter(parameterName)
          _      <- itemRollbackRepository(parseGlobalParameters = parameterArchive(parameterName))
                      .rollbackOneItem(commitId, ModifyGlobalParameter(eventDetails(parameterXml(parameterName))))
          after  <- paramsRepo.getGlobalParameter(parameterName)
        } yield assertTrue(before.exists(_.description == modifiedName), after.exists(_.description == archivedName))
      }
    ),
    suite("rule")(
      test("reverting an addition deletes the rule") {
        for {
          _      <- givenRule(archivedRule)
          before <- ruleRepo.getOpt(ruleId)
          _      <- itemRollbackRepository(parseRules = ruleArchive(ruleId))
                      .rollbackOneItem(commitId, AddRule(eventDetails(ruleXml(ruleId))))
          after  <- ruleRepo.getOpt(ruleId)
        } yield assertTrue(before.isDefined, after.isEmpty)
      },
      test("reverting a deletion creates the archived rule back") {
        for {
          _      <- givenNoRule
          before <- ruleRepo.getOpt(ruleId)
          _      <- itemRollbackRepository(parseRules = ruleArchive(ruleId))
                      .rollbackOneItem(commitId, DeleteRule(eventDetails(ruleXml(ruleId))))
          after  <- ruleRepo.getOpt(ruleId)
        } yield assertTrue(before.isEmpty, after.exists(_.name == archivedName))
      },
      test("reverting a modification updates the rule to the archived one") {
        for {
          _      <- givenRule(archivedRule.copy(name = modifiedName))
          before <- ruleRepo.getOpt(ruleId)
          _      <- itemRollbackRepository(parseRules = ruleArchive(ruleId))
                      .rollbackOneItem(commitId, ModifyRule(eventDetails(ruleXml(ruleId))))
          after  <- ruleRepo.getOpt(ruleId)
        } yield assertTrue(before.exists(_.name == modifiedName), after.exists(_.name == archivedName))
      }
    ),
    suite("editor technique")(
      // these read the technique.yml actually committed in the test configuration repository
      test("reverting a deletion writes the archived technique back") {
        for {
          w       <- writeLog
          _       <- editorTechniqueManager(w).rollbackOneItem(
                       headCommitId,
                       DeleteEditorTechnique(eventDetails(editorTechniqueXml(simpleTechnique, techniqueVersion)))
                     )
          written <- w.written.get
        } yield assertTrue(
          written.map(_.id).contains(simpleTechnique),
          written.map(_.version).contains(techniqueVersion)
        )
      },
      test("reverting a modification writes the archived technique back") {
        for {
          w       <- writeLog
          _       <- editorTechniqueManager(w).rollbackOneItem(
                       headCommitId,
                       ModifyEditorTechnique(eventDetails(editorTechniqueXml(blocksTechnique, techniqueVersion)))
                     )
          written <- w.written.get
        } yield assertTrue(written.map(_.id).contains(blocksTechnique))
      },
      test("reverting an addition deletes the technique, without reading any archive") {
        for {
          w       <- writeLog
          _       <- editorTechniqueManager(w).rollbackOneItem(
                       headCommitId,
                       AddEditorTechnique(eventDetails(editorTechniqueXml(anyTechnique, techniqueVersion)))
                     )
          deleted <- w.deleted.get
          written <- w.written.get
        } yield assertTrue(deleted.contains((anyTechnique.value, techniqueVersion.value)), written.isEmpty)
      },
      test("a technique absent from the commit fails with an explicit error") {
        for {
          w   <- writeLog
          res <- editorTechniqueManager(w)
                   .rollbackOneItem(
                     headCommitId,
                     ModifyEditorTechnique(eventDetails(editorTechniqueXml(BundleName("no_such_technique"), techniqueVersion)))
                   )
                   .either
        } yield assertTrue(res.left.exists(_.fullMsg.contains("was not found in the archive")))
      }
    ),
    suite("unsupported events")(
      test("an event type that can not be rolled back item by item is ignored") {
        // AcceptNode is a NoRollbackEventLogType: it must be skipped, not fail the whole rollback
        val event = AcceptNodeEventLog(eventDetails(<entry>
          <node>
            <id>node1</id>
          </node>
        </entry>))
        itemRollbackRepository().rollbackOneItem(commitId, event).as(assertCompletes)
      },
      test("details without an id fail with an explicit error") {
        for {
          res <- itemRollbackRepository()
                   .rollbackOneItem(
                     commitId,
                     AddDirective(eventDetails(<entry>
              <directive/>
            </entry>))
                   )
                   .either
        } yield assertTrue(res.left.exists(_.fullMsg.contains("Missing <id>")))
      },
      test("restoring an item absent from the archive fails with an error") {
        for {
          _   <- givenNoDirective
          res <- itemRollbackRepository(parseActiveTechniqueLibrary = emptyDirectiveArchive)
                   .rollbackOneItem(commitId, ModifyDirective(eventDetails(directiveXml(directiveUid))))
                   .either
        } yield assertTrue(res.left.exists(_.fullMsg.contains("was not found in the archive")))
      }
    )
    // the mock repositories are mutable and shared between tests: do not run them concurrently
  ) @@ TestAspect.sequential
}

private object ItemRollbackRepositoryImplTest {

  given cc: ChangeContext = ChangeContext.newForRudder(Some("rollback item test"))

  given qc: QueryContext = QueryContext.testQC

  val commitId: GitCommitId = GitCommitId("0000000000000000000000000000000000000000")

  // current name/description at "after modification" state i.e. "before rollback"
  val modifiedName: String = "modified"
  // name/description in every archived item, value to check for "after rollback to archived version"
  val archivedName: String = "from archive"

  //////////////////////////// in-memory repositories ////////////////////////////

  private val mockTenants    = new MockTenants()
  private val mockGitRepo    = new MockGitConfigRepo("")
  private val mockTechniques = MockTechniques(mockGitRepo)
  private val mockDirectives = new MockDirectives(mockTechniques, mockTenants)
  private val mockRules      = new MockRules(mockTenants)
  private val mockParams     = new MockGlobalParam(mockTenants)
  private val mockNodes      = new MockNodes(mockTenants)
  private val mockGroups     = new MockNodeGroups(mockNodes, mockParams, mockTenants)

  val directiveRepo: WoDirectiveRepository & RoDirectiveRepository = mockDirectives.directiveRepo
  val ruleRepo:      WoRuleRepository & RoRuleRepository           = mockRules.ruleRepo
  val paramsRepo:    WoParameterRepository & RoParameterRepository = mockParams.paramsRepo
  val groupsRepo:    WoNodeGroupRepository & RoNodeGroupRepository = mockGroups.groupsRepo

  //////////////////////////// configuration objects ID for tests ////////////////////////////

  val directiveUid:               DirectiveUid        = mockDirectives.directives.archiveDirective.id.uid
  val directiveActiveTechniqueId: ActiveTechniqueId   =
    ActiveTechniqueId(mockDirectives.directives.archiveTechnique.id.name.value)
  val groupId:                    NodeGroupId         = mockGroups.g1.id
  val groupCategoryId:            NodeGroupCategoryId = Constants.ROOT_GROUP_CATEGORY
  val parameterName:              String              = mockParams.stringParam.name
  val ruleId:                     RuleId              = mockRules.rules.defaultRule.id

  //////////////////////////// the archived configuration items with the same IDs ////////////////////////////

  val archivedDirective: Directive = mockDirectives.directives.archiveDirective.copy(name = archivedName)
  val archivedGroup:     NodeGroup = mockGroups.g1.copy(name = archivedName)
  val archivedRule:      Rule      = mockRules.rules.defaultRule.copy(name = archivedName)

  def parameterWith(name: String, description: String): GlobalParameter = GlobalParameter(
    name,
    GitVersion.DEFAULT_REV,
    "a value".toConfigValue,
    None,
    description,
    None,
    Visibility.default,
    None
  )
  def archivedParameter(name: String): GlobalParameter = parameterWith(name, archivedName)

  //////////////////////////// GIVEN-WHEN-THEN pattern helpers ////////////////////////////

  def givenDirective(directive: Directive): IOResult[Unit] =
    directiveRepo.saveDirective(directiveActiveTechniqueId, directive).unit
  val givenNoDirective:                     UIO[Unit]      = directiveRepo.delete(directiveUid).ignore

  def givenGroup(group: NodeGroup): IOResult[Unit] =
    groupsRepo.delete(group.id).ignore *> groupsRepo.create(group, groupCategoryId).unit
  val givenNoGroup:                 UIO[Unit]      = groupsRepo.delete(groupId).ignore

  def givenParameter(param: GlobalParameter): IOResult[Unit] =
    paramsRepo.delete(param.name, None).ignore *> paramsRepo.saveParameter(param).unit
  val givenNoParameter:                       UIO[Unit]      = paramsRepo.delete(parameterName, None).ignore

  def givenRule(rule: Rule): IOResult[Unit] = ruleRepo.delete(rule.id).ignore *> ruleRepo.create(rule).unit
  val givenNoRule: UIO[Unit] = ruleRepo.delete(ruleId).ignore

  /*
   * Class with all the unused methods for the test, skip to the methods below for actual constructor helpers
   */
  abstract private class DirectiveArchive extends ParseActiveTechniqueLibrary {
    override def getArchive(archiveId: GitCommitId): IOResult[ActiveTechniqueCategoryContent] =
      ActiveTechniqueCategoryContent(emptyActiveTechniqueCategory, Set.empty, Set.empty).succeed
    override def getRevisions(uid: DirectiveUid): IOResult[List[RevisionInfo]] = List.empty[RevisionInfo].succeed
  }

  abstract private class GroupArchive extends ParseGroupLibrary {
    override def getArchive(archiveId: GitCommitId): IOResult[NodeGroupCategoryContent] =
      NodeGroupCategoryContent(emptyNodeGroupCategory, Set.empty, Set.empty).succeed
  }

  abstract private class RuleArchive extends ParseRules {
    override def getArchive(archiveId: GitCommitId): IOResult[Seq[Rule]] = Seq.empty[Rule].succeed
  }

  def directiveArchive(uid: DirectiveUid): ParseActiveTechniqueLibrary = new DirectiveArchive {
    override def getDirectiveRevision(u: DirectiveUid, rev: Revision): IOResult[Option[(ActiveTechnique, Directive)]] = {
      if (u != uid) None.succeed
      else Some((archivedActiveTechnique, archivedDirective)).succeed
    }
  }

  def emptyDirectiveArchive: ParseActiveTechniqueLibrary = new DirectiveArchive {
    override def getDirectiveRevision(u: DirectiveUid, rev: Revision): IOResult[Option[(ActiveTechnique, Directive)]] =
      None.succeed
  }

  def groupArchive(id: NodeGroupId): ParseGroupLibrary = new GroupArchive {
    override def getGroupRevision(uid: NodeGroupUid, rev: Revision): IOResult[Option[GroupAndCat]] = {
      if (uid != id.uid) None.succeed
      else Some(GroupAndCat(archivedGroup, groupCategoryId)).succeed
    }
  }

  def parameterArchive(name: String): ParseGlobalParameters = new ParseGlobalParameters {
    override def getArchive(archiveId: GitCommitId): IOResult[Seq[GlobalParameter]] =
      Seq(archivedParameter(name)).succeed
  }

  def ruleArchive(id: RuleId): ParseRules = new RuleArchive {
    override def getRuleRevision(uid: RuleUid, rev: Revision): IOResult[Option[Rule]] = {
      if (uid != id.uid) None.succeed else Some(archivedRule).succeed
    }
  }

  private def archivedActiveTechnique = ActiveTechnique(
    id = directiveActiveTechniqueId,
    techniqueName = mockDirectives.directives.archiveTechnique.id.name,
    acceptationDatetimes = AcceptationDateTime(Map(mockDirectives.directives.archiveTechnique.id.version -> Instant.EPOCH)),
    directives = directiveUid :: Nil,
    security = None
  )

  private def emptyActiveTechniqueCategory = {
    ActiveTechniqueCategory(
      ActiveTechniqueCategoryId("Active Techniques"),
      "Active Techniques",
      "",
      Nil,
      Nil,
      isSystem = false,
      security = None
    )
  }

  private def emptyNodeGroupCategory =
    NodeGroupCategory(Constants.ROOT_GROUP_CATEGORY, "GroupRoot", "", Nil, Nil, isSystem = false, security = None)

  //////////////////////////// event logs ////////////////////////////

  def eventDetails(details: Elem): EventLogDetails = EventLogDetails(
    modificationId = None,
    principal = EventActor("test"),
    reason = None,
    details = details
  )

  def directiveXml(uid: DirectiveUid): Elem = <entry>
    <directive>
      <id>
        {uid.value}
      </id>
    </directive>
  </entry>

  def groupXml(id: NodeGroupId): Elem = <entry>
    <nodeGroup>
      <id>
        {id.serialize}
      </id>
    </nodeGroup>
  </entry>

  def parameterXml(name: String): Elem = <entry>
    <globalParameter>
      <name>
        {name}
      </name>
    </globalParameter>
  </entry>

  def ruleXml(id: RuleId): Elem = <entry>
    <rule>
      <id>
        {id.serialize}
      </id>
    </rule>
  </entry>

  //////////////////////////// the service under test ////////////////////////////

  //////////////////////////// editor techniques ////////////////////////////

  /*
   * Techniques committed in `src/test/resources/configuration-repository`
   */
  val simpleTechnique:  BundleName = BundleName("a_simple_yaml_technique")
  val blocksTechnique:  BundleName = BundleName("technique_with_blocks")
  val anyTechnique:     BundleName = BundleName("technique_any")
  val techniqueVersion: Version    = Version("1.0")

  def headCommitId: GitCommitId = GitCommitId(mockGitRepo.gitRepo.db.resolve("refs/heads/master").getName)

  def editorTechniqueXml(id: BundleName, version: Version): Elem = {
    <entry>
      <technique>
        <id>
          {id.value}
        </id> <version>
        {version.value}
      </version>
      </technique>
    </entry>
  }

  /*
   * Mock technique writer to record the methods that were called
   */
  final case class TechniqueWriterLog(
      written: Ref[Option[EditorTechnique]],
      deleted: Ref[Option[(String, String)]]
  ) extends TechniqueWriter {
    override def deleteTechnique(techniqueName: String, techniqueVersion: String, deleteDirective: Boolean)(implicit
        cc: ChangeContext
    ): IOResult[Unit] = deleted.set(Some((techniqueName, techniqueVersion)))

    override def writeTechniqueAndUpdateLib(technique: EditorTechnique)(implicit
        cc: ChangeContext
    ): IOResult[EditorTechnique] = written.set(Some(technique)).as(technique)

    override def writeTechnique(technique: EditorTechnique)(implicit cc: ChangeContext): IOResult[EditorTechnique] =
      written.set(Some(technique)).as(technique)

    override def writeTechniques(techniques: List[EditorTechnique])(implicit
        cc: ChangeContext
    ): IOResult[List[EditorTechnique]] = techniques.succeed
  }

  val writeLog: UIO[TechniqueWriterLog] = for {
    written <- Ref.make(Option.empty[EditorTechnique])
    deleted <- Ref.make(Option.empty[(String, String)])
  } yield TechniqueWriterLog(written, deleted)

  def editorTechniqueManager(writer: TechniqueWriter): ItemRollbackRepositoryImpl = itemRollbackRepository(
    techniqueWriter = writer,
    gitRepo = mockGitRepo.gitRepo,
    yamlTechniqueSerializer = mockTechniques.yamlSerializer
  )

  /*
   * Helper constructor to avoid the null pollution in tests: item repo needs many of
   * the configuration repos.
   * What the rollback never touches is left null (a lot).
   */
  def itemRollbackRepository(
      parseRules:                  ParseRules = null,
      parseActiveTechniqueLibrary: ParseActiveTechniqueLibrary = null,
      parseGlobalParameters:       ParseGlobalParameters = null,
      parseGroupLibrary:           ParseGroupLibrary = null,
      techniqueWriter:             TechniqueWriter = null,
      gitRepo:                     GitRepositoryProvider = null,
      yamlTechniqueSerializer:     YamlTechniqueSerializer = null
  ): ItemRollbackRepositoryImpl = new ItemRollbackRepositoryImpl(
    ruleRepo,
    ruleRepo,
    directiveRepo,
    groupsRepo,
    groupsRepo,
    paramsRepo,
    paramsRepo,
    gitRepo,
    parseRules,
    parseActiveTechniqueLibrary,
    parseGlobalParameters,
    parseGroupLibrary,
    null,
    null,
    techniqueWriter,
    yamlTechniqueSerializer,
    null
  )
}
