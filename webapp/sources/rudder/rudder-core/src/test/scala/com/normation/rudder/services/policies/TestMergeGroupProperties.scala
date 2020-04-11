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

package com.normation.rudder.services.policies

import com.normation.rudder.domain.nodes.GroupProperty
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.FullGroupTarget
import com.normation.rudder.domain.policies.FullRuleTargetInfo
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.queries._
import net.liftweb.json.JsonAST.JString
import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import com.softwaremill.quicklens._
import net.liftweb.json._

/*
 * This class test the JsEngine. 6.0
 * It must works identically on Java 7 and Java 8.
 *
 */

@RunWith(classOf[JUnitRunner])
class TestMergeGroupProperties extends Specification {

  sequential

  implicit class ToTarget(g: NodeGroup) {
    def toTarget = FullRuleTargetInfo(FullGroupTarget(GroupTarget(g.id), g), g.name, "", true, true)
    def toCriterion = CriterionLine(null, Criterion("some ldap attr", new SubGroupComparator(null)), null, g.id.value)
  }

  val parent1   = NodeGroup(NodeGroupId("parent1"), "parent1", "",
      List(GroupProperty("foo", JString("bar1")))
    , Some(Query(NodeReturnType, And, List()))
    , true, Set(), true
  )
  val parent2Prop = GroupProperty("foo", JString("bar2"))
  val parent2   = NodeGroup(NodeGroupId("parent2"), "parent2", "",
      List(parent2Prop)
    , Some(Query(NodeReturnType, And, List()))
    , true, Set(), true
  )
  val childProp = GroupProperty("foo", JString("baz"))
  val query = Query(NodeReturnType, And, List(parent1.toCriterion))
  val child = NodeGroup(NodeGroupId("child"), "child", "",
      List(childProp)
    , Some(query)
    , true, Set(), true
  )


  "overriding a property in a hierarchy should work" >> {
    val merged = MergeNodeProperties.checkPropertyMerge(parent1.toTarget :: child.toTarget :: Nil)
    merged must beRight(List(childProp))
  }

  "if the composition is OR, subgroup must be ignored" >> {
    val ct2 = child.modify(_.query).setTo(Some(query.modify(_.composition).setTo(Or))).toTarget
    val merged = MergeNodeProperties.checkPropertyMerge(parent1.toTarget :: ct2 :: Nil)
    merged must beLeft
  }

  "override is done in the same order of line, the last wins" >> {
    val q2 = query.modify(_.criteria).setTo(parent1.toCriterion::parent2.toCriterion::Nil)
    val ct2 = child
                .modify(_.query).setTo(Some(q2)) // parent 2 wins
                .modify(_.properties).setTo(Nil) // remove child property to get one of parent
                .toTarget

    val merged = MergeNodeProperties.checkPropertyMerge(parent2.toTarget :: parent1.toTarget  :: ct2 :: Nil)
    merged must beRight(List(parent2Prop))
  }

  "when overriding json we" should {
    def getOverrides(groups: List[NodeGroup]): Map[String, String] = {
      //sort obj by key for easy string comparison
      def sort(json: JValue): JValue = {
        json match {
          case JObject(list) => JObject(list.sortBy(_.name).map { case JField(k,v) => JField(k, sort(v)) })
          case x             => x
        }
      }
      MergeNodeProperties.checkPropertyMerge(groups.map(_.toTarget)) match {
        case Left(_)  => throw new IllegalArgumentException(s"Error when overriding properties")
        case Right(v) => v.map(g => (g.name, g.value match {
          case JString(s) => s
          case x          => compactRender(sort(x))
        })).toMap
      }
    }
    def getGroups(parentProps: Map[String, String], childProps: Map[String, String]) = {
      def toProps(map: Map[String, String]) = map.map { case (k, v) =>
        GroupProperty(k, v)
      }.toList
      val parent = NodeGroup(NodeGroupId("parent1"), "parent1", "",
          toProps(parentProps)
        , Some(Query(NodeReturnType, And, List()))
        , true, Set(), true
      )
      val child = NodeGroup(NodeGroupId("child"), "child", "",
          toProps(childProps)
        , Some(Query(NodeReturnType, And, List(parent1.toCriterion)))
        , true, Set(), true
      )
      parent :: child :: Nil
    }
    def checkOverrides(parentProps: Map[String, String], childProps: Map[String, String]) = {
      getOverrides(getGroups(parentProps, childProps))
    }
    "override whatever by simple values" in {
      val child =
        Map("s" -> "c" , "arr" -> "c"    , "obj" -> "c")
      val props = checkOverrides(
        Map("s" -> "p" , "arr" -> "[1,2]", "obj" -> """{"a" -> "b"}""")
      , child
      )
      props must beEqualTo(child)
    }
    "merge arr and objects" in {
      val props = checkOverrides(
        Map("arr" -> "[1,2]", "obj" -> """{"a":"b", "i":"j1", "x":{"y1":"z"}}""")
      , Map("arr" -> "[3,4]", "obj" -> """{"c":"d", "i":"j2", "x":{"y2":"z"}}""")
      )
      props must beEqualTo(
        Map(
          "arr" -> "[1,2,3,4]"
        , "obj" -> """{"a":"b","c":"d","i":"j2","x":{"y1":"z","y2":"z"}}"""
        )
      )
    }
  }
}

