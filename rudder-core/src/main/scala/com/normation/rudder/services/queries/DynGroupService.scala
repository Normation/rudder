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

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.{NodeGroup,NodeGroupId}
import com.unboundid.ldap.sdk.{DN,Filter}
import com.normation.ldap.sdk._
import BuildFilter._
import com.normation.rudder.domain.{RudderDit,RudderLDAPConstants}
import com.normation.inventory.ldap.core.LDAPConstants.{A_OC, A_NAME}
import RudderLDAPConstants._
import com.normation.utils.Control.sequence
import com.normation.inventory.ldap.core.LDAPConstants
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import net.liftweb.common._





/**
 * A service used to manage dynamic groups : find
 * dynGroup to which belongs nodes, updates them,
 * etc
 */
trait DynGroupService {

  /**
   * Retrieve the list of all dynamic groups.
   */
  def getAllDynGroups() : Box[Seq[NodeGroupId]]

  /**
   * For each node in the list, find
   * the list of dynamic group they belongs to.
   *
   * A node ID which does not belong to any dyn group
   * won't be in the resulting map.
   */
  def findDynGroups(nodeIds:Seq[NodeId]) : Box[Map[NodeId,Seq[NodeGroupId]]]

}


class DynGroupServiceImpl(
  rudderDit: RudderDit,
  ldap:LDAPConnectionProvider[RoLDAPConnection], 
  mapper:LDAPEntityMapper,
  queryChecker: PendingNodesLDAPQueryChecker
) extends DynGroupService with Loggable {

  /**
   * Get all dyn groups
   */
  private[this] val dynGroupFilter = AND(
      IS(OC_RUDDER_NODE_GROUP),
      EQ(A_IS_DYNAMIC, true.toLDAPString),
      HAS(A_QUERY_NODE_GROUP)
  )

  /**
   * don't get back
   * the list of members (we don't need them)
   */
  private[this] def dynGroupAttrs = (LDAPConstants.OC(OC_RUDDER_NODE_GROUP).attributes - LDAPConstants.A_NODE_UUID).toSeq


  override def getAllDynGroups() : Box[Seq[NodeGroupId]] = {
    for {
      con <- ldap
      dyngroupIds <- sequence(con.searchSub(rudderDit.GROUP.dn, dynGroupFilter, "1.1")) { entry =>
        rudderDit.GROUP.getGroupId(entry.dn) ?~! "Can not map entry DN to a node group ID: %s".format(entry.dn)
      }
    } yield {
      dyngroupIds.map(id => NodeGroupId(id))
    }
  }


  /**
   * Default algorithm, not expected to be performant:
   * - get all dynamic groups
   * - for each query, test if the groups match or not.
   *   For that, use the LdapQueryProcessor on "pending"
   *   branch, limiting queries to the argument list of nodes
   *
   * If any error is encountered during the sequence of tests,
   * the whole process is in error.
   */
  override def findDynGroups(nodeIds:Seq[NodeId]) : Box[Map[NodeId,Seq[NodeGroupId]]] = {
    for {
      con <- ldap
      dyngroups <- sequence(con.searchSub(rudderDit.GROUP.dn, dynGroupFilter, dynGroupAttrs:_*)) { entry =>
        mapper.entry2NodeGroup(entry) ?~! "Can not map entry to a node group: %s".format(entry)
      }
      //now, for each test the query
      mapGroupAndNodes <- sequence(dyngroups) { g =>
        (for {
          matchedIds <- g match {
            case NodeGroup(id, _, _, Some(query), true, _, _, _) =>
              queryChecker.check(query,nodeIds)
            case g => { //what ?
              logger.error("Found a group without a query or not dynamic: %s".format(g))
              Full(Seq())
            }
          }
        } yield {
          (g.id, matchedIds)
        }) ?~! "Error when trying to find what nodes belong to dynamic group %s".format(g)
      }
    } yield {
      swapMap(mapGroupAndNodes)
    }
  }


  /**
   * Transform the map of (groupid => seq(nodeids) into a map of
   * (nodeid => seq(groupids)
   */
  private[this] def swapMap(source:Seq[(NodeGroupId,Seq[NodeId])]) : Map[NodeId,Seq[NodeGroupId]] = {
    val dest = scala.collection.mutable.Map[NodeId,List[NodeGroupId]]()
    for {
      (gid, seqNodeIds) <- source
      nodeId <- seqNodeIds
    } {
      dest(nodeId) = gid :: dest.getOrElse(nodeId,Nil)
    }
    dest.toMap
  }
}


