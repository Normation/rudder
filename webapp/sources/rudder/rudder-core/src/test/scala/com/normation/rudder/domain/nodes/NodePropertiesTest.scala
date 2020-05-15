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
import com.normation.errors.PureResult
import GenericProperty._

@RunWith(classOf[JUnitRunner])
class NodePropertiesTest extends Specification with Loggable with BoxSpecMatcher {


  val RudderP = Some(PropertyProvider.defaultPropertyProvider)
  val P1 = Some(PropertyProvider("p1"))
  val P2 = Some(PropertyProvider("p2"))

  //just to have sequence in same order
  implicit val ord = new Ordering[NodeProperty]{
    override def compare(x: NodeProperty, y: NodeProperty): Int = x.name.compareTo(y.name)
  }

  val baseProps = List(
      NodeProperty("none"   , "node".toConfigValue   , None   )
    , NodeProperty("default", "default".toConfigValue, RudderP)
    , NodeProperty("p1"     , "p1".toConfigValue     , P1     )
    , NodeProperty("p2"     , "p2".toConfigValue     , P2     )
  ).sorted

  sequential

  "Creation of properties" should {
    "be ok" in {
      val newProps = baseProps.map( p => p.withName(p.name+"_2" ) )
      CompareProperties.updateProperties(baseProps, Some(newProps)).map( _.sorted ) must beRight((baseProps++newProps).sorted)
    }
  }

  "Deletion of properties" should {
    "be a noop if different keys" in {
      val newProps = baseProps.map( p => p.withName(p.name+"_2").withValue("") )
      CompareProperties.updateProperties(baseProps, Some(newProps)).map( _.sorted ) must beRight(baseProps)
    }
    "be ok with same metadata" in {
      val newProps = baseProps.map( p => p.withValue("") )
      CompareProperties.updateProperties(baseProps, Some(newProps)) must beRight(List.empty[NodeProperty])
    }
  }

  "Update with the same properties metadata" should {
    "be a noop with same values" in {
      CompareProperties.updateProperties(baseProps, Some(baseProps)).map( _.sorted ) must beRight(baseProps)
    }

    "ok with differents values" in {
      val newProps = baseProps.map( p => p.withValue("42") )
      CompareProperties.updateProperties(baseProps, Some(newProps)).map( _.sorted ) must beRight(baseProps.map( _.withValue("42") ) )
    }
  }

  "updating/deleting with different owners" should {
    //do an update and a delete of the prop
    def updateAndDelete(prop: NodeProperty) = {
      List(
          CompareProperties.updateProperties(baseProps, Some(prop :: Nil))
        , CompareProperties.updateProperties(baseProps, Some(prop.withValue("") :: Nil))
      )
    }

    "works if providers goes from default to an other" in {
      List(
          updateAndDelete(NodeProperty("none"   , "xxx".toConfigValue, P1))
        , updateAndDelete(NodeProperty("none"   , "xxx".toConfigValue, P2))
        , updateAndDelete(NodeProperty("default", "xxx".toConfigValue, P1))
        , updateAndDelete(NodeProperty("default", "xxx".toConfigValue, P2))
      ).flatten must contain( (res: PureResult[List[NodeProperty]]) => res must beAnInstanceOf[Right[_,_]] ).foreach
    }

    "works if providers goes from anything to system" in {
      List(
          updateAndDelete(NodeProperty("none"   , "xxx".toConfigValue, Some(PropertyProvider.systemPropertyProvider)))
        , updateAndDelete(NodeProperty("p1"     , "xxx".toConfigValue, Some(PropertyProvider.systemPropertyProvider)))
        , updateAndDelete(NodeProperty("default", "xxx".toConfigValue, Some(PropertyProvider.systemPropertyProvider)))
        , updateAndDelete(NodeProperty("default", "xxx".toConfigValue, Some(PropertyProvider.systemPropertyProvider)))
      ).flatten must contain( (res: PureResult[List[NodeProperty]]) => res must beAnInstanceOf[Right[_,_]] ).foreach
    }

    "fails for different, non default providers" in {
      List(
          updateAndDelete(NodeProperty("p1"     , "xxx".toConfigValue, None))
        , updateAndDelete(NodeProperty("p1"     , "xxx".toConfigValue, RudderP))
        , updateAndDelete(NodeProperty("p1"     , "xxx".toConfigValue, P2))
        , updateAndDelete(NodeProperty("p2"     , "xxx".toConfigValue, None))
        , updateAndDelete(NodeProperty("p2"     , "xxx".toConfigValue, RudderP))
        , updateAndDelete(NodeProperty("p2"     , "xxx".toConfigValue, P1))
      ).flatten must contain( (res: PureResult[List[NodeProperty]]) => res must beAnInstanceOf[Left[_,_]] ).foreach
    }


    "be ok with compatible one (default)" in {
      List(
          updateAndDelete(NodeProperty("none"   , "xxx".toConfigValue, RudderP))
        , updateAndDelete(NodeProperty("default", "xxx".toConfigValue, None))
      ).flatten must contain( (res: PureResult[List[NodeProperty]]) => res must beAnInstanceOf[Right[_,_]] ).foreach
    }
  }
}
