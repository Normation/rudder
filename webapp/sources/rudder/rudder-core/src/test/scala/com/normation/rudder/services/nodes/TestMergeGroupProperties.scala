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

import cats.data.Ior
import com.normation.GitVersion
import com.normation.errors.PureResult
import com.normation.inventory.domain.AcceptedInventory
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.policies.FullGroupTarget
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.properties.FailedNodePropertyHierarchy
import com.normation.rudder.domain.properties.GenericProperty
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.GroupProperty
import com.normation.rudder.domain.properties.GroupPropertyHierarchy
import com.normation.rudder.domain.properties.InheritMode
import com.normation.rudder.domain.properties.JsonPropertyHierarchySerialisation.*
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.domain.properties.NodePropertyHierarchy
import com.normation.rudder.domain.properties.ParentProperty
import com.normation.rudder.domain.properties.ParentProperty.VertexParentProperty
import com.normation.rudder.domain.properties.PropertyHierarchy
import com.normation.rudder.domain.properties.PropertyHierarchyError
import com.normation.rudder.domain.properties.SuccessNodePropertyHierarchy
import com.normation.rudder.domain.properties.Visibility
import com.normation.rudder.domain.queries.*
import com.normation.rudder.domain.queries.CriterionComposition.*
import com.normation.rudder.domain.queries.QueryReturnType.*
import com.normation.rudder.domain.queries.ResultTransformation.*
import com.normation.rudder.facts.nodes.NodeFact
import com.normation.rudder.properties.GroupProp.*
import com.normation.rudder.properties.MergeNodeProperties
import com.normation.rudder.services.policies.NodeConfigData
import com.softwaremill.quicklens.*
import com.typesafe.config.ConfigValue
import com.typesafe.config.ConfigValueFactory
import org.json4s.*
import org.json4s.JsonDSL.*
import org.junit.runner.*
import org.specs2.matcher.Matcher
import org.specs2.mutable.*
import org.specs2.runner.*
import scala.reflect.ClassTag
import zio.Chunk
import zio.NonEmptyChunk

@RunWith(classOf[JUnitRunner])
class TestMergeGroupProperties extends Specification {

  sequential

  implicit class ToTarget(g: NodeGroup) {
    def toTarget:    FullGroupTarget =
      FullGroupTarget(GroupTarget(g.id), g)
    def toCriterion: CriterionLine   = {
      val criterion = Criterion("some ldap attr", SubGroupComparator(() => null), AlwaysFalse("for tests"))
      CriterionLine(ObjectCriterion("any criterion", criterion :: Nil), criterion, Equals, g.id.serialize)
    }
  }

