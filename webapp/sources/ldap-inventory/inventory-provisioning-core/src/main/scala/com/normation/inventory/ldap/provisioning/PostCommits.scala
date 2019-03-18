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

import com.normation.NamedZioLogger
import com.normation.errors.SystemError
import com.normation.inventory.services.provisioning._
import com.normation.inventory.domain.InventoryReport
import com.normation.inventory.domain.InventoryResult.InventoryResult
import com.normation.inventory.services.core._
import com.unboundid.ldif.LDIFChangeRecord
import com.normation.inventory.ldap.core._
import net.liftweb.common._
import com.normation.inventory.domain._
import scalaz.zio._
import scalaz.zio.syntax._

/*
 * This file contains post commit action to
 * weave in with the report saver.
 */


/**
 * Post-commit: Accept a machine in Pending Branch if
 * a server whose container is that machine already is in
 * accepted branch.
 */
class AcceptPendingMachineIfServerIsAccepted(
    fullInventoryRepositoryImpl: FullInventoryRepositoryImpl
) extends PostCommit[Seq[LDIFChangeRecord]] with Loggable {

  override val name = "post_commit_inventory:accept_pending_machine_for_accepted_server"

  override def apply(report:InventoryReport,records:Seq[LDIFChangeRecord]) : InventoryResult[Seq[LDIFChangeRecord]] = {
    (report.node.main.status, report.machine.status ) match {
      case (AcceptedInventory,  PendingInventory) =>
        // Change the container state, no need to keep the machine
        val fullInventory = FullInventory(report.node.copy(machineId = Some((report.machine.id,AcceptedInventory))),None)
        InventoryLogger.debug(s"Found machine '${report.machine.id.value}' in pending DIT but that machine is the container of the accepted node '${report.node.main.id.value}'. Moving machine to accpeted") *>
        (for {
          res       <- fullInventoryRepositoryImpl.move(report.machine.id, AcceptedInventory)
          // Save Inventory to change the container too, no need to have the machine saved again
          inventory <- fullInventoryRepositoryImpl.save(fullInventory)
          _         <- InventoryLogger.debug("Machine '%s' moved to accepted DIT".format(report.machine.id))
        } yield {
          records ++ res ++ inventory
        })

      case _ => //nothing to do, just forward to next post commit
        records.succeed
    }
  }
}

/**
 * Post-commit: Move a node from Deleted Branch to Pending
 * if a new inventory arrives from this node
 */
class PendingNodeIfNodeWasRemoved(
    writeOnlyFullInventoryRepository  : WriteOnlyFullInventoryRepository[Seq[LDIFChangeRecord]]
) extends PostCommit[Seq[LDIFChangeRecord]] with Loggable {

  override val name = "post_commit_inventory:pending_node_for_deleted_server"

  override def apply(report:InventoryReport,records:Seq[LDIFChangeRecord]) : InventoryResult[Seq[LDIFChangeRecord]] = {

    (report.node.main.status, report.machine.status ) match {
      case (RemovedInventory,  RemovedInventory) =>

        InventoryLogger.debug("Found node '%s' and machine '%s' in removed DIT but we received an inventory for it, moving them into pending".format(report.node.main.id, report.machine.id)) *>
        (for {
          res <- writeOnlyFullInventoryRepository.move(report.node.main.id, RemovedInventory, PendingInventory)
          _   <- InventoryLogger.debug("Node and machine '%s' moved to pending DIT".format(report.machine.id))
        } yield {
          records ++ res
        })

      case (RemovedInventory,  _) =>
        InventoryLogger.debug("Found node '%s' ain removed DIT but we received an inventory for it, moving it into pending and leaving the container alone".format(report.node.main.id)) *>
        (for {
          res <- writeOnlyFullInventoryRepository.moveNode(report.node.main.id, RemovedInventory, PendingInventory)
          _   <- InventoryLogger.debug("Node '%s' moved to pending DIT".format(report.node.main.id))
        } yield {
          records ++ res
        })
      case _ => //nothing to do, just forward to next post commit
        records.succeed
    }
  }
}

/**
 * A post commit which log the list of
 * modification actually done in the directory
 */
class PostCommitLogger(log:LDIFReportLogger) extends PostCommit[Seq[LDIFChangeRecord]] {

  override val name = "post_commit_inventory:log_inventory"

  override def apply(report:InventoryReport,records:Seq[LDIFChangeRecord]) : InventoryResult[Seq[LDIFChangeRecord]] = {
    log.log(
        report.name
      , Some("LDIF actually commited to the LDAP directory for given report processing")
      , Some("COMMITED")
      , records
    ).mapError(ex => SystemError("An error happen during LDIF log", ex)) *> records.succeed
  }
}
