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

import com.normation.errors.*
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.RoParameterRepository
import zio.*

/*
 * This file contains a cache/in-memory repository for inherited node properties, ie the result
 * of the computation of a property from the node, group and global context.
 */

trait NodePropertiesService {

  /*
   * Update all property hierarchy
   */
  def updateAll(): IOResult[Unit]

}

class NodePropertiesServiceImpl(
    globalPropsRepo:       RoParameterRepository,
    roNodeGroupRepository: RoNodeGroupRepository,
    nodeFactRepository:    NodeFactRepository,
    propertiesRepository:  PropertiesRepository
) extends NodePropertiesService {
  override def updateAll(): IOResult[Unit] = {
    for {
      params       <- globalPropsRepo.getAllGlobalParameters().map(_.map(x => (x.name, x)).toMap)
      groups       <- roNodeGroupRepository.getFullGroupLibrary()
      nodes        <- nodeFactRepository.getAll()(QueryContext.systemQC).map(_.values)
      mergedGroups <- ZIO.foreach(groups.allGroups.keys) { gid =>
                        MergeNodeProperties.forGroup(gid, groups.allGroups, params).toIO.map(p => (gid, p))
                      }
      mergedNodes  <- ZIO.foreach(nodes)(n =>
                        MergeNodeProperties.forNode(n, groups.getTarget(n).values.toList, params).toIO.map(p => (n.id, p))
                      )
      _            <- propertiesRepository.saveNodeProps(mergedNodes.toMap)
      _            <- propertiesRepository.saveGroupProps(mergedGroups.toMap)
    } yield ()
  }
}
