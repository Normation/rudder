/*
 *************************************************************************************
 * Copyright 2018 Normation SAS
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

package com.normation.rudder.rest

import better.files.*
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.errors.IOResult
import com.normation.errors.effectUioUnit
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.MockDirectives
import com.normation.rudder.MockGitConfigRepo
import com.normation.rudder.MockTechniques
import com.normation.rudder.apidata.JsonQueryObjects.JQRule
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.properties.Visibility.Displayed
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.git.ZipUtils
import com.normation.rudder.ncf.ResourceFile
import com.normation.rudder.ncf.ResourceFileState
import com.normation.rudder.repository.xml.TechniqueFiles
import com.normation.rudder.rest.RudderJsonResponse.JsonRudderApiResponse
import com.normation.rudder.rest.RudderJsonResponse.LiftJsonResponse
import com.normation.rudder.rest.lift.CheckArchiveServiceImpl
import com.normation.rudder.rest.lift.MergePolicy
import com.normation.rudder.rest.lift.PolicyArchive
import com.normation.rudder.rest.lift.SaveArchiveServicebyRepo
import com.normation.rudder.rest.lift.TechniqueArchive
import com.normation.rudder.rest.lift.TechniqueInfo
import com.normation.rudder.rest.lift.TechniqueType
import com.normation.rudder.rest.lift.ZipArchiveBuilderService
import com.normation.utils.DateFormaterService
import com.normation.zio.*
import java.io.FileOutputStream
import java.util.zip.ZipFile
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.http.OutputStreamResponse
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.revwalk.RevWalk
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.AfterAll
import scala.annotation.nowarn
import zio.Chunk
import zio.ZIO

@nowarn("msg=a type was inferred to be `\\w+`; this may indicate a programming error.")
@RunWith(classOf[JUnitRunner])
class ArchiveApiTest extends Specification with AfterAll with Loggable {
  implicit val qc: QueryContext = QueryContext.testQC

  val restTestSetUp = RestTestSetUp.newEnv
  val restTest      = new RestTest(restTestSetUp.liftRules)
  val mockGitRepo   = new MockGitConfigRepo("")

  val mockTechniques: MockTechniques = MockTechniques(mockGitRepo)
  val mockDirectives = new MockDirectives(mockTechniques)

  val testDir: File = File(s"/tmp/test-rudder-response-content-${DateFormaterService.serialize(DateTime.now(DateTimeZone.UTC))}")
  testDir.createDirectoryIfNotExists(true)

  override def afterAll(): Unit = {
    if (System.getProperty("tests.clean.tmp") != "false") {
      logger.info("Cleanup rest env ")
      restTestSetUp.cleanup()
      logger.info("Deleting directory " + testDir.pathAsString)
      FileUtils.deleteDirectory(testDir.toJava)
    }
  }

  def children(f:       File): List[String] = f.listRecursively.toList.map(_.name)
  def directChildren(f: File): List[String] = f.list.toList.map(_.name)

  // format: off
//  org.slf4j.LoggerFactory.getLogger("application.archive").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)
//  org.slf4j.LoggerFactory.getLogger("configuration").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)
  // format: on

  sequential

  "We should be able to correctly make a diff between current technique files and archive technique files" >> {
    // we look at clockConfiguration that is on version 3 and contains files: changelog  clockConfiguration.st  metadata.xml
    val a = Array[Byte]()
    val t = TechniqueArchive(
      TechniqueInfo(
        TechniqueId(
          TechniqueName("clockConfiguration"),
          TechniqueVersion.parse("3.0").getOrElse(throw new IllegalArgumentException("test"))
        ),
        "clock configuration",
        TechniqueType.Metadata
      ),
      Chunk("systemSettings", "misc"),
      Chunk(("clockConfiguration.st", a), ("metadata.xml", a), ("resources/something.txt", a))
    )

    val res = SaveArchiveServicebyRepo
      .buildDiff(
        t,
        restTestSetUp.mockGitRepo.configurationRepositoryRoot / "techniques" / "systemSettings" / "misc" / "clockConfiguration" / "3.0"
      )
      .runNow

    res must containTheSameElementsAs(
      Chunk(
        // new
        ResourceFile("resources/something.txt", ResourceFileState.New),
        // same or updated
        ResourceFile("clockConfiguration.st", ResourceFileState.Modified),
        ResourceFile("metadata.xml", ResourceFileState.Modified),
        // deleted
        ResourceFile("changelog", ResourceFileState.Deleted)
      )
    )
  }

  "the archive feature is always enabled, and request" should {
    "succeed in GET /archives/export" in {
      restTest.testGETResponse("/api/latest/archives/export") {
        case Full(resp) => resp.toResponse.code must beEqualTo(200)
        case err        => ko(s"I got an error in test: ${err}")
      }
    }
  }

  "forbid import of archive with ZipSlip path transversal" >> {
    // archive `ZipSlip.zip` contains only one file with path '../vuln/pwn.sh' which is forbidden

    restTest.testBinaryPOSTResponse(
      s"/api/latest/archives/import",
      "archive",
      "archive.zip",
      Resource.getAsStream(s"archives/ZipSlip.zip").readAllBytes()
    ) {
      case Full(LiftJsonResponse(JsonRudderApiResponse(_, _, "error", _, Some(err)), _, 500)) =>
        if (err.contains("../")) ok(s"Archive refused with message: '${err}''")
        else ko(s"Error message does not talk about ../: ${err}")
      case Full(x)                                                                            =>
        ko(s"Response should be an error but got: ${x}")
      case err                                                                                =>
        ko(s"I got an error in test: ${err}")
    }

  }

  "correctly build an archive of one rule, no deps" >> {

    // rule with ID rule1 defined in com/normation/rudder/MockServices.scala has name:
    // 10. Global configuration for all nodes
    // so: 10__Global_configuration_for_all_nodes
    val fileName = "10__Global_configuration_for_all_nodes.json"

    val archiveName = "archive-rule-no-dep"
    restTestSetUp.archiveAPIModule.rootDirName.set(archiveName).runNow

    restTest.testGETResponse("/api/latest/archives/export?rules=rule1&include=none") {
      case Full(OutputStreamResponse(out, _, _, _, 200)) =>
        val zipFile = testDir / s"${archiveName}.zip"
        val zipOut  = new FileOutputStream(zipFile.toJava)
        out(zipOut)
        zipOut.close()
        // unzip
        ZipUtils.unzip(new ZipFile(zipFile.toJava), zipFile.parent.toJava).runNow

        (children(testDir / s"${archiveName}/rules") must containTheSameElementsAs(List(fileName))) and
        (children(testDir / s"${archiveName}/groups").isEmpty must beTrue) and
        (children(testDir / s"${archiveName}/directives").isEmpty must beTrue) and
        (children(testDir / s"${archiveName}/techniques").isEmpty must beTrue)

      case err => ko(s"I got an error in test: ${err}")
    }
  }

  "correctly build an archive with one rule, dep: group and technique (and implied directives)" in {
    val fileName = "10__Global_configuration_for_all_nodes.json"

    val archiveName = "archive-rule-with-dep"
    restTestSetUp.archiveAPIModule.rootDirName.set(archiveName).runNow

    restTest.testGETResponse("/api/latest/archives/export?rules=rule1&include=groups,techniques") {
      case Full(OutputStreamResponse(out, _, _, _, 200)) =>
        val zipFile = testDir / s"${archiveName}.zip"
        val zipOut  = new FileOutputStream(zipFile.toJava)
        out(zipOut)
        zipOut.close()
        // unzip
        ZipUtils.unzip(new ZipFile(zipFile.toJava), zipFile.parent.toJava).runNow

        (children(testDir / s"${archiveName}/rules") must containTheSameElementsAs(List(fileName))) and
        // only system group => none exported
        (children(testDir / s"${archiveName}/groups") must containTheSameElementsAs(Nil)) and
        (children(testDir / s"${archiveName}/directives") must containTheSameElementsAs(List("10__Clock_Configuration.json"))) and
        // we have all level of category metadata for technique parents *except* the root category, that is system
        // and that we don't want to change with import.
        (directChildren(testDir / s"${archiveName}/techniques") must containTheSameElementsAs(List("systemSettings"))) and
        (directChildren(testDir / s"${archiveName}/techniques/systemSettings") must containTheSameElementsAs(
          List("misc", "category.json")
        )) and
        (directChildren(testDir / s"${archiveName}/techniques/systemSettings/misc") must containTheSameElementsAs(
          List("clockConfiguration", "category.json")
        )) and
        (directChildren(
          testDir / s"${archiveName}/techniques/systemSettings/misc/clockConfiguration/3.0"
        ) must containTheSameElementsAs(List("changelog", "clockConfiguration.st", "metadata.xml")))

      case err => ko(s"I got an error in test: ${err}")
    }
  }

  "correctly build an archive of one directive" >> {

    // rule with ID rule1 defined in com/normation/rudder/MockServices.scala has name:
    // 10. Global configuration for all nodes
    // so: 10__Global_configuration_for_all_nodes
    val fileName = "10__Clock_Configuration.json"

    val archiveName = "archive-directive"
    restTestSetUp.archiveAPIModule.rootDirName.set(archiveName).runNow

    restTest.testGETResponse("/api/latest/archives/export?directives=directive1&include=none") {
      case Full(OutputStreamResponse(out, _, _, _, 200)) =>
        val zipFile = testDir / s"${archiveName}.zip"
        val zipOut  = new FileOutputStream(zipFile.toJava)
        out(zipOut)
        zipOut.close()
        // unzip
        ZipUtils.unzip(new ZipFile(zipFile.toJava), zipFile.parent.toJava).runNow

        (children(testDir / s"${archiveName}/directives") must containTheSameElementsAs(List(fileName))) and
        (children(testDir / s"${archiveName}/groups").isEmpty must beTrue) and
        (children(testDir / s"${archiveName}/rules").isEmpty must beTrue) and
        (children(testDir / s"${archiveName}/techniques").isEmpty must beTrue)

      case err => ko(s"I got an error in test: ${err}")
    }
  }

  "correctly export and import a directive with a user technique inside" >> {
    val fileName    = "test_import_export_archive_directive.json"
    val archiveName = "test_import_export_archive_directive"
    val zipFile     = testDir / s"${archiveName}.zip"

    restTestSetUp.archiveAPIModule.rootDirName.set(archiveName).runNow

    restTest.testGETResponse("/api/latest/archives/export?directives=test_import_export_archive_directive&include=all") {
      case Full(OutputStreamResponse(out, _, _, _, 200)) =>
        val zipFile = testDir / s"${archiveName}.zip"
        val zipOut  = new FileOutputStream(zipFile.toJava)
        out(zipOut)
        zipOut.close()
        ZipUtils.unzip(new ZipFile(zipFile.toJava), zipFile.parent.toJava).runNow

        (children(testDir / s"${archiveName}/directives") must containTheSameElementsAs(List(fileName))) and
        (children(testDir / s"${archiveName}/groups").isEmpty must beTrue) and
        (children(testDir / s"${archiveName}/rules").isEmpty must beTrue) and
        (
          // when we export a JSON technique, we let it as it is, with all its generated content
          children(
            testDir / s"${archiveName}/techniques/ncf_techniques/test_import_export_archive/1.0"
          ) must containTheSameElementsAs(
            List("technique.json", "technique.ps1", "technique.cf", "metadata.xml")
          )
        )

      case err => ko(s"I got an error in test: ${err}")
    } and {
      val tech = restTestSetUp.mockTechniques.techniqueRepo
        .get(
          TechniqueId(
            TechniqueName("test_import_export_archive"),
            TechniqueVersion.parse("1.0").getOrElse(throw new IllegalArgumentException("test"))
          )
        )
        .getOrElse(throw new IllegalArgumentException("test"))

      // during import, we are actually migrating to Yaml
      val techInfo = TechniqueInfo(tech.id, tech.name, TechniqueType.Yaml)

      val directive = restTestSetUp.mockDirectives.directiveRepo
        .getDirective(
          DirectiveUid("test_import_export_archive_directive")
        )
        .runNow
        .getOrElse(throw new IllegalArgumentException("test"))

      restTestSetUp.archiveAPIModule.rootDirName.set(archiveName).runNow
      restTest.testBinaryPOSTResponse(
        s"/api/latest/archives/import",
        "archive",
        zipFile.name,
        zipFile.newInputStream.readAllBytes()
      ) {
        case Full(LiftJsonResponse(res, _, 200)) =>
          restTestSetUp.archiveAPIModule.archiveSaver.base.get.runNow match {
            case None         => ko(s"No policies were saved")
            case Some((p, m)) =>
              (m must beEqualTo(MergePolicy.OverrideAll)) and
              (p.techniques(0).technique must beEqualTo(techInfo)) and
              (
                // when we import a JSON technique, then we migrate to YAML and don't import generated file which are regenerated
                p.techniques(0).files.map(_._1) must containTheSameElementsAs(List("technique.yml"))
              ) and
              (p.directives(0).directive must beEqualTo(directive))
          }

        case err => ko(s"I got an error in test: ${err}")
      }
    }
  }

  // here we use archive from previous test located in `testDir/test_import_export_archive_directive.zip`
  "correctly parse merge policy parameter on import" >> {
    val archiveName = "test_import_export_archive_directive"
    val zipFile     = testDir / s"${archiveName}.zip"

    restTestSetUp.archiveAPIModule.rootDirName.set(archiveName).runNow

    restTest.testBinaryPOSTResponse(
      s"/api/latest/archives/import",
      "archive",
      zipFile.name,
      zipFile.newInputStream.readAllBytes(),
      Map(("merge", "keep-rule-targets"))
    ) {
      case Full(LiftJsonResponse(res, _, 200)) =>
        restTestSetUp.archiveAPIModule.archiveSaver.base.get.runNow match {
          case None         => ko(s"No policies were saved")
          case Some((p, m)) =>
            (m must beEqualTo(MergePolicy.KeepRuleTargets))
        }

      case err => ko(s"I got an error in test: ${err}")
    }
  }

  "correctly checks if techniques already exists" >> {
    val checks    = new CheckArchiveServiceImpl(restTestSetUp.mockTechniques.techniqueRepo)
    val archive   =
      restTestSetUp.archiveAPIModule.archiveSaver.base.get.runNow.getOrElse(throw new IllegalArgumentException("test"))
    val techInfos = restTestSetUp.mockTechniques.techniqueRepo.getTechniquesInfo()

    checks.checkTechnique(techInfos, archive._1.techniques(0)).runNow must beEqualTo(())
  }

  "correctly build an archive of one group" >> {

    // group with ID 0000f5d3-8c61-4d20-88a7-bb947705ba8a defined in com/normation/rudder/MockServices.scala has name:
    // Real nodes
    // so: Real_nodes
    val fileName     = "Real_nodes.json"
    val categoryName = "category_1"

    val archiveName = "archive-group"
    restTestSetUp.archiveAPIModule.rootDirName.set(archiveName).runNow

    restTest.testGETResponse("/api/latest/archives/export?groups=0000f5d3-8c61-4d20-88a7-bb947705ba8a&include=none") {
      case Full(OutputStreamResponse(out, _, _, _, 200)) =>
        val zipFile = testDir / s"${archiveName}.zip"
        val zipOut  = new FileOutputStream(zipFile.toJava)
        out(zipOut)
        zipOut.close()
        // unzip
        ZipUtils.unzip(new ZipFile(zipFile.toJava), zipFile.parent.toJava).runNow

        (directChildren(testDir / s"${archiveName}/groups") must containTheSameElementsAs(List(categoryName)))
        (children(testDir / s"${archiveName}/groups/${categoryName}") must containTheSameElementsAs(
          List(ZipArchiveBuilderService.GROUP_CAT_FILENAME, fileName)
        )) and
        (children(testDir / s"${archiveName}/rules").isEmpty must beTrue) and
        (children(testDir / s"${archiveName}/directives").isEmpty must beTrue) and
        (children(testDir / s"${archiveName}/techniques").isEmpty must beTrue)

      case err => ko(s"I got an error in test: ${err}")
    }
  }

  "correctly build an archive of one technique" >> {
    val archiveName = "archive-technique"
    restTestSetUp.archiveAPIModule.rootDirName.set(archiveName).runNow
    val techniqueId = "Create_file/1.0"
    restTest.testGETResponse(s"/api/latest/archives/export?techniques=${techniqueId}&include=none") {
      case Full(OutputStreamResponse(out, _, _, _, 200)) =>
        val zipFile = testDir / s"${archiveName}.zip"
        val zipOut  = new FileOutputStream(zipFile.toJava)
        out(zipOut)
        zipOut.close()
        // unzip
        ZipUtils.unzip(new ZipFile(zipFile.toJava), zipFile.parent.toJava).runNow

        val techniqueFiles = List("Create_file.ps1", "expected_reports.csv", "metadata.xml", "rudder_reporting.st")

        (children(testDir / s"${archiveName}/techniques/ncf_techniques/${techniqueId}") must containTheSameElementsAs(
          techniqueFiles
        ))
        (children(testDir / s"${archiveName}/groups").isEmpty must beTrue) and
        (children(testDir / s"${archiveName}/directives").isEmpty must beTrue) and
        (children(testDir / s"${archiveName}/rules").isEmpty must beTrue)

      case err => ko(s"I got an error in test: ${err}")
    }
  }

  "correctly build an archive of a YAML technique and filter generated files" >> {
    val archiveName = "archive-technique-yaml"
    restTestSetUp.archiveAPIModule.rootDirName.set(archiveName).runNow
    val techniqueId = "a_simple_yaml_technique/1.0"
    restTest.testGETResponse(s"/api/latest/archives/export?techniques=${techniqueId}&include=none") {
      case Full(OutputStreamResponse(out, _, _, _, 200)) =>
        val zipFile = testDir / s"${archiveName}.zip"
        val zipOut  = new FileOutputStream(zipFile.toJava)
        out(zipOut)
        zipOut.close()
        // unzip
        ZipUtils.unzip(new ZipFile(zipFile.toJava), zipFile.parent.toJava).runNow

        val techniqueFiles =
          List(TechniqueFiles.yaml, "resources", "something.txt") // other generated files are not included in archive

        (
          children(testDir / s"${archiveName}/techniques/ncf_techniques/${techniqueId}") must containTheSameElementsAs(
            techniqueFiles
          )
        )
        (children(testDir / s"${archiveName}/groups").isEmpty must beTrue) and
        (children(testDir / s"${archiveName}/directives").isEmpty must beTrue) and
        (children(testDir / s"${archiveName}/rules").isEmpty must beTrue)

      case err => ko(s"I got an error in test: ${err}")
    }
  }

  "correctly build an archive with two rules and only group and directive" in {
    val fileName1 = "10__Global_configuration_for_all_nodes.json"
    val fileName2 = "60-rule-technique-std-lib.json"

    val archiveName = "archive-two-rules-with-dep"
    restTestSetUp.archiveAPIModule.rootDirName.set(archiveName).runNow

    restTest.testGETResponse(
      "/api/latest/archives/export?rules=rule1,ff44fb97-b65e-43c4-b8c2-0df8d5e8549f&include=groups,directives"
    ) {
      case Full(OutputStreamResponse(out, _, _, _, 200)) =>
        val zipFile = testDir / s"${archiveName}.zip"
        val zipOut  = new FileOutputStream(zipFile.toJava)
        out(zipOut)
        zipOut.close()
        // unzip
        ZipUtils.unzip(new ZipFile(zipFile.toJava), zipFile.parent.toJava).runNow

        (children(testDir / s"${archiveName}/rules") must containTheSameElementsAs(List(fileName1, fileName2))) and
        // only system group => none exported
        (children(testDir / s"${archiveName}/groups") must containTheSameElementsAs(Nil)) and
        (children(testDir / s"${archiveName}/directives") must containTheSameElementsAs(
          List(
            "10__Clock_Configuration.json",
            "directive_16617aa8-1f02-4e4a-87b6-d0bcdfb4019f.json",
            "directive_e9a1a909-2490-4fc9-95c3-9d0aa01717c9.json",
            "directive_99f4ef91-537b-4e03-97bc-e65b447514cc.json"
          )
        )) and
        (children(testDir / s"${archiveName}/techniques").isEmpty must beTrue)

      case err => ko(s"I got an error in test: ${err}")
    }
  }

  "correctly build an archive with all rules (only)" in {

    val archiveName = "archive-all-rules-no-dep"
    restTestSetUp.archiveAPIModule.rootDirName.set(archiveName).runNow

    restTest.testGETResponse(
      "/api/latest/archives/export?rules=all&include=none"
    ) {
      case Full(OutputStreamResponse(out, _, _, _, 200)) =>
        val zipFile = testDir / s"${archiveName}.zip"
        val zipOut  = new FileOutputStream(zipFile.toJava)
        out(zipOut)
        zipOut.close()
        // unzip
        ZipUtils.unzip(new ZipFile(zipFile.toJava), zipFile.parent.toJava).runNow

        children(testDir / s"${archiveName}/rules") must containTheSameElementsAs(
          List(
            "10__Global_configuration_for_all_nodes.json",
            "50__Deploy_PLOP_STACK.json",
            "50-rule-technique-ncf.json",
            "60-rule-technique-std-lib.json",
            "90-copy-git-file.json",
            "99-rule-technique-std-lib.json"
          )
        )
        children(testDir / s"${archiveName}/groups") must containTheSameElementsAs(Nil)
        children(testDir / s"${archiveName}/directives") must containTheSameElementsAs(Nil)
        children(testDir / s"${archiveName}/techniques") must containTheSameElementsAs(Nil)

      case err => ko(s"I got an error in test: ${err}")
    }
  }

  "correctly build an archive with all directives (only)" in {

    val archiveName = "archive-all-directives-no-dep"
    restTestSetUp.archiveAPIModule.rootDirName.set(archiveName).runNow

    restTest.testGETResponse(
      "/api/latest/archives/export?directives=all&include=none"
    ) {
      case Full(OutputStreamResponse(out, _, _, _, 200)) =>
        val zipFile = testDir / s"${archiveName}.zip"
        val zipOut  = new FileOutputStream(zipFile.toJava)
        out(zipOut)
        zipOut.close()
        // unzip
        ZipUtils.unzip(new ZipFile(zipFile.toJava), zipFile.parent.toJava).runNow

        children(testDir / s"${archiveName}/rules") must containTheSameElementsAs(Nil)
        children(testDir / s"${archiveName}/groups") must containTheSameElementsAs(Nil)
        children(testDir / s"${archiveName}/directives") must containTheSameElementsAs(
          List(
            "00__Generic_Variable_Def__2.json",
            "10__Clock_Configuration.json",
            "99__Generic_Variable_Def__1.json",
            "directive_16617aa8-1f02-4e4a-87b6-d0bcdfb4019f.json",
            "directive_16d86a56-93ef-49aa-86b7-0d10102e4ea9.json",
            "directive2.json",
            "directive_99f4ef91-537b-4e03-97bc-e65b447514cc.json",
            "directive-copyGitFile.json",
            "directive_e9a1a909-2490-4fc9-95c3-9d0aa01717c9.json",
            "test_import_export_archive_directive.json",
            "25__Testing_blocks.json"
          )
        )
        children(testDir / s"${archiveName}/techniques") must containTheSameElementsAs(Nil)

      case err => ko(s"I got an error in test: ${err}")
    }
  }

  "correctly build an archive with all techniques (only)" in {

    val archiveName = "archive-all-techniques-no-dep"
    restTestSetUp.archiveAPIModule.rootDirName.set(archiveName).runNow

    restTest.testGETResponse(
      "/api/latest/archives/export?techniques=all&include=none"
    ) {
      case Full(OutputStreamResponse(out, _, _, _, 200)) =>
        val zipFile = testDir / s"${archiveName}.zip"
        val zipOut  = new FileOutputStream(zipFile.toJava)
        out(zipOut)
        zipOut.close()
        // unzip
        ZipUtils.unzip(new ZipFile(zipFile.toJava), zipFile.parent.toJava).runNow

        children(testDir / s"${archiveName}/rules") must containTheSameElementsAs(Nil)
        children(testDir / s"${archiveName}/groups") must containTheSameElementsAs(Nil)
        children(testDir / s"${archiveName}/directives") must containTheSameElementsAs(Nil)
        children(testDir / s"${archiveName}/techniques") must containTheSameElementsAs(
          List(
            "applications",
            "category.json",
            "packageManagement",
            "1.0",
            "changelog",
            "metadata.xml",
            "packageManagement.st",
            "rpmPackageInstallation",
            "7.0",
            "changelog",
            "metadata.xml",
            "rpmPackageInstallationData.st",
            "rpmPackageInstallation.st",
            "fileDistribution",
            "category.json",
            "copyGitFile",
            "2.3",
            "changelog",
            "copyFileFromSharedFolder.ps1.st",
            "copyFileFromSharedFolder.st",
            "metadata.xml",
            "fileTemplate",
            "1.0",
            "fileTemplate.ps1.st",
            "fileTemplate.st",
            "fileWithIdOnPath.txt",
            "metadata.xml",
            "tmlWithIdOnPath.st",
            "ncf_techniques",
            "a_simple_yaml_technique",
            "1.0",
            "resources",
            "something.txt",
            "technique.yml",
            "category.json",
            "Create_file",
            "1.0",
            "Create_file.ps1",
            "expected_reports.csv",
            "metadata.xml",
            "rudder_reporting.st",
            "technique_any",
            "1.0",
            "technique.yml",
            "2.0",
            "metadata.xml",
            "technique.cf",
            "technique.ps1",
            "technique_by_Rudder",
            "1.0",
            "technique.yml",
            "technique_with_blocks",
            "1.0",
            "metadata.xml",
            "technique.cf",
            "technique.json",
            "technique.ps1",
            "technique.rd",
            "test_import_export_archive",
            "1.0",
            "metadata.xml",
            "technique.cf",
            "technique.json",
            "technique.ps1",
            "systemSettings",
            "category.json",
            "misc",
            "category.json",
            "clockConfiguration",
            "3.0",
            "changelog",
            "clockConfiguration.st",
            "metadata.xml",
            "genericVariableDefinition",
            "1.0",
            "changelog",
            "genericVariableDefinition.st",
            "metadata.xml",
            "2.0",
            "changelog",
            "genericVariableDefinition.st",
            "metadata.xml",
            "test_only",
            "category.json",
            "test_18205",
            "1.0",
            "metadata.xml",
            "test_18205.st"
          )
        )

      case err => ko(s"I got an error in test: ${err}")
    }
  }

  "correctly build an archive with all groups (only)" in {

    val archiveName = "archive-all-groups-no-dep"
    restTestSetUp.archiveAPIModule.rootDirName.set(archiveName).runNow

    restTest.testGETResponse(
      "/api/latest/archives/export?groups=all&include=none"
    ) {
      case Full(OutputStreamResponse(out, _, _, _, 200)) =>
        val zipFile = testDir / s"${archiveName}.zip"
        val zipOut  = new FileOutputStream(zipFile.toJava)
        out(zipOut)
        zipOut.close()
        // unzip
        ZipUtils.unzip(new ZipFile(zipFile.toJava), zipFile.parent.toJava).runNow

        children(testDir / s"${archiveName}/rules") must containTheSameElementsAs(Nil)
        children(testDir / s"${archiveName}/groups") must containTheSameElementsAs(
          List(
            "category_1", // our children also list directories
            "category.json",
            "Real_nodes.json",
            "Empty_group.json",
            "Even_nodes.json",
            "Nodes_id_divided_by_3.json",
            "Nodes_id_divided_by_5.json",
            "Odd_nodes.json",
            "only_root.json",
            "Serveurs_______casse_s.json"
          )
        )
        children(testDir / s"${archiveName}/directives") must containTheSameElementsAs(Nil)
        children(testDir / s"${archiveName}/techniques") must containTheSameElementsAs(Nil)

      case err => ko(s"I got an error in test: ${err}")
    }
  }

  "we should be able to unzip an archive with ZipInputStream" >> {
    /*
     * use one the previously downloaded archive, and unzip it into a new subdirectory, and compare content
     */
    val archiveDir = "archive-rule-with-dep"
    val unzipped   = testDir / archiveDir
    val is         = File(unzipped.pathAsString + ".zip").newInputStream
    val entries    = ZipUtils.getZipEntries("unzip-archive-with-input-stream", is).runNow
    val dest       = testDir / "unzip-with-input-stream"
    dest.createDirectory()
    entries.foreach {
      case (entry, bytes) =>
        val d = dest / entry.getName
        bytes match {
          case None    =>
            d.createDirectoryIfNotExists(true)
          case Some(b) =>
            d.parent.createDirectoryIfNotExists()
            d.writeBytes(b.iterator)
        }
    }

    children(dest / archiveDir) must containTheSameElementsAs(children(unzipped))
  }

  "unzipping and reading an archive" >> {
    /*
     * use one the previously downloaded archive, and unzip it into a new subdirectory, and compare content
     */
    val archiveDir = "archive-rule-with-dep"
    val unzipped   = testDir / archiveDir
    // read zip entries, load archive
    val p          = ZIO
      .acquireReleaseWith(IOResult.attempt(File(unzipped.pathAsString + ".zip").newInputStream))(is => effectUioUnit(is.close)) {
        is => ZipUtils.getZipEntries(archiveDir + ".zip", is)
      }
      .flatMap(entries => restTestSetUp.archiveAPIModule.zipArchiveReader.readPolicyItems(archiveDir + ".zip", entries))
      .runNow

    val rule1    = restTestSetUp.mockRules.ruleRepo.getOpt(RuleId(RuleUid("rule1"))).notOptional(s"test").runNow
    val dir1     = restTestSetUp.mockDirectives.directiveRepo.getDirective(DirectiveUid("directive1")).notOptional(s"test").runNow
    val tech     = restTestSetUp.mockTechniques.techniqueRepo
      .get(
        TechniqueId(
          TechniqueName("clockConfiguration"),
          TechniqueVersion.parse("3.0").getOrElse(throw new IllegalArgumentException("test"))
        )
      )
      .getOrElse(throw new IllegalArgumentException("test"))
    val techInfo = TechniqueInfo(tech.id, tech.name, TechniqueType.Metadata)

    (p.techniques(0).technique must beEqualTo(techInfo)) and
    (p.directives(0).directive must beEqualTo(dir1)) and
    (p.rules(0) must beEqualTo(rule1))
  }

  "uploading an archive" >> {
    def sed(f: File, replace: String, by: String): Unit = {

      val content = f.contentAsString.replaceAll(replace, by)
      f.writeText(content)
    }

    /*
     * Copy the content of an existing archive into an import directory, zip-it
     */
    val dest      = testDir / "import-rule-with-dep"
    // so that we have systemSettings/misc/clockConfiguration
    FileUtils.copyDirectory((testDir / "archive-rule-with-dep").toJava, dest.toJava)
    // so that we have a yaml technique
    FileUtils.copyDirectory((testDir / "archive-technique-yaml").toJava, dest.toJava)
    // add a group
    val subCatDir = testDir / "import-rule-with-dep" / "groups" / "category_1"
    subCatDir.createDirectoryIfNotExists(createParents = true)
    (testDir / "archive-group" / "groups" / "category_1" / "category.json").copyToDirectory(subCatDir)
    (testDir / "archive-group" / "groups" / "category_1" / "Real_nodes.json").copyToDirectory(subCatDir)

    val tech = restTestSetUp.mockTechniques.techniqueRepo
      .get(
        TechniqueId(
          TechniqueName("clockConfiguration"),
          TechniqueVersion.parse("3.0").getOrElse(throw new IllegalArgumentException("test"))
        )
      )
      .getOrElse(throw new IllegalArgumentException("test"))
      .copy(name = "Time settings updated")

    val techInfo = TechniqueInfo(tech.id, tech.name, TechniqueType.Metadata)

    val dir1 = restTestSetUp.mockDirectives.directiveRepo
      .getDirective(DirectiveUid("directive1"))
      .notOptional(s"test")
      .runNow
      .copy(shortDescription = "a new description")

    val group = {
      val (group, _) = restTestSetUp.mockNodeGroups.groupsRepo
        .getNodeGroup(NodeGroupId(NodeGroupUid("0000f5d3-8c61-4d20-88a7-bb947705ba8a")))
        .runNow
      // hidden properties are not copied in archive
      group.copy(description = "a new description").copy(properties = group.properties.filter(_.visibility == Displayed))
    }

    val rule1 = restTestSetUp.mockRules.ruleRepo
      .getOpt(RuleId(RuleUid("rule1")))
      .notOptional(s"test")
      .runNow
      .copy(shortDescription = "a new description")

    // change things
    sed(
      dest / "techniques" / "systemSettings" / "category.json",
      """"name" : "System settings"""",
      s""""name" : "System settings updated""""
    )
    sed(
      dest / "techniques" / "systemSettings" / "misc" / "clockConfiguration" / "3.0" / "metadata.xml",
      """<TECHNIQUE name="Time settings">""",
      s"""<TECHNIQUE name="${tech.name}">"""
    )
    sed(
      dest / "directives" / "10__Clock_Configuration.json",
      """"shortDescription" : """"",
      s""""shortDescription" : "${dir1.shortDescription}""""
    )
    sed(
      dest / "groups" / "category_1" / "category.json",
      """"description" : """"",
      s""""description" : "a new category 1 description""""
    )
    sed(
      dest / "groups" / "category_1" / "Real_nodes.json",
      """"description" : """"",
      s""""description" : "${group.description}""""
    )
    sed(
      dest / "rules" / "10__Global_configuration_for_all_nodes.json",
      """global config for all nodes""",
      s"""${rule1.shortDescription}"""
    )

    // now zip it
    val zip = File(dest.pathAsString + ".zip")
    dest.zipTo(zip)

    restTest.testBinaryPOSTResponse(s"/api/latest/archives/import", "archive", zip.name, zip.newInputStream.readAllBytes()) {
      case Full(LiftJsonResponse(res, _, 200)) =>
        restTestSetUp.archiveAPIModule.archiveSaver.base.get.runNow match {
          case None         => ko(s"No policies were saved")
          case Some((p, m)) =>
            // we have 3 techniques cats: techniques/ncf_techniques (0), techniques/systemSettings/misc (1), and techniques/systemSettings (2)
            // and two techniques: a_simple_yaml_technique (0) and clockConfiguration (1)
            // and only one group (added by hand), directive and rule.

            (p.techniqueCats.sortBy(_.metadata.name).apply(2).metadata.name must beEqualTo("System settings updated")) and
            (p.techniques.sortBy(_.technique.id.name).apply(1).technique must beEqualTo(techInfo)) and
            (p.directives(0).directive must beEqualTo(dir1)) and
            (p.groups(0).group must beEqualTo(group))
            (p.rules(0) must beEqualTo(rule1))
        }

      case err => ko(s"I got an error in test: ${err}")
    }
  }

  "uploading an archive with a yaml technique with a mismatch between id and path stop everything" >> {
    def sed(f: File, replace: String, by: String): Unit = {

      val content = f.contentAsString.replaceAll(replace, by)
      f.writeText(content)
    }

    /*
     * Copy the content of a existing archive into an import directory, zip-it
     */
    val dest = testDir / "import-bad-yaml"
    // so that we have systemSettings/misc/clockConfiguration
    FileUtils.copyDirectory((testDir / "archive-rule-with-dep").toJava, dest.toJava)
    // so that we have a yaml technique
    FileUtils.copyDirectory((testDir / "archive-technique-yaml").toJava, dest.toJava)
    // add a group
    (testDir / "archive-group" / "groups" / "category_1" / "Real_nodes.json").copyToDirectory(dest / "groups")

    // save content before upload
    val tech     = restTestSetUp.mockTechniques.techniqueRepo
      .get(
        TechniqueId(
          TechniqueName("clockConfiguration"),
          TechniqueVersion.parse("3.0").getOrElse(throw new IllegalArgumentException("test"))
        )
      )
      .getOrElse(throw new IllegalArgumentException("test"))
    val techInfo = TechniqueInfo(tech.id, tech.name, TechniqueType.Metadata)

    val dir1  = restTestSetUp.mockDirectives.directiveRepo
      .getDirective(DirectiveUid("directive1"))
      .notOptional(s"test")
      .runNow
    val group = {
      val (group, _) = restTestSetUp.mockNodeGroups.groupsRepo
        .getNodeGroup(NodeGroupId(NodeGroupUid("0000f5d3-8c61-4d20-88a7-bb947705ba8a")))
        .runNow
    }
    val rule1 = restTestSetUp.mockRules.ruleRepo
      .getOpt(RuleId(RuleUid("rule1")))
      .notOptional(s"test")
      .runNow

    // change things
    sed(
      dest / "techniques" / "systemSettings" / "misc" / "clockConfiguration" / "3.0" / "metadata.xml",
      """<TECHNIQUE name="Time settings">""",
      s"""<TECHNIQUE name="XXXXXXXXX">"""
    )
    sed(
      dest / "directives" / "10__Clock_Configuration.json",
      """"shortDescription" : """"",
      s""""shortDescription" : "XXXXXXXXXX""""
    )
    sed(dest / "groups" / "Real_nodes.json", """"description" : """"", s""""description" : "XXXXXXXX"""")
    sed(
      dest / "rules" / "10__Global_configuration_for_all_nodes.json",
      """global config for all nodes""",
      s"""XXXXXXXXXX"""
    )

    // change technique ID in YAML so that it mismatch path
    sed(
      dest / "techniques" / "ncf_techniques" / "a_simple_yaml_technique" / "1.0" / "technique.yml",
      """a_simple_yaml_technique""",
      s"""bad_bad_technique_id"""
    )

    // now zip it
    val zip = File(dest.pathAsString + ".zip")
    dest.zipTo(zip)

    // reset archive saver
    restTestSetUp.archiveAPIModule.archiveSaver.base.set(Option.empty[(PolicyArchive, MergePolicy)]).runNow

    restTest.testBinaryPOSTResponse(
      s"/api/latest/archives/import",
      "archive",
      zip.name,
      zip.newInputStream.readAllBytes()
    ) {
      case Full(LiftJsonResponse(res, _, 500)) =>
        restTestSetUp.archiveAPIModule.archiveSaver.base.get.runNow match {
          case None =>
            ok

          case Some((p, m)) =>
            // check that nothing changed - nothing should have
            (p.techniques(0).technique must beEqualTo(techInfo)) and
            (p.directives(0).directive must beEqualTo(dir1)) and
            (p.groups(0).group must beEqualTo(group))
            (p.rules(0) must beEqualTo(rule1))
        }

      case err => ko(s"I got an error in test (response should error 500): ${err}")
    }
  }

  /*
   * This one change content of rule1, directive1 and group1, do it once you don't need original values
   * anymore.
   */
  "correctly build an archive with past revision items" >> {
    import zio.json.*
    import com.normation.rudder.apidata.implicits.*

    val initRev = {
      val head   = restTestSetUp.mockGitRepo.gitRepo.db.exactRef("refs/heads/master")
      val walk   = new RevWalk(restTestSetUp.mockGitRepo.gitRepo.db)
      val commit = walk.parseCommit(head.getObjectId)
      walk.dispose()
      commit.name()
    }

    // update rule definition
    val ruleId       = "rule1"
    val ruleFileName = "10__Global_configuration_for_all_nodes.json"
    val newDesc      = "new rule description"

    (for {
      r <- restTestSetUp.mockRules.ruleRepo.getOpt(RuleId(RuleUid(ruleId))).notOptional(s"missing ${ruleId} in test")
      _ <-
        restTestSetUp.mockRules.ruleRepo
          .update(r.copy(shortDescription = newDesc), ModificationId("rule"), EventActor("test"), None)
    } yield ()).runNow
    // update technique
    val techniqueId = "Create_file/1.0"
    val relPath     = s"techniques/ncf_techniques/${techniqueId}/newfile"
    val f           = restTestSetUp.mockGitRepo.configurationRepositoryRoot / relPath
    f.write("hello world")
    restTestSetUp.mockGitRepo.gitRepo.git.add().addFilepattern(relPath).call()
    restTestSetUp.mockGitRepo.gitRepo.git.commit().setMessage(s"add file in ${techniqueId}").call()
    restTestSetUp.mockTechniques.techniqueReader.readTechniques

    val baseFiles = List("Create_file.ps1", "expected_reports.csv", "metadata.xml", "rudder_reporting.st")

    {
      val archiveName = "archive-technique-head"
      restTestSetUp.archiveAPIModule.rootDirName.set(archiveName).runNow
      restTest.testGETResponse(s"/api/latest/archives/export?rules=${ruleId}&techniques=${techniqueId}") {
        case Full(OutputStreamResponse(out, _, _, _, 200)) =>
          val zipFile = testDir / s"${archiveName}.zip"
          val zipOut  = new FileOutputStream(zipFile.toJava)
          out(zipOut)
          zipOut.close()
          // unzip
          ZipUtils.unzip(new ZipFile(zipFile.toJava), zipFile.parent.toJava).runNow

          val r = (testDir / s"${archiveName}/rules/${ruleFileName}").contentAsString
            .fromJson[JQRule]
            .getOrElse(throw new IllegalArgumentException(s"error in rule deserialization"))

          (r.shortDescription.getOrElse("") must beMatching(newDesc)) and
          (children(testDir / s"${archiveName}/techniques/ncf_techniques/${techniqueId}") must containTheSameElementsAs(
            "newfile" :: baseFiles
          ))

        case err => ko(s"I got an error in test: ${err}")
      }
    } and {
      val archiveName = "archive-technique-init"
      restTestSetUp.archiveAPIModule.rootDirName.set(archiveName).runNow
      // TODO: rule are not serialized in test repos, we won't find it!
      restTest.testGETResponse(s"/api/latest/archives/export?rules=${ruleId}&techniques=${techniqueId}%2B${initRev}") {
        case Full(OutputStreamResponse(out, _, _, _, 200)) =>
          val zipFile = testDir / s"${archiveName}.zip"
          val zipOut  = new FileOutputStream(zipFile.toJava)
          out(zipOut)
          zipOut.close()
          // unzip
          ZipUtils.unzip(new ZipFile(zipFile.toJava), zipFile.parent.toJava).runNow

          // val r = (testDir / s"${archiveName}/rules/${ruleFileName}").contentAsString.fromJson[JQRule].getOrElse(throw new IllegalArgumentException(s"error in rule deserialization"))
          // (r.shortDescription.getOrElse("") must beMatching("global config for all nodes")) and
          (children(testDir / s"${archiveName}/techniques/ncf_techniques/${techniqueId}") must containTheSameElementsAs(
            baseFiles
          ))

        case err => ko(s"I got an error in test: ${err}")
      }
    }
  }
}
