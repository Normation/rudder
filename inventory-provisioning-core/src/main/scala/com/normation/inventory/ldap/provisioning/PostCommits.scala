/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.inventory.ldap.provisioning

import com.normation.inventory.services.provisioning._

import com.normation.inventory.domain.InventoryReport
import com.normation.inventory.services.core._
import com.unboundid.ldap.sdk.Modification
import com.unboundid.ldap.sdk.ModificationType.ADD
import com.unboundid.ldif.{LDIFRecord,LDIFChangeRecord}
import com.normation.inventory.ldap.core._
import com.normation.utils.StringUuidGenerator
import com.normation.ldap.sdk._
import BuildFilter._
import LDAPConstants._
import net.liftweb.common._
import scala.xml.NodeSeq
import com.normation.inventory.domain._
import org.slf4j.LoggerFactory



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
    writeOnlyMachineRepository:WriteOnlyMachineRepository[Seq[LDIFChangeRecord]]
) extends PostCommit[Seq[LDIFChangeRecord]] with Loggable {
  
  override val name = "post_commit_inventory:accept_pending_machine_for_accepted_server"
  
  override def apply(report:InventoryReport,records:Seq[LDIFChangeRecord]) : Box[Seq[LDIFChangeRecord]] = {
    (report.node.main.status, report.machine.status ) match {
      case (AcceptedInventory,  PendingInventory) =>
        logger.debug("Found machine '%s' in pending DIT but that machine is the container of the accepted node '%s'. Moving machine to accpeted".format(report.machine.id,report.node.main.id))
        for {
          res <- writeOnlyMachineRepository.move(report.machine.id, PendingInventory, AcceptedInventory)
        } yield {
          logger.debug("Machine '%s' moved to accepted DIT".format(report.machine.id))
          records ++ res
        }
      case _ => //nothing to do, just forward to next post commit
        Full(records)
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
  
  override def apply(report:InventoryReport,records:Seq[LDIFChangeRecord]) : Box[Seq[LDIFChangeRecord]] = {
    (report.node.main.status, report.machine.status ) match {
      case (RemovedInventory,  RemovedInventory) =>
        logger.debug("Found node '%s' and machine '%s' in removed DIT but we received an inventory for it, moving them into pending".format(report.node.main.id, report.machine.id))
        for {
          res <- writeOnlyFullInventoryRepository.move(report.node.main.id, RemovedInventory, PendingInventory)
        } yield {
          logger.debug("Node and machine '%s' moved to pending DIT".format(report.machine.id))
          records ++ res
        }
      case (RemovedInventory,  _) =>
        logger.debug("Found node '%s' ain removed DIT but we received an inventory for it, moving it into pending and leaving the container alone".format(report.node.main.id))
        for {
          res <- writeOnlyFullInventoryRepository.moveNode(report.node.main.id, RemovedInventory, PendingInventory)
        } yield {
          logger.debug("Node '%s' moved to pending DIT".format(report.node.main.id))
          records ++ res
        }
      case _ => //nothing to do, just forward to next post commit
        Full(records)
    }
  }
}

/**
 * A post commit which log the list of
 * modification actually done in the directory
 */
class PostCommitLogger(log:LDIFReportLogger) extends PostCommit[Seq[LDIFChangeRecord]] {
  
  override val name = "post_commit_inventory:log_inventory"
  
  override def apply(report:InventoryReport,records:Seq[LDIFChangeRecord]) : Box[Seq[LDIFChangeRecord]] = {
    log.log(
        report.name,
        Some("LDIF actually commited to the LDAP directory for given report processing"),
        Some("COMMITED"),
        records)
    Full(records)
  }
}


/**
 * Historize the state of the inventory just saved. 
 * We don't historize softwares, only server and machine data. 
 * 
 * We use the server DN as id for the history log. 
 * 
 * We don't historize if the only modification is the inventory date
 * (or more preciselly, any attributes in the ignoreModificationOnAttributes list)
 */
class InventoryHistorizationPostCommit(
    historyRepos:InventoryHistoryLogRepository, 
    repos:ReadOnlyFullInventoryRepository,
    ignoreModificationOnAttributes: String*
) extends PostCommit[Seq[LDIFChangeRecord]] with Loggable {

  override val name = "post_commit_inventory:historize_inventory"
  
  private[this] val ignore = ignoreModificationOnAttributes.map( _.toLowerCase).toSet
    
  override def apply(report:InventoryReport,records:Seq[LDIFChangeRecord]) : Box[Seq[LDIFChangeRecord]] = {
    //don't historize if all modification are on ignorable attributes
    if( records.find{ 
      //historize if at least one modification is done on a non-ignorable attribute
      case mod:com.unboundid.ldif.LDIFModifyChangeRecord => 
        mod.getModifications.find { m => 
          !ignore.contains(m.getAttributeName.toLowerCase) 
        }.isDefined
      //historize on all other modification type
      case _ => true
    }.isDefined) {
      val id = report.node.main.id
      //make a fresh search, we want to have exactly what is in LDAP now, after commit
      (
        for {
          full <- repos.get(id, report.node.main.status)
          hlog <- historyRepos.save(id,full)
        } yield hlog
      ) match {
        case Failure(m,_,_) => logger.info("Saving report with id %s for historization failed with error message: %s".format(id.value, m) )
        case Empty => logger.info("Saving report for historization failed without any error message.".format(id.value))
        case Full(x) => logger.debug("Saved history report '%s' for server %s". format(x.id, id.value))
      }
    } else {
      logger.debug("Not historizing inventory with id %s because only modification were ignorable".format(report.node.main.id.value))
    }
    
    //whatever happened, don't block the process
    Full(records) 
  }
  
}