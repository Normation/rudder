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

import com.normation.rudder.campaigns.CampaignEvent
import com.normation.rudder.campaigns.CampaignEventState
import com.normation.rudder.campaigns.MainCampaignService
import com.normation.rudder.campaigns.Scheduled
import com.normation.rudder.rest.RestTestSetUp2.TestDir
import com.normation.rudder.rest.ZioLiftAssertions.assertJsonResponse
import com.normation.utils.DateFormaterService
import net.liftweb.common.Loggable
import org.joda.time.DateTime
import zio.Clock
import zio.Scope
import zio.ZIO
import zio.ZLayer
import zio.json.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

object CampaignApiTest extends ZIOSpecDefault with Loggable {

  val spec: Spec[TestEnvironment & Scope, Throwable] = (suite("CampaignApiTest")(
    suite("when rudder starts, we should")(
      test("have one campaign") {
        val expected = {
          s"""[{"info":{
             |"id":"c0",
             |"name":"first campaign",
             |"description":"a test campaign present when rudder boot",
             |"status":{"value":"enabled"},
             |"schedule":{"start":{"day":1,"hour":3,"minute":42},"end":{"day":1,"hour":4,"minute":42},"type":"weekly"}
             |},
             |"details":{"name":"campaign #0"},
             |"campaignType":"dumb-campaign",
             |"version":1
             |}]""".stripMargin.replaceAll("\n", "")
        }
        for {
          restTest <- ZIO.service[RestTest2]
          actual    = restTest.testGETResponse("/secure/api/campaigns")
        } yield assert(actual)(
          assertJsonResponse[Map[String, List[Json]]](hasKey("campaigns", hasField("json", _.toJson, equalTo(expected))))
        )
      },
      test("have two campaign events, one finished and one scheduled") {
        Live.live {
          for {
            ce0      <- ZIO.serviceWith[RestTestSetUp2](_.mockCampaign.e0)
            restTest <- ZIO.service[RestTest2]
            now      <- Clock.instant
            actual    = restTest.testGETResponse("/secure/api/campaigns/events")
          } yield assert(actual)(
            assertJsonResponse[Map[String, List[CampaignEvent]]](
              hasKey(
                "campaignEvents",
                hasSize[CampaignEvent](equalTo(2)) &&
                contains(ce0) &&
                hasField[List[CampaignEvent], Option[CampaignEvent]](
                  name = "next",
                  proj = _.collectFirst { case x if x.id != ce0.id => x },
                  assertion = isSome(
                    hasField[CampaignEvent, CampaignEventState]("state", _.state, equalTo(Scheduled)) &&
                    hasField[CampaignEvent, Long]("start", _.start.getMillis, isGreaterThan(now.toEpochMilli)) &&
                    hasField("campaignId", _.campaignId, equalTo(ce0.campaignId))
                  )
                )
              )
            )
          )

        }
      },
      test("save one campaign") {
        val c1json = {
          """{"info":{
            |"id":"c1",
            |"name":"second campaign",
            |"description":"a test campaign present when rudder boot",
            |"status":{"value":"enabled"},
            |"schedule":{"start":{"day":1,"hour":3,"minute":42},"end":{"day":1,"hour":4,"minute":42},"type":"weekly"}
            |},
            |"details":{"name":"campaign #0"},
            |"campaignType":"dumb-campaign",
            |"version":1
            |}""".stripMargin.replaceAll("""\n""", "")
        }

        val expected = s"""[$c1json]"""

        for {
          restTest <- ZIO.service[RestTest2]
          actual    = restTest.testPOSTResponse("/secure/api/campaigns", net.liftweb.json.parse(c1json))
        } yield assert(actual)(
          assertJsonResponse[Map[String, List[Json]]](hasKey("campaigns", hasField("json", _.toJson, equalTo(expected))))
        )
      }
    )
  ) @@ TestAspect.beforeAll(
    ZIO.attemptBlocking(
      org.slf4j.LoggerFactory
        .getLogger("campaign")
        .asInstanceOf[ch.qos.logback.classic.Logger]
        .setLevel(ch.qos.logback.classic.Level.TRACE)
    )
  ) @@ TestAspect.before(
    // start service now, it takes sometime and is async, so we need to start it early to avoid flakiness
    ZIO.serviceWithZIO[RestTestSetUp2](x => MainCampaignService.start(x.mockCampaign.mainCampaignService).forkScoped)
  ) @@ TestAspect.before(ZIO.serviceWithZIO[TestDir](_ => ZIO.unit)) // ensure testDir is created
  )
    .provideSome[Scope](
      ZLayer.succeed(logger),
      RestTestSetUp2.testDir(s"/tmp/test-rudder-campaign-${DateFormaterService.serialize(DateTime.now())}"),
      RestTestSetUp2.layer,
      RestTest2.layer
    ) @@ TestAspect.sequential

}
