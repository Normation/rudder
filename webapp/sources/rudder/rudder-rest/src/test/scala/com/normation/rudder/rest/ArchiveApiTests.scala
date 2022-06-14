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

import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.apidata.JsonQueryObjects.JQRule
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.git.ZipUtils
import com.normation.utils.DateFormaterService

import better.files.File
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.http.InMemoryResponse
import net.liftweb.http.OutputStreamResponse
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.revwalk.RevWalk
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.AfterAll

import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

import com.normation.zio._

@RunWith(classOf[JUnitRunner])
class ArchiveApiTests extends Specification with AfterAll with Loggable {

  val restTestSetUp = RestTestSetUp.newEnv
  val restTest = new RestTest(restTestSetUp.liftRules)

  val testDir = File(s"/tmp/test-rudder-response-content-${DateFormaterService.serialize(DateTime.now())}")
  testDir.createDirectoryIfNotExists(true)

  override def afterAll(): Unit = {
    if (System.getProperty("tests.clean.tmp") != "false") {
      logger.info("Cleanup rest env ")
      restTestSetUp.cleanup()
      logger.info("Deleting directory " + testDir.pathAsString)
      FileUtils.deleteDirectory(testDir.toJava)
    }
  }

  def children(f: File) = f.children.toList.map(_.name)

