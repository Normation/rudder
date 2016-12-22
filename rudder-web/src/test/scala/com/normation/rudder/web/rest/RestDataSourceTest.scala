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
import org.specs2.mutable._
import org.specs2.runner._

import RestTestSetUp._
import net.liftweb.common.Full
import net.liftweb.http.PlainTextResponse
import net.liftweb.common.Loggable
import net.liftweb.http.JsonResponse
import net.liftweb.http.LiftResponse
import net.liftweb.common.Failure
import net.liftweb.json.JsonAST
import net.liftweb.json.JsonAST.JObject
import net.liftweb.json.JsonAST.JField
import net.liftweb.json.JsonAST.JArray
import net.liftweb.json.JValue
import com.normation.rudder.datasources._
import net.liftweb.common.Box
import com.normation.rudder.web.rest.datasource.DataSourceApi
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit

@RunWith(classOf[JUnitRunner])
class RestDataSourceTest extends Specification with Loggable {

  def extractDataFromResponse (response : LiftResponse, kind : String) : Box[List[JValue]] = {
    response match {
      case JsonResponse(data,_,_,200) =>
        net.liftweb.json.parseOpt(data.toJsCmd) match {
          case None => Failure("malformed json in answer")
          case Some(json) =>
            json \ "data" \ kind match {
              case JArray(data) => Full(data)
              case _ => Failure(json.toString())
            }
        }
      case _ => Failure(response.toString())
    }
  }

  val baseSourceType = HttpDataSourceType("", Map(), "GET", false, "", OneRequestByNode, DataSource.defaultDuration)
  val baseRunParam  = DataSourceRunParameters(NoSchedule(DataSource.defaultDuration), false,false)

  val datasource1 = DataSource(DataSourceId( "datasource1"), DataSourceName(""), baseSourceType, baseRunParam, "", false, DataSource.defaultDuration)
  val d1Json = restDataSerializer.serializeDataSource(datasource1)
  RestTestSetUp.datasourceRepo.save(datasource1)

  val datasource2 = datasource1.copy(id = DataSourceId("datasource2"))
  val d2Json = restDataSerializer.serializeDataSource(datasource2)

  println(net.liftweb.json.compactRender(d1Json))

  println(net.liftweb.json.compactRender(d2Json))

  val dataSource2Updated = datasource2.copy(
      description = "new description"
    , sourceType = baseSourceType.copy(headers = Map( ("new header 1" -> "new value 1") , ("new header 2" -> "new value 2")))
    , runParam = baseRunParam.copy(Scheduled(Duration(70, TimeUnit.MINUTES))))
  val d2updatedJson = restDataSerializer.serializeDataSource(dataSource2Updated)
  val d2modJson = {
    import net.liftweb.json.JsonDSL._
    ( ( "type" ->
        ( "name" -> "http" )
      ~ ( "headers" ->
          ( ("new header 1" -> "new value 1")
          ~ ("new header 2" -> "new value 2")
          )
        )
      )
    ~ ("description" -> "new description")
    ~ ("runParam" -> ("schedule" -> ("type" -> "scheduled") ~ ("duration" -> "70 minutes") ))
    )
  }

  sequential ^ ("Data source api" should {

    "Get all base data source" in {
      testGET("/api/latest/datasources") { req =>
       val result = for {
         answer <- RestTestSetUp.api(req)()
         data <- extractDataFromResponse(answer, "datasources")
       } yield {
         data
       }

       result must beEqualTo(Full(d1Json :: Nil))
      }
    }

    "Accept new data source as json" in {
      testPUT("/api/latest/datasources", d2Json) { req =>
       val result = for {
         answer <- RestTestSetUp.api(req)()
         data <- extractDataFromResponse(answer, "datasources")
       } yield {
         data
       }

       result must beEqualTo(Full(d2Json :: Nil))
      }
    }

    "List new data source" in {
      testGET("/api/latest/datasources") { req =>
       val result = for {
         answer <- RestTestSetUp.api(req)()
         data <- extractDataFromResponse(answer, "datasources")

       } yield {
         data
       }
       result.getOrElse(Nil) must contain( exactly(d1Json , d2Json))
      }
    }

    "Accept modification as json" in {
      testPOST(s"/api/latest/datasources/${datasource2.id.value}", d2modJson) { req =>
       val result = for {
         answer <- RestTestSetUp.api(req)()
         data <- extractDataFromResponse(answer, "datasources")
       } yield {
         data
       }

       result must beEqualTo(Full(d2updatedJson :: Nil))
      }
    }

    "Get updated data source" in {
      testGET(s"/api/latest/datasources/${datasource2.id.value}") { req =>
       val result = for {
         answer <- RestTestSetUp.api(req)()
         data <- extractDataFromResponse(answer, "datasources")

       } yield {
         data
       }
       result.getOrElse(Nil) must contain( exactly( d2updatedJson))
      }
    }

    "Delete the newly added data source" in {
      testDELETE(s"/api/latest/datasources/${datasource2.id.value}") { req =>
       val result = for {
         answer <- RestTestSetUp.api(req)()
         data <- extractDataFromResponse(answer, "datasources")

       } yield {
         data
       }
       result must beEqualTo(Full(d2updatedJson :: Nil))
      }
    }

    "Be removed from list of all data sources" in {
      testGET("/api/latest/datasources") { req =>
       val result = for {
         answer <- RestTestSetUp.api(req)()
         data <- extractDataFromResponse(answer, "datasources")

       } yield {
         data
       }
       result.getOrElse(Nil) must contain( exactly(d1Json))
      }
    }
  })
}
