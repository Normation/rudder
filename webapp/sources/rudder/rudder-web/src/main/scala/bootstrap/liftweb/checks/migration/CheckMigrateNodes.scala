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

package bootstrap.liftweb.checks.migration

import bootstrap.liftweb.BootstrapChecks
import bootstrap.liftweb.BootstrapLogger

import com.normation.errors._
import com.normation.inventory.domain.AcceptedInventory
import com.normation.inventory.domain.FullInventory
import com.normation.inventory.domain.InventoryStatus
import com.normation.inventory.domain.PendingInventory
import com.normation.inventory.ldap.core.LDAPFullInventoryRepository
import com.normation.inventory.services.core.ReadOnlySoftwareDAO
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.facts.nodes.NodeFact
import com.normation.rudder.services.nodes.NodeInfoService

import com.normation.zio._
import zio._

/*
 * This class handles the migration of nodes from LDAP to Git.
 * It must be an early bootcheck that, once finished, set a promise to let other things
 * (in particular the CoreNodeFactRepository) process into its init.
 *
 * The strategy is:
 * - we have a guard (a promise) that forbid any git-fact-node-based repo/service to start until migration is complete.
 *   That guard is only for services using the new schema, it of course needs LDAP repo/service to work
 *
 * - we start by checking if the branch ou=Nodes,cn=rudder-configuration exists. If not, then a migration already happened
 *   or it's a fresh install
 *
 * - if it exists, we check if a migration already started (for ex before rudder restarted).
 *   For that, we check if a branch `ou=node-fact-migration,cn=rudder-configuration` exists.
 *   If not, it's a new migration:
 *   - we start by creating it and adding in `description` "Node fact migration started: YYYY_MM_dd hh:mm:ssZ"
 *   - we also create sub "ou" entries mirroring ou=Inventories (pending, accepted, not refused, not software) and ou=Nodes
 *   If yes, we append to `description` "- migration interrupted, restarting at: date"
 *
 * - then, we start with Pending nodes:
 *   - for each node in `ou=Nodes,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration`, we:
 *     - read it with machine and software
 *     - transform to NodeFact
 *     - persist to Git in JSON
 *     - move from `ou=Nodes,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration` (not software, only node & machine)
 *       to `ou=Nodes,ou=Accepted Inventories,ou=Inventories,ou=node-fact-migration,cn=rudder-configuration` and same for node.
 *     - log at 10, 100, then every 1000: "migration of pending nodes: 100/XXXX, estimated remaining time"
 *   - update migration entry description with "pending inventories migrated at: date"
 *  This seems to be ok for interrupt: if happens after json written before move, then we will just have other json written.
 *  If happens after node move and before machine moved, we will lose the info in migration branch for machine (likely nobody care)
 *  but data OK on json.
 *
 * - then for Accepted nodes:
 *   - for each node in `ou=Nodes,cn=rudder-configuration` (we don't care for inventories with no node in that branch)
 *     - get node info, full node inventory, software
 *     - persist to Git in Json
 *     - move from `ou=Nodes,cn=rudder-configuration` to `ou=Nodes,ou=node-fact-migration,cn=rudder-configuration`, then
 *       same for node and machine inventory.
 *     - log at 10, 100, then every 1000: "migration of pending nodes: 100/XXXX, estimated remaining time"
 *   - update migration entry description with "accepted nodes and inventories migrated at: date"
 *  This seems to be ok for interrupt: if happens after json written before move, then we will just have other json written.
 *  If happens after node move and before inventory moved, we will lose the info in migration branch for inventory (likely nobody care)
 *  but data OK on json.
 *
 * - at that point, we don't have any remaining interesting things in OUs, but they still may be non empty: move them to
 *   `ou=old-ous,ou=node-fact-migration,cn=rudder-configuration`
 * - at that point, everything is migrated:
 *   - update migration entry description with "node data migration finished: date"
 *   - succeed promise
 *
 */
class CheckMigrateNodes(
    nodeInfoService:    NodeInfoService,
    fullInventoryRepo:  LDAPFullInventoryRepository,
    softwareDAO:        ReadOnlySoftwareDAO,
    nodeMigrationGuard: Promise[Nothing, Unit]
) extends BootstrapChecks {

  override def description: String = "Check if nodes need to be migrated away from LDAP"

  override def checks(): Unit = {
    // todo: check if migration is needed, create migration ou, start migration...
    migrateAllNodes().runNow
  }

  def migrateAllNodes(): IOResult[Unit] = {
    for {
      _             <- BootstrapLogger.migration.info("Starting node migration from LDAP to node fact repository.")
      // empty because too long for test
      _             <- BootstrapLogger.migration.info("Migration of pending nodes...")
      pendingInfos  <- nodeInfoService.getPendingNodeInfos()
      -             <- migrateNode(pendingInfos.values, PendingInventory)
      _             <- BootstrapLogger.migration.info("Migration of accepted nodes...")
      acceptedInfos <- nodeInfoService.getAllNodeInfos()
      _             <- migrateNode(acceptedInfos, AcceptedInventory)
      _             <- nodeMigrationGuard.succeed(())
      _             <- BootstrapLogger.migration.info("Migration of nodes done")
    } yield ()
  }

  def migrateNode(nodeInfos: Iterable[NodeInfo], status: InventoryStatus): IOResult[Unit] = {
    migrateNodeFAKE(nodeInfos, status)
  }

  def migrateNodeFAKE(nodeInfos: Iterable[NodeInfo], status: InventoryStatus): IOResult[Unit] = {
    BootstrapLogger.migration.warn(s"waiting 5s to fake migration...") *> 
    ZIO.sleep(5.seconds)
  }

  def migrateNodeREAL(nodeInfos: Iterable[NodeInfo], status: InventoryStatus): IOResult[Unit] = {
    ZIO
      .foreachPar(nodeInfos.zipWithIndex) {
        case (nodeInfo, i) =>
          for {
            _    <-
              BootstrapLogger.migration.trace(
                s"Retrieving inv for ${i}/${nodeInfos.size} '${nodeInfo.id.value}' (${status.name})"
              )
            inv  <- fullInventoryRepo.get(nodeInfo.id, status)
            _    <- BootstrapLogger.migration.trace(s"  ... software ...")
            soft <- softwareDAO.getSoftwareByNode(Set(nodeInfo.id), status)
            _    <- persist(
                      NodeFact.fromCompat(
                        nodeInfo,
                        inv.toRight(status),
                        soft.getOrElse(nodeInfo.id, Nil)
                      )
                    )
            _    <- moveNodeInfo(nodeInfo, status)
            _    <- moveFullInventory(inv)
          } yield ()
      }
      .unit
      .withParallelism(16)
  }

  def persist(fact: NodeFact): IOResult[Unit] = ???
  def moveNodeInfo(nodeInfo: NodeInfo, status: InventoryStatus): IOResult[Unit] = ???
  def moveFullInventory(optFullInventory: Option[FullInventory]): IOResult[Unit] = ???
}