  org.slf4j.LoggerFactory.getLogger("application.archive").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)
  org.slf4j.LoggerFactory.getLogger("configuration").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)

  sequential

  "when the feature switch is disabled, request" should {
    "error in GET /archives/export" in {
      restTest.testGETResponse("/api/archives/export") {
        case Full(InMemoryResponse(json, _, _, 500)) => new String(json, StandardCharsets.UTF_8) must beMatching(".*This API is disabled.*")
        case err                                     => ko(s"I got an error in test: ${err}")
      }
    }

    "error in POST /archives/export" in {
      restTest.testEmptyPostResponse("/api/archives/import") {
        case Full(InMemoryResponse(json, _, _, 500)) => new String(json, StandardCharsets.UTF_8) must beMatching(".*This API is disabled.*")
        case err                                     => ko(s"I got an error in test: ${err}")
      }
    }
  }

  // FROM THERE, FEATURE IS ENABLED

  "when the feature switch is enabled, request" should {
    "succeed in GET /archives/export" in {
      // feature switch change needs to be at that level and not under "should" directly,
      // else it contaminated all tests, even with the sequential annotation
      restTestSetUp.archiveAPIModule.featureSwitchState.set(FeatureSwitch.Enabled).runNow
      restTest.testGETResponse("/api/archives/export") {
        case Full(resp) => resp.toResponse.code must beEqualTo(200)
        case err        => ko(s"I got an error in test: ${err}")
      }
    }

    "succeed in POST /archives/export" in {
      restTest.testEmptyPostResponse("/api/archives/import") {
        case Full(resp) => resp.toResponse.code must beEqualTo(200)
        case err        => ko(s"I got an error in test: ${err}")
      }
    }
  }

  "correctly build an archive of one rule, no deps" >> {

    // rule with ID rule1 defined in com/normation/rudder/MockServices.scala has name:
    // 10. Global configuration for all nodes
    // so: 10__Global_configuration_for_all_nodes
    val fileName = "10__Global_configuration_for_all_nodes.json"

    val archiveName = "archive-rule-no-dep"
    restTestSetUp.archiveAPIModule.rootDirName.set(archiveName).runNow

    restTest.testGETResponse("/api/archives/export?rules=rule1&include=none") {
      case Full(OutputStreamResponse(out, _, _, _, 200)) =>
        val zipFile = testDir/s"${archiveName}.zip"
        val zipOut = new FileOutputStream(zipFile.toJava)
        out(zipOut)
        zipOut.close()
        // unzip
        ZipUtils.unzip(new ZipFile(zipFile.toJava), zipFile.parent.toJava).runNow

        (children(testDir/s"${archiveName}/rules") must containTheSameElementsAs(List(fileName))) and
        (children(testDir/s"${archiveName}/groups").isEmpty must beTrue) and
        (children(testDir/s"${archiveName}/directives").isEmpty must beTrue) and
        (children(testDir/s"${archiveName}/techniques").isEmpty must beTrue)

      case err => ko(s"I got an error in test: ${err}")
    }
  }


  "correctly build an archive with one rule, dep: group and technique (and implied directives)" in {
    val fileName = "10__Global_configuration_for_all_nodes.json"

    val archiveName = "archive-rule-with-dep"
    restTestSetUp.archiveAPIModule.rootDirName.set(archiveName).runNow

    restTest.testGETResponse("/api/archives/export?rules=rule1&include=groups,techniques") {
      case Full(OutputStreamResponse(out, _, _, _, 200)) =>
        val zipFile = testDir/s"${archiveName}.zip"
        val zipOut = new FileOutputStream(zipFile.toJava)
        out(zipOut)
        zipOut.close()
        // unzip
        ZipUtils.unzip(new ZipFile(zipFile.toJava), zipFile.parent.toJava).runNow

        (children(testDir/s"${archiveName}/rules") must containTheSameElementsAs(List(fileName))) and
        // only system group => none exported
        (children(testDir/s"${archiveName}/groups") must containTheSameElementsAs(Nil)) and
        (children(testDir/s"${archiveName}/directives") must containTheSameElementsAs(List("10__Clock_Configuration.json"))) and
        (children(testDir/s"${archiveName}/techniques") must containTheSameElementsAs(List("clockConfiguration"))) and
        (children(testDir/s"${archiveName}/techniques/clockConfiguration/3.0") must containTheSameElementsAs(List("changelog", "clockConfiguration.st", "metadata.xml")))

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

    restTest.testGETResponse("/api/archives/export?directives=directive1&include=none") {
      case Full(OutputStreamResponse(out, _, _, _, 200)) =>
        val zipFile = testDir/s"${archiveName}.zip"
        val zipOut = new FileOutputStream(zipFile.toJava)
        out(zipOut)
        zipOut.close()
        // unzip
        ZipUtils.unzip(new ZipFile(zipFile.toJava), zipFile.parent.toJava).runNow

        (children(testDir/s"${archiveName}/directives") must containTheSameElementsAs(List(fileName))) and
        (children(testDir/s"${archiveName}/groups").isEmpty must beTrue) and
        (children(testDir/s"${archiveName}/rules").isEmpty must beTrue) and
        (children(testDir/s"${archiveName}/techniques").isEmpty must beTrue)

      case err => ko(s"I got an error in test: ${err}")
    }
  }

  "correctly build an archive of one group" >> {

    // group with ID 0000f5d3-8c61-4d20-88a7-bb947705ba8a defined in com/normation/rudder/MockServices.scala has name:
    // Real nodes
    // so: Real_nodes
    val fileName = "Real_nodes.json"

    val archiveName = "archive-group"
    restTestSetUp.archiveAPIModule.rootDirName.set(archiveName).runNow

    restTest.testGETResponse("/api/archives/export?groups=0000f5d3-8c61-4d20-88a7-bb947705ba8a&include=none") {
      case Full(OutputStreamResponse(out, _, _, _, 200)) =>
        val zipFile = testDir/s"${archiveName}.zip"
        val zipOut = new FileOutputStream(zipFile.toJava)
        out(zipOut)
        zipOut.close()
        // unzip
        ZipUtils.unzip(new ZipFile(zipFile.toJava), zipFile.parent.toJava).runNow

        (children(testDir/s"${archiveName}/groups") must containTheSameElementsAs(List(fileName)))
        (children(testDir/s"${archiveName}/rules").isEmpty must beTrue) and
        (children(testDir/s"${archiveName}/directives").isEmpty must beTrue) and
        (children(testDir/s"${archiveName}/techniques").isEmpty must beTrue)

      case err => ko(s"I got an error in test: ${err}")
    }
  }

  "correctly build an archive of one technique" >> {
    val archiveName = "archive-technique"
    restTestSetUp.archiveAPIModule.rootDirName.set(archiveName).runNow
    val techniqueId = "Create_file/1.0"
    restTest.testGETResponse(s"/api/archives/export?techniques=${techniqueId}&include=none") {
      case Full(OutputStreamResponse(out, _, _, _, 200)) =>
        val zipFile = testDir/s"${archiveName}.zip"
        val zipOut = new FileOutputStream(zipFile.toJava)
        out(zipOut)
        zipOut.close()
        // unzip
        ZipUtils.unzip(new ZipFile(zipFile.toJava), zipFile.parent.toJava).runNow

        val techniqueFiles = List("Create_file.ps1", "expected_reports.csv", "metadata.xml", "rudder_reporting.st")

        (children(testDir/s"${archiveName}/techniques/${techniqueId}") must containTheSameElementsAs(techniqueFiles))
        (children(testDir/s"${archiveName}/groups").isEmpty must beTrue) and
        (children(testDir/s"${archiveName}/directives").isEmpty must beTrue) and
        (children(testDir/s"${archiveName}/rules").isEmpty must beTrue)

      case err => ko(s"I got an error in test: ${err}")
    }
  }


  "correctly build an archive with two rules and only group and directive" in {
    val fileName1 = "10__Global_configuration_for_all_nodes.json"
    val fileName2 = "60-rule-technique-std-lib.json"

    val archiveName = "archive-two-rules-with-dep"
    restTestSetUp.archiveAPIModule.rootDirName.set(archiveName).runNow

    restTest.testGETResponse("/api/archives/export?rules=rule1,ff44fb97-b65e-43c4-b8c2-0df8d5e8549f&include=groups,directives") {
      case Full(OutputStreamResponse(out, _, _, _, 200)) =>
        val zipFile = testDir/s"${archiveName}.zip"
        val zipOut = new FileOutputStream(zipFile.toJava)
        out(zipOut)
        zipOut.close()
        // unzip
        ZipUtils.unzip(new ZipFile(zipFile.toJava), zipFile.parent.toJava).runNow

        (children(testDir/s"${archiveName}/rules") must containTheSameElementsAs(List(fileName1, fileName2))) and
        // only system group => none exported
        (children(testDir/s"${archiveName}/groups") must containTheSameElementsAs(Nil)) and
        (children(testDir/s"${archiveName}/directives") must containTheSameElementsAs(List(
            "10__Clock_Configuration.json"
          , "directive_16617aa8-1f02-4e4a-87b6-d0bcdfb4019f.json"
          , "directive_e9a1a909-2490-4fc9-95c3-9d0aa01717c9.json"
          , "directive_ff44fb97-b65e-43c4-b8c2-0df8d5e8549f.json"
        ))) and
        (children(testDir/s"${archiveName}/techniques").isEmpty must beTrue)

      case err => ko(s"I got an error in test: ${err}")
    }
  }


  "correctly build an archive with past revision items" >> {
    import zio.json._
    import com.normation.rudder.apidata.implicits._

    val initRev = {
      val head = restTestSetUp.mockGitRepo.gitRepo.db.exactRef("refs/heads/master")
      val walk = new RevWalk(restTestSetUp.mockGitRepo.gitRepo.db)
      val commit = walk.parseCommit(head.getObjectId)
      walk.dispose()
      commit.name()
    }

    //update rule definition
    val ruleId = "rule1"
    val ruleFileName = "10__Global_configuration_for_all_nodes.json"
    val newDesc = "new rule description"

    (for {
      r <- restTestSetUp.mockRules.ruleRepo.getOpt(RuleId(RuleUid(ruleId))).notOptional(s"missing ${ruleId} in test")
      _ <- restTestSetUp.mockRules.ruleRepo.update(r.copy(shortDescription = newDesc), ModificationId("rule"), EventActor("test"), None)
    } yield ()).runNow
    // update technique
    val techniqueId = "Create_file/1.0"
    val relPath = s"techniques/ncf_techniques/${techniqueId}/newfile"
    val f = restTestSetUp.mockGitRepo.configurationRepositoryRoot/relPath
    f.write("hello world")
    restTestSetUp.mockGitRepo.gitRepo.git.add().addFilepattern(relPath).call()
    restTestSetUp.mockGitRepo.gitRepo.git.commit().setMessage(s"add file in ${techniqueId}").call()
    restTestSetUp.mockTechniques.techniqueReader.readTechniques

    val baseFiles = List("Create_file.ps1", "expected_reports.csv", "metadata.xml", "rudder_reporting.st")

    {
      val archiveName = "archive-technique-head"
      restTestSetUp.archiveAPIModule.rootDirName.set(archiveName).runNow
      restTest.testGETResponse(s"/api/archives/export?rules=${ruleId}&techniques=${techniqueId}") {
        case Full(OutputStreamResponse(out, _, _, _, 200)) =>
          val zipFile = testDir/s"${archiveName}.zip"
          val zipOut = new FileOutputStream(zipFile.toJava)
          out(zipOut)
          zipOut.close()
          // unzip
          ZipUtils.unzip(new ZipFile(zipFile.toJava), zipFile.parent.toJava).runNow

          val r = (testDir / s"${archiveName}/rules/${ruleFileName}").contentAsString.fromJson[JQRule].getOrElse(throw new IllegalArgumentException(s"error in rule deserialization"))

          (r.shortDescription.getOrElse("") must beMatching(newDesc)) and
          (children(testDir/s"${archiveName}/techniques/${techniqueId}") must containTheSameElementsAs("newfile" :: baseFiles))

        case err => ko(s"I got an error in test: ${err}")
      }
    } and {
      val archiveName = "archive-technique-init"
      restTestSetUp.archiveAPIModule.rootDirName.set(archiveName).runNow
      // TODO: rule are not serialized in test repos, we won't find it!
      restTest.testGETResponse(s"/api/archives/export?rules=${ruleId}&techniques=${techniqueId}%2B${initRev}") {
        case Full(OutputStreamResponse(out, _, _, _, 200)) =>
          val zipFile = testDir/s"${archiveName}.zip"
          val zipOut = new FileOutputStream(zipFile.toJava)
          out(zipOut)
          zipOut.close()
          // unzip
          ZipUtils.unzip(new ZipFile(zipFile.toJava), zipFile.parent.toJava).runNow

          //val r = (testDir / s"${archiveName}/rules/${ruleFileName}").contentAsString.fromJson[JQRule].getOrElse(throw new IllegalArgumentException(s"error in rule deserialization"))
          //(r.shortDescription.getOrElse("") must beMatching("global config for all nodes")) and
          (children(testDir/s"${archiveName}/techniques/${techniqueId}") must containTheSameElementsAs(baseFiles))

        case err => ko(s"I got an error in test: ${err}")
      }
    }
  }


}

