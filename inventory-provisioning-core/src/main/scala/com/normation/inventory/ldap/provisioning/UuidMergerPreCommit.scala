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


import com.normation.inventory.domain.InventoryReport
import com.normation.inventory.services.provisioning._
import com.normation.utils.StringUuidGenerator
import com.normation.inventory.domain._
import com.normation.inventory.ldap.core._
import net.liftweb.common._
import org.slf4j.{Logger,LoggerFactory}
import scala.collection.mutable.Buffer
import UuidMergerPreCommit._
import java.security.MessageDigest

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
    uuidGen:StringUuidGenerator,
    DIT:InventoryDit,
    serverIdFinder:NodeInventoryDNFinderAction,
    machineIdFinder:MachineDNFinderAction,
    vmIdFinder:MachineDNFinderAction,
    softwareIdFinder:SoftwareDNFinderAction
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
  override def apply(report:InventoryReport) : Box[InventoryReport] = {

    /*
     * Machines are difficult to handle. For each one, we have to decide if it's a
     * virtual PhysicalMachine or real PhysicalMachine. If we found that's one or the other, we have
     * to specifically look in the matching repository to try to find if it already
     * contains that element (and if so, get back its id).
     * If we are not able to say if it's a real or virtual PhysicalMachine, we have to test
     * both repositories.
     * At the end, if we hadn't found an id, we have to generate one.
     * But we may be in a case where we don't know if the machine is a real or virtual
     * PhysicalMachine, and so we must have an "unspecified land", waiting for human input.
     * And so, we have to test that unspecified land to for an id.
     */

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
    var node = report.node.copy(
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

    val (finalNode : NodeInventory,finalMachine : MachineInventory) = (mergeNode(node),mergeMachine(report.machine)) match {
      case (fn : Failure,_) =>
        // Error on node inventory
        logger.error(s"Error when merging node inventory. Reported message: ${fn.messageChain}. Remove machine for saving")
        return fn
      case (_, fm: Failure) =>
        // Error on machine inventory
        logger.error(s"Error when merging machine inventory. Reported message: ${fm.messageChain}. Remove machine for saving")
        return fm
      case( Empty,_) =>
        // New node, save machine and node in reporting
        val pendingInventory = node.copyWithMain { m => m.copy(status = PendingInventory) }
        harmonizeNodeAndMachine(pendingInventory,report.machine)
      case (Full(node), Empty) =>
        // Existing Node, save the machine with a new id in the same status than node
        harmonizeNodeAndMachine(node,report.machine)
      case (Full(node),Full(machine)) =>
        // Existing node, with machine inventory
        if (node.main.status == machine.status) {
          // All Ok, keep info like this
          (node,machine)
        } else {
          // Machine and node don't have the same status, update status of the machine by creating a new inventory
          // This will not remove the previous machine inventory with bad info
          harmonizeNodeAndMachine(node,machine)
        }
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

  /**
   * Harmonize Node and Machine inventory
   * Generate a new id for the Machine, based on the node id
   * Use the same status for both inventory
   */
  private[this] def harmonizeNodeAndMachine(node : NodeInventory, machine : MachineInventory) = {
    val newMachineId = {
      val md5 = MessageDigest.getInstance("MD5").digest(node.main.id.value.getBytes)
      val id = (md5.map(0xFF & _).map { "%02x".format(_) }.foldLeft(""){_ + _}).toLowerCase

      MachineUuid(s"${id.substring(0,8)}-${id.substring(8,12)}-${id.substring(12,16)}-${id.substring(16,20)}-${id.substring(20)}")
    }
    val newMachine = machine.copy(id = newMachineId, status = node.main.status)
    val newNode = node.copy(machineId = Some((newMachineId,node.main.status)))
    (newNode,newMachine)
  }

  protected def mergeMachine(machine:MachineInventory) : Box[MachineInventory] = {
    for {
      (uuid,status) <- machineIdFinder.tryWith(machine)
    } yield {
      machine.copy(id = uuid, status = status)
    }
  }


  /*
   * for now, vm merging is almost the same of machine merging.
   * We should think about having specif uuid finders for VMs.
   */
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
