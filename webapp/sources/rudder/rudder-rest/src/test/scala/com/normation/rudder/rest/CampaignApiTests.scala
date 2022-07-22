/*
*************************************************************************************
* Copyright 2022 Normation SAS
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

import com.normation.rudder.DumbCampaign
import com.normation.rudder.campaigns.CampaignId
import com.normation.rudder.campaigns.MainCampaignService
import com.normation.rudder.rest.RudderJsonResponse.JsonRudderApiResponse
import com.normation.rudder.rest.RudderJsonResponse.LiftJsonResponse
import com.normation.utils.DateFormaterService

import better.files.File
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.AfterAll

@RunWith(classOf[JUnitRunner])
class CampaignApiTests extends Specification with AfterAll with Loggable {

  val restTestSetUp = RestTestSetUp.newEnv
  val restTest = new RestTest(restTestSetUp.liftRules)

  val testDir = File(s"/tmp/test-rudder-campaign-${DateFormaterService.serialize(DateTime.now())}")
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

 // org.slf4j.LoggerFactory.getLogger("api-processing").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)

  sequential

  "when rudder starts, we" should {
    val c0json =
        """{"info":{
            |"id":"c0",
            |"name":"first campaign",
            |"description":"a test campaign present when rudder boot",
            |"status":{"value":"enabled"},
            |"schedule":{"day":1,"startHour":3,"type":"weekly"},
            |"duration":3600000},
          |"details":{"name":"campaign #0"}
          |}""".stripMargin.replaceAll("""\n""","")

    "have one campaign" in {
      val resp = s"""[$c0json]"""

      restTest.testGETResponse("/secure/api/campaigns/models") {
        case Full(LiftJsonResponse(JsonRudderApiResponse(_, _, _, Some(map: Map[String, List[zio.json.ast.Json]]), _), _, _)) =>
          map("campaigns").toJson must beEqualTo(resp)
        case err => ko(s"I got an error in test: ${err}")
      }
    }

    // THIS TEST IS BROKEN FOR NOW

    "have a second one when we trigger a scheduling check" in {
      MainCampaignService.start(restTestSetUp.mockCampaign.mainCampaignService)

      // the second one won't be C0, but for now, it is not even generated
      val resp = s"""[$c0json,$c0json]"""

      restTest.testGETResponse("/secure/api/campaigns/models") {
        case Full(LiftJsonResponse(JsonRudderApiResponse(_, _, _, Some(map: Map[String, List[zio.json.ast.Json]]), _), _, _)) =>
          map("campaigns").toJson must beEqualTo(resp)
        case err => ko(s"I got an error in test: ${err}")
      }

    }
  }

}

