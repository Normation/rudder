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

import com.normation.rudder.domain.appconfig.FeatureSwitch

import better.files.File
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.http.InMemoryResponse
import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.AfterAll

import java.nio.charset.StandardCharsets
import com.normation.zio._

@RunWith(classOf[JUnitRunner])
class ArchiveApiTests extends Specification with AfterAll with Loggable {

  val restTestSetUp = RestTestSetUp.newEnv
  val restTest = new RestTest(restTestSetUp.liftRules)

  val testDir = File("/tmp/response-content")
  testDir.createDirectoryIfNotExists(true)

  override def afterAll(): Unit = {
    if (System.getProperty("tests.clean.tmp") != "false") {
      logger.info("Cleanup rest env ")
      restTestSetUp.cleanup()
      logger.info("Deleting directory " + testDir.pathAsString)
      FileUtils.deleteDirectory(testDir.toJava)
    }
  }

  sequential

  "when the feature switch is disabled, request" should {
    "error in GET /archive/export" in {
      restTest.testGETResponse("/api/archive/export") {
        case Full(InMemoryResponse(json, _, _, 500)) => new String(json, StandardCharsets.UTF_8) must beMatching(".*This API is disabled.*")
        case err                                     => ko(s"I got an error in test: ${err}")
      }
    }

    "error in POST /archive/export" in {
      restTest.testEmptyPostResponse("/api/archive/import") {
        case Full(InMemoryResponse(json, _, _, 500)) => new String(json, StandardCharsets.UTF_8) must beMatching(".*This API is disabled.*")
        case err                                     => ko(s"I got an error in test: ${err}")
      }
    }
  }

  "when the feature switch is enabled, request" should {


    "error in GET /archive/export" in {
      // feature switch change needs to be at that level and not under "should" directly,
      // else it contaminated all tests, even with the sequential annotation
      restTestSetUp.archiveAPIModule.featureSwitchState.set(FeatureSwitch.Enabled).runNow
      restTest.testGETResponse("/api/archive/export") {
        case Full(resp) => resp.toResponse.code must beEqualTo(200)
        case err        => ko(s"I got an error in test: ${err}")
      }
    }

    "error in POST /archive/export" in {
      restTest.testEmptyPostResponse("/api/archive/import") {
        case Full(resp) => resp.toResponse.code must beEqualTo(200)
        case err        => ko(s"I got an error in test: ${err}")
      }
    }
  }

}
