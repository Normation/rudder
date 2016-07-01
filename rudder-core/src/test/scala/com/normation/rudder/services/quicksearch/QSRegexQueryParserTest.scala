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

package com.normation.rudder.services.quicksearch


import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import org.specs2.specification.Fragments
import net.liftweb.common._
import org.specs2.matcher.MatchResult

@RunWith(classOf[JUnitRunner])
class QSRegexQueryParserTest extends Specification {


  import QSRegexQueryParser.parse
  import com.normation.rudder.services.quicksearch.QSObject._
  import com.normation.rudder.services.quicksearch.QSAttribute._

  implicit class BoxMustFails[T](t: Box[T]) {
    def mustFails(): MatchResult[Any] = t match {
      case f: Failure => ok("Got a Failure as expected")
      case x          => ko(s"I was expecting a Failure box and got ${x}")
    }
  }

  implicit class BoxMustEquals[T](t: Box[T]) {
    def mustFull(res: T): MatchResult[Any] = t match {
      case f: Failure => ko(s"I wasn't explecting the failure: ${f.msg}")
      case Empty      => ko(s"How can I get an Empty!")
      case Full(x)    => x === res
    }
  }

  //// the query part must be ALWAYS trimed ////
  /*
   * if not, with """attribute:hostname foo""", we can't look for just "foo", always for " foo"
   */

   //Test the component part
  "Bad queries" should {
    "be refused because empty string" in {
      parse("").mustFails
    }
    "be refused because whitespace string" in {
      parse(" \t  ").mustFails
    }
    "be refused because only filters" in {
      parse(" in:directives ").mustFails
    }
  }

  "Simple queries" should {
    "give the exact same string, but trimed" in {
      val q = """ some node """
      parse(q).mustFull(Query(q.trim, QSObject.all, QSAttribute.all))
    }
    "give the exact same string, but trimed, even with regexp" in {
      val q = """ some.node[0-9]+.foo """
      parse(q).mustFull(Query(q.trim, QSObject.all, QSAttribute.all))
    }
    "give the exact same string, but trimed, even with part of rudder variable" in {
      val q = """ /foo/${rudder. """
      parse(q).mustFull(Query(q.trim, QSObject.all, QSAttribute.all))
    }
  }

  "Queries with filter" should {
    "on both sides works" in {
      parse(" In:Directive in:RuLes iN:displayName here, the query in:NodeId").mustFull(
          Query("here, the query", Set(Directive, Rule), Set(NodeId, Name))
      )
    }
    "only at end works" in {
      parse(" here, the query in:node in:descriptions").mustFull(
          Query("here, the query", Set(Node), Set(Description, LongDescription, ShortDescription))
      )
    }
    "only at starts works" in {
      parse(" in:Directive in:RuLes in:displayName here, the query ").mustFull(
          Query("here, the query", Set(Directive, Rule), Set(Name))
      )
    }
    "parse multiple filter comma separated" in {
      parse(" in:Directive,rules in:displayName here, the query in:NodeId,RuleId").mustFull(
          Query("here, the query", Set(Directive, Rule), Set(NodeId, Name, RuleId))
      )
    }
  }


}
