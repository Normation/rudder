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

package com.normation.inventory.ldap.provisioning

import com.normation.errors._
import com.normation.inventory.domain.Inventory
import com.normation.inventory.domain.InventoryProcessingLogger
import com.normation.inventory.ldap.core._
import com.normation.inventory.services.provisioning._
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.zio._
import com.unboundid.ldif.LDIFChangeRecord
import zio._
import zio.syntax.ToZio

/**
 * Post-commit convention:
 * - Post-commit can't modify Inventory
 * - if succed, they can enhanced the list of ChangeRecords,
 *   but at least must forward existing LDIFChangeRecords
 * - a post commit which returns Failure or Empty stop the post-commit pipeline
 *
 */

class DefaultInventorySaver(
    ldapConnectionProvider:          LDAPConnectionProvider[RwLDAPConnection],
    dit:                             InventoryDit,
    mapper:                          InventoryMapper,
    override val preCommitPipeline:  Seq[PreCommit],
    override val postCommitPipeline: Seq[PostCommit[Seq[LDIFChangeRecord]]]
) extends PipelinedInventorySaver[Seq[LDIFChangeRecord]] {

  def commitChange(inventory: Inventory): IOResult[Seq[LDIFChangeRecord]] = {

    for {
      con <- ldapConnectionProvider
      t0  <- currentTimeMillis
      // we really want to save each software, and not the software tree as a whole - just think about the diff...
      d0  <- ZIO.foreach(inventory.applications)(x => con.save(mapper.entryFromSoftware(x)))
      t1  <- currentTimeMillis
      _   <- InventoryProcessingLogger.timing.trace(s"Saving software: ${t1 - t0} ms")

      d1 <- con.saveTree(mapper.treeFromMachine(inventory.machine), deleteRemoved = true)
      t2 <- currentTimeMillis
      _  <- InventoryProcessingLogger.timing.trace(s"Saving machine: ${t2 - t1} ms")

      d2 <- con.saveTree(mapper.treeFromNode(inventory.node), deleteRemoved = true)
      t3 <- currentTimeMillis
      _  <- InventoryProcessingLogger.timing.trace(s"Saving node: ${t3 - t2} ms")

      d3 <- con
              .save(mapper.processesFromNode(inventory.node), removeMissingAttributes = false)
              .map(x => Seq(x))
              .catchAll(err => { // we don't want to fail because we tried to compensate
                InventoryProcessingLogger.error(
                  s"Couldn't update 'processes' for node '${inventory.node.main.id.value}', Error is: ${err.fullMsg}"
                ) *> Seq().succeed
              })
      t4 <- currentTimeMillis
      _  <- InventoryProcessingLogger.timing.trace(s"Saving processes: ${t4 - t3} ms")

      d4 <- ZIO.foreach(inventory.vms)(x => con.saveTree(mapper.treeFromMachine(x), deleteRemoved = true))
      t5 <- currentTimeMillis
      _  <- InventoryProcessingLogger.timing.trace(s"Saving vms: ${t5 - t4} ms")
    } yield {
      d0 ++ d1 ++ d2 ++ d3 ++ d4.flatten
    }
  }
}
