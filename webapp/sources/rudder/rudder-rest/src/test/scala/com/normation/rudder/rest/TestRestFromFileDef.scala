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

package com.normation.rudder.rest

import scala.jdk.CollectionConverters._
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import org.specs2.specification.core.Fragment
import org.specs2.specification.core.Fragments
import org.yaml.snakeyaml.Yaml
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.http.InMemoryResponse
import net.liftweb.http.LiftResponse
import net.liftweb.mocks.MockHttpServletRequest
import net.liftweb.util.Helpers.tryo
import org.apache.commons.io.IOUtils

import java.nio.charset.StandardCharsets
import scala.util.control.NonFatal

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
  , queryBody      : String
  , headers        : List[String]
  , params         : List[(String, String)]
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

  // file to test, by default all under "src/main/resources/api", you can comment that line
  // an uncoment the following one to test only one file.
  val files = IOUtils.readLines(src_test_resources_api_(""), StandardCharsets.UTF_8).asScala.toList
//  val files = List("api_parameters.yml")


  // try to not make everyone loose hours on java NPE 
  implicit class SafeGet(data: scala.collection.mutable.Map[String, Any]) {
    def safe[A](key: String, default: Option[A], map: Any => A): A = {
      data.get(key) match {
        case null | None | Some(null) => default match {
          case Some(a) => a
          case None => throw new IllegalArgumentException(s"Missing mandatory key '${key}' in data: ${data}")
        }
        case Some(a) => try {
          map(a)
        } catch {
          case NonFatal(ex) =>throw new IllegalArgumentException(s"Error when mapping key '${key}' in data: ${data}:\n ${ex.getMessage}")
        }
      }
    }
  }

  /*
   * I *love* java. The snakeyaml parser returns a list of Objects. The may be null if empty, or
   * others things. We don't know what other things. So I believe we just hope and cast to
   * a Map[String, Object]. Which not much better. Expects other cast along the line.
   */
  def readSpecification(obj: Object): Box[TestRequest] = {
    import java.util.{ Map => JUMap }
    import java.util.{ArrayList => JUList}
    type YMap = JUMap[String, Any]

    // transform parameter "Any" to a scala List[(String, String)] where key can be repeated.
    // Can throw exception since it's used in a `safe`
    def paramsToScala(t: Any): List[(String, String)] = {
      t.asInstanceOf[JUMap[String, Any]].asScala.toList.flatMap{
        case (k, v: String   ) => List((k,v))
        case (k, v: JUList[_]) => v.asScala.map(x => (k,x.toString)).toList
        case (k, x           ) => throw new IllegalArgumentException(s"Can not parse '${x}:${x.getClass.getName}' as either a string or a list of string")
      }
    }

    if(obj == null) Failure("The YAML document is empty")
    else tryo {
      val data = obj.asInstanceOf[YMap].asScala
      val response = data("response").asInstanceOf[YMap].asScala
      TestRequest(
          data    .safe("description", Some("")    , _.asInstanceOf[String] )
        , data    .safe("method"     , None        , _.asInstanceOf[String])
        , data    .safe("url"        , None        , _.asInstanceOf[String])
        , data    .safe("body"       , Some("")    , _.asInstanceOf[String])
        , data    .safe("headers"    , Some(Nil)   , _.asInstanceOf[JUList[String]].asScala.toList)
        , data    .safe("params"     , Some(Nil)   , x => paramsToScala(x))
        , response.safe("type"       , Some("json"), _.asInstanceOf[String] ) match {
            case "json" => ResponseType.Json
            case "text" => ResponseType.Text
            case x      => throw new IllegalArgumentException(s"Unrecognized response type: '${x}'. Use 'json' or 'text'")
          }
        , response.safe("code"       , None        , _.asInstanceOf[Int])
        , response.safe("content"    , None        , _.asInstanceOf[String])
      )
    }
  }

  // we are testing error cases, so we don't want to output error log for them
  org.slf4j.LoggerFactory.getLogger("com.normation.rudder.rest.RestUtils").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.OFF)

  sequential

  ///// tests ////

  def cleanBreakline(text: String) = text.replaceAll("(\\W)\\s+", "$1").replaceAll("\\s+$", "")
  def cleanResponse(r: LiftResponse): (Int, String) = {
    val response = r.toResponse.asInstanceOf[InMemoryResponse]
    val resp = cleanBreakline(new String(response.data, "UTF-8"))
    (response.code, resp)
  }

  sequential

  Fragments.foreach(files) { file =>

    val objects = (new Yaml()).loadAll(src_test_resources_api_(file)).asScala

    // "." are breaking description, we need to remove it from file name
    s"For each test defined in '${file.split("\\.").head}'" should {
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
            val p = test.url.split('?')
            mockReq.path = p.head // exists, split always returns at least one element
            mockReq.queryString = if(p.size > 1) p(1) else ""
            mockReq.body   = test.queryBody.getBytes(StandardCharsets.UTF_8)
            mockReq.headers = test.headers.map{ h =>
              val parts = h.split(":")
              (parts(0), List(parts.tail.mkString(":").trim))
            }.toMap
            // query string may have already set some params
            mockReq.parameters = mockReq.parameters ++ test.params
            mockReq.contentType = mockReq.headers.get("Content-Type").flatMap(_.headOption).getOrElse("text/plain")

            // authorize space in response formating
            val expected = cleanBreakline(test.responseContent)

            RestTestSetUp.execRequestResponse(mockReq)(response =>
              response.map(cleanResponse(_)) must beEqualTo(Full((test.responseCode, expected)))
            )
          }
      } }
    }
  }
}
