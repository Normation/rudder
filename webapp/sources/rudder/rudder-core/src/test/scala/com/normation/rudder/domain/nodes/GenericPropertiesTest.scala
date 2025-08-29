/*
 *************************************************************************************
 * Copyright 2020 Normation SAS
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

package com.normation.rudder.domain.nodes

import com.normation.BoxSpecMatcher
import com.normation.errors.PureResult
import com.normation.errors.RudderError
import com.normation.rudder.domain.properties.GenericProperty
import com.normation.rudder.domain.properties.GenericProperty.*
import com.normation.rudder.domain.properties.NodeProperty
import com.typesafe.config.ConfigValueFactory
import java.util.ArrayList as JList
import java.util.HashMap as JMap
import net.liftweb.common.*
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.*

@RunWith(classOf[JUnitRunner])
class GenericPropertiesTest extends Specification with Loggable with BoxSpecMatcher {

  def jmap[A, B](tuples: (A, B)*): JMap[A, B] = {
    val jmap = new JMap[A, B]()
    tuples.foreach { case (a, b) => jmap.put(a, b) }
    jmap
  }

  def jlist[A](as: A*): JList[A] = {
    val jlist = new JList[A]()
    as.foreach(a => jlist.add(a))
    jlist
  }

  implicit class GetPureResult[A](res: PureResult[A]) {
    def forceGet: A = res match {
      case Right(v)  => v
      case Left(err) => throw new RuntimeException(err.fullMsg)
    }
  }

  sequential

  "parsing" should {
    "recognize empty string" in {
      GenericProperty.parseValue("") must beRight(ConfigValueFactory.fromAnyRef(""))
    }
    "recognize a string" in {
      GenericProperty.parseValue("foo") must beRight(ConfigValueFactory.fromAnyRef("foo"))
    }
    "correctly parse a json like file" in {
      GenericProperty.parseValue("""{"a":"b"}""") must beRight(ConfigValueFactory.fromMap(jmap(("a", "b"))))
    }
    "parse int as string" in {
      GenericProperty.parseValue("1").map(_.getClass.getSimpleName) must beRight(
        "Quoted"
      ) // Quoted is package private, didn't found another way
    }
    "parse array as array, keeping primitive types and objects" in {
      GenericProperty.parseValue("""[1,true,2.43, "a", {"a": "b"} ]""") must beRight(
        ConfigValueFactory.fromIterable(
          jlist(1, true, 2.43, "a", ConfigValueFactory.fromMap(jmap(("a", "b"))))
        )
      )
    }
    "correctly parse in a empty json-like structure" in {
      GenericProperty.parseValue("""{}""") must beRight
    }
    "correctly parse non json-like structure as a string" in {
      GenericProperty.parseValue("hello, world!") must beRight(ConfigValueFactory.fromAnyRef("hello, world!"))
    }
    "correctly parse a string which is a comment as a string, not an hocon comment, 1" in {
      GenericProperty.parseValue("# I'm a string, not a comment") must beRight(
        ConfigValueFactory.fromAnyRef("# I'm a string, not a comment")
      )
    }
    "correctly parse a string which is a comment as a string, not an hocon comment, 2" in {
      GenericProperty.parseValue("// I'm a string, not a comment") must beRight(
        ConfigValueFactory.fromAnyRef("// I'm a string, not a comment")
      )
    }
    "correctly accept comments in json-like, wherever they are" in {
      val s = """//here, a comment
                |# another
                |  { // start of value
                |  "a": # comment in line
                |  # comment new line
                |  "b" // another
                |  // again
                |} // after end of value
                | # even on new lines
                |""".stripMargin
      (firstNonCommentChar(s) must_=== (Some('{'))) and
      (GenericProperty.parseValue(s) must beRight(ConfigValueFactory.fromMap(jmap(("a", "b")))))
    }
    "fails in a badly eneded json-like structure" in {
      GenericProperty.parseValue("""{"a":"b" """) must beLeft[RudderError].like(
        _.msg must beMatching("The JSON object or array is not valid.*")
      )
    }
    "fails in a non key/value property structure" in {
      GenericProperty.parseValue("""{"a"} """) must beLeft[RudderError].like(
        _.msg must beMatching("The JSON object or array is not valid.*")
      )
    }
  }

  "serialization / deserialisation" should {
    val check = (s: String) => GenericProperty.parseValue(s).map(x => GenericProperty.serializeToHocon(x)) must beRight(s)

    "be idempotent for string" in {
      val strings = List(
        "",
        "some string",
        "1",
        "true",
        """
          |# some things
          |plop
          |""".stripMargin,
        "strange char: ${plop} $ @ ¹ \n [fsj] (abc) $foo"
      )
      strings must contain(check).foreach
    }

    val checkPrimitive =
      (s: AnyVal) => GenericProperty.parseValue(s.toString).map(x => GenericProperty.serializeToHocon(x)) must beRight(s.toString)

    "primitives like int and boolean are stringified" in {
      val primitives = List[AnyVal](1, 2.42, true)
      primitives must contain(checkPrimitive).forall
    }

    "be idempotent for values" in {
      val strings = List(
        """{"a":"b"}""",
        """{"a":[1,2,3],"b":true,"c":{"d":"e"}}""",
        "{}"
      )
      strings must contain(check).foreach
    }

    "hocon does not keep comment out of value and remove/reorder spaces/comments" in {
      val s = """
                |# some things
                |{ "a" :
                |  # comments!
                |  "b"
                |}// comment
                |""".stripMargin
      val t = """{# comments!
                |"a":"b"}""".stripMargin
      GenericProperty.parseValue(s).map(x => GenericProperty.serializeToHocon(x)) must beRight(t)
    }
  }

  /*

This is what is the serialized values of properties from node in 6.0. They must be kept
for compatibility

Format:
==
propName propType
propValue entered in node UI
serialized value in LDAP
==
json1 JSON
{
  "foo": "bar"
}
{"name":"json1","value":{"foo":"bar"}}
==
string1 String
simple string
{"name":"string1","value":"simple string"}
==
string2 String
#comment string
{"name":"string2","value":"#comment string"}
==
string3 String
{ contains curly braces }
{"name":"string3","value":"{ contains curly braces }"}
==
string4 String
line1
line2
line3
{"name":"string4","value":"line1\nline2\nline3"}
==
string5 String
"with double quotes"
{"name":"string5","value":"\"with double quotes\""}
==
string6 String
'with simple quotes'
{"name":"string6","value":"'with simple quotes'"}
==
string7 String
"""with triple double quotes"""
{"name":"string7","value":"\"\"\"with triple double quotes\"\"\""}
==
   */
  "compat with 6.0" should {

    val strings = List(
      """{"name":"string1","value":"simple string"}"""                         -> "simple string",
      """{"name":"string2","value":"#comment string"}"""                       -> "#comment string",
      """{"name":"string3","value":"{ contains curly braces }"}"""             -> "{ contains curly braces }",
      """{"name":"string4","value":"line1\nline2\nline3"}"""                   -> "line1\nline2\nline3",
      """{"name":"string5","value":"\"with double quotes\""}"""                -> "\"with double quotes\"",
      """{"name":"string6","value":"'with simple quotes'"}"""                  -> "'with simple quotes'",
      """{"name":"string7","value":"\"\"\"with triple double quotes\"\"\""}""" -> "\"\"\"with triple double quotes\"\"\""
    )

    "strings" in {
      val unser = strings.map {
        case (a, b) =>
          NodeProperty(GenericProperty.parseConfig(a).forceGet).valueAsString must_=== b
      }
      unser.reduce(_ and _)
    }
  }
}
