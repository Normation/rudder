/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.inventory.domain

import com.normation.zio.ZioRuntime
import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._

/**
 * Test properties about agent type, especially regarding serialisation
 */
@RunWith(classOf[JUnitRunner])
class AgentTypesTest extends Specification {

  // test serialisation of agent type and security token.

  /*
   * In 4.1 and before, we add two seperated attribute:
   * - agentType for agent type and version
   * - publicKey to store node RSA public key in pkcs1 format
   *
   * Starting with 4.2 and up, we use an unified field to store both
   * agent version and security token. The security token can be
   * a certificate of just a public key. In all cases, it's a json
   * with format:
   * {
   *   "agentType":"agent-type"
   * , "version"  : "version"
   * , "securityToken": {
   *     "value": "-----BEGIN RSA PUBLIC KEY-----\nMIIBCgKCA...."
   *   , "type" : "publicKey | certificate"
   *   }
   * }
   *
   */

  val key = "-----BEGIN RSA PUBLIC KEY-----\nMIIBCgKCAQEAtDTtbfw2ic1pcUludrs1I+HGGdRL0r5dKjMzLhUGahCrVaH7H3ND\n/py+XPZKY/Iolttgf1RriQAazEVbFEWXozistMTXtWJu/5IxV47QNqbS82KrhQNp\ns4abfqraGOlYbYS5BXCaHYrKI2VzvAwwvCsE7vmhnO1Br4AueagrFU+itjr/0gMd\nu58xYDiAADXqGDzES75NIxCZelv5vefMfpEMlBmztKmgY+iT+Q8lhf42WUsZ9OBl\nRDRfQ9VCW+8336C1JEpcHAcSElF4mn4D0GN7RvxNOSGpuLjxAvp5qFVbp4Xtd+4q\n8DaGe+w8MplwMVCFTyEMS3E1pS4DdctdLwIDAQAB\n-----END RSA PUBLIC KEY-----"

  val json43 = s"""
    {
       "agentType":"cfengine-community"
      ,"version"  :"4.3.2"
      ,"securityToken": { "value":"$key","type":"publicKey"}
    }"""

  val json41 = s"""
    {
       "agentType":"Community"
      ,"version"  :"4.1.13"
    }"""


  "Parsing agent type" should {

    "works for 4_3 format" in {
      val res = ZioRuntime.unsafeRun(AgentInfoSerialisation.parseJson(json43, None))
      res must beEqualTo(
        AgentInfo(AgentType.CfeCommunity, Some(AgentVersion("4.3.2")), PublicKey(key))
      )
    }

    "be able to read 4_1 serialized info" in {
      val res = ZioRuntime.unsafeRun(AgentInfoSerialisation.parseJson(json41, Some(key)))
      res must beEqualTo(
        AgentInfo(AgentType.CfeCommunity, Some(AgentVersion("4.1.13")), PublicKey(key))
      )
    }
  }
}
