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
import com.normation.rudder.domain.nodes.GenericProperty._
import com.typesafe.config.ConfigValueFactory
import net.liftweb.common._
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner._
import java.util.{HashMap => JMap}

@RunWith(classOf[JUnitRunner])
class GenericPropertiesTest extends Specification with Loggable with BoxSpecMatcher {

  def jmap[A, B](tuples: (A,B)*) : JMap[A, B] = {
    val jmap = new JMap[A,B]()
    tuples.foreach { case (a,b) => jmap.put(a, b) }
    jmap
  }

  sequential

  "parsing" should {
    "recognize empty string" in {
      GenericProperty.parseValue("") must beRight(ConfigValueFactory.fromAnyRef(""))
    }
    "correctly parse a json like file" in {
      GenericProperty.parseValue("""{"a":"b"}""") must beRight(ConfigValueFactory.fromMap(jmap(("a", "b"))))
    }
    "correctly parse in a empty json-like structure" in {
      GenericProperty.parseValue("""{}""") must beRight
    }
    "correctly parse non json-like structure as a string" in {
      GenericProperty.parseValue("hello, world!") must beRight(ConfigValueFactory.fromAnyRef("hello, world!"))
    }
    "correctly parse a string which is a comment as a string, not an hocon comment, 1" in {
      GenericProperty.parseValue("# I'm a string, not a comment") must beRight(ConfigValueFactory.fromAnyRef("# I'm a string, not a comment"))
    }
    "correctly parse a string which is a comment as a string, not an hocon comment, 2" in {
      GenericProperty.parseValue("// I'm a string, not a comment") must beRight(ConfigValueFactory.fromAnyRef("// I'm a string, not a comment"))
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
      (firstNonCommentChar(s) must_=== Some('{')) and
      (GenericProperty.parseValue(s) must beRight(ConfigValueFactory.fromMap(jmap(("a", "b")))))
    }
    "fails in a badly eneded json-like structure" in {
      GenericProperty.parseValue("""{"a":"b" """) must beLeft
    }
    "fails in a non key/value property structure" in {
      GenericProperty.parseValue("""{"a"} """) must beLeft
    }
  }

  "serialization / deserialisation" should {
    val check = (s: String) => GenericProperty.parseValue(s).map(GenericProperty.serializeToHocon) must beRight(s)

    "be idempotent for string" in {
      val strings = List(
        ""
      , "some string"
      , """
        |# some things
        |plop
        |""".stripMargin
      , "strange char: ${plop} $ @ ¹ \n [fsj] (abc) $foo"
      )
      strings must contain(check).foreach
    }

    "be idempotent for values" in {
      val strings = List(
        """{"a":"b"}"""
      , """{"a":[1,2,3],"b":true,"c":{"d":"e"}}"""
      , "{}"
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
       GenericProperty.parseValue(s).map(GenericProperty.serializeToHocon) must beRight(t)
    }
  }
}
