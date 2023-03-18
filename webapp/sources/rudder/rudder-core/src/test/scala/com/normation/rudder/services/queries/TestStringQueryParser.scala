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

package com.normation.rudder.services.queries

import com.normation.rudder.domain.queries._
import com.normation.rudder.domain.queries.ResultTransformation._
import net.liftweb.common._
import org.junit._
import org.junit.Assert._
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import zio.Chunk

@RunWith(classOf[BlockJUnit4ClassRunner])
class TestStringQueryParser {

  /*
   * our data store:
   * two criteria
   */
  val c1 = Criterion("name", BareComparator(Exists, Greater), NodeCriterionMatcherString(_ => Chunk("something")))
  val c2 = Criterion("id", BareComparator(Equals), NodeCriterionMatcherString(_ => Chunk("something")))
  val c3 = Criterion("name", BareComparator(Exists, Greater), NodeCriterionMatcherString(_ => Chunk("something")))

  val oc1 = ObjectCriterion("node", List(c1, c2))
  val oc2 = ObjectCriterion("machine", List(c3))

  val criteria = Map(
    "node"    -> oc1,
    "machine" -> oc2
  )

  val parser = new DefaultStringQueryParser() {
    override val criterionObjects = criteria
  }

  val valid1_0 = StringQuery(
    NodeReturnType,
    Some("and"),
    None,
    List(
      StringCriterionLine("node", "name", "exists"),
      StringCriterionLine("machine", "name", "gt", Some("plop")),
      StringCriterionLine("node", "id", "eq", Some("foo"))
    )
  )

  val valid1_1 = StringQuery(NodeReturnType, Some("and"), None, List())
  val valid1_2 = StringQuery(NodeReturnType, Some("or"), None, List())
  val valid1_3 = StringQuery(NodeReturnType, None, None, List())             // default to and
  val valid2_1 = StringQuery(NodeReturnType, None, Some("identity"), List()) // default to and
  val valid2_2 = StringQuery(NodeReturnType, None, Some("none"), List())     // default to and
  val valid2_3 = StringQuery(NodeReturnType, None, Some("invert"), List())   // default to and

  val unvalidComp          = StringQuery(NodeReturnType, Some("foo"), None, List())
  val unknowObjectType     = StringQuery(
    NodeReturnType,
    None,
    None,
    List(
      StringCriterionLine("unknown", "name", "exists")
    )
  )
  val unknowAttribute      = StringQuery(
    NodeReturnType,
    None,
    None,
    List(
      StringCriterionLine("node", "unknown", "exists")
    )
  )
  val unknowComparator     = StringQuery(
    NodeReturnType,
    None,
    None,
    List(
      StringCriterionLine("node", "name", "unknown")
    )
  )
  val missingRequiredValue = StringQuery(
    NodeReturnType,
    None,
    None,
    List(
      StringCriterionLine("node", "name", "eq")
    )
  )

  @Test
  def basicParsing(): Unit = {

    assertEquals(
      Full(
        Query(
          NodeReturnType,
          And,
          Identity,
          List(
            CriterionLine(oc1, c1, Exists),
            CriterionLine(oc2, c3, Greater, "plop"),
            CriterionLine(oc1, c2, Equals, "foo")
          )
        )
      ),
      parser.parse(valid1_0)
    )

    assertEquals(
      Full(Query(NodeReturnType, And, Identity, List())),
      parser.parse(valid1_1)
    )
    assertEquals(
      Full(Query(NodeReturnType, Or, Identity, List())),
      parser.parse(valid1_2)
    )
    assertEquals(
      Full(Query(NodeReturnType, And, Identity, List())),
      parser.parse(valid1_3)
    )

    assertEquals(
      Full(Query(NodeReturnType, And, Identity, List())),
      parser.parse(valid2_1)
    )
    assertEquals(
      Full(Query(NodeReturnType, And, Identity, List())),
      parser.parse(valid2_2)
    )
    assertEquals(
      Full(Query(NodeReturnType, And, Invert, List())),
      parser.parse(valid2_3)
    )

    assertFalse(parser.parse(unvalidComp).isDefined)
    assertFalse(parser.parse(unknowObjectType).isDefined)
    assertFalse(parser.parse(unknowAttribute).isDefined)
    assertFalse(parser.parse(unknowComparator).isDefined)
    assertFalse(parser.parse(missingRequiredValue).isDefined)

  }
}
