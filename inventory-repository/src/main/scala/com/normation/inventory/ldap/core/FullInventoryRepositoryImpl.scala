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

package com.normation.inventory.ldap.core

import LDAPConstants.{A_CONTAINER_DN, A_NODE_UUID}
import com.normation.inventory.services.core._
import com.normation.inventory.domain._
import com.normation.ldap.sdk._
import net.liftweb.common._
import com.unboundid.ldif.LDIFChangeRecord
import com.unboundid.ldap.sdk.{Modification, ModificationType}
import com.normation.ldap.sdk.BuildFilter.EQ


trait LDAPFullInventoryRepository extends FullInventoryRepository[Seq[LDIFChangeRecord]]

/**
 * Default implementation of a ServerAndMachine read write repository.
 */
class FullInventoryRepositoryImpl(
    inventoryDitService:InventoryDitService,
    mapper:InventoryMapper,
    ldap:LDAPConnectionProvider[RwLDAPConnection]
) extends MachineRepository[Seq[LDIFChangeRecord]] with LDAPFullInventoryRepository with Loggable {


  /**
   * Get the expected DN of a machine from its ID and status
   */
  private[this] def dn(uuid:MachineUuid, inventoryStatus : InventoryStatus) = {
    inventoryDitService.getDit(inventoryStatus).MACHINES.MACHINE.dn(uuid)
  }
  private[this] def dn(uuid:NodeId, inventoryStatus : InventoryStatus) = {
    inventoryDitService.getDit(inventoryStatus).NODES.NODE.dn(uuid)
  }
  private[this] def nodeDn(inventoryStatus : InventoryStatus) = {
    inventoryDitService.getDit(inventoryStatus).NODES.dn
  }

  /**
   * Get a machine by its ID
   */
  override def get(id:MachineUuid, inventoryStatus : InventoryStatus) : Box[MachineInventory] = {
    for {
      con <- ldap
      tree <- con.getTree(dn(id, inventoryStatus))
      machine <- mapper.machineFromTree(tree)
    } yield {
      machine
    }
  }

  /**
   * For a given machine, find all the node on it
   */
  override def getNodes(id:MachineUuid, inventoryStatus : InventoryStatus) : Box[Seq[NodeId]] = {
    val entries = (for {
      con <- ldap
      entries = con.searchOne(nodeDn(inventoryStatus), EQ(A_CONTAINER_DN, dn(id, inventoryStatus).toString()), A_NODE_UUID)
    } yield {
      entries
    })
    entries match {
      case e: EmptyBox => e
      case Full(seq) =>
         com.normation.utils.Control.boxSequence(seq.map(x => inventoryDitService.getDit(inventoryStatus).NODES.NODE.idFromDN(x.dn)))
    }
  }


  override def save(machine:MachineInventory) : Box[Seq[LDIFChangeRecord]] = {
    for {
      con <- ldap
      res <- con.saveTree(mapper.treeFromMachine(machine))
    } yield res
  }

  override def delete(id:MachineUuid, inventoryStatus : InventoryStatus) : Box[Seq[LDIFChangeRecord]] = {
    for {
       con <- ldap
       res <- con.delete(dn(id, inventoryStatus))
    } yield res
  }


  override def move(id:MachineUuid, from: InventoryStatus, into : InventoryStatus) : Box[Seq[LDIFChangeRecord]] = {
    if(from == into ) Full(Seq())
    else {
      for {
        con <- ldap
        dnFrom = dn(id, from)
        dnTo = dn(id, into)
        moved <- con.move(dnFrom, dnTo.getParent)
      } yield {
       Seq(moved)
      }
    }
  }

  /**
   * Note: it may happen strange things between get:ServerAndMachine and Node if there is
   * several machine for one server, and that the first retrieved is not always the same.
   */
  override def getMachineId(id:NodeId, inventoryStatus : InventoryStatus) : Box[(MachineUuid,InventoryStatus)] = {
    for {
      con <- ldap
      entry <- con.get(dn(id, inventoryStatus), A_CONTAINER_DN)
      machineId <- Box(mapper.mapSeqStringToMachineIdAndStatus(entry.valuesFor(A_CONTAINER_DN)).headOption)
    } yield {
      machineId
    }
  }

  override def get(id:NodeId, inventoryStatus : InventoryStatus) : Box[FullInventory] = {
    for {
      con <- ldap
      tree <- con.getTree(dn(id, inventoryStatus))
      server <- mapper.nodeFromTree(tree)
      //now, try to add a machine
      optMachine <- {
        server.machineId match {
          case None => Full(None)
          case Some((machineId, status)) => this.get(machineId,status) match {
            case Empty => Full(None)
            case Full(x) => Full(Some(x))
            case f:Failure => f
          }
        }
      }
    } yield {
      FullInventory(server, optMachine)
    }
  }

  override def save(inventory:FullInventory, inventoryStatus : InventoryStatus) : Box[Seq[LDIFChangeRecord]] = {
    for {
      con <- ldap
      resServer <- con.saveTree(mapper.treeFromNode(inventory.node))
      resMachine <- inventory.machine match {
        case None => Full(Seq())
        case Some(m) => this.save(m)
      }
    } yield resServer ++ resMachine
  }

  override def delete(id:NodeId, inventoryStatus : InventoryStatus) : Box[Seq[LDIFChangeRecord]] = {
    for {
      con <- ldap
      //if there is a machine, delete it. Continu on error, but on success log ldiff records
      machineRecord <- {
        (for {
          (machineId,machineStatus) <- getMachineId(id, inventoryStatus)
          res <- this.delete(machineId, machineStatus)
        } yield {
          (machineId, machineStatus, res)
        }) match {
          case Empty => Full(Seq())
          case Full((machineId, machineStatus, diff)) => Full(diff)
          case f:Failure =>
            logger.warn("Error when trying to delete machine for server with id '%s' and inventory status '%s'. Message was: %s".
                format(id.value,inventoryStatus,f.msg))
            Full(Seq())
        }
      }
      res <- con.delete(dn(id, inventoryStatus))
    } yield {
      res ++ machineRecord
    }
  }

  override def moveNode(id:NodeId, from: InventoryStatus, into : InventoryStatus) : Box[Seq[LDIFChangeRecord]] = {
    if(from == into ) Full(Seq())
    else
      for {
        con <- ldap
        dnFrom = dn(id, from)
        dnTo = dn(id, into)
        moved <- con.move(dnFrom, dnTo.getParent)
      } yield {
        Seq(moved)
      }
  }

  override def move(id:NodeId, from: InventoryStatus, into : InventoryStatus) : Box[Seq[LDIFChangeRecord]] = {
    def moveMachine(con:RwLDAPConnection) : Box[Seq[LDIFChangeRecord]] = {
      //given the sequence of the move (start with node, then machine)
      //when that method is called, the node is already in "into" branch
      getMachineId(id, into) match {
        case Empty => Full(Seq()) //the node may not have a machine, stop here
        case f:Failure => f
        case Full((machineId, status)) =>
          if(status == into) { //the machine is already in the same place than the node, great !
            Full(Seq())
          } else {
            // is the machine linked to others machines ?
            getNodes(machineId, status) match {
              case e:EmptyBox => logger.error("cannot fetch nodes linked to machine %s, cause %s".format(machineId, e)); e
              case Full(seq) =>
                if (seq.size>1) {
                  // more than one node linked to this machine, it has to be copied
                  get(machineId, status) match {
                    case e:EmptyBox => logger.error("cannot fetch machine linked to node %s, cause %s".format(id.value, e)); e
                    case Full(entry) =>
                      save( entry.copy(status = RemovedInventory))
                  }
                } else {
                  // only this node on this machine, it has to be moved
                  for {
                    machineMoved <- move(machineId,status,into)
                    //update reference dn for that machine in node
                    updatedContainer <- con.modify(
                        dn(id,into),
                        new Modification(ModificationType.REPLACE, A_CONTAINER_DN, dn(machineId,into).toString)
                    )
                  } yield {
                    machineMoved :+ updatedContainer
                  }
                }
            }
          }
      }
    }

    def deleteMachine(where:InventoryStatus) : Box[LDIFChangeRecord] = {
      for {
        con              <- ldap
        updatedContainer <- con.modify(
                              dn(id,where),
                              new Modification(ModificationType.DELETE, A_CONTAINER_DN)
                            )
      } yield {
        updatedContainer
      }
    }

    for {
      con <- ldap
      dnFrom = dn(id, from)
      dnTo = dn(id, into)
      moved <- con.move(dnFrom, dnTo.getParent)
    } yield {
      //try to move the referenced machine too
      moveMachine(con) match {
        case empty:EmptyBox =>
          val e = (empty ?~! ("Error when moving machine referenced by container for node '%s'. ".format(id.value) +
              "We will assume that the machine was deleted, and remove the reference in node"))
          logger.debug(e.failureChain.map( _.msg).mkString("", "\n     cause:", ""))

          deleteMachine(into) match {
            case e:EmptyBox =>
              logger.debug("Error when trying to delete bad container ID for node '%s'".format(id.value))
              Seq(moved)
            case Full(mod) =>
              moved +: Seq(mod)
          }
        case Full(machineMoved) =>
          moved +: machineMoved
      }
    }
  }
}

