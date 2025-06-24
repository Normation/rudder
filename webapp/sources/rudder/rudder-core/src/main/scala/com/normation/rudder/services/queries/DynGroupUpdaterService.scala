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

import com.normation.box.*
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.logger.DynamicGroupLoggerPure
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.WoNodeGroupRepository
import net.liftweb.common.*

/**
 * A container for a dynamic group update.
 * members are the list of members post-update,
 * removed/added members are compared with the
 * state pre-update.
 */
final case class DynGroupDiff(
    members: Set[NodeId],
    removed: Set[NodeId],
    added:   Set[NodeId]
)

object DynGroupDiff {
  def apply(newGroup: NodeGroup, oldGroup: NodeGroup): DynGroupDiff = {
    val plus  = newGroup.serverList -- oldGroup.serverList
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
  def update(dynGroupId: NodeGroupId)(implicit cc: ChangeContext): Box[DynGroupDiff]

  def updateAll(modId: ModificationId)(implicit cc: ChangeContext): Box[Seq[DynGroupDiff]]

  def computeDynGroup(group: NodeGroup)(implicit qc: QueryContext): Box[NodeGroup]
}

class DynGroupUpdaterServiceImpl(
    roNodeGroupRepository: RoNodeGroupRepository,
    woNodeGroupRepository: WoNodeGroupRepository,
    queryProcessor:        QueryProcessor
) extends DynGroupUpdaterService {

  override def computeDynGroup(group: NodeGroup)(implicit qc: QueryContext): Box[NodeGroup] = {
    // we only compute the node list for dynamic groups with a query. For other groups (static, newly created groups, etc)
    // we don't do anything
    if (!group.isDynamic) Full(group)
    else {
      group.query match {
        case None        => Full(group)
        case Some(query) =>
          val timePreCompute = System.currentTimeMillis
          for {
            newMembers      <-
              queryProcessor.processOnlyId(
                query
              ) ?~! s"Error when processing request for updating dynamic group '${group.name}' (${group.id.serialize})"
            timeGroupCompute = (System.currentTimeMillis - timePreCompute)
            _                = DynamicGroupLoggerPure.Timing.logEffect.trace(
                                 s"Dynamic group ${group.id.serialize} with name ${group.name} computed in ${timeGroupCompute} ms"
                               )
          } yield {
            group.copy(serverList = newMembers.toSet)
          }
      }
    }
  }

  override def updateAll(modId: ModificationId)(implicit cc: ChangeContext): Box[Seq[DynGroupDiff]] = {
    for {
      allGroups <- roNodeGroupRepository.getAll().toBox
      dynGroups  = allGroups.filter(_.isDynamic)
      result    <- com.normation.utils.Control.traverse(dynGroups) { group =>
                     for {
                       newGroup   <- computeDynGroup(group)(using cc.toQuery)
                       savedGroup <-
                         woNodeGroupRepository
                           .updateDynGroupNodes(newGroup, cc.modId, cc.actor, cc.message)
                           .toBox ?~! s"Error when saving update for dynamic group '${group.name}' (${group.id.serialize})"
                     } yield {
                       DynGroupDiff(newGroup, group)
                     }
                   }
    } yield {
      result
    }
  }

  override def update(
      dynGroupId: NodeGroupId
  )(implicit cc: ChangeContext): Box[DynGroupDiff] = {
    val timePreUpdate = System.currentTimeMillis
    for {
      (group, _)     <- roNodeGroupRepository.getNodeGroup(dynGroupId)(using cc.toQuery).toBox
      newGroup       <- computeDynGroup(group)(using cc.toQuery)
      savedGroup     <- woNodeGroupRepository
                          .updateDynGroupNodes(newGroup, cc.modId, cc.actor, cc.message)
                          .toBox ?~! s"Error when saving update for dynamic group '${group.name}' (${group.id.serialize})"
      timeGroupUpdate = (System.currentTimeMillis - timePreUpdate)
      _               = DynamicGroupLoggerPure.Timing.logEffect.trace(
                          s"Dynamic group ${group.id.serialize} with name ${group.name} updated in ${timeGroupUpdate} ms"
                        )
    } yield {
      DynGroupDiff(newGroup, group)
    }
  }

}