  implicit class ToPropertyHierarchy(groups: List[NodeGroup]) {
    def toParents(name: String):                                   ParentProperty.Group = {
      groups.reverse
        .flatMap(g => g.properties.find(_.name == name).map(p => ParentProperty.Group(g.name, g.id, p, None)))
        .reduce((old, newer) => newer.copy(parentProperty = Some(old)))
    }
    // use first parent to build a fully inherited prop
    def toH1(id: Either[NodeGroupId, NodeId], name: String):       PropertyHierarchy    = {
      id match {
        case Left(gid)  => GroupPropertyHierarchy(gid, toParents(name))
        case Right(nid) => NodePropertyHierarchy(nid, toParents(name))
      }
    }
    def toH2(id: Either[NodeGroupId, NodeId], prop: NodeProperty): PropertyHierarchy    = {
      id match {
        case Left(gid)  => GroupPropertyHierarchy(gid, toParents(prop.name))
        case Right(nid) => NodePropertyHierarchy(nid, toParents(prop.name))
      }
    }

    def toH3(id: Either[NodeGroupId, NodeId], name: String, globalParam: GlobalParameter): PropertyHierarchy = {

      def recAppendParent(prop: VertexParentProperty[?]): VertexParentProperty[?] = {
        prop match {
          case global: ParentProperty.Global => global
          case group:  ParentProperty.Group  =>
            group.parentProperty match {
              case None        => group.copy(parentProperty = Some(ParentProperty.Global(globalParam)))
              case Some(value) => group.copy(parentProperty = Some(recAppendParent(value)))
            }
        }
      }
      def recAppend(prop: ParentProperty[?]):             ParentProperty[?]       = {
        prop match {
          case global: ParentProperty.Global => global
          case group:  ParentProperty.Group  =>
            group.parentProperty match {
              case None        => group.copy(parentProperty = Some(ParentProperty.Global(globalParam)))
              case Some(value) => group.copy(parentProperty = Some(recAppendParent(value)))
            }

          case node: ParentProperty.Node =>
            node.parentProperty match {
              case None        => node.copy(parentProperty = Some(ParentProperty.Global(globalParam)))
              case Some(value) => node.copy(parentProperty = Some(recAppendParent(value)))
            }
        }
      }
      toH1(id, name) match {
        case g: GroupPropertyHierarchy => g.modify(_.hierarchy).using(recAppendParent)
        case g: NodePropertyHierarchy  => g.modify(_.hierarchy).using(recAppend)
      }
    }
  }
  implicit class ToNodeProp(global: ConfigValue)              {
    def toG(name: String, mode: Option[InheritMode], nodeId: NodeId): PropertyHierarchy = {
      NodePropertyHierarchy(
        nodeId,
        ParentProperty.Global(GlobalParameter(name, GitVersion.DEFAULT_REV, global, mode, "", None, Visibility.default, None))
      )
    }
    def toGP(name: String, mode: Option[InheritMode]):                GlobalParameter   = {
      GlobalParameter(name, GitVersion.DEFAULT_REV, global, mode, "", None, Visibility.default, None)
    }
  }
  implicit class ToConfigValue(s: String)                     {
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
   *   parent1       parent2
   *    (bar1)        (bar2)
   *      |
   *    child
   *    (baz)
   *      |
   *     node
   *  (barNode)
   */
  val parent1: NodeGroup = NodeGroup(
    NodeGroupId(NodeGroupUid("parent1")),
    name = "parent1",
    description = "",
    properties = List(GroupProperty("foo", GitVersion.DEFAULT_REV, "bar1".toConfigValue, None, None)),
    query = Some(Query(NodeReturnType, And, Identity, List())),
    isDynamic = true,
    serverList = Set(),
    _isEnabled = true,
    security = None
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
    _isEnabled = true,
    security = None
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
      _isEnabled = true,
      security = None
    )
  }
  val nodeInfo =
    NodeConfigData.node1.modify(_.node.properties).setTo(NodeProperty("foo", "barNode".toConfigValue, None, None) :: Nil)
  val nodeId1  = NodeConfigData.node1.id
  "overriding a property in a hierarchy should work" >> {
    val merged: Ior[PropertyHierarchyError, List[PropertyHierarchy]] = MergeNodeProperties
      .checkPropertyMerge(
        Map(parent1.id -> parent1.toGroupProp, parent2.id -> parent2.toGroupProp, child.id -> child.toGroupProp),
        Map()
      )
      .map(_.map(n => NodePropertyHierarchy(nodeId1, n)))
    // there is a conflict between parent2 (bar2) and child (baz) < parent1 (bar1)
    val expectedHierarchy = List(child, parent1).toParents("foo")
    merged must beBoth(
      PropertyHierarchyError.PropertyHierarchySpecificInheritanceConflicts(
        Map(
          expectedHierarchy.resolvedValue ->
          NonEmptyChunk(expectedHierarchy, List(parent2).toParents("foo"))
        )
      ),
      List.empty
    )
  }

  "if the composition is OR, subgroup must be ignored" >> {
    val ct2          = child.modify(_.query).setTo(Some(query.modify(_.composition).setTo(Or)))
    val merged       = {
      MergeNodeProperties
        .checkPropertyMerge(Map(parent1.id -> parent1.toGroupProp, ct2.id -> ct2.toGroupProp), Map())
        .map(_.map(n => NodePropertyHierarchy(nodeId1, n)))
    }
    // it appears as an inheritance conflict
    val expectedProp = List(parent1).toParents("foo")
    merged must beBoth(
      PropertyHierarchyError.PropertyHierarchySpecificInheritanceConflicts(
        Map(expectedProp.resolvedValue -> NonEmptyChunk(expectedProp, List(ct2).toParents("foo")))
      ),
      List.empty
    )
  }

