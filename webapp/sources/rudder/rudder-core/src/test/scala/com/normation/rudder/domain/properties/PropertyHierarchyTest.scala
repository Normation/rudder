/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

package com.normation.rudder.domain.properties

import com.normation.GitVersion
import com.normation.inventory.domain.*
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.properties.InheritMode.*
import com.typesafe.config.*
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.*

/*
 * Simple tests on hierarchies
 */

@RunWith(classOf[JUnitRunner])
class PropertyHierarchyTest extends Specification {
  implicit class ToConfigValue(s: String) {
    def toConfigValue: ConfigValue = ConfigValueFactory.fromAnyRef(s)
    def toArray:       ConfigValue = ConfigValueFactory.fromIterable(java.util.Arrays.asList(s))
  }

  implicit class StringToGroupId(s: String) {
    def toGroupId = NodeGroupId(NodeGroupUid(s))
  }

  private val opt = ConfigRenderOptions.concise()

  "Simple strings is correctly overridden" >> {
    val nn  = "node 1"
    val nid = NodeId("node1")
    val np  = NodeProperty("foo", "barNode1".toConfigValue, None, None)
    val npv = PropertyValueKind.SelfValue(np)

    val gn1  = "group 1"
    val gid1 = "group1".toGroupId
    val gp1  = GroupProperty("foo", GitVersion.DEFAULT_REV, "barGroup1".toConfigValue, None, None)
    val gp1v = PropertyValueKind.SelfValue(gp1)
    val g1   = PropertyVertex.Group(gn1, gid1, gp1v)

    val pp1  = GlobalParameter("foo", GitVersion.DEFAULT_REV, "barGlob1".toConfigValue, None, "", None, Visibility.default)
    val pp1v = PropertyValueKind.SelfValue(pp1)
    val p1   = PropertyVertex.Global(pp1v)

    val n1 = PropertyVertex.Node(nn, nid, npv)
    n1.value.resolvedValue.value.render(opt) === "\"barNode1\""

    val n2 = PropertyVertex.Node(nn, nid, PropertyValueKind.Inherited(p1)(NodeProperty.apply))
    n2.value.resolvedValue.value.render(opt) === "\"barGlob1\""

    val n3 = PropertyVertex.Node(nn, nid, PropertyValueKind.Overridden(np, p1))
    n3.value.resolvedValue.value.render(opt) === "\"barNode1\""

    val n4 = PropertyVertex.appendAsRoot(n3, g1)
    n4.value.resolvedValue.value.render(opt) === "\"barNode1\""
  }

  "Array append is correctly appended" >> {

    val mode = Some(InheritMode(ObjectMode.Merge, ArrayMode.Append, StringMode.Override))

    val nn  = "node 1"
    val nid = NodeId("node1")
    val np  = NodeProperty("foo", "barNode1".toArray, None, None)
    val npv = PropertyValueKind.SelfValue(np)

    val gn1  = "group 1"
    val gid1 = "group1".toGroupId
    val gp1  = GroupProperty("foo", GitVersion.DEFAULT_REV, "barGroup1".toArray, mode, None)
    val gp1v = PropertyValueKind.SelfValue(gp1)
    val g1   = PropertyVertex.Group(gn1, gid1, gp1v)
    val g2   = PropertyVertex.Group(gn1, gid1, PropertyValueKind.SelfValue(gp1.withValue("barGroup2".toArray)))

    val pp1  = GlobalParameter("foo", GitVersion.DEFAULT_REV, "barGlob1".toArray, mode, "", None, Visibility.default)
    val pp1v = PropertyValueKind.SelfValue(pp1)
    val p1   = PropertyVertex.Global(pp1v)

    val n1 = PropertyVertex.Node(nn, nid, npv)
    n1.value.resolvedValue.value.render(opt) === """["barNode1"]"""

    val n2 = PropertyVertex.Node(nn, nid, PropertyValueKind.Inherited(p1)(NodeProperty.apply))
    n2.value.resolvedValue.value.render(opt) === """["barGlob1"]"""

    val n3 = PropertyVertex.Node(nn, nid, PropertyValueKind.Overridden(np, p1))
    n3.value.resolvedValue.value.render(opt) === """["barGlob1","barNode1"]"""

    val n4 = PropertyVertex.appendAsRoot(n3, g1)
    n4.value.resolvedValue.value.render(opt) === """["barGlob1","barGroup1","barNode1"]"""

    // check repetitive adds, some part of the algo trigger only with parents
    val n5 = PropertyVertex.appendAsRoot(n4, g2)
    n5.value.resolvedValue.value.render(opt) === """["barGlob1","barGroup2","barGroup1","barNode1"]"""
  }
}
