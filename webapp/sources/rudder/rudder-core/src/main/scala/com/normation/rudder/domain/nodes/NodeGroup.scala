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

package com.normation.rudder.domain.nodes

import com.normation.GitVersion
import com.normation.GitVersion.Revision
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.properties.GroupProperty
import com.normation.rudder.domain.queries.CriterionComposition.And
import com.normation.rudder.domain.queries.CriterionLine
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.domain.queries.SubGroupComparator
import com.normation.rudder.tenants.HasSecurityTag
import com.normation.rudder.tenants.SecurityTag

/**
 * UUId type for Node Groups, so that they
 * can be uniquely identified in our world.
 */
final case class NodeGroupUid(value: String) extends AnyVal {
  def debugString: String = value
  def serialize:   String = value
}
final case class NodeGroupId(uid: NodeGroupUid, rev: Revision = GitVersion.DEFAULT_REV) {
  def debugString: String = serialize

  def serialize: String = rev match {
    case GitVersion.DEFAULT_REV => uid.value
    case rev                    => s"${uid.value}+${rev.value}"
  }

  def withDefaultRev: NodeGroupId = this.copy(rev = GitVersion.DEFAULT_REV)
}
object NodeGroupId {

  // parse a directiveId which was serialized by "id.serialize"
  def parse(s: String): Either[String, NodeGroupId] = {
    GitVersion.parseUidRev(s).map {
      case (id, rev) =>
        NodeGroupId(NodeGroupUid(id), rev)
    }
  }
}

object NodeGroup {
  /*
   * Test if group "maybeSubGroup" is a sub group of "group".
   * A sub group is defined by:
   * - both groups have th same isDynamic
   * - they both have a query
   * - subgroup has an "and" composition and the "group query line" with parent,
   *   - or they both have an "and" composition and sub groups has strictly more
   *     query criterion and group query lines are all in sub group query criterion
   */
  def isSubgroupOf(maybeSubgroup: NodeGroup, group: NodeGroup): Boolean = {
    maybeSubgroup.isDynamic == group.isDynamic && ((maybeSubgroup.query, group.query) match {
      case (Some(subQuery), Some(query)) => isSubqueryQuery(subQuery, query, group.id)
      case _                             => false
    })
  }

  /*
   * Check the query part of subgroupiness.
   */
  def isSubqueryQuery(subgroup: Query, group: Query, groupId: NodeGroupId): Boolean = {
    // you need to refined query to be a subgroup
    (subgroup.composition == And) &&
    // not sure about that one: it seems that they should have the same type,
    // but perhaps if parent returns node & server but not child, is it ok, too ?
    (subgroup.returnType == group.returnType) &&
    isSubgroupByCriterion(subgroup.criteria, groupId)
  }

  /*
   * Check if one criterion is a "subgroup" one and id matches
   */
  def isSubgroupByCriterion(subgroup: Seq[CriterionLine], group: NodeGroupId): Boolean = {
    subgroup.exists(line => {
      line.attribute.cType.isInstanceOf[SubGroupComparator] &&
      line.value == group.serialize
    })
  }
  given HasSecurityTag[NodeGroup] with {
    extension (a: NodeGroup) {
      override def security: Option[SecurityTag] = a.security
      override def debugId:  String              = a.id.debugString
      override def updateSecurityContext(security: Option[SecurityTag]): NodeGroup = a.copy(security = security)
    }
  }
}

/**
 * This class define a node group
 *
 * A node group contains :
 * - the name and a description
 * - a search query (optional)
 * - an indicator whether the group is static or dynamic
 * - a list of node id, if the group is static (or even dynamic?)
 *
 */
final case class NodeGroup(
    id:          NodeGroupId,
    name:        String,
    description: String,
    properties:  List[GroupProperty],
    query:       Option[Query],
    isDynamic:   Boolean = true,
    serverList:  Set[NodeId],
    _isEnabled:  Boolean,
    isSystem:    Boolean = false,
    security:    Option[SecurityTag]
) {
  // system object must ALWAYS be ENABLED.
  def isEnabled: Boolean = _isEnabled || isSystem
}
