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
import com.normation.rudder.domain.properties.PropertyHierarchy
import com.normation.rudder.domain.properties.ResolvedNodePropertyHierarchy
import com.normation.rudder.domain.properties.SuccessNodePropertyHierarchy
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import zio.*

/*
 * This file contains a cache/in-memory repository for inherited node properties, ie the result
 * of the computation of a property from the node, group and global context.
 */

trait PropertiesRepository {

  def getAllNodeProps()(implicit qc: QueryContext): IOResult[Map[NodeId, ResolvedNodePropertyHierarchy]]

  def getAllGroupProps(): IOResult[Map[NodeGroupId, ResolvedNodePropertyHierarchy]]

  /*
   * Get all properties for node with given ID
   */
  def getNodeProps(nodeId: NodeId)(implicit qc: QueryContext): IOResult[Option[ResolvedNodePropertyHierarchy]]

  /*
   * Get a given property for a given node
   */
  def getNodesProp(nodeIds: Set[NodeId], propName: String)(implicit
      qc: QueryContext
  ): IOResult[Map[NodeId, PropertyHierarchy]]

  /*
   * Save updated properties for nodes.
   * The chunk is considered to be all of the node properties, so previous
   * properties not in the new chunk will be deleted.
   */
  def saveNodeProps(props: Map[NodeId, ResolvedNodePropertyHierarchy]): IOResult[Unit]

  /*
   * Delete all properties for a node
   */
  def deleteNode(nodeId: NodeId): IOResult[Unit]

  /*
   * Get all properties for node with given ID
   */
  def getGroupProps(groupId: NodeGroupId): IOResult[Option[ResolvedNodePropertyHierarchy]]

  /*
   * Get a given property for a given node
   */
  def getGroupProp(groupId: NodeGroupId, propName: String): IOResult[Option[PropertyHierarchy]]

  /*
   * Save updated properties for nodes.
   * The chunk is considered to be all of the node properties, so previous
   * properties not in the new chunk will be deleted.
   */
  def saveGroupProps(props: Map[NodeGroupId, ResolvedNodePropertyHierarchy]): IOResult[Unit]

  /*
   * Delete all properties for a node
   */
  def deleteGroup(groupId: NodeGroupId): IOResult[Unit]
}

object InMemoryPropertiesRepository {
  def make(nodeFactRepo: NodeFactRepository): IOResult[InMemoryPropertiesRepository] = {
    for {
      nodes  <- Ref.make(Map.empty[NodeId, ResolvedNodePropertyHierarchy])
      groups <- Ref.make(Map.empty[NodeGroupId, ResolvedNodePropertyHierarchy])
    } yield {
      new InMemoryPropertiesRepository(nodeFactRepo, nodes, groups)
    }
  }
}

class InMemoryPropertiesRepository(
    nodeFactRepository: NodeFactRepository,
    nodeProps:          Ref[Map[NodeId, ResolvedNodePropertyHierarchy]],
    groupProps:         Ref[Map[NodeGroupId, ResolvedNodePropertyHierarchy]]
) extends PropertiesRepository {

  override def getAllNodeProps()(implicit qc: QueryContext): IOResult[Map[NodeId, ResolvedNodePropertyHierarchy]] = {
    // we need core node fact to filter access
    for {
      ids   <- nodeFactRepository.getAll().map(_.keySet)
      props <- nodeProps.get
    } yield {
      props.filter { case (id, _) => ids.contains(id) }
    }
  }

  override def getAllGroupProps(): IOResult[Map[NodeGroupId, ResolvedNodePropertyHierarchy]] = {
    groupProps.get
  }

  override def getNodeProps(nodeId: NodeId)(implicit qc: QueryContext): IOResult[Option[ResolvedNodePropertyHierarchy]] = {
    for {
      // needed to check access to node
      foundNode <- nodeFactRepository.get(nodeId)
      props     <- nodeProps.get
    } yield {
      foundNode.flatMap(_ => props.get(nodeId))
    }
  }

  // This API inherently swallows known errors, it does only return success node property in the collection
  override def getNodesProp(nodeIds: Set[NodeId], propName: String)(implicit
      qc: QueryContext
  ): IOResult[Map[NodeId, PropertyHierarchy]] = {
    for {
      // needed to check access to node
      ids   <- nodeFactRepository.getAll().map(_.keySet)
      props <- nodeProps.get
    } yield {
      val view = ids.intersect(nodeIds)
      props.collect {
        case (id, SuccessNodePropertyHierarchy(ps)) if view.contains(id) =>
          ps.find(_.prop.name == propName).map(p => (id, p))
      }.flatten.toMap
    }
  }

  override def saveNodeProps(props: Map[NodeId, ResolvedNodePropertyHierarchy]): IOResult[Unit] = {
    nodeProps.set(props)
  }

  override def deleteNode(nodeId: NodeId): IOResult[Unit] = {
    nodeProps.update(_.removed(nodeId))
  }

  override def getGroupProps(groupId: NodeGroupId): IOResult[Option[ResolvedNodePropertyHierarchy]] = {
    groupProps.get.map(
      _.get(groupId)
    )
  }

  override def getGroupProp(groupId: NodeGroupId, propName: String): IOResult[Option[PropertyHierarchy]] = {
    groupProps.get.map(
      _.get(groupId).flatMap(_.resolved.find(_.prop.name == propName))
    )
  }

  override def saveGroupProps(props: Map[NodeGroupId, ResolvedNodePropertyHierarchy]): IOResult[Unit] = {
    groupProps.set(props)
  }

  override def deleteGroup(groupId: NodeGroupId): IOResult[Unit] = {
    groupProps.update(_.removed(groupId))
  }
}
