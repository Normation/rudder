/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.services.queries

import com.normation.rudder.services.queries._
import com.normation.rudder.domain.queries._

import org.junit._
import org.junit.Assert._
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner

import net.liftweb.common._
import com.normation.rudder.domain._
import com.normation.rudder.services.queries._


@RunWith(classOf[BlockJUnit4ClassRunner])
class TestStringQueryParser {

  
  /*
   * our data store:
   * two criteria
   * 
   * 
   */
  val c1 = Criterion("name", BareComparator(Exists,Greater))
  val c2 = Criterion("id", BareComparator(Equals))
  val c3 = Criterion("name", BareComparator(Exists,Greater))

  val oc1 = ObjectCriterion("node", Seq(c1,c2))
  val oc2 = ObjectCriterion("machine", Seq(c3))
  
  val criteria = Map(
    "node" -> oc1,
    "machine" -> oc2
  )
  
  val parser = new DefaultStringQueryParser() {
    override val criterionObjects = criteria
  }
  
  
  val valid1_0 = StringQuery(NodeReturnType, Some("and"), Seq(
      StringCriterionLine("node","name","exists"),
      StringCriterionLine("machine","name","gt",Some("plop")),
      StringCriterionLine("node","id","eq",Some("foo"))
  ))
  
  val valid1_1 = StringQuery(NodeReturnType, Some("and"), Seq())
  val valid1_2 = StringQuery(NodeReturnType, Some("or"), Seq())
  val valid1_3 = StringQuery(NodeReturnType, None, Seq()) //default to and
  

  val unvalidComp = StringQuery(NodeReturnType, Some("foo"), Seq())
  val unknowObjectType = StringQuery(NodeReturnType, None, Seq(
      StringCriterionLine("unknown","name","exists")
  ))
  val unknowAttribute = StringQuery(NodeReturnType, None, Seq(
      StringCriterionLine("node","unknown","exists")
  ))
  val unknowComparator = StringQuery(NodeReturnType, None, Seq(
      StringCriterionLine("node","name","unknown")
  ))
  val missingRequiredValue = StringQuery(NodeReturnType, None, Seq(
      StringCriterionLine("node","name","eq")
  ))
  
  
  @Test
  def basicParsing() {
    
    assertEquals(
      Full(Query(NodeReturnType, And, Seq(
          CriterionLine(oc1,c1,Exists),
          CriterionLine(oc2,c3,Greater,"plop"),
          CriterionLine(oc1,c2,Equals,"foo")
      ))),
      parser.parse(valid1_0)
    )
  
    assertEquals(
      Full(Query(NodeReturnType, And, Seq())),
      parser.parse(valid1_1)
    )
    assertEquals(
      Full(Query(NodeReturnType, Or, Seq())),
      parser.parse(valid1_2)
    )
    assertEquals(
      Full(Query(NodeReturnType, And, Seq())),
      parser.parse(valid1_3)
    )
    
    assertFalse(parser.parse(unvalidComp).isDefined)
    assertFalse(parser.parse(unknowObjectType).isDefined)
    assertFalse(parser.parse(unknowAttribute).isDefined)
    assertFalse(parser.parse(unknowComparator).isDefined)
    assertFalse(parser.parse(missingRequiredValue).isDefined)
    
  }
}
