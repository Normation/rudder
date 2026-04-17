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

import com.normation.rudder.domain.archives.ArchiveType
import com.normation.rudder.rest.RudderJsonResponse.ResponseSchema
import com.normation.rudder.rest.lift.*
import com.normation.rudder.rest.v1.RestStatus
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipFile
import net.liftweb.common.*
import net.liftweb.http.InMemoryResponse
import net.liftweb.http.PlainTextResponse
import net.liftweb.http.Req
import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.AfterAll
import scala.annotation.nowarn
import zio.json.ast.Json.*

@nowarn("msg=a type was inferred to be `\\w+`; this may indicate a programming error.")
@RunWith(classOf[JUnitRunner])
class SystemApiTest extends Specification with AfterAll with Loggable {
  given prettify: Boolean = false

  private val restTestSetUp = RestTestSetUp.newEnv
  private val restTest      = new RestTest(restTestSetUp.liftRules)

  "testing status REST API" should {
    "be correct" in {
      restTest.testGET("/api/status") { req =>
        RestStatus(req)() must beEqualTo(Full(PlainTextResponse("""OK
                                                                  |""".stripMargin)))
      }
    }
  }

  "Testing system API status" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("getStatus", None)
      val response = RudderJsonResponse.successOne(schema, Map("global" -> "OK"), None)

      restTest.testGET("/api/latest/system/status") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing technique reload of System Api" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("reloadTechniques", None)
      val response = RudderJsonResponse.successOne(schema, JReloadStatus(None, Some("Started")), None)

      restTest.testEmptyPost("/api/latest/system/reload/techniques") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing dynamic groups reload on System Api" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("reloadGroups", None)
      val response = RudderJsonResponse.successOne(schema, JReloadStatus(Some("Started"), None), None)

      restTest.testEmptyPost("/api/latest/system/reload/groups") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing dynamic groups and techniques reload of System Api" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("reloadAll", None)
      val response = RudderJsonResponse.successOne(schema, JReloadStatus(Some("Started"), Some("Started")), None)

      restTest.testEmptyPost("/api/latest/system/reload") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing policies update of System Api" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("updatePolicies", None)
      val response = RudderJsonResponse.successOne(schema, Obj("policies", Str("Started")), None)

      restTest.testEmptyPost("/api/latest/system/update/policies") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing policies regenerate of System Api" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("regeneratePolicies", None)
      val response = RudderJsonResponse.successOne(schema, Obj("policies", Str("Started")), None)

      restTest.testEmptyPost("/api/latest/system/regenerate/policies") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  // To test with multiple archives, declare more archive in restTestSetup
  // and add their Json representation to the archives list below.

  private val dateTimeId = "1970-01-01_01-00-00.042"

  private val archive1: JArchiveInfo =
    JArchiveInfo("path", "1970-01-01T010000Z", "test-user", "6d6b2ceb46adeecd845ad0c0812fee07e2727104")

  private val archives: List[JArchiveInfo] = List(archive1)

  "Testing archive groups listing of System Api" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("listGroupsArchive", None)
      val response = RudderJsonResponse.successOne(schema, Map("groups" -> archives), None)

      restTest.testGET("/api/latest/system/archives/groups") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing archive directives of System Api" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("listDirectivesArchive", None)
      val response = RudderJsonResponse.successOne(schema, Map("directives" -> archives), None)

      restTest.testGET("/api/latest/system/archives/directives") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing archive rules listing of System Api" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("listRulesArchive", None)
      val response = RudderJsonResponse.successOne(schema, Map("rules" -> archives), None)

      restTest.testGET("/api/latest/system/archives/rules") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing full archive listing of System Api" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("listFullArchive", None)
      val response = RudderJsonResponse.successOne(schema, Map("full" -> archives), None)

      restTest.testGET("/api/latest/system/archives/full") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing restore groups latest archive of System Api" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("restoreGroupsLatestArchive", None)
      val response = RudderJsonResponse.successOne(schema, Map("groups" -> "Started"), None)

      restTest.testEmptyPost("/api/latest/system/archives/groups/restore/latestArchive") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing restore directives latest archive of System Api" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("restoreDirectivesLatestArchive", None)
      val response = RudderJsonResponse.successOne(schema, Map("directives" -> "Started"), None)

      restTest.testEmptyPost("/api/latest/system/archives/directives/restore/latestArchive") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing restore rules latest archive of System Api" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("restoreRulesLatestArchive", None)
      val response = RudderJsonResponse.successOne(schema, Map("rules" -> "Started"), None)

