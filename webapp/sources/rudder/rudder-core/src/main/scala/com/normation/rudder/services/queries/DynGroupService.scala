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

package com.normation.rudder.services.queries

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.{NodeGroup, NodeGroupId}
import com.normation.ldap.sdk._
import BuildFilter._
import com.normation.rudder.domain.{RudderDit, RudderLDAPConstants}
import RudderLDAPConstants._
import com.normation.utils.Control.sequence
import com.normation.inventory.ldap.core.LDAPConstants
import com.normation.rudder.domain.logger.NodeLogger
import com.normation.rudder.domain.queries.CriterionLine
import com.normation.rudder.domain.queries.Equals
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import net.liftweb.common._
import com.normation.ldap.sdk.LdapResult._
import cats.implicits._

import scalaz.zio._
import scalaz.zio.syntax._

import com.normation.box._

/**
 * A service used to manage dynamic groups : find
 * dynGroup to which belongs nodes, updates them,
 * etc
 */
trait DynGroupService {

  /**
   * Retrieve the list of all dynamic groups.
   */
  def getAllDynGroups(): Box[Seq[NodeGroup]]
}

class DynGroupServiceImpl(
  rudderDit: RudderDit,
  ldap:LDAPConnectionProvider[RoLDAPConnection],
  mapper:LDAPEntityMapper,
) extends DynGroupService {

  /**
   * Get all dyn groups
   */
  private[this] val dynGroupFilter =
    AND(
        IS(OC_RUDDER_NODE_GROUP)
      , EQ(A_IS_DYNAMIC, true.toLDAPString)
      , HAS(A_QUERY_NODE_GROUP)
    )

  /**
   * don't get back
   * the list of members (we don't need them)
   */
  private[this] def dynGroupAttrs = (LDAPConstants.OC(OC_RUDDER_NODE_GROUP).attributes - LDAPConstants.A_NODE_UUID).toSeq

  override def getAllDynGroups() : Box[Seq[NodeGroup]] = {
    for {
      con         <- ldap
      entries     <- con.searchSub(rudderDit.GROUP.dn, dynGroupFilter, dynGroupAttrs:_*)
      dyngroupIds <- ZIO.foreach(entries) { entry =>
                       mapper.entry2NodeGroup(entry).toIO.chainError(s"Can not map entry to a node group: ${entry}")
                     }
    } yield {
      // The idea is to sort group to update groups with a query based on other groups content (objecttype group) at the end so their base group is already updated
      // This does not treat all cases (what happens when you have a group depending on a group which also depends on another group content)
      // We will sort by number of group queries we have in our group (the more group we depend on, the more we want to update it last)
      def numberOfQuery(group : NodeGroup) = group.query.map( _.criteria.filter(_.objectType.objectType == "group").size).getOrElse(0)
      dyngroupIds.sortBy(numberOfQuery)
    }
  }.toBox
}

/**
 * A service that check if nodes are in dynamique groups
 */
