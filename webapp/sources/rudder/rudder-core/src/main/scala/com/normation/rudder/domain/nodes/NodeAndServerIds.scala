/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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

import com.normation.inventory.domain.NodeId
import com.normation.rudder.facts.nodes.CoreNodeFact

/*
 * The set of node ids visible in the caller's context along with the ids of the
 * policy servers (root and relays) among them. This is the information needed to
 * resolve rule targets into node ids (see RuleTarget.getNodeIdsChunk), and the two
 * sets are almost always used together.
 *
 * It is an opaque, zero-cost pair: build it with the named-argument `apply`, with
 * `fromFacts` on a facts map (QueryContext-filtered repository view or generation
 * snapshot), or get it directly from `NodeFactRepository.getNodeAndServerIds()`.
 */
opaque type NodeAndServerIds = (Set[NodeId], Set[NodeId])

object NodeAndServerIds {

  // prefer named arguments at call sites: both sets have the same type
  def apply(nodeIds: Set[NodeId], serverIds: Set[NodeId]): NodeAndServerIds = (nodeIds, serverIds)

  // build from a facts map: nodeFactRepository.getAll()(qc), a generation snapshot, ...
  def fromFacts(facts: IterableOnce[(NodeId, CoreNodeFact)]): NodeAndServerIds = {
    facts.iterator.foldLeft((Set.empty[NodeId], Set.empty[NodeId])) {
      case ((nodes, servers), (id, fact)) =>
        (nodes + id, if (fact.rudderSettings.isPolicyServer) servers + id else servers)
    }
  }

  extension (x: NodeAndServerIds) {
    def nodeIds:   Set[NodeId] = x._1
    def serverIds: Set[NodeId] = x._2
  }
}
