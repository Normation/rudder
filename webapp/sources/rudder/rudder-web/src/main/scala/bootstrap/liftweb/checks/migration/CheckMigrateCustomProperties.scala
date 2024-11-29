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

package bootstrap.liftweb.checks.migration

import bootstrap.liftweb.BootstrapChecks
import com.normation.errors.*
import com.normation.inventory.domain.NodeId
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.ldap.core.LDAPConstants.*
import com.normation.ldap.sdk.BuildFilter
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.rudder.domain.logger.MigrationLoggerPure
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.zio.*
import zio.*

/*
 * This migration check looks if there is still nodes with custom properties
 * in the ou=Accepted Inventories branch, and if so, it save back them so that
 * they are moved along with other properties.
 * Added in rudder 8.3 (https://issues.rudder.io/issues/25979)
 * Can be removed in Rudder 9.2.
 */

class CheckMigrateCustomProperties(
    ldap:         LDAPConnectionProvider[RwLDAPConnection],
    factRepo:     NodeFactRepository,
    inventoryDit: InventoryDit
) extends BootstrapChecks {

  override def description: String =
    "Check if some nodes still have custom properties store in LDAP inventory and migrate them to LDAP node is so"

  override def checks(): Unit = {
    migrateAll()
      .catchAll(err => {
        MigrationLoggerPure.error(s"Error when trying to migrate nodes' custom properties in LDAP: ${err.fullMsg}")
      })
      .forkDaemon // make it async to avoid blocking startup, there can be a lot of nodes to migrate
      .runNow
  }

  /*
   * The whole process
   */
  def migrateAll(): IOResult[Unit] = {
    for {
      ids <- findNodeNeedingMigration()
      _   <- migrateProperties(ids)
    } yield ()
  }

  /*
   * Look for nodes with customProperty attribute
   */
  def findNodeNeedingMigration(): IOResult[Seq[NodeId]] = {
    for {
      con           <- ldap
      needMigration <- con
                         .searchOne(inventoryDit.NODES.dn, BuildFilter.HAS(A_CUSTOM_PROPERTY), A_NODE_UUID)
                         .chainError(s"Error when trying to get node entries with existing ${A_CUSTOM_PROPERTY} attributes.")
      ids           <- ZIO.foreach(needMigration)(e => inventoryDit.NODES.NODE.idFromDN(e.dn).toIO)
    } yield ids
  }

  /*
   * Migrate node after node. We don't want that one failure stop the process
   */
  def migrateProperties(nodeIds: Seq[NodeId]): UIO[Unit] = {
    implicit val cc = ChangeContext.newForRudder(Some("Migrating custom properties LDAP storage"))
    ZIO
      .foreach(nodeIds) { id =>
        (for {
          opt <- factRepo.get(id)(cc.toQuery)
          _   <- opt match {
                   case Some(node) => factRepo.save(node)
                   case None       => ZIO.unit
                 }
        } yield ()).catchAll {
          case e => MigrationLoggerPure.error(s"Error when migrating custom properties for node '${id.value}': ${e.fullMsg}")
        }
      }
      .unit
  }

}
