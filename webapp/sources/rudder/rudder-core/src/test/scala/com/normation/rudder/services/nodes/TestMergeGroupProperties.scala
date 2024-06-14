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

import com.normation.GitVersion
import com.normation.errors.PureResult
import com.normation.errors.RudderError
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.policies.FullGroupTarget
import com.normation.rudder.domain.policies.FullRuleTargetInfo
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.properties.GenericProperty
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.GroupProperty
import com.normation.rudder.domain.properties.InheritMode
import com.normation.rudder.domain.properties.JsonPropertySerialisation.*
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.domain.properties.NodePropertyHierarchy
import com.normation.rudder.domain.properties.ParentProperty
import com.normation.rudder.domain.properties.PropertyProvider
import com.normation.rudder.domain.queries.*
import com.normation.rudder.domain.queries.ResultTransformation.*
import com.normation.rudder.services.policies.NodeConfigData
import com.softwaremill.quicklens.*
import com.typesafe.config.ConfigValue
import com.typesafe.config.ConfigValueFactory
import net.liftweb.json.*
import net.liftweb.json.JsonDSL.*
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*

/*
 * This class test the JsEngine. 6.0
 * It must works identically on Java 7 and Java 8.
 *
 */

@RunWith(classOf[JUnitRunner])
class TestMergeGroupProperties extends Specification {

  sequential

  implicit class ToTarget(g: NodeGroup) {
    def toTarget:    FullRuleTargetInfo =
      FullRuleTargetInfo(FullGroupTarget(GroupTarget(g.id), g), g.name, "", isEnabled = true, isSystem = true)
    def toCriterion: CriterionLine      =
      CriterionLine(null, Criterion("some ldap attr", SubGroupComparator(null), null), null, g.id.serialize)
  }

  implicit class ToNodePropertyHierarchy(groups: List[NodeGroup]) {
    def toParents(name: String):                      List[ParentProperty.Group] = {
      groups.flatMap(g => g.properties.find(_.name == name).map(p => ParentProperty.Group(g.name, g.id, p.value)))
    }
    // use first parent to build a fully inherited prop
    def toH1(name: String):                           NodePropertyHierarchy      = {
      toParents(name) match {
        case h :: t => NodePropertyHierarchy(NodeProperty(name, h.value, None, Some(GroupProp.INHERITANCE_PROVIDER)), h :: t)
        case _      => throw new IllegalArgumentException(s"No value found for prop '${name}' in group list")
      }
    }
    def toH2(prop: NodeProperty):                     NodePropertyHierarchy      = {
      com.normation.rudder.domain.properties.NodePropertyHierarchy(prop, toParents(prop.name))
    }
    def toH3(name: String, globalParam: ConfigValue): NodePropertyHierarchy      = {
      toH1(name).modify(_.hierarchy).using(_ :+ ParentProperty.Global(globalParam))
    }
  }
  implicit class ToNodeProp(global: ConfigValue)                  {
    def toG(name: String):  NodePropertyHierarchy = {
      NodePropertyHierarchy(
        NodeProperty(name, global, None, Some(GroupProp.INHERITANCE_PROVIDER)),
        ParentProperty.Global(global) :: Nil
      )
    }
    def toGP(name: String): GlobalParameter       = {
      GlobalParameter(name, GitVersion.DEFAULT_REV, global, None, "", None)
    }
  }
  implicit class ToConfigValue(s: String)                         {
    def toConfigValue: ConfigValue = ConfigValueFactory.fromAnyRef(s)
  }

  implicit class ForceGet[A](a: PureResult[A]) {
    def forceGet: A = a match {
      case Right(value) => value
      case Left(err)    => throw new RuntimeException(s"Error in test: forceGet a result which was in error: ${err.fullMsg}")
    }
  }

  /*
   *  Hierarchy:
   *   global
   *      |
   *   parent1       parent2
   *      |
   *   childProp
   *      |
   *    node
   */

