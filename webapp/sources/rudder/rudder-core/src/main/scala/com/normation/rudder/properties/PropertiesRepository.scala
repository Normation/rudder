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
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.properties.NodePropertyHierarchy
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import zio.*

/*
 * This file contains a cache/in-memory repository for inherited node properties, ie the result
 * of the computation of a property from the node, group and global context.
 */

trait PropertiesRepository {

  def getAllNodeProps()(implicit qc: QueryContext): IOResult[Map[NodeId, Chunk[NodePropertyHierarchy]]]

  /*
   * Get all properties for node with given ID
   */
  def getNodeProps(nodeId: NodeId)(implicit qc: QueryContext): IOResult[Option[Chunk[NodePropertyHierarchy]]]

  /*
   * Get a given property for a given node
   */
  def getNodesProp(nodeIds: Set[NodeId], propName: String)(implicit
      qc: QueryContext
  ): IOResult[Map[NodeId, NodePropertyHierarchy]]

  /*
   * Save updated properties for nodes.
   * The chunk is considered to be all of the node properties, so previous
   * properties not in the new chunk will be deleted.
   */
  def saveNodeProps(props: Map[NodeId, Chunk[NodePropertyHierarchy]]): IOResult[Unit]

  /*
   * Delete all properties for a node
   */
  def deleteNode(nodeId: NodeId): IOResult[Unit]

  /*
   * Get all properties for node with given ID
   */
  def getGroupProps(groupId: NodeGroupId): IOResult[Option[Chunk[NodePropertyHierarchy]]]

  /*
   * Get a given property for a given node
   */
  def getGroupProp(groupId: NodeGroupId, propName: String): IOResult[Option[NodePropertyHierarchy]]

  /*
   * Save updated properties for nodes.
   * The chunk is considered to be all of the node properties, so previous
   * properties not in the new chunk will be deleted.
   */
  def saveGroupProps(props: Map[NodeGroupId, Chunk[NodePropertyHierarchy]]): IOResult[Unit]

  /*
   * Delete all properties for a node
   */
  def deleteGroup(groupId: NodeGroupId): IOResult[Unit]
}

object InMemoryPropertiesRepository {
  def make(nodeFactRepo: NodeFactRepository): IOResult[InMemoryPropertiesRepository] = {
    for {
      nodes  <- Ref.make(Map.empty[NodeId, Chunk[NodePropertyHierarchy]])
      groups <- Ref.make(Map.empty[NodeGroupId, Chunk[NodePropertyHierarchy]])
    } yield {
      new InMemoryPropertiesRepository(nodeFactRepo, nodes, groups)
    }
  }
}

class InMemoryPropertiesRepository(
    nodeFactRepository: NodeFactRepository,
    nodeProps:          Ref[Map[NodeId, Chunk[NodePropertyHierarchy]]],
    groupProps:         Ref[Map[NodeGroupId, Chunk[NodePropertyHierarchy]]]
) extends PropertiesRepository {

  override def getAllNodeProps()(implicit qc: QueryContext): IOResult[Map[NodeId, Chunk[NodePropertyHierarchy]]] = {
    // we need core node fact to filter access
    for {
      ids   <- nodeFactRepository.getAll().map(_.keySet)
      props <- nodeProps.get
    } yield {
      props.filter { case (id, _) => ids.contains(id) }
    }
  }

  override def getNodeProps(nodeId: NodeId)(implicit qc: QueryContext): IOResult[Option[Chunk[NodePropertyHierarchy]]] = {
    for {
      // needed to check access to node
      _     <- nodeFactRepository.get(nodeId)
      props <- nodeProps.get
    } yield {
      props.get(nodeId)
    }
  }

  override def getNodesProp(nodeIds: Set[NodeId], propName: String)(implicit
      qc: QueryContext
  ): IOResult[Map[NodeId, NodePropertyHierarchy]] = {
    for {
      // needed to check access to node
      ids   <- nodeFactRepository.getAll().map(_.keySet)
      props <- nodeProps.get
    } yield {
      val view = ids.intersect(nodeIds)
      props.collect {
        case (id, ps) if view.contains(id) =>
          ps.find(_.prop.name == propName).map(p => (id, p))
      }.flatten.toMap
    }
  }

  override def saveNodeProps(props: Map[NodeId, Chunk[NodePropertyHierarchy]]): IOResult[Unit] = {
    nodeProps.set(props)
  }

  override def deleteNode(nodeId: NodeId): IOResult[Unit] = {
    nodeProps.update(_.removed(nodeId))
  }

  override def getGroupProps(groupId: NodeGroupId): IOResult[Option[Chunk[NodePropertyHierarchy]]] = {
    groupProps.get.map(_.get(groupId))
  }

  override def getGroupProp(groupId: NodeGroupId, propName: String): IOResult[Option[NodePropertyHierarchy]] = {
    groupProps.get.map(_.get(groupId).flatMap(_.find(_.prop.name == propName)))
  }

  override def saveGroupProps(props: Map[NodeGroupId, Chunk[NodePropertyHierarchy]]): IOResult[Unit] = {
    groupProps.set(props)
  }

  override def deleteGroup(groupId: NodeGroupId): IOResult[Unit] = {
    groupProps.update(_.removed(groupId))
  }
}