      restTest.testEmptyPost("/api/latest/system/archives/rules/restore/latestArchive") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing restore full latest archive of System Api" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("restoreFullLatestArchive", None)
      val response = RudderJsonResponse.successOne(schema, Map("full" -> "Started"), None)

      restTest.testEmptyPost("/api/latest/system/archives/full/restore/latestArchive") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing restore groups latest commit archive of System Api" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("restoreGroupsLatestCommit", None)
      val response = RudderJsonResponse.successOne(schema, Map("groups" -> "Started"), None)

      restTest.testEmptyPost("/api/latest/system/archives/groups/restore/latestCommit") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing restore directives latest commit archive of System Api" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("restoreDirectivesLatestCommit", None)
      val response = RudderJsonResponse.successOne(schema, Map("directives" -> "Started"), None)

      restTest.testEmptyPost("/api/latest/system/archives/directives/restore/latestCommit") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing restore rules latest commit archive of System Api" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("restoreRulesLatestCommit", None)
      val response = RudderJsonResponse.successOne(schema, Map("rules" -> "Started"), None)

      restTest.testEmptyPost("/api/latest/system/archives/rules/restore/latestCommit") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing restore full latest commit archive of System Api" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("restoreFullLatestCommit", None)
      val response = RudderJsonResponse.successOne(schema, Map("full" -> "Started"), None)

      restTest.testEmptyPost("/api/latest/system/archives/full/restore/latestCommit") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  private val archive2: JArchiveLog = JArchiveLog("test-user", "6d6b2ceb46adeecd845ad0c0812fee07e2727104", "path")

  "Testing archive groups of System Api" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("archiveGroups", None)
      val response = RudderJsonResponse.successOne(schema, Map("groups" -> archive2), None)

      restTest.testEmptyPost("/api/latest/system/archives/groups") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing archive directives of System Api" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("archiveDirectives", None)
      val response = RudderJsonResponse.successOne(schema, Map("directives" -> archive2), None)

      restTest.testEmptyPost("/api/latest/system/archives/directives") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing archive rules of System Api" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("archiveRules", None)
      val response = RudderJsonResponse.successOne(schema, Map("rules" -> archive2), None)

      restTest.testEmptyPost("/api/latest/system/archives/rules") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing all archive of System Api" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("archiveAll", None)
      val response = RudderJsonResponse.successOne(schema, Map("full" -> archive2), None)

      restTest.testEmptyPost("/api/latest/system/archives/full") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing group archive restore based on a date time" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("archiveGroupDateRestore", None)
      val response = RudderJsonResponse.successOne(schema, Map("group" -> "Started"), None)

      restTest.testEmptyPost(s"/api/latest/system/archives/groups/restore/$dateTimeId") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing directive archive restore based on its date time" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("archiveDirectiveDateRestore", None)
      val response = RudderJsonResponse.successOne(schema, Map("directive" -> "Started"), None)

      restTest.testEmptyPost(s"/api/latest/system/archives/directives/restore/$dateTimeId") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing rule archive restore based on its date time" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("archiveRuleDateRestore", None)
      val response = RudderJsonResponse.successOne(schema, Map("rule" -> "Started"), None)

