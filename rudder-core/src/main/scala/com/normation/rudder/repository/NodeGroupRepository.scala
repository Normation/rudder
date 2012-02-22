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

package com.normation.rudder.repository

import com.normation.rudder.domain.nodes._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.queries.Query
import net.liftweb.common._
import com.normation.eventlog.EventActor
import com.normation.utils.HashcodeCaching
import scala.collection.SortedMap


/**
 * A simple container for a category 
 * and its direct children ActiveTechniques
 */
final case class CategoryAndNodeGroup(
    category: NodeGroupCategory
  , groups  : Set[NodeGroup]
) extends HashcodeCaching 


trait NodeGroupRepository {

  /**
   * Get a server group by its id
   * @param id
   * @return
   */
  def getNodeGroup(id: NodeGroupId) : Box[NodeGroup]
  
  
  /**
   * Fetch the parent category of the NodeGroup
   * Caution, its a lightweight version of the entry (no children nor item)
   * @param id
   * @return
   */
  def getParentGroupCategory(id: NodeGroupId): Box[NodeGroupCategory]
  
  /**
   * Get all node groups defined in that repository
   */
  def getAll() : Box[Seq[NodeGroup]]
  
  /**
   * Get all pairs of (category details, Set(node groups) )
   * in a map in which keys are the parent category of the groups. 
   * The map is sorted by category:
   * 
   *   "/"           -> [/_details, Set(G1, G2)]
   *   "/cat1"       -> [cat1_details, Set(G3)]
   *   "/cat1/cat11" -> [/cat1/cat11_details, Set(G4)]
   *   "/cat2"       -> [/cat2_details, Set(G5)]
   *   ...    * 
   * 
   */
  def getGroupsByCategory(includeSystem:Boolean = false) : Box[SortedMap[List[NodeGroupCategoryId], CategoryAndNodeGroup]]
  
  /**
   * Retrieve all groups that have at least one of the given
   * node ID in there member list.
   * @param nodeIds
   * @return
   */
  def findGroupWithAnyMember(nodeIds:Seq[NodeId]) : Box[Seq[NodeGroupId]]
  
  /**
   * Retrieve all groups that have ALL given node ID in their
   * member list.
   * @param nodeIds
   * @return
   */
  def findGroupWithAllMember(nodeIds:Seq[NodeId]) : Box[Seq[NodeGroupId]]

  //// write operations ////
  
  /**
   * Add a server group into the a parent category
   * Fails if the parent category does not exists
   * 
   * return the newly created server group
   */
  def createNodeGroup( name:String, description : String, q: Option[Query],
        isDynamic : Boolean, srvList : Set[NodeId], into: NodeGroupCategoryId,
                       isEnabled : Boolean, actor:EventActor): Box[AddNodeGroupDiff]
  
  
  /**
   * Update the given existing group
   * That method does nothing at the configuration level,
   * so you will have to manage configuration rule deployment
   * if needed
   */
  def update(group:NodeGroup, actor:EventActor) : Box[Option[ModifyNodeGroupDiff]]


  /**
   * Move the given existing group to the new container.
   * That method does nothing at the configuration level, 
   * so you will have to manage configuration rule deployment
   * if needed
   */
  def move(group:NodeGroup, containerId : NodeGroupCategoryId, actor:EventActor) : Box[Option[ModifyNodeGroupDiff]]
  
  /**
   * Delete the given nodeGroup. 
   * If no nodegroup has such id in the directory, return a success. 
   * @param id
   * @return
   */
  def delete(id:NodeGroupId, actor:EventActor) : Box[DeleteNodeGroupDiff]

}