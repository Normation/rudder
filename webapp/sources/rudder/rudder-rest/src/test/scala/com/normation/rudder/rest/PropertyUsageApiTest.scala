package com.normation.rudder.rest

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

import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.json.compactRender
import org.specs2.mutable.Specification

class PropertyUsageApiTest extends Specification with Loggable {
  sequential
  val restTestSetUp = RestTestSetUp.newEnv
  val restTest      = new RestTest(restTestSetUp.liftRules)

  "Property usage API" should {
    "find a property toto in Technique" in {
      val propertyName1 = "toto"
      val jsonRes: String = {
        """{
          |"action":"usageOfProperty"
          |,"result":"success"
          |,"data":{
          |"directives":[]
          |,"techniques":[
          |{
          |"id":"technique_with_a_property_in_a_block"
          |,"name":"Test technique with block and property 'toto'"
          |}
          |]
          |}
          |}""".stripMargin.replaceAll("""\n""", "")
      }
      restTest.testGETResponse(s"/api/latest/nodes/details/property/usage/${propertyName1}") { resp =>
        resp match {
          case Full(JsonResponsePrettify(content, _, _, 200, _)) =>
            val jsonString = compactRender(content)
            (jsonString must beEqualTo(jsonRes))
          case _                                                 => ko("unexpected answer")
        }
      }
    }

    "find a property toto in Directive" in {
      val propertyName2 = "pouet"
      val jsonRes: String = {
        """{
          |"action":"usageOfProperty"
          |,"result":"success"
          |,"data":{
          |"directives":[
          |{
          |"id":"dir_with_property_pouet"
          |,"name":"Directive with property 'pouet'"
          |}
          |]
          |,"techniques":[]
          |}
          |}""".stripMargin.replaceAll("""\n""", "")
      }
      restTest.testGETResponse(s"/api/latest/nodes/details/property/usage/${propertyName2}") { resp =>
        resp match {
          case Full(JsonResponsePrettify(content, _, _, 200, _)) =>
            val jsonString = compactRender(content)
            (jsonString must beEqualTo(jsonRes))
          case _                                                 => ko("unexpected answer")
        }
      }
    }
    "find a property common_property in Directive and Technique" in {
      val propertyName3 = "common_property"
      val jsonRes: String = {
        """{
          |"action":"usageOfProperty"
          |,"result":"success"
          |,"data":{
          |"directives":[
          |{
          |"id":"dir_with_property_common_property"
          |,"name":"Directive with property 'common_property'"
          |}
          |]
          |,"techniques":[
          |{
          |"id":"technique_by_Rudder_with_common_property"
          |,"name":"Test Technique with property 'common_property'"
          |}
          |]
          |}
          |}""".stripMargin.replaceAll("""\n""", "")
      }
      restTest.testGETResponse(s"/api/latest/nodes/details/property/usage/${propertyName3}") { resp =>
        resp match {
          case Full(JsonResponsePrettify(content, _, _, 200, _)) =>
            val jsonString = compactRender(content)
            (jsonString must beEqualTo(jsonRes))
          case _                                                 => ko("unexpected answer")
        }
      }
    }
  }

}
