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
import net.liftweb.common._
import com.normation.rudder.repository.WoNodeGroupRepository
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId

import com.normation.box._


/**
 * A container for a dynamic group update.
 * members are the list of members post-update,
 * removed/added members are compared with the
 * state pre-update.
 */
final case class DynGroupDiff(
    members:Set[NodeId],
    removed:Set[NodeId],
    added:Set[NodeId]
)

object DynGroupDiff {
  def apply(newGroup : NodeGroup, oldGroup : NodeGroup): DynGroupDiff = {
    val plus = newGroup.serverList -- oldGroup.serverList
    val minus = oldGroup.serverList -- newGroup.serverList
    DynGroupDiff(newGroup.serverList, minus, plus)
  }
}

trait DynGroupUpdaterService {
  /**
   * Update the given dynamic group, returning the diff
   * from the pre-update.
   *
   * IMPORTANT NOTE: system group are not updated with
   * that service !
   *
   * @return
   */
  def update(dynGroupId:NodeGroupId, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[DynGroupDiff]

  def updateAll(modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[Seq[DynGroupDiff]]

  def computeDynGroup (group : NodeGroup): Box[NodeGroup]
}


class DynGroupUpdaterServiceImpl(
  roNodeGroupRepository: RoNodeGroupRepository,
  woNodeGroupRepository: WoNodeGroupRepository,
  queryProcessor     : QueryProcessor
) extends DynGroupUpdaterService with Loggable {

  override def computeDynGroup (group : NodeGroup): Box[NodeGroup] = {
    for {
      _              <- if(group.isDynamic) Full("OK") else Failure("Can not update a not dynamic group")
      timePreCompute =  System.currentTimeMillis
      query          <- Box(group.query) ?~! s"No query defined for group '${group.name}' (${group.id.value})"
      _ = println("query")
      newMembers     <- queryProcessor.processOnlyId(query) ?~! s"Error when processing request for updating dynamic group '${group.name}' (${group.id.value})"
      _ = println("new members")
      //save
      newMemberIdsSet = newMembers.toSet
      timeGroupCompute=  (System.currentTimeMillis - timePreCompute)
      _               =  logger.debug(s"Dynamic group ${group.id.value} with name ${group.name} computed in ${timeGroupCompute} ms")
    } yield {
      group.copy(serverList = newMemberIdsSet)
    }
  }

  override def updateAll(modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[Seq[DynGroupDiff]] = {
    for {
      allGroups <- roNodeGroupRepository.getAll().toBox
      dynGroups = allGroups.filter(_.isDynamic)
      result    <- com.normation.utils.Control.sequence(dynGroups) {
        group =>
          for {
            newGroup   <- computeDynGroup(group)
            savedGroup <- woNodeGroupRepository.updateDynGroupNodes(newGroup, modId, actor, reason).toBox ?~! s"Error when saving update for dynamic group '${group.name}' (${group.id.value})"
          } yield {
            DynGroupDiff(newGroup, group)
          }
      }
    } yield {
      result
    }
  }

  override def update(dynGroupId:NodeGroupId, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[DynGroupDiff] = {
    val timePreUpdate =  System.currentTimeMillis
    for {
      (group,_)        <- roNodeGroupRepository.getNodeGroup(dynGroupId).toBox
      _ = println("group found")
      newGroup         <- computeDynGroup(group)
      _ = println("group computed")
      savedGroup       <- woNodeGroupRepository.updateDynGroupNodes(newGroup, modId, actor, reason).toBox ?~! s"Error when saving update for dynamic group '${group.name}' (${group.id.value})"
      _ = println("group saved")
      timeGroupUpdate  =  (System.currentTimeMillis - timePreUpdate)
      _                =  logger.debug(s"Dynamic group ${group.id.value} with name ${group.name} updated in ${timeGroupUpdate} ms")
    } yield {
      DynGroupDiff(newGroup, group)
    }
  }

}