  val parent1: NodeGroup = NodeGroup(
    NodeGroupId(NodeGroupUid("parent1")),
    name = "parent1",
    description = "",
    properties = List(GroupProperty("foo", GitVersion.DEFAULT_REV, "bar1".toConfigValue, None, None)),
    query = Some(Query(NodeReturnType, And, Identity, List())),
    isDynamic = true,
    serverList = Set(),
    _isEnabled = true
  )
  val parent2Prop = GroupProperty("foo", GitVersion.DEFAULT_REV, "bar2".toConfigValue, None, None)
  val parent2: NodeGroup = NodeGroup(
    NodeGroupId(NodeGroupUid("parent2")),
    name = "parent2",
    description = "",
    properties = List(parent2Prop),
    query = Some(Query(NodeReturnType, And, Identity, List())),
    isDynamic = true,
    serverList = Set(),
    _isEnabled = true
  )
  val childProp = GroupProperty("foo", GitVersion.DEFAULT_REV, "baz".toConfigValue, None, None)
  val query: Query     = Query(NodeReturnType, And, Identity, List(parent1.toCriterion))
  val child: NodeGroup = {
    NodeGroup(
      NodeGroupId(NodeGroupUid("child")),
      name = "child",
      description = "",
      properties = List(childProp),
      query = Some(query),
      isDynamic = true,
      serverList = Set(),
      _isEnabled = true
    )
  }
  val nodeInfo =
    NodeConfigData.node1.modify(_.node.properties).setTo(NodeProperty("foo", "barNode".toConfigValue, None, None) :: Nil)

  "overriding a property in a hierarchy should work" >> {
    val merged   = MergeNodeProperties.checkPropertyMerge(parent1.toTarget :: child.toTarget :: Nil, Map())
    val expected = List(child, parent1).toH1("foo") :: Nil
    merged must beRight(expected)
  }

  "if the composition is OR, subgroup must be ignored" >> {
    val ct2    = child.modify(_.query).setTo(Some(query.modify(_.composition).setTo(Or))).toTarget
    val merged = MergeNodeProperties.checkPropertyMerge(parent1.toTarget :: ct2 :: Nil, Map())
    merged must beLeft
  }

  "when the parent is in not in an inverted query and is missing, its an error" >> {
    val merged = MergeNodeProperties.checkPropertyMerge(child.toTarget :: Nil, Map())
    merged must beLeft
  }

  "when the parent is in an inverted query, its properties are not inherited" >> {
    val ct2      = child.modify(_.query).setTo(Some(query.modify(_.transform).setTo(ResultTransformation.Invert))).toTarget
    val merged   = MergeNodeProperties.checkPropertyMerge(ct2 :: Nil, Map())
    val expected = List(child).toH1("foo")
    (merged must beRight(expected :: Nil)) and (merged.getOrElse(Nil).head.prop.valueAsString === "baz")
  }

  "override is done in the same order of line, the last wins" >> {
    val q2  = query.modify(_.criteria).setTo(parent1.toCriterion :: parent2.toCriterion :: Nil)
    val ct2 = child
      .modify(_.query)
      .setTo(Some(q2)) // parent 2 wins
      .modify(_.properties)
      .setTo(Nil)      // remove child property to get one of parent
      .toTarget

    val merged   = MergeNodeProperties.checkPropertyMerge(parent1.toTarget :: parent2.toTarget :: ct2 :: Nil, Map())
    val expected = List(parent2, parent1).toH1("foo")
    (merged must beRight(expected :: Nil)) and (merged.getOrElse(Nil).head.prop.valueAsString === "bar2")
  }

