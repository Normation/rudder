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

package com.normation.rudder.services.nodes

import com.normation.errors.PureResult
import com.normation.errors.RudderError
import com.normation.rudder.domain.nodes._
import com.normation.rudder.domain.policies.FullGroupTarget
import com.normation.rudder.domain.policies.FullRuleTargetInfo
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.queries._
import com.normation.rudder.services.policies.NodeConfigData
import com.softwaremill.quicklens._
import com.typesafe.config.ConfigValue
import com.typesafe.config.ConfigValueFactory
import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import com.normation.rudder.domain.nodes.JsonPropertySerialisation._
import net.liftweb.json._
import net.liftweb.json.JsonDSL._

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

  implicit class ToNodePropertyHierarchy(groups: List[NodeGroup]) {
    def toParents(name: String) = {
      groups.flatMap { g =>
        g.properties.find(_.name == name).map(p => ParentProperty.Group(g.name, g.id, p.value))
      }
    }
    // use first parent to build a fully inherited prop
    def toH1(name: String) = {
      toParents(name) match {
        case h::t => NodePropertyHierarchy(NodeProperty(name, h.value, Some(GroupProp.INHERITANCE_PROVIDER)), h :: t)
        case _ => throw new IllegalArgumentException(s"No value found for prop '${name}' in group list")
      }
    }
    def toH2(prop: NodeProperty) = {
      NodePropertyHierarchy(prop, toParents(prop.name))
    }
    def toH3(name: String, globalParam: ConfigValue) = {
      toH1(name).modify(_.hierarchy).using(_ :+ ParentProperty.Global(globalParam))
    }
  }
  implicit class ToNodeProp(global: ConfigValue) {
    def toG(name: String) = {
      NodePropertyHierarchy(NodeProperty(name, global, Some(GroupProp.INHERITANCE_PROVIDER)), ParentProperty.Global(global) :: Nil)
    }
  }
  implicit class ToConfigValue(s: String) {
    def toConfigValue = ConfigValueFactory.fromAnyRef(s)
  }

  implicit class ForceGet[A](a: PureResult[A]) {
    def forceGet = a match {
      case Right(value) => value
      case Left(err)    => throw new RuntimeException(s"Error in test: forceGet a result which was in error: ${err.fullMsg}")
    }
  }

  /*
   *  Hierartchy:
   *   global
   *      |
   *   parent1       parent2
   *      |
   *   childProp
   *      |
   *    node
   */

  val parent1   = NodeGroup(NodeGroupId("parent1"), "parent1", "",
      List(GroupProperty("foo", "bar1".toConfigValue, None))
    , Some(Query(NodeReturnType, And, List()))
    , true, Set(), true
  )
  val parent2Prop = GroupProperty("foo", "bar2".toConfigValue, None)
  val parent2   = NodeGroup(NodeGroupId("parent2"), "parent2", "",
      List(parent2Prop)
    , Some(Query(NodeReturnType, And, List()))
    , true, Set(), true
  )
  val childProp = GroupProperty("foo", "baz".toConfigValue, None)
  val query = Query(NodeReturnType, And, List(parent1.toCriterion))
  val child = NodeGroup(NodeGroupId("child"), "child", "",
      List(childProp)
    , Some(query)
    , true, Set(), true
  )
  val nodeInfo = NodeConfigData.node1.modify(_.node.properties).setTo(NodeProperty("foo", "barNode".toConfigValue, None) :: Nil)

  "overriding a property in a hierarchy should work" >> {
    val merged = MergeNodeProperties.checkPropertyMerge(parent1.toTarget :: child.toTarget :: Nil, Map())
    val expected = List(child, parent1).toH1("foo") :: Nil
    merged must beRight(expected)
  }

  "if the composition is OR, subgroup must be ignored" >> {
    val ct2 = child.modify(_.query).setTo(Some(query.modify(_.composition).setTo(Or))).toTarget
    val merged = MergeNodeProperties.checkPropertyMerge(parent1.toTarget :: ct2 :: Nil, Map())
    merged must beLeft
  }

  "override is done in the same order of line, the last wins" >> {
    val q2 = query.modify(_.criteria).setTo(parent1.toCriterion::parent2.toCriterion::Nil)
    val ct2 = child
                .modify(_.query).setTo(Some(q2)) // parent 2 wins
                .modify(_.properties).setTo(Nil) // remove child property to get one of parent
                .toTarget

    val merged = MergeNodeProperties.checkPropertyMerge(parent1.toTarget :: parent2.toTarget :: ct2 :: Nil, Map())
    val expected = List(parent2, parent1).toH1("foo")
    (merged must beRight(expected :: Nil)) and (merged.getOrElse(Nil).head.prop.valueAsString === "bar2")
  }


  "when looking for a node property, we" should {

    "be able to detect conflict" in {
      val parent1 = NodeGroup(NodeGroupId("parent1"), "parent1", "",
          List(GroupProperty("dns", "1.1.1.1".toConfigValue, None))
        , Some(Query(NodeReturnType, And, List()))
        , true, Set(), true
      )
      val parent2   = NodeGroup(NodeGroupId("parent2"), "parent2", "",
          List(GroupProperty("dns", "9.9.9.9".toConfigValue, None))
        , Some(Query(NodeReturnType, And, List()))
        , true, Set(), true
      )

      val merged = MergeNodeProperties.checkPropertyMerge(parent1.toTarget :: parent2.toTarget :: Nil, Map())

      merged must beLeft[RudderError].like { case e =>
        e.fullMsg must =~("find overrides for group property 'dns'. Several groups")
      }
    }

    "be able to correct conflict" in {
      val parent1 = NodeGroup(NodeGroupId("parent1"), "parent1", "",
          List(GroupProperty("dns", "1.1.1.1".toConfigValue, None))
        , Some(Query(NodeReturnType, And, List()))
        , true, Set(), true
      )
      val parent2   = NodeGroup(NodeGroupId("parent2"), "parent2", "",
          List(GroupProperty("dns", "9.9.9.9".toConfigValue, None))
        , Some(Query(NodeReturnType, And, List()))
        , true, Set(), true
      )
      val priorize   = NodeGroup(NodeGroupId("parent3"), "parent3", "",
          Nil
        , Some(Query(NodeReturnType, And, List(parent1.toCriterion, parent2.toCriterion)))
        , true, Set(), true
      )

      val merged = MergeNodeProperties.checkPropertyMerge(parent1.toTarget :: parent2.toTarget :: priorize.toTarget :: Nil, Map())
      val expected = List(parent2, parent1).toH1("dns") :: Nil
      merged must beRight(expected)
    }

    /*
     * Test case:
     * p1: dns=1.1.1.1     p2: dns=9.9.9.9
     *           p3: p1 overriden by p2
     * p4: only subgroup of p1
     * ---------
     * node in p4 and p3
     */
    "one can solve conflicts at parent level" in {
      val parent1 = NodeGroup(NodeGroupId("parent1"), "parent1", "",
          List(GroupProperty("dns", "1.1.1.1".toConfigValue, None))
        , Some(Query(NodeReturnType, And, List()))
        , true, Set(), true
      )
      val parent2   = NodeGroup(NodeGroupId("parent2"), "parent2", "",
          List(GroupProperty("dns", "9.9.9.9".toConfigValue, None))
        , Some(Query(NodeReturnType, And, List()))
        , true, Set(), true
      )
      val priorize   = NodeGroup(NodeGroupId("parent3"), "parent3", "",
          Nil
        , Some(Query(NodeReturnType, And, List(parent1.toCriterion, parent2.toCriterion)))
        , true, Set(), true
      )
      val parent4   = NodeGroup(NodeGroupId("parent4"), "parent4", "",
          Nil
        , Some(Query(NodeReturnType, And, List(parent1.toCriterion)))
        , true, Set(), true
      )

      val merged = MergeNodeProperties.checkPropertyMerge(List(parent1, parent2, priorize, parent4).map(_.toTarget), Map())
      val expected = List(parent2, parent1).toH1("dns") :: Nil
      merged must beRight(expected)
    }
  }

  "global parameter are inherited" >> {
    val g = "bar".toConfigValue
    val merged = MergeNodeProperties.checkPropertyMerge(Nil, Map("foo" -> g))
    merged must beRight(List(g.toG("foo")))
  }

  "global parameter are inherited and overriden by group and only one time" >> {
    // empty properties, see if global is duplicated
    val p2 = parent2.copy(properties = Nil)
    val g = "bar".toConfigValue
    val merged = MergeNodeProperties.checkPropertyMerge(List(parent1, p2, child).map(_.toTarget), Map("foo" -> g))
    val expected = List(child, parent1).toH3("foo", g) :: Nil
    merged must beRight(expected)
  }

  "when overriding json we" should {
    def getOverrides(groups: List[NodeGroup]): Map[String, String] = {

      MergeNodeProperties.checkPropertyMerge(groups.map(_.toTarget), Map()) match {
        case Left(_)  => throw new IllegalArgumentException(s"Error when overriding properties")
        case Right(v) => v.map(p => (p.prop.name, GenericProperty.serializeToHocon(p.prop.value))).toMap
      }
    }
    def getGroups(parentProps: Map[String, String], childProps: Map[String, String]) = {
      def toProps(map: Map[String, String]) = map.map { case (k, v) =>
        GroupProperty.parse(k, v, None).fold(
          err => throw new IllegalArgumentException("Error in test: " + err.fullMsg)
        , res => res
        )
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
        Map("s" -> "p" , "arr" -> "[1,2]", "obj" -> """{"a":"b"}""")
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
          "arr" -> "[3,4]"
        , "obj" -> """{"a":"b","c":"d","i":"j2","x":{"y1":"z","y2":"z"}}"""
        )
      )
    }
  }

  // checking that we get the overriden value for node and groups
  "preparing value for API" should {

    "present only node value for override" in {
      val globals = Map(                                ("foo" -> GenericProperty.parseValue("""{"global":"global value", "override":"global"}""").forceGet) )
      val parent  = parent1 .modify(_.properties)     .setTo(List(GroupProperty.parse("foo", """{"parent":"parent value", "override":"parent"}""", None).forceGet))
      val child_  = child   .modify(_.properties)     .setTo(List(GroupProperty.parse("foo", """{"child" :"child value" , "override":"child" }""", None).forceGet))
      val node    = nodeInfo.modify(_.node.properties).setTo(List(NodeProperty.parse ("foo", """{"node"  :"node value"  , "override":"node"  }""", None).forceGet))
      val merged = MergeNodeProperties.forNode(node, List(parent, child_).map(_.toTarget), globals).forceGet

      val actual = merged.toApiJsonRenderParents
      val expected = JArray(List(
          ( "name"   -> "foo" )
        ~ ( "value"  -> (
              ("child"    -> "child value")
            ~ ("global"   -> "global value")
            ~ ("node"     -> "node value")
            ~ ("override" -> "node")
            ~ ("parent"   -> "parent value")
          ) )
        ~ ( "provider" -> "overridden" )
        ~ ("hierarchy" ->
            """<p>from <b>Global Parameter</b>:<pre>{
             |    "global" : "global value",
             |    "override" : "global"
             |}
             |</pre></p><p>from <b>parent1 (parent1)</b>:<pre>{
             |    "override" : "parent",
             |    "parent" : "parent value"
             |}
             |</pre></p><p>from <b>child (child)</b>:<pre>{
             |    "child" : "child value",
             |    "override" : "child"
             |}
             |</pre></p><p>from <b>this node (node1)</b>:<pre>{
             |    "node" : "node value",
             |    "override" : "node"
             |}
             |</pre></p>""".stripMargin
          )
        ~ ("origval" -> (
              ("node" -> "node value")
            ~ ("override" -> "node")
          ) )
      ))

      actual must beEqualTo(expected)
    }
  }
}


