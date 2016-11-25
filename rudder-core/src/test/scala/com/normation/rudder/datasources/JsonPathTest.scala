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

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner._
import net.liftweb.common._
import net.liftweb.json._
import com.normation.inventory.domain.PublicKey
import com.normation.BoxSpecMatcher

@RunWith(classOf[JUnitRunner])
class JsonPathTest extends Specification with BoxSpecMatcher with Loggable {



  "These path are valid" should {

    "just an identifier" in  {
      JsonSelect.compilePath("foo").map( _.getPath ) mustFullEq( "$['foo']" )
    }
  }


  "The selection" should {
    "fail if source is not a json" in {
      JsonSelect.fromPath("$", """{ not a json!} ,pde at all!""") mustFails
    }

    "fail if input path is not valid " in {
      JsonSelect.fromPath("$$$..$", """not a json! missing quotes!""") mustFails
    }

    "retrieve first" in {
      val res = JsonSelect.fromPath("$.store.book", json).map( _.head )

      res mustFullEq( compactRender(parse("""
            {
                "category": "reference",
                "author": "Nigel Rees",
                "title": "Sayings of the Century",
                "price": 8.95
            }
        """)) )
    }
  }

  "get childrens" should {
    "retrieve JSON childrens forming an array" in {
      JsonSelect.fromPath("$.store.book[*]", json) mustFullEq(List(
          """{
                "category": "reference",
                "author": "Nigel Rees",
                "title": "Sayings of the Century",
                "price": 8.95
            }""",
            """{
                "category": "fiction",
                "author": "Evelyn Waugh",
                "title": "Sword of Honour",
                "price": 12.99
            }""",
            """{
                "category": "\"quotehorror\"",
                "author": "Herman Melville",
                "title": "Moby Dick",
                "isbn": "0-553-21311-3",
                "price": 8.99
            }""",
            """{
                "category": "fiction",
                "author": "J. R. R. Tolkien",
                "title": "The Lord of the Rings",
                "isbn": "0-395-19395-8",
                "price": 22.99
            }""").map(s => compactRender(parse(s))))
    }
    "retrieve NUMBER childrens forming an array" in {
      JsonSelect.fromPath("$.store.book[*].price", json) mustFullEq(List("8.95", "12.99", "8.99", "22.99"))
    }
    "retrieve STRING childrens forming an array" in {
      JsonSelect.fromPath("$.store.book[*].category", json) mustFullEq(List("reference", "fiction", "\"quotehorror\"", "fiction"))
    }
    "retrieve JSON childrens (one)" in {
      JsonSelect.fromPath("$.store.bicycle", json) mustFullEq(List("""{"color":"red","price":19.95}"""))
    }
    "retrieve NUMBER childrens (one)" in {
      JsonSelect.fromPath("$.store.bicycle.price", json) mustFullEq(List("19.95"))
    }
    "retrieve STRING childrens (one)" in {
      JsonSelect.fromPath("$.store.bicycle.color", json) mustFullEq(List("red"))
    }
    "retrieve ARRAY INT childrens (one)" in {
      JsonSelect.fromPath("$.intTable", json) mustFullEq(List("1", "2", "3"))
    }
    "retrieve ARRAY STRING childrens (one)" in {
      JsonSelect.fromPath("$.stringTable", json) mustFullEq(List("one", "two"))
    }
  }




  lazy val json = """
  {
    "store": {
        "book": [
            {
                "category": "reference",
                "author": "Nigel Rees",
                "title": "Sayings of the Century",
                "price": 8.95
            },
            {
                "category": "fiction",
                "author": "Evelyn Waugh",
                "title": "Sword of Honour",
                "price": 12.99
            },
            {
                "category": "\"quotehorror\"",
                "author": "Herman Melville",
                "title": "Moby Dick",
                "isbn": "0-553-21311-3",
                "price": 8.99
            },
            {
                "category": "fiction",
                "author": "J. R. R. Tolkien",
                "title": "The Lord of the Rings",
                "isbn": "0-395-19395-8",
                "price": 22.99
            }
        ],
        "bicycle": {
            "color": "red",
            "price": 19.95
        }
    },
    "expensive": 10,
    "intTable": [1,2,3],
    "stringTable": ["one", "two"]
  }
  """

}