  "when looking for a node property, we" should {

    "be able to detect conflict" in {
      val parent1 = NodeGroup(
        NodeGroupId(NodeGroupUid("parent1")),
        name = "parent1",
        description = "",
        properties = List(GroupProperty("dns", GitVersion.DEFAULT_REV, "1.1.1.1".toConfigValue, None, None)),
        query = Some(Query(NodeReturnType, And, Identity, List())),
        isDynamic = true,
        serverList = Set(),
        _isEnabled = true
      )
      val parent2 = NodeGroup(
        NodeGroupId(NodeGroupUid("parent2")),
        name = "parent2",
        description = "",
        properties = List(GroupProperty("dns", GitVersion.DEFAULT_REV, "9.9.9.9".toConfigValue, None, None)),
        query = Some(Query(NodeReturnType, And, Identity, List())),
        isDynamic = true,
        serverList = Set(),
        _isEnabled = true
      )

      val merged = MergeNodeProperties.checkPropertyMerge(parent1.toTarget :: parent2.toTarget :: Nil, Map())

      merged must beLeft[RudderError].like {
        case e =>
          e.fullMsg must =~("find overrides for group property 'dns'. Several groups")
      }
    }

    "be able to correct conflict" in {
      val parent1    = NodeGroup(
        NodeGroupId(NodeGroupUid("parent1")),
        name = "parent1",
        description = "",
        properties = List(GroupProperty("dns", GitVersion.DEFAULT_REV, "1.1.1.1".toConfigValue, None, None)),
        query = Some(Query(NodeReturnType, And, Identity, List())),
        isDynamic = true,
        serverList = Set(),
        _isEnabled = true
      )
      val parent2    = NodeGroup(
        NodeGroupId(NodeGroupUid("parent2")),
        name = "parent2",
        description = "",
        properties = List(GroupProperty("dns", GitVersion.DEFAULT_REV, "9.9.9.9".toConfigValue, None, None)),
        query = Some(Query(NodeReturnType, And, Identity, List())),
        isDynamic = true,
        serverList = Set(),
        _isEnabled = true
      )
      val prioritize = NodeGroup(
        NodeGroupId(NodeGroupUid("parent3")),
        name = "parent3",
        description = "",
        properties = Nil,
        query = Some(Query(NodeReturnType, And, Identity, List(parent1.toCriterion, parent2.toCriterion))),
        isDynamic = true,
        serverList = Set(),
        _isEnabled = true
      )

      val merged   =
        MergeNodeProperties.checkPropertyMerge(parent1.toTarget :: parent2.toTarget :: prioritize.toTarget :: Nil, Map())
      val expected = List(parent2, parent1).toH1("dns") :: Nil
      merged must beRight(expected)
    }

    /*
     * Test case:
     * p1: dns=1.1.1.1     p2: dns=9.9.9.9
     *           p3: p1 overridden by p2
     * p4: only subgroup of p1
     * ---------
     * node in p4 and p3
     */
    "one can solve conflicts at parent level" in {
      val parent1    = NodeGroup(
        NodeGroupId(NodeGroupUid("parent1")),
        name = "parent1",
        description = "",
        properties = List(GroupProperty("dns", GitVersion.DEFAULT_REV, "1.1.1.1".toConfigValue, None, None)),
        query = Some(Query(NodeReturnType, And, Identity, List())),
        isDynamic = true,
        serverList = Set(),
        _isEnabled = true
      )
      val parent2    = NodeGroup(
        NodeGroupId(NodeGroupUid("parent2")),
        name = "parent2",
        description = "",
        properties = List(GroupProperty("dns", GitVersion.DEFAULT_REV, "9.9.9.9".toConfigValue, None, None)),
        query = Some(Query(NodeReturnType, And, Identity, List())),
        isDynamic = true,
        serverList = Set(),
        _isEnabled = true
      )
      val prioritize = NodeGroup(
        NodeGroupId(NodeGroupUid("parent3")),
        name = "parent3",
        description = "",
        properties = Nil,
        query = Some(Query(NodeReturnType, And, Identity, List(parent1.toCriterion, parent2.toCriterion))),
        isDynamic = true,
        serverList = Set(),
        _isEnabled = true
      )
      val parent4    = NodeGroup(
        NodeGroupId(NodeGroupUid("parent4")),
        name = "parent4",
        description = "",
        properties = Nil,
        query = Some(Query(NodeReturnType, And, Identity, List(parent1.toCriterion))),
        isDynamic = true,
        serverList = Set(),
        _isEnabled = true
      )

      val merged   = MergeNodeProperties.checkPropertyMerge(List(parent1, parent2, prioritize, parent4).map(_.toTarget), Map())
      val expected = List(parent2, parent1).toH1("dns") :: Nil
      merged must beRight(expected)
    }
  }

  "global parameter are inherited" >> {
    val g      = "bar".toConfigValue
    val merged = MergeNodeProperties.checkPropertyMerge(Nil, Map("foo" -> g.toGP("foo")))
    merged must beRight(List(g.toG("foo")))
  }

  "global parameter are inherited and overridden by group and only one time" >> {
    // empty properties, see if global is duplicated
    val p2       = parent2.copy(properties = Nil)
    val g        = "bar".toConfigValue
    val merged   = MergeNodeProperties.checkPropertyMerge(List(parent1, p2, child).map(_.toTarget), Map("foo" -> g.toGP("foo")))
    val expected = List(child, parent1).toH3("foo", g) :: Nil
    merged must beRight(expected)
  }

