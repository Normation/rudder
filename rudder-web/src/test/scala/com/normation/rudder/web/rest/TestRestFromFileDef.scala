/*
*************************************************************************************
* Copyright 2016 Normation SAS
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

import org.junit._
import org.junit.Assert._
import org.junit.runner._
import org.junit.runner.RunWith
import org.specs2.matcher.MatchResult
import org.specs2.mutable._
import org.specs2.runner._

import net.liftweb.common.Full
import net.liftweb.http.LiftRules
import net.liftweb.http.LiftRulesMocker
import net.liftweb.http.PlainTextResponse
import net.liftweb.http.Req
import net.liftweb.mocks.MockHttpServletRequest
import net.liftweb.mockweb.MockWeb
import net.liftweb.util.Helpers.tryo

import RestTestSetUp._
import org.apache.commons.io.IOUtils
import org.apache.commons.io.Charsets
import org.yaml.snakeyaml.Yaml
import scala.collection.JavaConverters._
import net.liftweb.common.EmptyBox
import net.liftweb.common.Loggable
import net.liftweb.common.Failure
import net.liftweb.common.Box
import net.liftweb.util.NamedPF
import org.specs2.execute.Result
import org.specs2.specification.core.Fragment
import org.specs2.specification.core.Fragments

/*
 * Utily data structures
 */
sealed trait ResponseType
final object ResponseType {
  final object Text extends ResponseType
  final object Json extends ResponseType
}

// Our object representing a test specification
final case class TestRequest(
    description    : String
  , method         : String
  , url            : String
  , responseType   : ResponseType
  , responseCode   : Int
  , responseContent: String
)

@RunWith(classOf[JUnitRunner])
class TestRestFromFileDef extends Specification with Loggable {

  //read all file in src/test/resources/api.
  //each file is a new test

  //get stream for files in src/main/resources/api. Empty name == the "." directory
  def filename(name: String) = "api/" + name
  def src_test_resources_api_(name: String) = this.getClass.getClassLoader.getResourceAsStream(filename(name))

  val files = IOUtils.readLines(src_test_resources_api_(""), Charsets.UTF_8).asScala

  /*
   * I *love* java. The snakeyaml parser returns a list of Objects. The may be null if empty, or
   * others things. We don't know what other things. So I believe we just hope and cast to
   * a Map[String, Object]. Which not much better. Expects other cast along the line.
   */
  def readSpecification(obj: Object): Box[TestRequest] = {
    import java.util.{Map => juMap}
    type YMap = juMap[String, Any]

    if(obj == null) Failure("The YAML document is empty")
    else tryo {
      val data = obj.asInstanceOf[YMap].asScala
      val response = data("response").asInstanceOf[YMap].asScala
      TestRequest(
          data.get("description").map( _.asInstanceOf[String] ).getOrElse("")
        , data("method").asInstanceOf[String]
        , data("url").asInstanceOf[String]
        , response.get("type").map( _.asInstanceOf[String] ).getOrElse("json") match {
            case "json" => ResponseType.Json
            case "text" => ResponseType.Text
            case x      => throw new IllegalArgumentException(s"Unrecognized response type: '${x}'. Use 'json' or 'text'")
          }
        , response("code").asInstanceOf[Int]
        , response("content").asInstanceOf[String]
      )
    }
  }

  ///// tests ////

  Fragments.foreach(files) { file =>

    s"For each test defined in ${file}" should {

      val objects = (new Yaml()).loadAll(src_test_resources_api_(file)).asScala

      Fragment.foreach(objects.map(readSpecification).zipWithIndex.toSeq) { x => x match {
        case (eb: EmptyBox, i) =>
          val f = eb ?~! s"I wasn't able to run the ${i}th tests in file ${filename(file)}"
          logger.error(f.messageChain)

          s"[${i}] failing test ${i}: can not read description" in {
            ko(s"The yaml description ${i} in ${file} cannot be read: ${f.messageChain}")
          }

        case (Full(test), i) =>

          s"[${i}] ${test.description}" in {
            val mockReq = new MockHttpServletRequest("http://localhost:8080")
            mockReq.method = test.method
            mockReq.path   = test.url

            RestTestSetUp.execRequestResponse(mockReq)(response =>
              response must beEqualTo(Full(PlainTextResponse(test.responseContent)))
            )
          }
      } }
    }
  }
}
