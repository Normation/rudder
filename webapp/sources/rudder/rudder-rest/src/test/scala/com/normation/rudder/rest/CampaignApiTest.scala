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

import better.files.File
import com.normation.JsonSpecMatcher
import com.normation.rudder.campaigns.CampaignEvent
import com.normation.rudder.campaigns.CampaignEventState.*
import com.normation.rudder.campaigns.MainCampaignService
import com.normation.rudder.rest.RudderJsonResponse.JsonRudderApiResponse
import com.normation.rudder.rest.RudderJsonResponse.LiftJsonResponse
import com.normation.utils.DateFormaterService
import com.normation.zio.*
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.AfterAll
import scala.annotation.nowarn
import zio.json.*
import zio.json.ast.Json
import zio.json.ast.JsonCursor

@nowarn("msg=a type was inferred to be `\\w+`; this may indicate a programming error.")
@RunWith(classOf[JUnitRunner])
class CampaignApiTest extends Specification with AfterAll with Loggable with JsonSpecMatcher {

  val tz = DateTimeZone.getDefault().getID()

  val restTestSetUp = RestTestSetUp.newEnv
  ZioRuntime.unsafeRun(MainCampaignService.start(restTestSetUp.mockCampaign.mainCampaignService))
  val restTest      = new RestTest(restTestSetUp.liftRules)

  val testDir: File = File(s"/tmp/test-rudder-campaign-${DateFormaterService.serialize(DateTime.now())}")
  testDir.createDirectoryIfNotExists(true)

  override def afterAll(): Unit = {
    if (System.getProperty("tests.clean.tmp") != "false") {
      logger.info("Cleanup rest env ")
      restTestSetUp.cleanup()
      logger.info("Deleting directory " + testDir.pathAsString)
      FileUtils.deleteDirectory(testDir.toJava)
    }
  }

  def children(f: File): List[String] = f.children.toList.map(_.name)

  val tzCursor = {
    JsonCursor.isObject >>>
    JsonCursor.field("info") >>>
    JsonCursor.isObject >>>
    JsonCursor.field("schedule") >>>
    JsonCursor.isObject
  }

  // start service now, it takes sometime and is async, so we need to start it early to avoid flakiness

//  org.slf4j.LoggerFactory.getLogger("campaign").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)

  val c0json: String = {
    """{
      |"campaignType":"dumb-campaign",
      |"info":{
      |"id":"c0",
      |"name":"first campaign",
      |"description":"a test campaign present when rudder boot",
      |"status":{"value":"enabled"},
      |"schedule":{"type":"weekly","start":{"day":1,"hour":3,"minute":42},"end":{"day":1,"hour":4,"minute":42}}
      |},
      |"details":{"name":"campaign #0"},
      |"version":1
      |}""".stripMargin.replaceAll("""\n""", "")
  }

  // init in mock
  val ce0 = restTestSetUp.mockCampaign.e0

  sequential

