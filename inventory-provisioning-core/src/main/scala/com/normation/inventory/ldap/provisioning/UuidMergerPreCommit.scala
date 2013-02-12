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

import net.liftweb.common.{Box,Empty,Failure,Full}

import org.slf4j.{Logger,LoggerFactory}

import scala.collection.mutable.Buffer

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
    var applications = List.empty[Software]
    val toUpdate = scala.collection.mutable.Map.empty[/* from */ SoftwareUuid, /* to */ SoftwareUuid]

    report.applications.foreach { s =>
      mergeSoftware(s) match {
        //these one were already saved : update Node#softwareId, but remove them from the list of soft to save
        case Full(soft) if(soft.id != s.id) => toUpdate += (s.id -> soft.id)
        //these ones were not in the backend. Keep them for saving
        case Full(soft) => applications = soft :: applications
        case Empty => None
        case f:Failure =>
          logger.error(f.msg)
          None
      }
    }

    //update node's soft ids
    var node = report.node.copy(
        softwareIds = report.node.softwareIds.map { id => toUpdate.get(id) match {
          case None => id
          case Some(nid) => nid
        } }
    )


    /*
     * Don't forget to update:
     * - server's software if one or more softwareId changed ;
     * - server's vms is one or more vms id changed
     */

    val machine = mergeMachine(report.machine) match {
      case f@Failure(m,e,c) =>
        logger.error("Error when merging machine. Reported message: {}. Remove machine for saving", f.messageChain)
        return f
      case Empty => //new machine. Use what is given as UUID, save in Pending
        report.machine.copy(status = PendingInventory )
      case Full(fm) =>  fm
    }

    val vms = for {
      vm <- report.vms
    } yield { mergeVm(vm) match {
      case f@Failure(_,_,_) =>
        logger.error("Error when merging vm. Reported message: {}. Remove vm for saving", f.messageChain)
        return f
      case Empty => return Empty
      case Full(x) => x
    } }

    node = mergeNode(node) match {
      case f@Failure(m,e,c) =>
        logger.error("Error when merging machine. Reported message: {}. Remove machine for saving", f.messageChain)
        return f
      case Empty => //new node. Use what is given as UUID, save in Pending
        node.copyWithMain { m => m.copy(status = PendingInventory) }
      case Full(s) => s
    }

    node = node.copy(
        machineId = Some(machine.id, machine.status)
    )

    //here, set all the software Id after merge
/*    node.softwareIds.set(softwareAndId.map(x => x._2.id.getOrElse {
      val m = "Error when processing software ids for '%s' in report %s".format(x,report.name)
      logger.error(m)
      return Failure(m)
    })) //id undefined here is an error
*/

    //ok, build the merged report
    Full(InventoryReport(
      report.name,
      report.inventoryAgentDevideId,
      node,
      machine,
      report.version,
      vms,
      applications,
      report.sourceReport
    ))
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

  protected def mergeSoftware(software:Software) : Box[Software] = {
    softwareIdFinder.tryWith(software) match {
      case f@Failure(_,_,_) => f
      case Empty =>
        logger.debug("Use new generated ID for software: {}",software.id)
        Full(software)
      case Full(id) => Full(software.copy(id))
    }
  }
}
