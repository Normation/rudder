/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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

package com.normation.rudder.properties

import com.normation.errors.IOResult
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.properties.NodePropertyHierarchy
import zio.*

/*
 * This file contains a cache/in-memory repository for inherited node properties, ie the result
 * of the computation of a property from the node, group and global context.
 */

trait NodePropertiesRepository {

  /*
   * Get all properties for node with given ID
   */
  def getNodeAll(nodeId: NodeId): IOResult[Option[Chunk[NodePropertyHierarchy]]]

  /*
   * Get a given property for a given node
   */
  def getNodeProp(nodeId: NodeId, propName: String): IOResult[Option[NodePropertyHierarchy]]

  /*
   * Save updated properties for nodes.
   * The chunk is considered to be all of the node properties, so previous
   * properties not in the new chunk will be deleted.
   */
  def save(props: Map[NodeId, Chunk[NodePropertyHierarchy]]): IOResult[Unit]

  /*
   * Delete all properties for a node
   */
  def deleteNode(nodeId: NodeId): IOResult[Unit]

}

object InMemoryNodePropertiesRepository {
  def make(): IOResult[InMemoryNodePropertiesRepository] = {
    Ref.make(Map.empty[NodeId, Chunk[NodePropertyHierarchy]]).map(c => new InMemoryNodePropertiesRepository(c))
  }
}

class InMemoryNodePropertiesRepository(cache: Ref[Map[NodeId, Chunk[NodePropertyHierarchy]]]) extends NodePropertiesRepository {
  override def getNodeAll(nodeId: NodeId): IOResult[Option[Chunk[NodePropertyHierarchy]]] = {
    cache.get.map(_.get(nodeId))
  }

  override def getNodeProp(nodeId: NodeId, propName: String): IOResult[Option[NodePropertyHierarchy]] = {
    cache.get.map(_.get(nodeId).flatMap(_.find(_.prop.name == propName)))
  }

  override def save(props: Map[NodeId, Chunk[NodePropertyHierarchy]]): IOResult[Unit] = {
    cache.set(props)
  }

  override def deleteNode(nodeId: NodeId): IOResult[Unit] = {
    cache.update(_.removed(nodeId))
  }
}