  "when the parent is in not in an inverted query and is missing, its an error" >> {
    val merged = {
      MergeNodeProperties
        .checkPropertyMerge(Map(child.id -> child.toGroupProp), Map())
        .map(_.map(n => NodePropertyHierarchy(nodeId1, n)))
    }
    merged must beBoth[PropertyHierarchyError.DAGHierarchyError](List.empty)
    merged.left must beLike {
      case Some(err: PropertyHierarchyError.DAGHierarchyError) =>
        (err.message must =~("Error when looking for parent group 'parent1' of group 'child'")) and
        (err.message must =~("Please check criterium for that group"))
    }
  }

  "when the parent is in an inverted query, its properties are not inherited" >> {
    val ct2      = child.modify(_.query).setTo(Some(query.modify(_.transform).setTo(ResultTransformation.Invert)))
    val merged   = {
      MergeNodeProperties
        .checkPropertyMerge(Map(ct2.id -> ct2.toGroupProp), Map())
        .map(_.map(n => NodePropertyHierarchy(nodeId1, n)))
    }
    val expected = List(ct2).toH1(Right(nodeId1), "foo")
    (merged must beRight(expected :: Nil)) and (merged.getOrElse(Nil).head.prop.valueAsString === "baz")
  }

  "override is done in the same order of line, the last wins" >> {
    /*   parent1     parent2
     *      |           |
     *       \         /
     *         \     /
     *          child
     *            |
     *           node
     */
    val q2  = query.modify(_.criteria).setTo(parent1.toCriterion :: parent2.toCriterion :: Nil)
    val ct2 = child
      .modify(_.query)
      .setTo(Some(q2)) // parent 2 wins
      .modify(_.properties)
      .setTo(Nil)      // remove child property to get one of parent

    val merged   = MergeNodeProperties
      .checkPropertyMerge(
        Map(parent1.id -> parent1.toGroupProp, parent2.id -> parent2.toGroupProp, ct2.id -> ct2.toGroupProp),
        Map()
      )
      .map(_.map(n => NodePropertyHierarchy(nodeId1, n)))
    val expected = List(parent2, parent1).toH1(Right(nodeId1), "foo")
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
        _isEnabled = true,
        security = None
      )
      val parent2 = NodeGroup(
        NodeGroupId(NodeGroupUid("parent2")),
        name = "parent2",
        description = "",
        properties = List(GroupProperty("dns", GitVersion.DEFAULT_REV, "9.9.9.9".toConfigValue, None, None)),
        query = Some(Query(NodeReturnType, And, Identity, List())),
        isDynamic = true,
        serverList = Set(),
        _isEnabled = true,
        security = None
      )

      val merged = {
        MergeNodeProperties
          .checkPropertyMerge(Map(parent1.id -> parent1.toGroupProp, parent2.id -> parent2.toGroupProp), Map())
      }.map(_.map(n => NodePropertyHierarchy(nodeId1, n)))

