package com.normation.rudder.rest

import com.normation.inventory.domain.AcceptedInventory
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.PendingInventory
import com.normation.zio._
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.json.JsonParser
import net.liftweb.json.compactRender
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.annotation.nowarn

@nowarn("msg=a type was inferred to be `\\w+`; this may indicate a programming error.")
@RunWith(classOf[JUnitRunner])
class NodeApiTest extends Specification with Loggable {
  sequential
  isolated
  val restTestSetUp = RestTestSetUp.newEnv
  val restTest      = new RestTest(restTestSetUp.liftRules)

  val node1     = "88888888-c0a9-4874-8485-478e7e999999"
  val hostname1 = "hostname.node1"
  val node2     = "aaaaaaaa-c0a9-4874-8485-478e7e999999"
  val hostname2 = "hostname.node2"

  "Node API" should {

    "Create node" in {
      val json = {
        s"""
           |   [
           |     {
           |        "id": "$node1"
           |      , "hostname": "$hostname1"
           |      , "status"  : "pending"
           |      , "os": { "type": "linux", "name": "debian", "version": "9.5", "fullName": "Debian GNU/Linux 9 (stretch)"}
           |      , "policyServerId": "root"
           |      , "machineType": "vmware"
           |      , "agentKey" : {
           |        "value" : "----BEGIN CERTIFICATE---- ...."
           |      }
           |      , "properties": [
           |        { "name": "tags",
           |          "value": ["some","tags"]
           |        },
           |        { "name": "env",
           |          "value": "prod"
           |        },
           |        { "name": "vars",
           |          "value": {"var1": "value1","var2": "value2"}
           |        }
           |      ]
           |      , "ipAddresses": ["192.168.180.90", "127.0.0.1"]
           |      , "timezone": { "name":"CEST", "offset": "+0200" }
           |    }
           |  , {
           |        "id": "$node2"
           |      , "hostname": "$hostname2"
           |      , "status"  : "accepted"
           |      , "os": { "type": "linux", "name": "debian", "version": "11", "fullName": "Debian GNU/Linux 11"}
           |      , "policyServerId": "root"
           |      , "machineType": "physical"
           |      , "agentKey" : {
           |        "value" : "----BEGIN CERTIFICATE---- ...."
           |      }
           |      , "properties": [
           |        { "name": "env",
           |          "value": "test"
           |        }
           |      ]
           |      , "ipAddresses": ["192.168.23.21", "127.0.0.1"]
           |      , "timezone": { "name":"CEST", "offset": "+0200" }
           |    }
           | ]
           |""".stripMargin
      }

      val jsonRes = s"""{"action":"createNodes","result":"success","data":{"created":["${node1}","${node2}"],"failed":[]}}"""

      org.slf4j.LoggerFactory
        .getLogger("nodes.pending")
        .asInstanceOf[ch.qos.logback.classic.Logger]
        .setLevel(ch.qos.logback.classic.Level.TRACE)

      restTest.testPUTResponse(s"/api/latest/nodes", JsonParser.parse(json)) { resp =>
        resp match {
          case Full(JsonResponsePrettify(content, _, _, 200, _)) =>
            val jsonString = compactRender(content)
            val n1         = restTestSetUp.mockNodes.nodeFactRepo.getPending(NodeId(node1)).notOptional("missing node1").runNow
            val n2         = restTestSetUp.mockNodes.nodeFactRepo.getAccepted(NodeId(node2)).notOptional("missing node2").runNow

            (jsonString must beEqualTo(jsonRes)) and
            (n1.rudderSettings.status must beEqualTo(PendingInventory)) and
            (n2.rudderSettings.status must beEqualTo(AcceptedInventory))

          case x => ko(s"unexpected answer: ${x}")
        }
      }
    }
  }
}
