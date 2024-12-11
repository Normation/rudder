/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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
import com.normation.rudder.rest.internal.SharedFilesAPI
import net.liftweb.common.Full
import net.liftweb.http.JsonResponse
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*

@RunWith(classOf[JUnitRunner])
class SharedFilesApiTest extends Specification with JsonSpecMatcher {

  val restTestSetUp = RestTestSetUp.newEnv
  val restTest      = new RestTest(restTestSetUp.liftRules)

  "Testing resourceExplorer" >> {
    "uploading file" in {
      restTest.testBinaryPOSTResponse(
        "/secure/api/resourceExplorer/draft/09533e85-37ae-431e-b9bb-87666aeecf6b/1.0",
        "file",
        "test-file.txt",
        "test upload shared file content".getBytes("UTF-8"),
        Map("destination" -> "/")
      ) {
        // This is still the old Lift Json response, migrate to JsonRudderApiResponse after migrating SharedFilesAPI to zio-json
        case Full(jsonResponse: JsonResponse) =>
          jsonResponse.code must beEqualTo(200)
          jsonResponse.json.toJsCmd must equalsJsonSemantic("""
                                                              |{
                                                              |  "success":true,
                                                              |  "error":null
                                                              |}
                                                              |""".stripMargin)

        case err => ko(s"I got an error in test: ${err}")
      }
    }

    "uploading file which is too big" in {
      val fileContent: Array[Byte] = new Array(SharedFilesAPI.MAX_FILE_SIZE + 1)
      // Fill with random bytes
      // scala.util.Random.nextBytes(fileContent)

      restTest.testBinaryPOSTResponse(
        "/secure/api/resourceExplorer/draft/09533e85-37ae-431e-b9bb-87666aeecf6b/1.0",
        "file",
        "test-file.txt",
        fileContent,
        Map("destination" -> "/")
      ) {
        // This is still the old Lift Json response, migrate to JsonRudderApiResponse after migrating SharedFilesAPI to zio-json
        case Full(jsonResponse: JsonResponse) =>
          jsonResponse.code must beEqualTo(413)
          val maxSize = restTestSetUp.liftRules.maxMimeSize / 1024 / 1024
          jsonResponse.json.toJsCmd must equalsJsonSemantic(s"""
                                                               |{
                                                               |  "success":false,
                                                               |  "error":"File exceeds the maximum upload size of ${maxSize}MB"
                                                               |}
                                                               |""".stripMargin)

        case err => ko(s"I got an error in test: ${err}")
      }
    }
  }

}