      restTest.testEmptyPost(s"/api/latest/system/archives/rules/restore/$dateTimeId") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing full archive restore based on its datetime" should {
    "match the response defined below" in {

      val schema   = ResponseSchema("archiveFullDateRestore", None)
      val response = RudderJsonResponse.successOne(schema, Map("full" -> "Started"), None)

      restTest.testEmptyPost(s"/api/latest/system/archives/full/restore/$dateTimeId") { req =>
        restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  /*
  **
   *  From here is the code needed to test the zip api endpoints. It is a 4 step process :
   *  1. Create a clone of /var/rudder/configuration-repository in the /tmp folder
   *  2. Call the appropriate endpoint and get the archive in /tmp/resource-content
   *  3. Unzip the archive
   *  4. Compare the appropriate data
   */
  private val commit = restTestSetUp.mockGitRepo.gitRepo.db.resolve("master")
  private val commitId: String = commit.getName

  private val commitDate: Instant =
    restTestSetUp.mockGitRepo.gitRepo.db.parseCommit(commit.toObjectId()).getCommitterIdent().getWhenAsInstant

  // Init directory needed to temporary store archive data that zip API returns.
  // It will be cleared at the end of the test in "afterAll"

  private val testDir = new File("/tmp/response-content")
  if (!testDir.exists()) {
    Files.createDirectory(testDir.toPath)
  }

  override def afterAll(): Unit = {
    if (System.getProperty("tests.clean.tmp") != "false") {
      logger.info("Cleanup rest env ")
      restTestSetUp.cleanup()
      logger.info("Deleting directory " + "/tmp/response-content")
      FileUtils.deleteDirectory(testDir)
    }
  }

  private def contentArchiveDiff(req: Req, matcher: List[File], archiveType: ArchiveType) = {
    import com.normation.rudder.git.ZipUtils

    restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() match {

      case Full(resp: InMemoryResponse)                    =>
        val filenameRegex = """attachment;\s*filename="(.*)"""".r
        val filename      = resp.headers.collectFirst {
          case (h, filenameRegex(filename)) if h.equalsIgnoreCase("Content-Disposition") => filename
        }
        val tmpFile       = new File(s"/tmp/response-content/${filename.getOrElse("_bad_file")}")
        FileUtils.writeByteArrayToFile(tmpFile, resp.data)
        val zip           = new ZipFile(tmpFile)
        ZipUtils.unzip(zip, testDir)
        def filterGeneratedFile(f: File): Boolean = matcher.contains(f)
        (resp.headers must beLike {
          case l: ::[(String, String)] =>
            l must containTheSameElementsAs(
              List(
                "Content-Type"        -> "application/zip",
                "Content-Disposition" -> s"""attachment;filename="${SystemApi.getArchiveName(archiveType, commitDate)}""""
              )
            )
        }) and
        (filename must beSome(
          beEqualTo(SystemApi.getArchiveName(archiveType, commitDate))
        )) and (testDir must org.specs2.matcher.ContentMatchers
          .haveSameFilesAs(restTestSetUp.mockGitRepo.configurationRepositoryRoot.toJava)
          .withFilter(filterGeneratedFile))
      case Full(JsonResponsePrettify(json, _, _, code, _)) =>
        import net.liftweb.http.js.JsExp.*
        (code must beEqualTo(500)) and
        (json.toJsCmd must beMatching(
          ".*Error when trying to get archive as a Zip: SystemError: Error when retrieving commit revision.*"
        ))
      case x                                               =>
        ko
    }
  }

  private val configRootPath = restTestSetUp.mockGitRepo.configurationRepositoryRoot.pathAsString

  "Getting directives zip archive from System Api" should {
    "satisfy the matcher defined below" in {

      val matcher = {
        val l = List(
          new File(configRootPath + "/directives"),
          new File(configRootPath + "/parameters"),
          new File(configRootPath + "/techniques"),
          new File(configRootPath + "/ncf")
        )
        l.filter(_.exists())
      }

      restTest.testGET(s"/api/latest/system/archives/directives/zip/$commitId") { req =>
        contentArchiveDiff(req, matcher, ArchiveType.Directives)
      }
    }
  }

  "Getting groups zip archive from System Api" should {
    "satisfy the matcher defined below" in {

      val matcher = List(new File(configRootPath + "/groups"))

      restTest.testGET(s"/api/latest/system/archives/groups/zip/$commitId") { req =>
        contentArchiveDiff(req, matcher, ArchiveType.Groups)
      }
    }
  }

  "Getting rules zip archive from System Api" should {
    "satisfy the matcher defined below" in {

      val matcher = List(new File(configRootPath + "/rules"), new File(configRootPath + "/ruleCategories"))

      restTest.testGET(s"/api/latest/system/archives/rules/zip/$commitId") { req =>
        contentArchiveDiff(req, matcher, ArchiveType.Rules)
      }
    }
  }

  "Getting full zip archive from System Api" should {
    "satisfy the matcher defined below" in {

      val matcher = {
        val l = List(
          new File(configRootPath + "/directives"),
          new File(configRootPath + "/groups"),
          new File(configRootPath + "/parameters"),
          new File(configRootPath + "/ruleCategories"),
          new File(configRootPath + "/rules"),
          new File(configRootPath + "/techniques")
        )
        l.filter(_.exists())
      }

      restTest.testGET(s"/api/latest/system/archives/full/zip/$commitId") { req =>
        contentArchiveDiff(req, matcher, ArchiveType.All)
      }
    }
  }

}