  "when rudder starts, we" should {
    "have one campaign" in {
      val resp = s"""[$c0json]"""

      restTest.testGETResponse("/secure/api/campaigns") {
        case Full(LiftJsonResponse(JsonRudderApiResponse(_, _, _, Some(map), _), _, _)) =>
          map.asInstanceOf[Map[String, List[Json]]]("campaigns").toJson must equalsJson(resp)
        case err                                                                        =>
          ko(s"I got an error in test: ${err}")
      }
    }

    "get one campaign with same schedule timezone when server timezone changes" in {
      // MIGRATION to java-time WARNING !
      // This may be specific to Joda, if java-time is implemented, the change in server timezone should be a different operation
      DateTimeZone.setDefault(DateTimeZone.forID("Antarctica/South_Pole"))

      val resp = s"""[$c0json]"""

      restTest.testGETResponse("/secure/api/campaigns") {
        case Full(LiftJsonResponse(JsonRudderApiResponse(_, _, _, Some(map), _), _, _)) =>
          (map.asInstanceOf[Map[String, List[Json]]]("campaigns").toJson must equalsJsonSemantic(resp)) and {
            DateTimeZone.setDefault(DateTimeZone.forID(tz))
            DateTimeZone.getDefault().getID() must beEqualTo(tz)
          }
        case err                                                                        =>
          ko(s"I got an error in test: ${err}")
      }
    }

    "have two campaign events, one finished and one scheduled" in {

      restTest.testGETResponse("/secure/api/campaigns/events") {
        case Full(LiftJsonResponse(JsonRudderApiResponse(_, _, _, Some(map), _), _, _)) =>
          val events = map.asInstanceOf[Map[String, List[CampaignEvent]]]("campaignEvents")
          val ce0res = events.find(_.id == ce0.id)
          // ce0 exists
          (ce0res must beEqualTo(Some(ce0))) and
          // scheduled event
          (events.size must beEqualTo(2)) and {
            val next = events.collectFirst { case x if x.id != ce0.id => x }
              .getOrElse(throw new IllegalArgumentException(s"Missing test value"))
            // it's in the future
            (next.start.getMillis must be_>(System.currentTimeMillis())) and
            (next.state must beEqualTo(Scheduled)) and
            (next.campaignId must beEqualTo(ce0.campaignId))
          }

        case err => ko(s"I got an error in test: ${err}")
      }
    }

    val c1json = {
      s"""{"info":{
         |"id":"c1",
         |"name":"second campaign",
         |"description":"a test campaign present when rudder boot",
         |"status":{"value":"enabled"},
         |"schedule":{"start":{"day":1,"hour":3,"minute":42},"end":{"day":1,"hour":4,"minute":42},"tz":"${tz}","type":"weekly"}
         |},
         |"details":{"name":"campaign #0"},
         |"campaignType":"dumb-campaign",
         |"version":1
         |}""".stripMargin.replaceAll("""\n""", "")
    }

    "save one campaign" in {

      val resp = s"""[$c1json]"""

      restTest.testPOSTResponse("/secure/api/campaigns", net.liftweb.json.parse(c1json)) {
        case Full(LiftJsonResponse(JsonRudderApiResponse(_, _, _, Some(map), _), _, _)) =>
          map.asInstanceOf[Map[String, List[Json]]]("campaigns").toJson must equalsJsonSemantic(resp)
        case err                                                                        =>
          ko(s"I got an error in test: ${err}")
      }
    }

    "get the two existing campaigns" in {
      val resp = s"[$c0json,$c1json]"
      restTest.testGETResponse("/secure/api/campaigns") {
        case Full(LiftJsonResponse(JsonRudderApiResponse(_, _, _, Some(map), _), _, _)) =>
          map.asInstanceOf[Map[String, List[Json]]]("campaigns").toJson must equalsJsonSemantic(resp)
        case err                                                                        =>
          ko(s"I got an error in test: ${err}")
      }
    }

    val c2json                     = {
      s"""{"info":{
         |"id":"c2",
         |"name":"third campaign",
         |"description":"a test campaign without explicit timezone",
         |"status":{"value":"enabled"},
         |"schedule":{"start":{"hour":1,"minute":23},"end":{"hour":3,"minute":21},"type":"daily"}
         |},
         |"details":{"name":"campaign #2"},
         |"campaignType":"dumb-campaign",
         |"version":1
         |}""".stripMargin.replaceAll("""\n""", "")
    }
    def c2jsonTz(timeZone: String) = {
      c2json
        .fromJson[Json.Obj]
        .flatMap(_.transformAt(tzCursor)(_.add("tz", Json.Str(timeZone))))
        .fold(
          err => throw new RuntimeException(s"c2jsonTz setup is failing in tests: ${err}"),
          _.toJson
        )
    }

    "save campaign without schedule timezone" in {
      val resp = s"[${c2jsonTz(tz)}]"

      restTest.testPOSTResponse("/secure/api/campaigns", net.liftweb.json.parse(c2json)) {
        case Full(LiftJsonResponse(JsonRudderApiResponse(_, _, _, Some(map), _), _, _)) =>
          map.asInstanceOf[Map[String, List[Json]]]("campaigns").toJson must equalsJsonSemantic(resp)
        case err                                                                        =>
          ko(s"I got an error in test: ${err}")
      }
    }

    "save campaign with specific schedule timezone" in {
      val jsonWithTz = c2jsonTz("Antarctica/South_Pole")
      val resp       = s"[${jsonWithTz}]"

      restTest.testPOSTResponse("/secure/api/campaigns", net.liftweb.json.parse(jsonWithTz)) {
        case Full(LiftJsonResponse(JsonRudderApiResponse(_, _, _, Some(map), _), _, _)) =>
          map.asInstanceOf[Map[String, List[Json]]]("campaigns").toJson must equalsJsonSemantic(resp)
        case err                                                                        =>
          ko(s"I got an error in test: ${err}")
      }
    }

    "refuse to save a campaign with offset timezone" in {
      val jsonWithTz = c2jsonTz("+01:00")

      restTest.testPOSTResponse("/secure/api/campaigns", net.liftweb.json.parse(jsonWithTz)) {
        case Full(LiftJsonResponse(JsonRudderApiResponse(_, _, _, _, Some(err)), _, _)) =>
          err must contain("Error parsing schedule time zone, unknown IANA ID : '+01:00'")
        case s                                                                          =>
          ko(s"I got a success error in test but should be error : ${s}")
      }
    }
  }
}
