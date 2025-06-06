/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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

import com.normation.JsonSpecMatcher
import com.normation.errors.IOResult
import com.normation.errors.RudderError
import com.normation.plugins.*
import com.normation.plugins.settings.*
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.rest.data.JsonGlobalPluginLimits
import com.normation.rudder.rest.data.JsonPluginDetails
import com.normation.rudder.rest.data.JsonPluginInstallStatus
import com.normation.rudder.rest.data.JsonPluginLicense
import com.normation.rudder.rest.data.JsonPluginsDetails
import com.normation.rudder.rest.lift.LiftApiModuleProvider
import com.normation.rudder.rest.lift.PluginApi
import com.normation.zio.UnsafeRun
import java.time.ZonedDateTime
import net.liftweb.common.Full
import net.liftweb.http.InMemoryResponse
import net.liftweb.mocks.MockHttpServletRequest
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.JUnitRunner
import zio.Chunk
import zio.NonEmptyChunk
import zio.Ref
import zio.syntax.*

// test that the "+" in path is correctly kept as a "+", not changed into " "
// See: https://issues.rudder.io/issues/20943

@RunWith(classOf[JUnitRunner])
class TestRestPluginInfo extends Specification with JsonSpecMatcher {

  // we are testing error cases, so we don't want to output error log for them
  org.slf4j.LoggerFactory
    .getLogger("com.normation.rudder.rest.RestUtils")
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.OFF)

  /*
   * we have three plugins:
   * - freePlugin doesn't have any limit,
   * - limitedPlugin has limits and is enabled
   * - disabledPlugin has limits and is disabled
   */
  val pluginInfo = JsonPluginsDetails(
    Some(
      JsonGlobalPluginLimits(
        Some(NonEmptyChunk("Rudder corporation ltd")),
        Some(ZonedDateTime.parse("2024-03-03T00:00:00Z")),
        Some(ZonedDateTime.parse("2024-06-30T12:00:00Z")),
        Some(50)
      )
    ),
    List(
      JsonPluginDetails(
        "freePluginId",
        "rudder-plugin-free-plugin",
        "free-plugin",
        "A description for the free plugin",
        "2.3.0",
        JsonPluginInstallStatus.Enabled,
        None,
        None
      ),
      JsonPluginDetails(
        "LimitedPluginId",
        "rudder-plugin-limited-plugin",
        "limited-plugin",
        "A description for the limited plugin",
        "4.4.0",
        JsonPluginInstallStatus.Enabled,
        None,
        Some(
          JsonPluginLicense(
            "Rudder corporation ltd",
            "LimitedPluginId",
            "0.0-0.0",
            "99.99-99.99",
            ZonedDateTime.parse("2024-01-01T00:00:00Z"),
            ZonedDateTime.parse("2024-12-31T23:59:59Z"),
            Some(1000),
            Map()
          )
        )
      ),
      JsonPluginDetails(
        "DisabledPluginId",
        "rudder-plugin-disabled-plugin",
        "disabled-plugin",
        "A description for the disabled plugin",
        "1.3.5",
        JsonPluginInstallStatus.Disabled,
        Some(
          "This license for 'rudder-plugin-disabled-plugin' is disabled to '50' nodes but Rudder currently manages '132' nodes."
        ),
        Some(
          JsonPluginLicense(
            "Rudder corporation ltd",
            "DisabledPluginId",
            "0.0-0.0",
            "99.99-99.99",
            ZonedDateTime.parse("2024-03-03T00:00:00Z"),
            ZonedDateTime.parse("2024-06-30T12:00:00Z"),
            Some(50),
            Map()
          )
        )
      )
    )
  )

  val makePluginSettingsService = (ref: Ref[PluginSettings]) => {
    new PluginSettingsService {
      override def readPluginSettings(): IOResult[PluginSettings] = ref.get
      override def writePluginSettings(settings: PluginSettings): IOResult[Unit] = ref.set(settings)

      override def checkIsSetup(): IOResult[Boolean] = ???
    }
  }

  // return (service, test function to check if the ref is updated)
  def makePluginService(ref: Ref[Boolean]): (PluginService, () => Boolean) = {
    (
      new PluginService {
        override def updateIndex(): IOResult[Option[RudderError]] = ref.set(true).as(None)

        override def list(): IOResult[Chunk[Plugin]] = ???
        override def install(plugins:     Chunk[PluginId]): IOResult[Unit] = ???
        override def remove(plugins:      Chunk[PluginId]): IOResult[Unit] = ???
        override def updateStatus(status: PluginInstallStatus, plugins: Chunk[PluginId]): IOResult[Unit] = ???

      },
      () => ref.get.runNow
    )
  }
  val (pluginSettingsService, (pluginService, isUpdated)) = {
    (Ref.make[PluginSettings](PluginSettings.empty).map(makePluginSettingsService) <*> Ref
      .make[Boolean](false)
      .map(makePluginService)).runNow
  }

  val pluginApi = new PluginApi(pluginSettingsService, pluginService, pluginInfo.succeed)
  val apiModules: List[LiftApiModuleProvider[? <: EndpointSchema with SortIndex]] = List(pluginApi)

  val (handlers, rules) = TraitTestApiFromYamlFiles.buildLiftRules(apiModules, List(ApiVersion(42, deprecated = false)), None)
  val test              = new RestTest(rules)

  sequential

  ///// tests ////

  "Getting plugin info should" >> {
    val mockReq = new MockHttpServletRequest("http://localhost:8080")
    mockReq.method = "GET"
    mockReq.path = "/api/latest/plugins/info"
    mockReq.body = ""
    mockReq.headers = Map()
    mockReq.contentType = "application/json"

    // authorize space in response formatting
    val expected = {
      """{"action":"getPluginsInfo","result":"success","data":{"plugins":[{
        |"globalLimits":
        |{"licensees":["Rudder corporation ltd"],
        |"startDate":"2024-03-03T00:00:00Z",
        |"endDate":"2024-06-30T12:00:00Z",
        |"maxNodes":50
        |},
        |"details":[
        |{
        |"id":"freePluginId",
        |"name":"rudder-plugin-free-plugin",
        |"shortName":"free-plugin",
        |"description":"A description for the free plugin",
        |"version":"2.3.0",
        |"status":"enabled"
        |},{
        |"id":"LimitedPluginId",
        |"name":"rudder-plugin-limited-plugin",
        |"shortName":"limited-plugin",
        |"description":"A description for the limited plugin",
        |"version":"4.4.0",
        |"status":"enabled",
        |"license":{
        |"licensee":"Rudder corporation ltd",
        |"softwareId":"LimitedPluginId",
        |"minVersion":"0.0-0.0",
        |"maxVersion":"99.99-99.99",
        |"startDate":"2024-01-01T00:00:00Z",
        |"endDate":"2024-12-31T23:59:59Z",
        |"maxNodes":1000,
        |"additionalInfo":{}
        |}
        |},{
        |"id":"DisabledPluginId",
        |"name":"rudder-plugin-disabled-plugin",
        |"shortName":"disabled-plugin",
        |"description":"A description for the disabled plugin",
        |"version":"1.3.5",
        |"status":"disabled",
        |"statusMessage":"This license for 'rudder-plugin-disabled-plugin' is disabled to '50' nodes but Rudder currently manages '132' nodes.",
        |"license":{
        |"licensee":"Rudder corporation ltd",
        |"softwareId":"DisabledPluginId",
        |"minVersion":"0.0-0.0",
        |"maxVersion":"99.99-99.99",
        |"startDate":"2024-03-03T00:00:00Z",
        |"endDate":"2024-06-30T12:00:00Z",
        |"maxNodes":50,
        |"additionalInfo":{}
        |}
        |}
        |]
        |}]
        |}}""".stripMargin.replaceAll("""\n""", "")
    }

    test.execRequestResponse(mockReq)(response => {
      response.map { r =>
        val rr = r.toResponse.asInstanceOf[InMemoryResponse]
        (rr.code, new String(rr.data, "UTF-8"))
      } must beEqualTo(Full((200, expected)))
    })

  }

  "Updating plugin settings should" >> {
    val mockReq = new MockHttpServletRequest("http://localhost:8080")
    mockReq.method = "POST"
    mockReq.path = "/api/latest/plugins/settings"
    mockReq.body = """{
      "url": "http://localhost:8888",
      "username": "testuser",
      "password": "testpassword",
      "proxyUrl": "http://localhost:8888",
      "proxyUser": "testuser",
      "proxyPassword": "testpassword"
    }"""
    mockReq.headers = Map()
    mockReq.contentType = "application/json"

    val expected =
      """{"action":"updatePluginsSettings","result":"success","data":{"url":"http://localhost:8888","username":"testuser","proxyUrl":"http://localhost:8888","proxyUser":"testuser"}}"""

    test.execRequestResponse(mockReq)(response => {
      response.map { r =>
        val rr = r.toResponse.asInstanceOf[InMemoryResponse]
        (rr.code, new String(rr.data, "UTF-8"))
      } match {
        case Full((200, json)) => {
          json must equalsJsonSemantic(expected)
          // then the info should have been updated, and the index too
          isUpdated() must beTrue
        }
        case e                 => ko(s"Not the expected response : ${e}")
      }
    })

  }
}
