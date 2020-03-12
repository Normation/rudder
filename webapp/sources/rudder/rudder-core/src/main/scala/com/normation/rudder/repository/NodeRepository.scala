/*
*************************************************************************************
* Copyright 2014 Normation SAS
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

package com.normation.rudder.repository

import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.nodes._
import com.normation.errors._
import com.normation.inventory.domain.KeyStatus
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.SecurityToken

/**
 * Node Repository
 * To update the Node Run Configuration
 */
trait WoNodeRepository {
  /**
   * Complete update of the Node object.
   */
  def updateNode(node: Node, modId: ModificationId, actor:EventActor, reason:Option[String]) : IOResult[Node]

  def createNode(node: Node, modId: ModificationId, actor:EventActor, reason:Option[String]) : IOResult[Node]
  def deleteNode(node: Node, modId: ModificationId, actor:EventActor, reason:Option[String]) : IOResult[Node]



  /**
   * We have a special case for agentKey and agentKey status because even if they are from inventory,
   * we want to be able to directly manage them via API to:
   * - reset or enforce the key status,
   * - enforce a new certificate different from the one previously in inventory.
   */
  def updateNodeKeyInfo(nodeId: NodeId, agentKey: Option[SecurityToken], agentKeyStatus: Option[KeyStatus], modId: ModificationId, actor:EventActor, reason:Option[String]) : IOResult[Unit]

}