class CheckPendingNodeInDynGroups(
  queryChecker: QueryChecker
) {
  /**
   * For each node in the list, find
   * the list of dynamic group they belongs to from the parameter.
   *
   * A node ID which does not belong to any dyn group
   * won't be in the resulting map.
   */
  def findDynGroups(nodeIds: Set[NodeId], groups: List[NodeGroup]): Box[Map[NodeId, Seq[NodeGroupId]]] = {

    for {
      mapGroupAndNodes <- processDynGroups(groups, nodeIds) ?~! s"Can not find dynamic groups for nodes: '${nodeIds.map(_.value).mkString("','")}'"
    } yield {
      swapMap(mapGroupAndNodes)
    }
  }

  /**
   * Given a list of dynamique groups and a list of nodes, find which groups contains which
   * nodes.
   * This method proceed recursively, starting by dealing with nodes without any other group
   * dependency (ie: group of groups are set aside), and then continuing until everything
   * is done. See https://www.rudder-project.org/redmine/issues/12060#note-6 for
   * algo detail in image.
   *
   * Raw behavior: 3 queues: TODO, BLOCKED, DONE
   * All dyn groups start in TODO with the list of nodeIds and their query splitted in two parts:
   * - a list of depandent groups
   * - simple query criterions
   *
   * Init: all groups with at least one dependency go to BLOCKED
   * Then iterativelly proceed groups in TODO (until TODO empty) so that for each group G:
   * - compute nodes in G
   * - put G in DONE
   * - find all node in BLOCKED with a dependency toward G, and for each of these H group, do:
   *   - union or intersect G nodes with H groups (depending of the H composition kind)
   *   - remove G as dependency from H
   *   - if H has no more dependencies, put it back at the end of TODO
   * When TODO is empty, do:
   *   - for each remaining groups in BLOCKED, act as if there dependencies have 0 nodes (and => empty, or => keep
   *     only simple query part)
   *   - put empty group into DONE, and non-empty one into TODO
   *   - process TODO
   *
   * And done ! :)
   */
  def processDynGroups(groups: List[NodeGroup], nodeIds: Set[NodeId]): Box[List[(NodeGroupId, Set[NodeId])]] = {
    // a data structure to keep a group ID, set of nodes, dependencies, query and composition/
    // the query does not contain group anymore.

    final case class DynGroup(id: NodeGroupId, dependencies: Set[NodeGroupId], testNodes: Set[NodeId], query: Query, includeNodes: Set[NodeId])

    NodeLogger.PendingNode.Policies.debug(s"Checking dyn-groups belonging for nodes [${nodeIds.map(_.value).mkString(", ")}]:${groups.map(g => s"${g.id.value}: ${g.name}").sorted.mkString("{", "}{", "}")}")

    // for debuging message
    implicit class DynGroupsToString(gs: List[(NodeGroupId, Set[NodeId])]) {
      def show: String = gs.map { case (id, nodes) =>
        id.value + ":" + nodes.map(_.value).mkString(",")
      }.mkString("[", "][", "]")
    }
    implicit class ResToString(gs: List[DynGroup]) {
      def show: String = gs.map { case DynGroup(id, dep, nodes, q, inc) =>
        id.value + ":" + nodes.size + "{"+dep.map(_.value).mkString(",")+"}"
      }.mkString("[", "][", "]")
    }

    /*
     * one step of the algo
     */
    def recProcess(todo: List[DynGroup], blocked: List[DynGroup], done: List[(NodeGroupId, Set[NodeId])]): Box[List[(NodeGroupId, Set[NodeId])]] = {
      import com.normation.rudder.domain.queries.{ And => CAnd}

      NodeLogger.PendingNode.Policies.trace("TODO   :" + todo.show   )
      NodeLogger.PendingNode.Policies.trace("BLOCKED:" + blocked.show)
      NodeLogger.PendingNode.Policies.trace("DONE   :" + done.show   )

      (todo, blocked, done) match {

        case (Nil, Nil, res) => // termination condition
          NodeLogger.PendingNode.Policies.trace("==> end")
          Full(res)

        case (Nil, b, res) => // end of main phase: zero-ïze group dependencies in b and put them back in the other two queues
          NodeLogger.PendingNode.Policies.trace("==> unblock things")
          val (newTodo, newRes) = ( (List.empty[DynGroup], res) /: b) { case ( (t, r), next ) =>
            if(next.query.composition == CAnd) { // the group has zero node b/c intersect with 0 => new result
              NodeLogger.PendingNode.Policies.trace(" -> evicting " + next.id.value)
              (t, (next.id, Set.empty[NodeId])::r )
            } else { // we can just ignore the dependencies and proceed the remaining group as a normal dyn group
              NodeLogger.PendingNode.Policies.trace(" -> process back" + next.id.value)
              (next :: t, r)
            }
          }
          // start back the process with empty BLOCKED
          recProcess(newTodo, Nil, newRes)


        case (h::tail, b, res) => // standard step: takes the group and deals with it
          NodeLogger.PendingNode.Policies.trace("==> process " + h.id.value)
          (queryChecker.check(h.query, h.testNodes.toSeq).flatMap { nIds =>
            // node matching that group - also include the one from "include" coming from "or" dep
            val setNodeIds = nIds.toSet ++ h.includeNodes
            // get blocked group with h as a dep
            val (withDep, stillBlocked) = b.partition( _.dependencies.contains(h.id) )
            // for each node with that dep: intersect or union nodeids, remove dep
            val newTodos = withDep.map { case DynGroup(id, dependencies, nodes, query, inc) =>
              val (newNodeIds, newInc) = if(query.composition == CAnd) {
                (nodes.intersect(setNodeIds), inc)
              } else {
                (nodes, setNodeIds ++ inc)
              }
              val newDep = dependencies - h.id
              DynGroup(id, newDep, newNodeIds, query, newInc)
            }
            // we can be in a case where the dyngroup don't have any criteria remaning, because they were
            // only subgroups. In that case, avoid to put it back in processing list, because their is nothing
            // left to do for it.
            // for these case, we keep all the node that are coming from the subgroup and that are looked for
            val (alreadyDone, remainingNewTodos) = newTodos.partition( _.query.criteria.isEmpty )

            NodeLogger.PendingNode.Policies.trace(" -> unblock     : " + remainingNewTodos.map(_.id.value).mkString(", "))
            NodeLogger.PendingNode.Policies.trace(" -> already done: " + alreadyDone.map(_.id.value).mkString(", "))

            val newRes =  alreadyDone.map(x => (x.id, x.includeNodes.union(x.testNodes)) ) ::: ((h.id, setNodeIds) :: res)
            recProcess(tail ::: remainingNewTodos, stillBlocked, newRes)
          }) ?~! s"Error when trying to find what nodes belong to dynamic group ${h.id}"
      }
    }

    // init: transform NodeGroups into DynGroup.
    // group without query are filtered out (they will have 0 node so that does not change
    // anything if a group had a dep towards one of them)
    val dynGroups = groups.flatMap { g =>
      if(g._isEnabled) {
        g.query.map { query =>
          // partition group query into Subgroup / simple criterion
          val (dep, criteria) = ( (Set.empty[NodeGroupId], List.empty[CriterionLine])/: query.criteria) { case ( (g, q),  next) =>
            if(next.objectType.objectType == "group") { // it's a dependency
              // we only know how to process the comparator "exact string match" for group
              next.comparator match {
                case Equals =>
                  ( g + NodeGroupId(next.value), q)
                case _      =>
                  NodeLogger.PendingNode.Policies.warn("Warning: group criteria use something else than exact string match comparator: " + next)
                  (g, q)
              }
            } else { // keep that criterium
              (g, next :: q)
            }
          }
          DynGroup(g.id, dep, nodeIds, query.copy(criteria = criteria), Set())
        }
      } else {
        None
      }
    }


    // partition nodes with dependencies / node without:
    val (nodep, withdep) = dynGroups.partition( _.dependencies.isEmpty )

    // start the process ! End at the end, transform the result into a map.
    val res = recProcess(nodep, withdep, Nil)
    // end result
    res.foreach(r => NodeLogger.PendingNode.Policies.debug("Result: " + r.show) )
    res
  }


  /**
   * Transform the map of (groupid => seq(nodeids) into a map of
   * (nodeid => seq(groupids)
   */
  private[this] def swapMap(source:Seq[(NodeGroupId, Set[NodeId])]) : Map[NodeId, Seq[NodeGroupId]] = {
    val dest = scala.collection.mutable.Map[NodeId,List[NodeGroupId]]()
    for {
      (gid, seqNodeIds) <- source
      nodeId            <- seqNodeIds
    } {
      dest(nodeId) = gid :: dest.getOrElse(nodeId, Nil)
    }
    dest.toMap
  }
}
