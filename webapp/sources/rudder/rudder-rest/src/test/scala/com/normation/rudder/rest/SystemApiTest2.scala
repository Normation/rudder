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

import com.normation.rudder.git.ZipUtils
import com.normation.rudder.rest.RestTestSetUp2.TestDir
import com.normation.rudder.rest.RestUtils.toJsonResponse
import com.normation.rudder.rest.ZioLiftAssertions.assertPlainResponse
import com.normation.rudder.rest.ZioLiftAssertions.assertPrettifiedJsonResponse
import com.normation.rudder.rest.v1.RestStatus
import java.io.File
import java.util.zip.ZipFile
import net.liftweb.common.*
import net.liftweb.http.InMemoryResponse
import net.liftweb.http.Req
import net.liftweb.json.JsonAST.*
import net.liftweb.json.JsonDSL.*
import net.liftweb.json.JValue
import org.apache.commons.io.FileUtils
import zio.Scope
import zio.ZIO
import zio.ZLayer
import zio.test.*
import zio.test.Assertion.*

object SystemApiTest2 extends ZIOSpecDefault with Loggable {

  val archive1: JObject = JObject(
    List(
      JField("id", "path"),
      JField("date", "1970-01-01T010000"),
      JField("committer", "test-user"),
      JField("gitCommit", "6d6b2ceb46adeecd845ad0c0812fee07e2727104")
    )
  )

  // To test with multiple archives, declare more archive in restTestSetup
  // and add their Json representation to the archives list below.
  val archives: List[JObject] = List(archive1)

  val archive2: JObject = JObject(
    List(
      JField("committer", "test-user"),
      JField("gitCommit", "6d6b2ceb46adeecd845ad0c0812fee07e2727104"),
      JField("id", "path")
    )
  )

  val dateTimeId = "1970-01-01_01-00-00.042"

  def apiTest(
      name: String
  )(req: RestTest2 => Req, expected: JValue, action: String): Spec[RestTestSetUp2 & RestTest2, Nothing] = {
    test(name) {
      val response = toJsonResponse(None, expected)(action = action, prettify = false)
      for {
        restTest <- ZIO.service[RestTest2]
        req_      = req(restTest)
        setup    <- ZIO.service[RestTestSetUp2]
        actual    = setup.rudderApi.getLiftRestApi().apply(req_).apply()
      } yield assert(actual)(assertPrettifiedJsonResponse(equalTo(response)))
    }
  }