      val expectedProp = List(parent1).toParents("dns")
      merged must beBoth(
        PropertyHierarchyError.PropertyHierarchySpecificInheritanceConflicts(
          Map(
            expectedProp.resolvedValue ->
            NonEmptyChunk(expectedProp, List(parent2).toParents("dns"))
          )
        ),
        List.empty
      )
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
        _isEnabled = true,
        security = None
      )
      val parent2    = NodeGroup(
        NodeGroupId(NodeGroupUid("parent2")),
        name = "parent2",
        description = "",
        properties = List(GroupProperty("dns", GitVersion.DEFAULT_REV, "9.9.9.9".toConfigValue, None, None)),
        query = Some(Query(NodeReturnType, And, Identity, List())),
        isDynamic = true,
        serverList = Set(),
        _isEnabled = true,
        security = None
      )
      val prioritize = NodeGroup(
        NodeGroupId(NodeGroupUid("parent3")),
        name = "parent3",
        description = "",
        properties = Nil,
        query = Some(Query(NodeReturnType, And, Identity, List(parent1.toCriterion, parent2.toCriterion))),
        isDynamic = true,
        serverList = Set(),
        _isEnabled = true,
        security = None
      )

      val merged   = {
        MergeNodeProperties
          .checkPropertyMerge(
            Map(parent1.id -> parent1.toGroupProp, parent2.id -> parent2.toGroupProp, prioritize.id -> prioritize.toGroupProp),
            Map()
          )
      }.map(_.map(n => NodePropertyHierarchy(nodeId1, n)))
      val expected = List(parent2, parent1).toH1(Right(nodeId1), "dns") :: Nil
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
        _isEnabled = true,
        security = None
      )
      val parent2    = NodeGroup(
        NodeGroupId(NodeGroupUid("parent2")),
        name = "parent2",
        description = "",
        properties = List(GroupProperty("dns", GitVersion.DEFAULT_REV, "9.9.9.9".toConfigValue, None, None)),
        query = Some(Query(NodeReturnType, And, Identity, List())),
        isDynamic = true,
        serverList = Set(),
        _isEnabled = true,
        security = None
      )
      val prioritize = NodeGroup(
        NodeGroupId(NodeGroupUid("parent3")),
        name = "parent3",
        description = "",
        properties = Nil,
        query = Some(Query(NodeReturnType, And, Identity, List(parent1.toCriterion, parent2.toCriterion))),
        isDynamic = true,
        serverList = Set(),
        _isEnabled = true,
        security = None
      )
      val parent4    = NodeGroup(
        NodeGroupId(NodeGroupUid("parent4")),
        name = "parent4",
        description = "",
        properties = Nil,
        query = Some(Query(NodeReturnType, And, Identity, List(parent1.toCriterion))),
        isDynamic = true,
        serverList = Set(),
        _isEnabled = true,
        security = None
      )

      val merged   = MergeNodeProperties
        .checkPropertyMerge(
          Map(
            parent1.id    -> parent1.toGroupProp,
            parent2.id    -> parent2.toGroupProp,
            prioritize.id -> prioritize.toGroupProp,
            parent4.id    -> parent4.toGroupProp
          ),
          Map()
        )
        .map(_.map(n => NodePropertyHierarchy(nodeId1, n)))
      val expected = List(parent2, parent1).toH1(Right(nodeId1), "dns") :: Nil
      merged must beRight(expected)
    }
  }

  "when computing group properties" should {
    "have error when parent group is missing" >> {
      val res = MergeNodeProperties.forGroup(child.toTarget, Map(child.id -> child.toTarget), Map())
      res must beEqualTo(
        FailedNodePropertyHierarchy(
          Chunk.empty,
          PropertyHierarchyError.MissingParentGroup(child.id, child.name, parent1.id.serialize),
          ""
        )
      )
    }

    /*   parent1  <  parent2
     *      |           |
     *       \         /
     *         \     /
     *          child
     */
    "resolve conflict on parent groups by taking most overriding group" >> {
      val q2  = query.modify(_.criteria).setTo(parent1.toCriterion :: parent2.toCriterion :: Nil)
      val ct2 = child
        .modify(_.id.uid.value)
        .setTo("ct2")
        .modify(_.query)
        .setTo(Some(q2)) // parent 2 wins
        .modify(_.properties)
        .setTo(Nil)

      val res = MergeNodeProperties.forGroup(
        ct2.toTarget,
        Map(parent1.id -> parent1.toTarget, parent2.id -> parent2.toTarget, ct2.id -> ct2.toTarget),
        Map()
      )
      // ct2 is resolving conflict by assigning priority to parent2
      res must beEqualTo(SuccessNodePropertyHierarchy(Chunk(List(parent2).toH1(Left(ct2.id), "foo"))))
    }

  }

  "global parameter are inherited" >> {
    val g      = "bar".toConfigValue
    val merged = MergeNodeProperties
      .checkPropertyMerge(Map.empty, Map("foo" -> g.toGP("foo", None)))
      .map(_.map(n => NodePropertyHierarchy(nodeId1, n)))
    merged must beRight(List(g.toG("foo", None, nodeId1)))
  }

  /*
   *  Hierarchy:
   *    global
   *    (bar)
   *      |
   *   parent1       parent2
   *    (bar1)
   *      |
   *    child
   *    (baz)
   */
  "global parameter are inherited and overridden by group and only one time" >> {
    // empty properties, see if global is duplicated
    val p2       = parent2.copy(properties = Nil)
    val g        = "bar".toConfigValue
    val merged   = MergeNodeProperties
      .checkPropertyMerge(
        Map(parent1.id -> parent1.toGroupProp, p2.id -> p2.toGroupProp, child.id -> child.toGroupProp),
        Map("foo"      -> g.toGP("foo", None))
      )
      .map(_.map(n => NodePropertyHierarchy(nodeId1, n)))
    val expected = List(child, parent1).toH3(Right(nodeId1), "foo", g.toGP("foo", None)) :: Nil
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
      (haveLength[List[PropertyHierarchy]](1)) and /*ç
      (beEqualTo(
        GenericProperty
          .toConfig("foo", GitVersion.DEFAULT_REV, configValue, mode, Some(PropertyProvider("inherited")), None, None)
      ) ^^ { (l: List[NodePropertyHierarchy]) => l.head.prop.config }) and*/
      (beEqualTo(globalProperty match {
        case Some(prop) => List(c, p2, p1).toH3(Right(nodeId1), "foo", prop.toGP("foo", mode)).hierarchy
        case None       => List(c, p2, p1).toH1(Right(nodeId1), "foo").hierarchy
      }) ^^ { (l: List[PropertyHierarchy]) => l.head.hierarchy })
    }

    "global append mode" in {
      val merged = MergeNodeProperties
        .checkPropertyMerge(
          Map(p1.id -> p1.toGroupProp, p2.id -> p2.toGroupProp, c.id -> c.toGroupProp),
          Map(
            "foo"   -> GlobalParameter(
              "foo",
              GitVersion.DEFAULT_REV,
              globalProperty,
              maaInheritMode,
              "",
              None,
              Visibility.default,
              security = None
            )
          )
        )
        .map(_.map(n => NodePropertyHierarchy(nodeId1, n)))
        .toEither
      (merged must beRight(
        beMerged(
          ConfigValueFactory.fromIterable(java.util.Arrays.asList("p1", "p2", "node")),
          maaInheritMode,
          Some(globalProperty)
        )
      ))
    }
    "global prepend mode" in {
      val merged = MergeNodeProperties
        .checkPropertyMerge(
          Map(p1.id -> p1.toGroupProp, p2.id -> p2.toGroupProp, c.id -> c.toGroupProp),
          Map(
            "foo"   -> GlobalParameter(
              "foo",
              GitVersion.DEFAULT_REV,
              globalProperty,
              mpaInheritMode,
              "",
              None,
              Visibility.default,
              security = None
            )
          )
        )
        .map(_.map(n => NodePropertyHierarchy(nodeId1, n)))
        .toEither
      (merged must beRight(
        beMerged(
          ConfigValueFactory.fromIterable(java.util.Arrays.asList("node", "p2", "p1")),
          mpaInheritMode,
          Some(globalProperty)
        )
      ))
    }

    "none global mode with default 'override' mode" in {
      val merged = MergeNodeProperties
        .checkPropertyMerge(
          Map(p1.id -> p1.toGroupProp, p2.id -> p2.toGroupProp, c.id -> c.toGroupProp),
          Map.empty
        )
        .map(_.map(n => NodePropertyHierarchy(nodeId1, n)))
        .toEither
      (merged must beRight(
        beMerged(ConfigValueFactory.fromIterable(java.util.Arrays.asList("node")), None, None)
      ))
    }
  }

  "when overriding json we" should {
    def getOverrides(groups: List[NodeGroup]): Map[String, String] = {
      MergeNodeProperties.checkPropertyMerge(groups.map(el => el.id -> el.toGroupProp).toMap, Map()).toEither match {
        case Left(e)  => throw new IllegalArgumentException(s"Error when overriding properties: ${e.message}")
        case Right(v) => v.map(p => (p.value.name, GenericProperty.serializeToHocon(p.resolvedValue.value))).toMap
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
        _isEnabled = true,
        security = None
      )
      val child                             = NodeGroup(
        NodeGroupId(NodeGroupUid("child")),
        name = "child",
        description = "",
        properties = toProps(childProps),
        query = Some(Query(NodeReturnType, And, Identity, List(parent1.toCriterion))),
        isDynamic = true,
        serverList = Set(),
        _isEnabled = true,
        security = None
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
      val globals       = Map(
        ("foo" -> GlobalParameter(
          "foo",
          GitVersion.DEFAULT_REV,
          GenericProperty.parseValue("""{"global":"global value", "override":"global"}""").forceGet,
          None,
          "",
          None,
          Visibility.default,
          security = None
        ))
      )
      val parent        = parent1
        .modify(_.properties)
        .setTo(
          List(
            GroupProperty
              .parse("foo", GitVersion.DEFAULT_REV, """{"parent":"parent value", "override":"parent"}""", None, None)
              .forceGet
          )
        )
      val child_        = child
        .modify(_.properties)
        .setTo(
          List(
            GroupProperty
              .parse("foo", GitVersion.DEFAULT_REV, """{"child" :"child value" , "override":"child" }""", None, None)
              .forceGet
          )
        )
      val node          = nodeInfo
        .modify(_.node.properties)
        .setTo(List(NodeProperty.parse("foo", """{"node"  :"node value"  , "override":"node"  }""", None, None).forceGet))
      val successMerged = MergeNodeProperties
        .forNode(
          NodeFact.fromCompat(node, Left(AcceptedInventory), Seq(), None).toCore,
          List(parent, child_).map(_.toTarget),
          globals
        )

      successMerged must haveClass[SuccessNodePropertyHierarchy]
      val props    = successMerged.resolved
      val actual   = props.toList.toApiJsonRenderParents
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
          """<p>from global property <b>foo (foo)</b>:<pre>{
            |    &quot;global&quot; : &quot;global value&quot;,
            |    &quot;override&quot; : &quot;global&quot;
            |}
            |</pre></p><p>from group <b>parent1 (parent1)</b>:<pre>{
            |    &quot;override&quot; : &quot;parent&quot;,
            |    &quot;parent&quot; : &quot;parent value&quot;
            |}
            |</pre></p><p>from group <b>child (child)</b>:<pre>{
            |    &quot;child&quot; : &quot;child value&quot;,
            |    &quot;override&quot; : &quot;child&quot;
            |}
            |</pre></p><p>from node <b>node1.localhost (node1)</b>:<pre>{
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
  // only match error sub type to expected one
  def beLeft[E <: PropertyHierarchyError: ClassTag] = { (ior: Ior[PropertyHierarchyError, List[PropertyHierarchy]]) =>
    ior match {
      case Ior.Left(l: E) => ok("")
      case o: Ior[?, ?] =>
        ko(s"Result was not the one expected, expected Ior.Left with specific error type but got ${o}")
    }
  }

  // only match error sub type to expected one
  def beBoth[E <: PropertyHierarchyError: ClassTag](success: List[PropertyHierarchy]) = {
    (ior: Ior[PropertyHierarchyError, List[PropertyHierarchy]]) =>
      ior match {
        case Ior.Both(l: E, r) =>
          if (success == r) { // strict equality and not same elements
            ok("")
          } else {
            ko(
              s"Properties were not the ones expected, got Both with left : ${l} and right ${r}\nbut expected left specific error type and right ${success}"
            )
          }
        case o: Ior[?, ?] =>
          ko(s"Result was not the one expected, expected Ior.Both with specific error type but got ${o}")
      }
  }
  def beBoth(
      leftError:    PropertyHierarchyError,
      rightSuccess: List[PropertyHierarchy]
  ): Matcher[Ior[PropertyHierarchyError, List[PropertyHierarchy]]] = {
    (ior: Ior[PropertyHierarchyError, List[PropertyHierarchy]]) =>
      ior match {
        case Ior.Both(l, r) =>
          if (leftError == l && rightSuccess == r) { // strict equality and not same elements
            ok("")
          } else {
            ko(
              s"Properties were not the ones expected, got Both with left : ${l} and right ${r.props}\nbut expected left ${leftError} and right ${rightSuccess}"
            )
          }
        case o: Ior[?, ?] =>
          ko(s"Result was not the one expected, expected Ior.Both but got ${o}")
      }
  }
  def beRight(value: List[PropertyHierarchy]): Matcher[Ior[PropertyHierarchyError, List[PropertyHierarchy]]] = {
    (ior: Ior[PropertyHierarchyError, List[PropertyHierarchy]]) =>
      ior match {
        case Ior.Right(v) =>
          if (v == value) // strict equality and not same elements
            ok("")
          else
            ko(s"Properties were not the ones expected, expected ${value}, got ${v}")
        case o: Ior[?, ?] =>
          ko(s"Result was not the one expected, expected Ior.Right but got ${o}")
      }
  }
}
