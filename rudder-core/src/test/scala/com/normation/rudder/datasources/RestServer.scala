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

package com.normation.rudder.datasources

import scala.concurrent.ExecutionContext

import org.http4s._
import org.http4s.dsl._
import org.http4s.server.blaze.BlazeBuilder

/*
Corresponding datasources:
 % cat datasource.json
{
  "name": "datasource number one",
  "id": "datasource1",
  "description": "make data source great again",
  "type": {
    "name": "http",
    "url": "htt://localhost:8282/cmdb/${rudder.node.id}",
    "headers": {},
    "path": "$.hostnames.fqdn",
    "checkSsl": false,
    "requestTimeout": "5 minutes",
    "requestMode": {
      "name": "byNode"
    }
  },
  "runParam": {
    "onGeneration": false,
    "onNewNode": false,
    "schedule": {
      "type": "scheduled",
      "duration": "5 minutes"
    }
  },
  "updateTimeOut": "5 minutes",
  "enabled": true
}

% curl -k -H "X-API-Token: xxxxxx" -H "Content-Type: application/json" -X PUT 'http://localhost:8082/rudder-web/api/latest/datasources' -d@datasource.json
...
 */

object RestServer {

  def main(args: Array[String]): Unit = {

    println("starting server on port 8282...")
    //start server
    val server = BlazeBuilder.bindHttp(8282)
      .mountService(NodeDataset.service, "/cmdb")
      .run
      .awaitShutdown()
  }

  //create a rest server for test
  object NodeDataset {

    def service(implicit executionContext: ExecutionContext = ExecutionContext.global) = HttpService {
      case _ -> Root =>
        MethodNotAllowed()

      case GET -> Root / x =>
        Ok {
          println(s"Received a request for node '${x}'")
          nodeJson(x)
        }
    }

    //expample of what a CMDB could return for a node.
    def nodeJson(name: String) = s""" {
      "hostname" : "${name}",
      "ad_groups" : [ "ad-grp1 " ],
      "ssh_groups" : [ "ssh-power-users" ],
      "sudo_groups" : [ "sudo-masters" ],
      "hostnames" : {
       "fqdn" : "$name.some.host.com $name",
       "local" : "localhost.localdomain localhost localhost4 localhost4.localdomain4"
      },
      "netfilter4_rules" : {
       "all" : "lo",
       "ping" : "eth0",
       "tcpint" : "",
       "udpint" : "",
       "exceptions" : "",
       "logdrop" : false,
       "gateway" : false,
       "extif" : "eth0",
       "intif" : "eth1"
      },
    "netfilter6_rules" : {
       "all" : "lo",
       "ping" : "eth0",
       "tcpint" : "",
       "udpint" : "",
       "exceptions" : "",
       "logdrop" : false,
       "gateway" : false,
       "extif" : "eth0",
       "intif" : "eth1"
      }
    }
    """
  }
}
