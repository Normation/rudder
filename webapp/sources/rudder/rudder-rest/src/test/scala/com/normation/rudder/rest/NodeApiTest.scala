package com.normation.rudder.rest

import com.normation.rudder.rest.data.NodeDetailsSerialize
import com.normation.rudder.rest.v1.RestStatus
import net.liftweb.common.Box
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.http.JsonResponse
import net.liftweb.http.LiftResponse
import net.liftweb.http.PlainTextResponse
import net.liftweb.json.JString
import net.liftweb.json.JsonAST.JArray
import net.liftweb.json.JsonAST.JValue
import net.liftweb.json.JsonDSL.pair2Assoc
import net.liftweb.json.JsonParser
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class NodeApiTest extends Specification with Loggable {
  sequential
  isolated
  val restTestSetUp = RestTestSetUp.newEnv
  val restTest = new RestTest(restTestSetUp.liftRules)

//  val serializeNodeDetails = new NodeDetailsSerialize

  def extractDataFromResponse (response : LiftResponse, kind : String) : Box[List[JValue]] = {
    response match {
      case JsonResponse(data,_,_,200) =>
        net.liftweb.json.parseOpt(data.toJsCmd) match {
          case None => Failure("malformed json in answer")
          case Some(json) =>
            json \ "data" \ kind match {
              case JArray(data) => Full(data)
              case _ => Failure(json.toString)
            }
        }
      case _ => Failure(response.toString)
    }
  }

  val nodeId = "88888888-c0a9-4874-8485-478e7e999999"
  val hostname = "test.rest.api"

  "Node API" should {


    "testing status REST API" should {
      "be correct" in {
        restTest.testGET("/api/status") { req =>
          RestStatus(req)() must beEqualTo(Full(PlainTextResponse(
            """OK
              |""".stripMargin)))
        }
      }
    }

    "Create node" in {
      val json = s"""
          |   [
          |     {
          |        "id": "$nodeId"
          |      , "hostname": "$hostname"
          |      , "status"  : "pending"
          |      , "os": {
          |          "type": "linux"
          |        , "name": "debian"
          |        , "version": "9.5"
          |        , "fullName": "Debian GNU/Linux 9 (stretch)"
          |      }
          |      , "policyServerId": "root"
          |      , "machineType": "vmware"
          |      , "agentKey" : {
          |        "value" : "----BEGIN CERTIFICATE---- ...."
          |      }
          |      , "properties": [
          |        {
          |          "name": "tags",
          |          "value": [
          |          "some",
          |          "tags"
          |          ]
          |        },
          |        {
          |          "name": "env",
          |          "value": "prod"
          |        },
          |        {
          |          "name": "vars",
          |          "value": {
          |            "var1": "value1",
          |            "var2": "value2"
          |          }
          |        }
          |      ]
          |      , "ipAddresses": ["192.168.180.90", "127.0.0.1"]
          |      , "timezone": {
          |        "name":"CEST"
          |        , "offset": "+0200"
          |      }
          |    }
          |  ]
          |""".stripMargin
      val jsonRes =
        (
          ("created" -> JArray(List(JString(nodeId))))
        ~ ("failed"  -> JArray(List.empty))
        )
      val d = JsonParser.parse(json)
      println(d)
      restTest.testPUT(s"/api/latest/nodes", d) { req =>
        val result = for {
          answer <- restTestSetUp.rudderApi.getLiftRestApi().apply(req).apply()
          data   <- extractDataFromResponse( answer, "nodes")
        } yield {
          data
        }
        result must beEqualTo(Full(jsonRes))
      }
    }
  }




}