  "global parameter are inherited and inherit mode is used at any level of merge" >> {
    val globalProperty           = """["glob"]""".toConfigValue
    val parent1Properties        = List(GroupProperty.parse("foo", GitVersion.DEFAULT_REV, """["p1"]""", None, None).forceGet)
    val parent2Properties        = List(GroupProperty.parse("foo", GitVersion.DEFAULT_REV, """["p2"]""", None, None).forceGet)
    // child property to check that it still gets merged with all parent ones
    val childOfParent2Properties = List(GroupProperty.parse("foo", GitVersion.DEFAULT_REV, """["node"]""", None, None).forceGet)

    val p1         = parent1.modify(_.properties).setTo(parent1Properties)
    val p2Query    = query.modify(_.criteria).setTo(parent1.toCriterion :: Nil)
    val p2         = parent2.modify(_.query).setTo(Some(p2Query)).modify(_.properties).setTo(parent2Properties)
    val childQuery = query.modify(_.criteria).setTo(parent2.toCriterion :: Nil)
    val c          = child
      .modify(_.query)
      .setTo(Some(childQuery)) // parent 2 wins
      .modify(_.properties)
      .setTo(childOfParent2Properties)

    val maaInheritMode = Some(InheritMode.parseString("maa").forceGet)
    val mpaInheritMode = Some(InheritMode.parseString("mpa").forceGet)

    // global property is not yet supposed to be merged now, but added in mergeDefault instead. However it is added to hierarchy if it exists
    def beMerged(configValue: ConfigValue, mode: Option[InheritMode], globalProperty: Option[ConfigValue]) = {
      (haveLength[List[NodePropertyHierarchy]](1)) and
      (beEqualTo(
        GenericProperty.toConfig("foo", GitVersion.DEFAULT_REV, configValue, mode, Some(PropertyProvider("inherited")), None)
      ) ^^ { (l: List[NodePropertyHierarchy]) => l.head.prop.config }) and
      (beEqualTo(globalProperty match {
        case Some(prop) => List(c, p2, p1).toH3("foo", prop).hierarchy
        case None       => List(c, p2, p1).toH1("foo").hierarchy
      }) ^^ { (l: List[NodePropertyHierarchy]) => l.head.hierarchy })
    }

    "global append mode" in {
      val merged = MergeNodeProperties.checkPropertyMerge(
        List(p1.toTarget, p2.toTarget, c.toTarget),
        Map(
          "foo" -> GlobalParameter(
            "foo",
            GitVersion.DEFAULT_REV,
            globalProperty,
            maaInheritMode,
            "",
            None
          )
        )
      )

      (merged must beRight(
        beMerged(
          ConfigValueFactory.fromIterable(java.util.Arrays.asList("p1", "p2", "node")),
          maaInheritMode,
          Some(globalProperty)
        )
      ))
    }
    "global prepend mode" in {
      val merged = MergeNodeProperties.checkPropertyMerge(
        List(p1.toTarget, p2.toTarget, c.toTarget),
        Map(
          "foo" -> GlobalParameter(
            "foo",
            GitVersion.DEFAULT_REV,
            globalProperty,
            mpaInheritMode,
            "",
            None
          )
        )
      )
      (merged must beRight(
        beMerged(
          ConfigValueFactory.fromIterable(java.util.Arrays.asList("node", "p2", "p1")),
          mpaInheritMode,
          Some(globalProperty)
        )
      ))
    }

    "none global mode with default 'override' mode" in {
      val merged = MergeNodeProperties.checkPropertyMerge(
        List(p1.toTarget, p2.toTarget, c.toTarget),
        Map.empty
      )
      (merged must beRight(
        beMerged(ConfigValueFactory.fromIterable(java.util.Arrays.asList("node")), None, None)
      ))
    }
  }

