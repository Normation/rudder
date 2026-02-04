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

package com.normation.rudder.domain.properties

import net.liftweb.common.*
import org.json4s.other.JsonUtils.*
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.*

/*
 * Our internal representation of node properties must correctly serialize/unserialise to JSON
 */

@RunWith(classOf[JUnitRunner])
class NodePropertiesTest extends Specification with Loggable {

  implicit class ForceGet[A, E](a: Either[E, A]) {
    def forceGet: A = a match {
      case Left(x)  => throw new IllegalArgumentException(s"I got a left in test: ${x}")
      case Right(x) => x
    }
  }

  val p1:    NodeProperty       = NodeProperty.parse("jsonArray", """[ "one", 2, true]  """, None, None).forceGet
  val p2:    NodeProperty       = NodeProperty.parse("jsonProp", """{"jsonObject":"ok"}""", None, None).forceGet
  val p3:    NodeProperty       = NodeProperty.parse("stringArray", """[array]""", None, None).forceGet
  val p4:    NodeProperty       = NodeProperty.parse("stringSimple", "simple string", None, None).forceGet
  val props: List[NodeProperty] = List(p1, p2, p3, p4)

  // used to generate nodeproperties.d content
  "Node property set toDataJson correctly be rendered" >> {

    val expected = {
      """{
        |  "jsonArray":["one",2,true],
        |  "jsonProp":{
        |    "jsonObject":"ok"
        |  },
        |  "stringArray":["array"],
        |  "stringSimple":"simple string"
        |}""".stripMargin
    }
    val json     = props.toDataJson.prettyRender

    json === expected
  }
}
