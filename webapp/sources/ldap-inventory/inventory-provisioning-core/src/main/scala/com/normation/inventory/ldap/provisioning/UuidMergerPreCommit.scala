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


import com.normation.inventory.domain.InventoryReport
import com.normation.inventory.services.provisioning._
import com.normation.utils.StringUuidGenerator
import com.normation.inventory.domain._
import com.normation.inventory.ldap.core._
import net.liftweb.common._
import org.slf4j.LoggerFactory
import UuidMergerPreCommit._

object UuidMergerPreCommit {
  val logger = LoggerFactory.getLogger(classOf[UuidMergerPreCommit])
}

/**
 * this service takes care of reconciliation
 * between UUID of objects on the repository and new
 * coming object from automatic inventories.
 *
 * The service may fail if given an inconsistant report,
 * or if the database is unavailable at time of merge.
 *
 */
class UuidMergerPreCommit(
    uuidGen         : StringUuidGenerator
  , DIT             : InventoryDit
  , serverIdFinder  : NodeInventoryDNFinderAction
  , vmIdFinder      : MachineDNFinderAction
  , softwareIdFinder: SoftwareDNFinderAction
) extends PreCommit {

  override val name = "pre_commit_inventory:merge_uuid"

  /**
   * The goal of this method is:
   * - for each elements, find its ID. That may be a new ID if the automatic resolution
   *   was not able to find this element on the repos, or the ID of a element already on
   *   the repos if the reconciliation succeed ;
   * - for each elements, save it in its repos, perhaps after a merge with its last past state ;
   *
   *
   * The big problem is that we can't have any transaction here, and so we MUST take into
   * account the possibility of change between a resolution or merge and the commit.
   * Moreover, as we are in a automatic mode, all choices about such a situation
   * must be predefined (retry being optimistic ? How many time ? etc)
   *
   * @return The actually saved InventoryReport, or a failure if one happened
   */
  override def apply(report: InventoryReport) : Box[InventoryReport] = {


    ///////
    ////// we don't want to add anything about a report if any of the merging part fails /////
    //////


    //check some size matching
    if(report.node.softwareIds.toSet != report.applications.map( _.id ).toSet) {
      val m = "Inconsistant report. Server#softwareIds does not match list of application in the report"
      logger.error(m)
      return Failure(m)
    }

    /*
     * Software are special. They are legions. And they are really simple.
     * So, if one merge works, we assume that the software is the same
     * and just remove it from the list of application BUT NOT
     * from the values in server
     *
     */
    val mergedSoftwares = softwareIdFinder.tryWith(report.applications.toSet) match {
      case eb: EmptyBox =>
        val e = eb ?~! "Error when trying to find existing software UUIDs"
        logger.error(e.messageChain)
        return e
      case Full(s) => s
    }

    //update node's soft ids
    val node = report.node.copy(
        softwareIds = (mergedSoftwares.alreadySavedSoftware.map( _.id ) ++ mergedSoftwares.newSoftware.map(_.id)).toSeq
    )

    /*
     * Don't forget to update:
     * - server's software if one or more softwareId changed ;
     * - server's vms is one or more vms id changed
     */

    val vms = for {
      vm <- report.vms
    } yield { mergeVm(vm) match {
      case f@Failure(_,_,_) =>
        logger.error("Error when merging vm. Reported message: {}. Remove vm for saving", f.messageChain)
        return f
      case Empty => return Empty
      case Full(x) => x
    } }



    /*
     * We always want the node and machine to have the same status.
     * Also, we ALWAYS derive machine ID from nodeId. So we only check
     * if the nodeId is present to find the correct status.
     */

    val (finalNode : NodeInventory, finalMachine : MachineInventory) = {
      val nodeWithStatus = mergeNode(node) match {
        case fn: Failure =>
          // Error on node inventory
          logger.error(s"Error when merging node inventory. Reported message: ${fn.messageChain}. Remove machine for saving")
          return fn
        case Empty =>
          // New node, save machine and node in reporting
          node.copyWithMain { m => m.copy(status = PendingInventory) }
        case Full(n) =>
          // Existing Node, save the machine with a new id in the same status than node
          n
      }

      // now, set the correct machineId and status for machine
      val newMachineId = MachineUuid(IdGenerator.md5Hash(nodeWithStatus.main.id.value))
      val newMachine   = report.machine.copy(id = newMachineId, status = nodeWithStatus.main.status)
      val newNode      = nodeWithStatus.copy(machineId = Some((newMachineId, nodeWithStatus.main.status)))

      (newNode, newMachine)
    }

    //ok, build the merged report
    Full(InventoryReport(
      report.name,
      report.inventoryAgentDevideId,
      finalNode,
      finalMachine,
      report.version,
      vms,
      //no need to put again already saved softwares
      mergedSoftwares.newSoftware.toSeq,
      report.sourceReport
    ))
  }

  protected def mergeVm(machine:MachineInventory) : Box[MachineInventory] = {
    for {
      (uuid,status) <- vmIdFinder.tryWith(machine)
    } yield {
      machine.copy(id = uuid, status = status)
    }
  }

  protected def mergeNode(node:NodeInventory) : Box[NodeInventory] = {
    for {
      (uuid,status) <- serverIdFinder.tryWith(node)
    } yield {
      val main = node.main
      node.copy( main = main.copy(id = uuid, status = status) )
    }
  }

}
