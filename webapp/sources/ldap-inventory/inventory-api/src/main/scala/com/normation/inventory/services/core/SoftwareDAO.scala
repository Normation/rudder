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

package com.normation.inventory.services.core

import com.normation.errors._
import com.normation.inventory.domain.InventoryStatus
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.Software
import com.normation.inventory.domain.SoftwareUuid

trait ReadOnlySoftwareNameDAO {

  /**
   * Return softwares for the node id, as efficiently
   * as possible
   */
  def getSoftwareByNode(nodeIds: Set[NodeId], status: InventoryStatus): IOResult[Map[NodeId, Seq[Software]]]

  def getNodesbySofwareName(softName: String): IOResult[List[(NodeId, Software)]]
}

trait ReadOnlySoftwareDAO extends ReadOnlySoftwareNameDAO {

  def getSoftware(ids: Seq[SoftwareUuid]): IOResult[Seq[Software]]

  /**
    * Returns all software ids in ou=Software,ou=Inventories
    */
  def getAllSoftwareIds(): IOResult[Set[SoftwareUuid]]

  /**
    * Returns all software ids pointed by at least a node (in any of the 3 DIT)
    */
  def getSoftwaresForAllNodes(): IOResult[Set[SoftwareUuid]]
}

trait WriteOnlySoftwareDAO {

  /**
    * Delete softwares in ou=Software,ou=Inventories
    */
  def deleteSoftwares(softwares: Seq[SoftwareUuid], batchSize: Int = 1000): IOResult[Unit]
}
