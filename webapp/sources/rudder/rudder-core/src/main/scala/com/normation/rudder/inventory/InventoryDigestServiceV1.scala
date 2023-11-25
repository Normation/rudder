/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

package com.normation.inventory.services.provisioning

import com.normation.errors._
import com.normation.errors.IOResult
import com.normation.inventory.domain._
import com.normation.rudder.facts.nodes.CoreNodeFact
import zio.syntax._

class InventoryDigestServiceV1(
    getExistingNode: NodeId => IOResult[Option[CoreNodeFact]]
) extends ParseInventoryDigestFileV1 with GetKey with CheckInventoryDigest {

  /**
   * Get key in V1 will get the key from inventory data.
   * either an inventory has already been treated before, it will look into ldap repository
   * or if there was no inventory before, it will look for the key in the received inventory
   */
  def getKey(receivedInventory: Inventory): IOResult[(SecurityToken, KeyStatus)] = {

    def extractKey(node: NodeInventory): IOResult[SecurityToken] = {
      for {
        agent <- node.agents.headOption.notOptional("There is no public key in inventory")
      } yield {
        agent.securityToken
      }
    }

    getExistingNode(receivedInventory.node.main.id).flatMap {
      case Some(storedNodeFact) =>
        val keyStatus = storedNodeFact.rudderSettings.keyStatus

        (storedNodeFact.rudderSettings.status match {
          case RemovedInventory => // if inventory was deleted, don't care, use new one
            extractKey(receivedInventory.node)
          case _                => // in other case, check is the key update is valid
            keyStatus match {
              case UndefinedKey => // Trust On First Use (TOFU) (user may have reseted, etc)
                extractKey(receivedInventory.node)

              case CertifiedKey => // Certified node always use stored inventory key
                storedNodeFact.rudderAgent.securityToken.succeed
            }
        }).map(st => (st, keyStatus))

      case _ =>
        val status = receivedInventory.node.main.keyStatus
        extractKey(receivedInventory.node).map((_, status))
    }
  }
}