  val spec: Spec[TestEnvironment & Scope, Throwable] = suite("SystemApiTest")(
    suite("testing status REST API")(
      test("be correct") {
        for {
          restTest <- ZIO.service[RestTest2]
          req       = restTest.testGET("/api/status")
          actual    = RestStatus(req)()
        } yield assert(actual)(assertPlainResponse(equalTo("""OK
                                                             |""".stripMargin)))
      }
    ),
    suite("Testing system API info")(
      apiTest("match the response defined below")(
        req = _.testGET("/api/latest/system/info"),
        expected = "rudder" -> (
          ("major-version"  -> "5.0")
          ~ ("full-version" -> "5.0.0")
          ~ ("build-time"   -> "some time")
        ),
        action = "getSystemInfo"
      )
    ),
    suite("Testing system API status")(
      apiTest("match the response defined below")(
        req = _.testGET("/api/latest/system/status"),
        expected = "global" -> "OK",
        action = "getStatus"
      )
    ),
    suite("Testing technique reload of System Api")(
      apiTest("match the response defined below")(
        req = _.testEmptyPost("/api/latest/system/reload/techniques"),
        expected = "techniques" -> "Started",
        action = "reloadTechniques"
      )
    ),
    suite("Testing dynamic groups reload on System Api")(
      apiTest("match the response defined below")(
        req = _.testEmptyPost("/api/latest/system/reload/groups"),
        expected = "groups" -> "Started",
        action = "reloadGroups"
      )
    ),
    suite("Testing dynamic groups and techniques reload of System Api")(
      apiTest("match the response defined below")(
        req = _.testEmptyPost("/api/latest/system/reload"),
        expected = List(JField("groups", "Started"), JField("techniques", "Started")),
        action = "reloadAll"
      )
    ),
    suite("Testing policies update of System Api")(
      apiTest("match the response defined below")(
        req = _.testEmptyPost("/api/latest/system/update/policies"),
        expected = "policies" -> "Started",
        action = "updatePolicies"
      )
    ),
    suite("Testing policies regenerate of System Api")(
      apiTest("match the response defined below")(
        req = _.testEmptyPost("/api/latest/system/regenerate/policies"),
        expected = "policies" -> "Started",
        action = "regeneratePolicies"
      )
    ),
    suite("Testing archive groups listing of System Api")(
      apiTest("match the response defined below")(
        req = _.testGET("/api/latest/system/archives/groups"),
        expected = "groups" -> JArray(archives),
        action = "listGroupsArchive"
      )
    ),
    suite("Testing archive directives of System Api")(
      apiTest("match the response defined below")(
        req = _.testGET("/api/latest/system/archives/directives"),
        expected = "directives" -> JArray(archives),
        action = "listDirectivesArchive"
      )
    ),
    suite("Testing archive rules listing of System Api")(
      apiTest("match the response defined below")(
        req = _.testGET("/api/latest/system/archives/rules"),
        expected = "rules" -> JArray(archives),
        action = "listRulesArchive"
      )
    ),
    suite("Testing full archive listing of System Api")(
      apiTest("match the response defined below")(
        req = _.testGET("/api/latest/system/archives/full"),
        expected = "full" -> JArray(archives),
        action = "listFullArchive"
      )
    ),
    suite("Testing restore groups latest archive of System Api")(
      apiTest("match the response defined below")(
        req = _.testEmptyPost("/api/latest/system/archives/groups/restore/latestArchive"),
        expected = "groups" -> "Started",
        action = "restoreGroupsLatestArchive"
      )
    ),
    suite("Testing restore directives latest archive of System Api")(
      apiTest("match the response defined below")(
        req = _.testEmptyPost("/api/latest/system/archives/directives/restore/latestArchive"),
        expected = "directives" -> "Started",
        action = "restoreDirectivesLatestArchive"
      )
    ),
    suite("Testing restore rules latest archive of System Api")(
      apiTest("match the response defined below")(
        req = _.testEmptyPost("/api/latest/system/archives/rules/restore/latestArchive"),
        expected = "rules" -> "Started",
        action = "restoreRulesLatestArchive"
      )
    ),
    suite("Testing restore full latest archive of System Api")(
      apiTest("match the response defined below")(
        req = _.testEmptyPost("/api/latest/system/archives/full/restore/latestArchive"),
        expected = "full" -> "Started",
        action = "restoreFullLatestArchive"
      )
    ),
    suite("Testing restore groups latest commit archive of System Api")(
      apiTest("match the response defined below")(
        req = _.testEmptyPost("/api/latest/system/archives/groups/restore/latestCommit"),
        expected = "groups" -> "Started",
        action = "restoreGroupsLatestCommit"
      )
    ),
    suite("Testing restore directives latest commit archive of System Api")(
      apiTest("match the response defined below")(
        req = _.testEmptyPost("/api/latest/system/archives/directives/restore/latestCommit"),
        expected = "directives" -> "Started",
        action = "restoreDirectivesLatestCommit"
      )
    ),
    suite("Testing restore rules latest commit archive of System Api")(
      apiTest("match the response defined below")(
        req = _.testEmptyPost("/api/latest/system/archives/rules/restore/latestCommit"),
        expected = "rules" -> "Started",
        action = "restoreRulesLatestCommit"
      )
    ),
    suite("Testing restore full latest commit archive of System Api")(
      apiTest("match the response defined below")(
        req = _.testEmptyPost("/api/latest/system/archives/full/restore/latestCommit"),
        expected = "full" -> "Started",
        action = "restoreFullLatestCommit"
      )
    ),
    suite("Testing archive groups of System Api")(
      apiTest("match the response defined below")(
        req = _.testEmptyPost("/api/latest/system/archives/groups"),
        expected = "groups" -> archive2,
        action = "archiveGroups"
      )
    ),
    suite("Testing archive directives of System Api")(
      apiTest("match the response defined below")(
        req = _.testEmptyPost("/api/latest/system/archives/directives"),
        expected = "directives" -> archive2,
        action = "archiveDirectives"
      )
    ),
    suite("Testing archive rules of System Api")(
      apiTest("match the response defined below")(
        req = _.testEmptyPost("/api/latest/system/archives/rules"),
        expected = "rules" -> archive2,
        action = "archiveRules"
      )
    ),
    suite("Testing all archive of System Api")(
      apiTest("match the response defined below")(
        req = _.testEmptyPost("/api/latest/system/archives/full"),
        expected = "full" -> archive2,
        action = "archiveAll"
      )
    ),
    suite("Testing group archive restore based on a date time")(
      apiTest("match the response defined below")(
        req = _.testEmptyPost(s"/api/latest/system/archives/groups/restore/$dateTimeId"),
        expected = "group" -> "Started",
        action = "archiveGroupDateRestore"
      )
    ),
    suite("Testing directive archive restore based on its date time")(
      apiTest("match the response defined below")(
        req = _.testEmptyPost(s"/api/latest/system/archives/directives/restore/$dateTimeId"),
        expected = "directive" -> "Started",
        action = "archiveDirectiveDateRestore"
      )
    ),
    suite("Testing rule archive restore based on its date time")(
      apiTest("match the response defined below")(
        req = _.testEmptyPost(s"/api/latest/system/archives/rules/restore/$dateTimeId"),
        expected = "rule" -> "Started",
        action = "archiveRuleDateRestore"
      )
    ),
    suite("Testing full archive restore based on its datetime")(
      apiTest("match the response defined below")(
        req = _.testEmptyPost(s"/api/latest/system/archives/full/restore/$dateTimeId"),
        expected = "full" -> "Started",
        action = "archiveFullDateRestore"
      )
    ),
    /*
     *  From here is the code needed to test the zip api endpoints. It is a 4 step process :
     *  1. Create a clone of /var/rudder/configuration-repository in the /tmp folder
     *  2. Call the appropriate endpoint and get the archive in /tmp/resource-content
     *  3. Unzip the archive
     *  4. Compare the appropriate data
     */
    {
      def zipTest(name: String)(req: String => RestTest2 => Req, expected: String => List[java.io.File]) = test(name) {
        for {
          setup    <- ZIO.service[RestTestSetUp2]
          restTest <- ZIO.service[RestTest2]
          refCommit: String = setup.mockGitRepo.gitRepo.db.findRef("master").toString
          // I found no other way to get the commitId from a JGit ref object than parse its String representation
          commitId:  String = refCommit.slice(refCommit.indexOf('=') + 1, refCommit.indexOf(']'))
          req_           = req(commitId)(restTest)
          bytes          = setup.rudderApi.getLiftRestApi().apply(req_).apply().asInstanceOf[Full[InMemoryResponse]].value.data
          _             <-
            ZIO.attemptBlockingIO(FileUtils.writeByteArrayToFile(new File("/tmp/response-content/response-archive.zip"), bytes))
          zip            = new ZipFile("/tmp/response-content/response-archive.zip")
          testDir       <- ZIO.service[TestDir]
          _             <- ZIO.attemptBlockingIO(ZipUtils.unzip(zip, testDir.dir.toJava))
          files         <- ZIO.attemptBlockingIO(testDir.dir.list.map(_.toJava).toList)
          configRootPath = setup.mockGitRepo.configurationRepositoryRoot.pathAsString
        } yield assert(files)(
          hasSameElements(expected(configRootPath))
        )

      }

      suite("zip api endpoint")(
        suite("Getting directives zip archive from System Api")(
          zipTest("satisfy the matcher defined below")(
            req = commitId => _.testGET(s"/api/latest/system/archives/directives/zip/$commitId"),
            expected = configRootPath => {
              List(
                new File(configRootPath + "/directives"),
                new File(configRootPath + "/parameters"),
                new File(configRootPath + "/techniques"),
                new File(configRootPath + "/ncf")
              )
            }
          )
        ),
        suite("Getting groups zip archive from System Api")(
          zipTest("satisfy the matcher defined below")(
            req = commitId => _.testGET(s"/api/latest/system/archives/groups/zip/$commitId"),
            expected = configRootPath => List(new File(configRootPath + "/groups"))
          )
        ),
        suite("Getting rules zip archive from System Api")(
          zipTest("satisfy the matcher defined below")(
            req = commitId => _.testGET(s"/api/latest/system/archives/groups/zip/$commitId"),
            expected = configRootPath => List(new File(configRootPath + "/rules"), new File(configRootPath + "/ruleCategories"))
          )
        ),
        suite("Getting full zip archive from System Api")(
          zipTest("satisfy the matcher defined below")(
            req = commitId => _.testGET(s"/api/latest/system/archives/full/zip/$commitId"),
            expected = configRootPath => {
              List(
                new File(configRootPath + "/directives"),
                new File(configRootPath + "/groups"),
                new File(configRootPath + "/parameters"),
                new File(configRootPath + "/ruleCategories"),
                new File(configRootPath + "/rules"),
                new File(configRootPath + "/techniques")
              )
            }
          )
        )
      )
    }
  ).provideSome[Scope](
    ZLayer.succeed(logger),
    RestTestSetUp2.layer,
    RestTest2.layer,
    RestTestSetUp2.testDir("/tmp/response-content")
  ) @@ TestAspect.sequential
}
