/*
*************************************************************************************
* Copyright 2017 Normation SAS
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

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner._
import net.liftweb.common._
import com.normation.inventory.domain.PublicKey
import com.normation.BoxSpecMatcher
import net.liftweb.json.JsonAST.JString

@RunWith(classOf[JUnitRunner])
class NodePropertiesTest extends Specification with Loggable with BoxSpecMatcher {

  import NodePropertyRights._

  val RudderP = Some(NodeProperty.rudderNodePropertyProvider)
  val P1 = Some(NodePropertyProvider("p1"))
  val P2 = Some(NodePropertyProvider("p2"))

  //just to have sequence in same order
  implicit val ord = new Ordering[NodeProperty]{
    override def compare(x: NodeProperty, y: NodeProperty): Int = x.name.compareTo(y.name)
  }

  val baseProps = Seq(
      NodeProperty("000", "000", None, None)
    , NodeProperty("001", "001", None, Some(ReadWrite))
    , NodeProperty("002", "002", None, Some(ReadOnly))
    , NodeProperty("003", "003", RudderP, None)
    , NodeProperty("004", "004", RudderP, Some(ReadWrite))
    , NodeProperty("005", "005", RudderP, Some(ReadOnly))
    , NodeProperty("006", "006", P1, None)
    , NodeProperty("007", "007", P1, Some(ReadWrite))
    , NodeProperty("008", "008", P1, Some(ReadOnly))
    , NodeProperty("009", "009", P2, None)
    , NodeProperty("010", "010", P2, Some(ReadWrite))
    , NodeProperty("011", "011", P2, Some(ReadOnly))
  )

  "Creation of prorties" should {
    "be ok" in {
      val newProps = baseProps.map( p => p.copy(name = "1" + p.name.tail ) )
      CompareProperties.updateProperties(baseProps, Some(newProps)).map( _.sorted ) mustFullEq( baseProps++newProps )
    }
  }

  "Deletion of properties" should {
    "be a noop if different keys" in {
      val newProps = baseProps.map( p => p.copy(name = "1" + p.name.tail, value = JString("") ) )
      CompareProperties.updateProperties(baseProps, Some(newProps)).map( _.sorted ) mustFullEq( baseProps )
    }
    "be ok with same metadata" in {
      val newProps = baseProps.map( p => p.copy(value = JString("") ) )
      CompareProperties.updateProperties(baseProps, Some(newProps)) mustFullEq( Seq() )
    }
  }

  "Update with the same properties metadata" should {
    "be a noop with same values" in {
      CompareProperties.updateProperties(baseProps, Some(baseProps)).map( _.sorted ) mustFullEq( baseProps )
    }

    "ok with differents values" in {
      val newProps = baseProps.map( p => p.copy(value = JString("42") ) )
      CompareProperties.updateProperties(baseProps, Some(newProps)).map( _.sorted ) mustFullEq( baseProps.map( _.copy(value = JString("42") ) ) )
    }
  }

  "updating/deleting with different owners" should {
    //do an update and a delete of the prop
    def updateAndDelete(prop: NodeProperty) = {
      List(
          CompareProperties.updateProperties(baseProps, Some(prop :: Nil))
        , CompareProperties.updateProperties(baseProps, Some(prop.copy(value = JString("")) :: Nil))
      )
    }

    "always fails with read-only" in {
      List(
          updateAndDelete(NodeProperty("002", "002", P1, None))
        , updateAndDelete(NodeProperty("005", "005", P1, None))
        , updateAndDelete(NodeProperty("008", "008", P2, None))
        , updateAndDelete(NodeProperty("008", "008", None, None))
        , updateAndDelete(NodeProperty("008", "008", RudderP, None))
      ).flatten must contain( (res: Box[Seq[NodeProperty]]) => res must beAnInstanceOf[Failure] ).foreach
    }

    "be ok with read-write" in {
      List(
          updateAndDelete(NodeProperty("001", "001", P1, None))
        , updateAndDelete(NodeProperty("004", "004", P1, None))
        , updateAndDelete(NodeProperty("007", "007", P2, None))
        , updateAndDelete(NodeProperty("007", "007", None, None))
        , updateAndDelete(NodeProperty("007", "007", RudderP, None))
      ).flatten must contain( (res: Box[Seq[NodeProperty]]) => res must beAnInstanceOf[Full[_]] ).foreach
    }
  }
}
