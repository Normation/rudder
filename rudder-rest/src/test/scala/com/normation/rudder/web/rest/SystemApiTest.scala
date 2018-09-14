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

package com.normation.rudder.web.rest

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

import com.normation.rudder.rest.RestTestSetUp
import com.normation.rudder.rest.RestUtils.{toJsonResponse, toJsonError}
import net.liftweb.common.{Full, Loggable}
import net.liftweb.http.{InMemoryResponse, LiftResponse, Req}
import net.liftweb.json.JsonAST.{JArray, JField, JObject}
import net.liftweb.json.JsonDSL._
import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.AfterAll


@RunWith(classOf[JUnitRunner])
class SystemApiTests extends Specification with AfterAll with Loggable {

  "Testing system API status" should {
    "match the response defined below" in {

      implicit val action = "getStatus"
      implicit val prettify = false

      val response = toJsonResponse(None, "global" -> "OK")

      RestTestSetUp.testGET("/api/latest/system/status") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing technique reload of System Api" should {
    "match the response defined below" in {

      implicit val action = "reloadTechniques"
      implicit val prettify = false

      val response = toJsonResponse(None, "techniques" -> "Started")

      RestTestSetUp.testEmptyPost("/api/latest/system/action/techniques/reload") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing dynamic groups reload on System Api" should {
    "match the response defined below" in {

      implicit val action = "reloadDynGroups"
      implicit val prettify = false

      val response = toJsonResponse(None, "groups" -> "Started")

      RestTestSetUp.testEmptyPost("/api/latest/system/action/groups/reload") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing dynamic groups and techniques reload of System Api" should {
    "match the response defined below" in {

      implicit val action = "reloadAll"
      implicit val prettify = false


      val response = toJsonResponse(None, List(JField("groups", "Started"), JField("techniques", "Started")))

      RestTestSetUp.testEmptyPost("/api/latest/system/action/reload") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing policies update of System Api" should {
    "match the response defined below" in {

      implicit val action = "updatePolicies"
      implicit val prettify = false

      val response = toJsonResponse(None, "policies" -> "Started")

      RestTestSetUp.testEmptyPost("/api/latest/system/action/policies/update") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing policies regenerate of System Api" should {
    "match the response defined below" in {

      implicit val action = "regeneratePolicies"
      implicit val prettify = false

      val response = toJsonResponse(None, "policies" -> "Started")
      RestTestSetUp.testEmptyPost("/api/latest/system/action/policies/regenerate") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  // To test with multiple archives, declare more archive in RestTestSetup
  // and add their Json representation to the archives list below.


  val dateTimeId = "1970-01-01_01-00-00.042"

  val archive1 = JObject(List(
    JField("id", dateTimeId)
    , JField("date", "1970-01-01 at 01:00:00")
    , JField("commiter", "test-user")
    , JField("gitCommit", "6d6b2ceb46adeecd845ad0c0812fee07e2727104")
  ))

  val archives = List(archive1)

  "Testing archive groups listing of System Api" should {
    "match the response defined below" in {

      implicit val action = "listGroupsArchive"
      implicit val prettify = false

      val response = toJsonResponse(None, "groups" -> JArray(archives))

      RestTestSetUp.testGET("/api/latest/system/archives/groups/list") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing archive directives of System Api" should {
    "match the response defined below" in {

      implicit val action = "listDirectivesArchive"
      implicit val prettify = false

      val response = toJsonResponse(None, "directives" -> JArray(archives))

      RestTestSetUp.testGET("/api/latest/system/archives/directives/list") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing archive rules listing of System Api" should {
    "match the response defined below" in {

      implicit val action = "listRulesArchive"
      implicit val prettify = false

      val response = toJsonResponse(None, "rules" -> JArray(archives))

      RestTestSetUp.testGET("/api/latest/system/archives/rules/list") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing full archive listing of System Api" should {
    "match the response defined below" in {

      implicit val action = "listFullArchive"
      implicit val prettify = false

      val response = toJsonResponse(None, "full" -> JArray(archives))

      RestTestSetUp.testGET("/api/latest/system/archives/list") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing restore groups latest archive of System Api" should {
    "match the response defined below" in {

      implicit val action = "restoreGroupsLatestArchive"
      implicit val prettify = false

      val response = toJsonResponse(None, "groups" -> "Started")

      RestTestSetUp.testEmptyPost("/api/latest/system/archives/groups/latestArchive/restore") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing restore directives latest archive of System Api" should {
    "match the response defined below" in {

      implicit val action = "restoreDirectivesLatestArchive"
      implicit val prettify = false

      val response = toJsonResponse(None, "directives" -> "Started")

      RestTestSetUp.testEmptyPost("/api/latest/system/archives/directives/latestArchive/restore") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing restore rules latest archive of System Api" should {
    "match the response defined below" in {

      implicit val action = "restoreRulesLatestArchive"
      implicit val prettify = false

      val response = toJsonResponse(None, "rules" -> "Started")

      RestTestSetUp.testEmptyPost("/api/latest/system/archives/rules/latestArchive/restore") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing restore full latest archive of System Api" should {
    "match the response defined below" in {

      implicit val action = "restoreFullLatestArchive"
      implicit val prettify = false

      val response = toJsonResponse(None, "full" -> "Started")

      RestTestSetUp.testEmptyPost("/api/latest/system/archives/full/latestArchive/restore") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing restore groups latest commit archive of System Api" should {
    "match the response defined below" in {

      implicit val action = "restoreGroupsLatestCommit"
      implicit val prettify = false

      val response = toJsonResponse(None, "groups" -> "Started")

      RestTestSetUp.testEmptyPost("/api/latest/system/archives/groups/latestCommit/restore") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing restore directives latest commit archive of System Api" should {
    "match the response defined below" in {

      implicit val action = "restoreDirectivesLatestCommit"
      implicit val prettify = false

      val response = toJsonResponse(None, "directives" -> "Started")

      RestTestSetUp.testEmptyPost("/api/latest/system/archives/directives/latestCommit/restore") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing restore rules latest commit archive of System Api" should {
    "match the response defined below" in {

      implicit val action = "restoreRulesLatestCommit"
      implicit val prettify = false

      val response = toJsonResponse(None, "rules" -> "Started")

      RestTestSetUp.testEmptyPost("/api/latest/system/archives/rules/latestCommit/restore") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing restore full latest commit archive of System Api" should {
    "match the response defined below" in {

      implicit val action = "restoreFullLatestCommit"
      implicit val prettify = false

      val response = toJsonResponse(None, "full" -> "Started")

      RestTestSetUp.testEmptyPost("/api/latest/system/archives/full/latestCommit/restore") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing archive groups of System Api" should {
    "match the response defined below" in {

      implicit val action = "archiveGroups"
      implicit val prettify = false

      val response = toJsonResponse(None, "groups" -> "OK")

      RestTestSetUp.testEmptyPost("/api/latest/system/archives/groups/archive") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing archive directives of System Api" should {
    "match the response defined below" in {

      implicit val action = "archiveDirectives"
      implicit val prettify = false

      val response = toJsonResponse(None, "directives" -> "OK")

      RestTestSetUp.testEmptyPost("/api/latest/system/archives/directives/archive") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }


  "Testing archive rules of System Api" should {
    "match the response defined below" in {

      implicit val action = "archiveRules"
      implicit val prettify = false

      val response = toJsonResponse(None, "rules" -> "OK")

      RestTestSetUp.testEmptyPost("/api/latest/system/archives/rules/archive") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing all archive of System Api" should {
    "match the response defined below" in {

      implicit val action = "archiveAll"
      implicit val prettify = false

      val response = toJsonResponse(None, "full archive" -> "OK")

      RestTestSetUp.testEmptyPost("/api/latest/system/archives/full/archive") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing group archive restore based on a date time" should {
    "match the response defined below" in {

      implicit val action = "archiveGroupDateRestore"
      implicit val prettify = false

      val response = toJsonResponse(None, "group" -> "Started")

      RestTestSetUp.testEmptyPost(s"/api/latest/system/archives/group/restore/$dateTimeId") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing directive archive restore based on its date time" should {
    "match the response defined below" in {

      implicit val action = "archiveDirectiveDateRestore"
      implicit val prettify = false

      val response = toJsonResponse(None, "directive" -> "Started")

      RestTestSetUp.testEmptyPost(s"/api/latest/system/archives/directive/restore/$dateTimeId") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing rule archive restore based on its date time" should {
    "match the response defined below" in {

      implicit val action = "archiveRuleDateRestore"
      implicit val prettify = false

      val response = toJsonResponse(None, "rule" -> "Started")

      RestTestSetUp.testEmptyPost(s"/api/latest/system/archives/rule/restore/$dateTimeId") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
      }
    }
  }

  "Testing full archive restore based on its datetime" should {
    "match the response defined below" in {

      implicit val action = "archiveFullDateRestore"
      implicit val prettify = false

      val response = toJsonResponse(None, "full" -> "Started")

      RestTestSetUp.testEmptyPost(s"/api/latest/system/archives/full/restore/$dateTimeId") { req =>
        RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() must beEqualTo(Full(response))
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

  val refCommit = RestTestSetUp.fakeRepo.db.findRef("master").toString

  // I found no other way to get the commitId from a JGit ref object than parse its String representation
  val commitId = refCommit.slice(refCommit.indexOf('=') + 1, refCommit.indexOf(']'))

  // Init directory needed to temporary store archive data that zip API returns.
  // It will be cleared at the end of the test in "afterAll"

  val testDir = new File("/tmp/response-content")
  Files.createDirectory(testDir.toPath)

  val testRootPath = RestTestSetUp.testNodeConfiguration.abstractRoot.getAbsolutePath

  override def afterAll(): Unit = {
    if (System.getProperty("tests.clean.tmp") != "false") {
      logger.info("Deleting directory " + testRootPath)
      FileUtils.deleteDirectory(RestTestSetUp.testNodeConfiguration.abstractRoot)
      logger.info("Deleting directory " + "/tmp/response-content")
      FileUtils.deleteDirectory(testDir)
    }
  }

  private[this] def contentArchiveDiff(req: Req, matcher: List[File], action: String) = {
    import com.normation.rudder.repository.xml.ZipUtils

    RestTestSetUp.rudderApi.getLiftRestApi().apply(req).apply() match {

      case Full(resp: InMemoryResponse) =>
        FileUtils.writeByteArrayToFile(new File("/tmp/response-content/response-archive.zip"), resp.data)
        val zip = new ZipFile("/tmp/response-content/response-archive.zip")
        ZipUtils.unzip(zip, testDir)
        def filterGeneratedFile(f: File): Boolean = matcher.contains(f)
        testDir must org.specs2.matcher.ContentMatchers.haveSameFilesAs(RestTestSetUp.testNodeConfiguration.configurationRepositoryRoot)
          .withFilter(filterGeneratedFile _)
      case Full(resp: LiftResponse) => resp must beEqualTo(toJsonError(None, "Error when trying to get archive as a Zip")(action, false))
      case _ => ko
    }
  }

  val configRootPath = RestTestSetUp.testNodeConfiguration.configurationRepositoryRoot.getAbsolutePath

  "Getting directives zip archive from System Api" should {
    "satisfy the matcher defined below" in {

      val matcher = {
        val l = List(
          new File(configRootPath + "/directives")
          , new File(configRootPath + "/parameters")
          , new File(configRootPath + "/techniques")
          , new File(configRootPath + "/ncf")
        )
        l.filter(_.exists())
      }

      RestTestSetUp.testGET(s"/api/latest/system/archives/zip/directives/$commitId")
      { req => contentArchiveDiff(req, matcher, "getDirectivesZipArchive") }
    }
  }

  "Getting groups zip archive from System Api" should {
    "satisfy the matcher defined below" in {

      val matcher = List(new File(configRootPath + "/groups"))

      RestTestSetUp.testGET(s"/api/latest/system/archives/zip/groups/$commitId")
      { req => contentArchiveDiff(req, matcher, "getGroupsZipArchive") }
    }
  }

  "Getting rules zip archive from System Api" should {
    "satisfy the matcher defined below" in {

      val matcher = List(new File(configRootPath + "/rules"), new File(configRootPath + "/ruleCategories"))

      RestTestSetUp.testGET(s"/api/latest/system/archives/zip/rules/$commitId")
      { req => contentArchiveDiff(req, matcher, "getRulesZipArchive") }
    }
  }

  "Getting full zip archive from System Api" should {
    "satisfy the matcher defined below" in {

      val matcher = {
        val l =  List(
            new File(configRootPath + "/directives")
          , new File(configRootPath + "/groups")
          , new File(configRootPath + "/parameters")
          , new File(configRootPath + "/ruleCategories")
          , new File(configRootPath + "/rules")
          , new File(configRootPath + "/techniques")
        )
        l.filter(_.exists())
      }

      RestTestSetUp.testGET(s"/api/latest/system/archives/zip/full/$commitId")
      { req => contentArchiveDiff(req, matcher, "getAllZipArchive") }
    }
  }

}