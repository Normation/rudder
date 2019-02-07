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
import com.normation.BoxSpecMatcher
import net.liftweb.json.JsonAST.JString

@RunWith(classOf[JUnitRunner])
class NodePropertiesTest extends Specification with Loggable with BoxSpecMatcher {


  val RudderP = Some(NodeProperty.rudderNodePropertyProvider)
  val P1 = Some(NodePropertyProvider("p1"))
  val P2 = Some(NodePropertyProvider("p2"))

  //just to have sequence in same order
  implicit val ord = new Ordering[NodeProperty]{
    override def compare(x: NodeProperty, y: NodeProperty): Int = x.name.compareTo(y.name)
  }

  val baseProps = Seq(
      NodeProperty("none"   , "node"   , None   )
    , NodeProperty("default", "default", RudderP)
    , NodeProperty("p1"     , "p1"     , P1     )
    , NodeProperty("p2"     , "p2"     , P2     )
  ).sorted

  "Creation of properties" should {
    "be ok" in {
      val newProps = baseProps.map( p => p.copy(name = p.name+"_2" ) )
      CompareProperties.updateProperties(baseProps, Some(newProps)).map( _.sorted ) mustFullEq( (baseProps++newProps).sorted )
    }
  }

  "Deletion of properties" should {
    "be a noop if different keys" in {
      val newProps = baseProps.map( p => p.copy(name = p.name+"_2", value = JString("") ) )
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

    "works is providers goes from default to an other" in {
      List(
          updateAndDelete(NodeProperty("none"   , "xxx", P1))
        , updateAndDelete(NodeProperty("none"   , "xxx", P2))
        , updateAndDelete(NodeProperty("default", "xxx", P1))
        , updateAndDelete(NodeProperty("default", "xxx", P2))
      ).flatten must contain( (res: Box[Seq[NodeProperty]]) => res must beAnInstanceOf[Full[_]] ).foreach
    }

    "fails for different, non default providers" in {
      List(
          updateAndDelete(NodeProperty("p1"     , "xxx", None))
        , updateAndDelete(NodeProperty("p1"     , "xxx", RudderP))
        , updateAndDelete(NodeProperty("p1"     , "xxx", P2))
        , updateAndDelete(NodeProperty("p2"     , "xxx", None))
        , updateAndDelete(NodeProperty("p2"     , "xxx", RudderP))
        , updateAndDelete(NodeProperty("p2"     , "xxx", P1))
      ).flatten must contain( (res: Box[Seq[NodeProperty]]) => res must beAnInstanceOf[Failure] ).foreach
    }


    "be ok with compatible one (default)" in {
      List(
          updateAndDelete(NodeProperty("none"   , "xxx", RudderP))
        , updateAndDelete(NodeProperty("default", "xxx", None))
      ).flatten must contain( (res: Box[Seq[NodeProperty]]) => res must beAnInstanceOf[Full[_]] ).foreach
    }
  }
}