  "when overriding json we" should {
    def getOverrides(groups: List[NodeGroup]): Map[String, String] = {

      MergeNodeProperties.checkPropertyMerge(groups.map(_.toTarget), Map()) match {
        case Left(e)  => throw new IllegalArgumentException(s"Error when overriding properties: ${e.fullMsg}")
        case Right(v) => v.map(p => (p.prop.name, GenericProperty.serializeToHocon(p.prop.value))).toMap
      }
    }
    def getGroups(parentProps: Map[String, String], childProps: Map[String, String], inheritModes: Map[String, String]) = {
      def toProps(map: Map[String, String]) = map.map {
        case (k, v) =>
          GroupProperty
            .parse(k, GitVersion.DEFAULT_REV, v, InheritMode.parseString(inheritModes.getOrElse(k, "")).toOption, None)
            .fold(
              err => throw new IllegalArgumentException("Error in test: " + err.fullMsg),
              res => res
            )
      }.toList
      val parent                            = NodeGroup(
        NodeGroupId(NodeGroupUid("parent1")),
        name = "parent1",
        description = "",
        properties = toProps(parentProps),
        query = Some(Query(NodeReturnType, And, Identity, List())),
        isDynamic = true,
        serverList = Set(),
        _isEnabled = true
      )
      val child                             = NodeGroup(
        NodeGroupId(NodeGroupUid("child")),
        name = "child",
        description = "",
        properties = toProps(childProps),
        query = Some(Query(NodeReturnType, And, Identity, List(parent1.toCriterion))),
        isDynamic = true,
        serverList = Set(),
        _isEnabled = true
      )
      parent :: child :: Nil
    }
    def checkOverrides(
        parentProps:  Map[String, String],
        childProps:   Map[String, String],
        inheritModes: Map[String, String] = Map()
    ) = {
      getOverrides(getGroups(parentProps, childProps, inheritModes))
    }
    "override whatever by simple values" in {
      val child =
        Map("s" -> "c", "arr" -> """[-1]""", "obj" -> """{"a":"c"}""")
      val props = checkOverrides(
        Map("s" -> "p", "arr" -> "[1,2]", "obj" -> """{"a":"b"}"""),
        child
      )
      props must beEqualTo(child)
    }
    "merge arr and objects" in {
      // option can be specified only has first characters only, and behavior is inherited everywhere
      val props = checkOverrides(
        Map("arr" -> "[1,2]", "obj" -> """{"a":"b", "i":"j1", "x":{"y1":"z"}, "z":[2]}"""),
        Map("arr" -> "[3,4]", "obj" -> """{"c":"d", "i":"j2", "x":{"y2":"z"}, "z":[1]}"""), //

        Map("arr" -> "maa", "obj" -> "mpo") // inherit modes, a(ppend) for array/string in "arr", p(repend) for arr in obj
      )
      props must beEqualTo(
        Map(
          "arr" -> "[1,2,3,4]",
          "obj" -> """{"a":"b","c":"d","i":"j2","x":{"y1":"z","y2":"z"},"z":[1,2]}"""
        )
      )
    }
  }

  // checking that we get the overridden value for node and groups
  "preparing value for API" should {

    "present only node value for override" in {
      val globals = Map(
        ("foo" -> GlobalParameter(
          "foo",
          GitVersion.DEFAULT_REV,
          GenericProperty.parseValue("""{"global":"global value", "override":"global"}""").forceGet,
          None,
          "",
          None
        ))
      )
      val parent  = parent1
        .modify(_.properties)
        .setTo(
          List(
            GroupProperty
              .parse("foo", GitVersion.DEFAULT_REV, """{"parent":"parent value", "override":"parent"}""", None, None)
              .forceGet
          )
        )
      val child_  = child
        .modify(_.properties)
        .setTo(
          List(
            GroupProperty
              .parse("foo", GitVersion.DEFAULT_REV, """{"child" :"child value" , "override":"child" }""", None, None)
              .forceGet
          )
        )
      val node    = nodeInfo
        .modify(_.node.properties)
        .setTo(List(NodeProperty.parse("foo", """{"node"  :"node value"  , "override":"node"  }""", None, None).forceGet))
      val merged  = MergeNodeProperties.forNode(node, List(parent, child_).map(_.toTarget), globals).forceGet

      val actual   = merged.toApiJsonRenderParents
      val expected = JArray(
        List(
          ("name"          -> "foo")
          ~ ("value"       -> (
            ("child"       -> "child value")
            ~ ("global"    -> "global value")
            ~ ("node"      -> "node value")
            ~ ("override"  -> "node")
            ~ ("parent"    -> "parent value")
          ))
          ~ ("provider"    -> "overridden")
          ~ ("inheritMode" -> JNothing) // I don't understand why I need to add it
          ~ ("hierarchy"   ->
          """<p>from <b>Global Parameter</b>:<pre>{
            |    &quot;global&quot; : &quot;global value&quot;,
            |    &quot;override&quot; : &quot;global&quot;
            |}
            |</pre></p><p>from <b>parent1 (parent1)</b>:<pre>{
            |    &quot;override&quot; : &quot;parent&quot;,
            |    &quot;parent&quot; : &quot;parent value&quot;
            |}
            |</pre></p><p>from <b>child (child)</b>:<pre>{
            |    &quot;child&quot; : &quot;child value&quot;,
            |    &quot;override&quot; : &quot;child&quot;
            |}
            |</pre></p><p>from <b>this node (node1)</b>:<pre>{
            |    &quot;node&quot; : &quot;node value&quot;,
            |    &quot;override&quot; : &quot;node&quot;
            |}
            |</pre></p>""".stripMargin)
          ~ ("origval"     -> (
            ("node"        -> "node value")
            ~ ("override"  -> "node")
          ))
        )
      )

      actual must beEqualTo(expected)
    }
  }
}
