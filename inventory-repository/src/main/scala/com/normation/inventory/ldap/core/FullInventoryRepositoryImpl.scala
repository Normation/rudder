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


import com.normation.utils.Control.{bestEffort, sequence}
import LDAPConstants.{A_CONTAINER_DN, A_NODE_UUID}
import com.normation.inventory.services.core._
import com.normation.inventory.domain._
import com.normation.ldap.sdk._
import net.liftweb.common._
import com.unboundid.ldif.LDIFChangeRecord
import com.unboundid.ldap.sdk.{Modification, ModificationType}
import com.normation.ldap.sdk.BuildFilter.EQ
import com.unboundid.ldap.sdk.DN
import com.normation.ldap.ldif.LDIFNoopChangeRecord


trait LDAPFullInventoryRepository extends FullInventoryRepository[Seq[LDIFChangeRecord]]

/**
 * Default implementation of a ServerAndMachine read write repository.
 */
class FullInventoryRepositoryImpl(
    inventoryDitService: InventoryDitService
  , mapper             : InventoryMapper
  , ldap               : LDAPConnectionProvider[RwLDAPConnection]
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

  private[this] def findDnForMachine(con: RwLDAPConnection, id:MachineUuid): Option[DN] = {
    Seq(AcceptedInventory, PendingInventory, RemovedInventory).find(s => con.exists(dn(id,s))).map(dn(id, _))
  }

  /*
   * return the list of status for machine, in the order:
   * index 0: Accepted
   * index 1: Pending
   * index 2: Removed
   *
   */
  private[this] def getExistingMachineDN(con: RwLDAPConnection, id: MachineUuid): Seq[(Boolean, DN)] = {
    val status = Seq(AcceptedInventory, PendingInventory, RemovedInventory)
    status.map{ x =>
      val d = dn(id, x)
      val exists = con.exists(d)
      (exists, d)
    }
  }

  /**
   * Get a machine by its ID
   */
  override def get(id:MachineUuid) : Box[MachineInventory] = {
    for {
      con <- ldap
      tree <- {
        findDnForMachine(con, id).map(con.getTree(_)).getOrElse(Empty)
      }
      machine <- mapper.machineFromTree(tree)
    } yield {
      machine
    }
  }

  /*
   * For a given machine, find all the node that use it
   * Return node entries with only the container attribute, in a map with
   * the node status for key.
   */
  def getNodesForMachine(con: RwLDAPConnection, id:MachineUuid) : Map[InventoryStatus, Set[LDAPEntry]] = {

    val status = Seq(PendingInventory, AcceptedInventory, RemovedInventory)
    val orFilter = BuildFilter.OR(status.map(x => EQ(A_CONTAINER_DN,dn(id, x).toString)):_*)

    def machineForNodeStatus(con:RwLDAPConnection, inventoryStatus: InventoryStatus) = {
      con.searchOne(nodeDn(inventoryStatus),  orFilter, A_NODE_UUID).toSet
    }

    //only add keys for non empty node list
    Seq(PendingInventory, AcceptedInventory, RemovedInventory).map(x => (x, machineForNodeStatus(con, x))).filterNot( _._2.isEmpty).toMap
  }

  /*
   * Update the list of node, setting the container value to the one given.
   * Delete the attribute if None.
   */
  private[this] def updateNodes(con: RwLDAPConnection, nodes: Map[InventoryStatus, Set[LDAPEntry]], newMachineId:Option[(MachineUuid, InventoryStatus)] ) = {
    import com.normation.utils.Control.bestEffort
    val mod = newMachineId match {
      case None => new Modification(ModificationType.DELETE, A_CONTAINER_DN)
      case Some((id, status)) => new Modification(ModificationType.REPLACE, A_CONTAINER_DN, dn(id, status).toString)
    }

    bestEffort(nodes.values.flatten.map( _.dn).toSeq) { dn =>
      con.modify(dn, mod)
    }

  }

  override def save(machine:MachineInventory) : Box[Seq[LDIFChangeRecord]] = {
    for {
      con <- ldap
      res <- con.saveTree(mapper.treeFromMachine(machine))
    } yield res
  }

  override def delete(id:MachineUuid) : Box[Seq[LDIFChangeRecord]] = {
    for {
       con <- ldap
       machines = getExistingMachineDN(con,id).collect { case(exists,dn) if exists => dn }
       res <- bestEffort(machines) { dn =>
                con.delete(dn)
              }
       nodes <- updateNodes(con, getNodesForMachine(con, id), None)
    } yield res.flatten ++ nodes
  }



  /**
   * We want to actually move the machine toward the same place
   * as the node of highest priority (accepted > pending > removed),
   * and if no node, to the asked place.
   */
  override def move(id:MachineUuid, into : InventoryStatus) : Box[Seq[LDIFChangeRecord]] = {
    val priorityStatus = Seq(AcceptedInventory, PendingInventory, RemovedInventory)
    for {
      con <- ldap
      nodes = getNodesForMachine(con, id)
      intoStatus = priorityStatus.find(x => nodes.isDefinedAt(x) && nodes(x).size > 0).getOrElse(into)
      //now, check what to do:
      //if the machine is already in the target, does nothing
      //else, move
      moved <- {
                 val machinePresences = getExistingMachineDN(con, id)

                 val machineToKeep = machinePresences.find( _._1 ).map( _._2)

                 machineToKeep match {
                   case None => Full(Seq())
                   case Some(machineDN) =>

                     def testAndMove(i:Int) = {
                       for {
                         moved <- if(machinePresences(i)._1) {
                                    //if there is already a machine at the destination,
                                    //keep it and delete the other one
                                    con.delete(machineDN)
                                  } else {
                                    con.move(machineDN, dn(id, intoStatus).getParent).map(Seq(_))
                                  }
                       } yield {
                         moved
                       }
                     }
                     intoStatus match {
                       case AcceptedInventory => testAndMove(0)
                       case PendingInventory => testAndMove(1)
                       case RemovedInventory => testAndMove(2)
                     }
                 }
               }
      nodes <- updateNodes(con, nodes, Some((id, intoStatus)))
    } yield {
     moved++nodes
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

  override def getAllNodeInventories(inventoryStatus : InventoryStatus): Box[Map[NodeId, NodeInventory]] = {
    for {
      con       <- ldap
      nodeTrees <- con.getTree(inventoryDitService.getDit(inventoryStatus).NODES.dn)
      nodes     <- sequence(nodeTrees.children.values.toSeq) { tree => mapper.nodeFromTree(tree) }
    } yield {
      nodes.map(n => (n.main.id, n)).toMap
    }
  }


  override def getAllInventories(inventoryStatus : InventoryStatus): Box[Map[NodeId, FullInventory]] = {

    for {
      con  <- ldap
      dit  = inventoryDitService.getDit(inventoryStatus)
      // Get base tree, we will go into each subtree after
      tree <- con.getTree(dit.BASE_DN)

      // Get into Nodes subtree
      nodeTree    <- tree.children.get(dit.NODES.rdn) match {
                      case None => Failure(s"Could not find node inventories in ${dit.BASE_DN}")
                      case Some(tree) => Full(tree)
                     }
      nodes       <- sequence(nodeTree.children.values.toSeq) { tree => mapper.nodeFromTree(tree) }

      // Get into Machines subtree
      machineTree <- tree.children.get(dit.MACHINES.rdn) match {
                       case None => Failure(s"Could not find machine inventories in ${dit.BASE_DN}")
                       case Some(tree) => Full(tree)
                     }
      machines    <- sequence(machineTree.children.values.toSeq) { tree => mapper.machineFromTree(tree) }

    } yield {
      val machineMap =  machines.map(m => (m.id, m)).toMap
      nodes.map(
        node => {
          val machine = node.machineId.flatMap(mid => machineMap.get(mid._1))
          val inventory = FullInventory(node,machine)
          node.main.id ->  inventory
      } ).toMap
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
          case Some((machineId, status)) =>
            //here, we want to actually use the provided DN to:
            // 1/ not make 3 existence tests each time we get a node,
            // 2/ make the thing more debuggable. If we don't use the DN and display
            //    information taken elsewhere, future debugging will leads people to madness
            con.getTree(dn(machineId, status)) match {
            case Empty => Full(None)
            case Full(x) => mapper.machineFromTree(x).map(Some(_))
            case f:Failure => f
          }
        }
      }
    } yield {
      FullInventory(server, optMachine)
    }
  }

  override def save(inventory:FullInventory) : Box[Seq[LDIFChangeRecord]] = {
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
      //if there is only one node using the machine, delete it. Continue on error, but on success log ldif records
      //if several node use it, does nothing
      machineRecord <- {
        (for {
          (machineId,_) <- getMachineId(id, inventoryStatus)
          res <- {
                    val nodes = getNodesForMachine(con, machineId).values.flatten.size
                    if(nodes < 2) {
                      this.delete(machineId)
                    } else {
                      Full(Seq())
                    }
                  }
        } yield {
          (machineId, res)
        }) match {
          case Empty => Full(Seq())
          case Full((machineId, diff)) => Full(diff)
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
    for {
      con <- ldap
      dnFrom = dn(id, from)
      dnTo = dn(id, into)
      moved <- con.move(dnFrom, dnTo.getParent)
    } yield {
      // try to move the referenced machine too, but move it to the same
      // status that the more prioritary node with
      // accepted > pending > deleted
      // logic in machine#move
      val machineMoved = (for {
        (machineId, _) <- getMachineId(id, into)
        moved <- move(machineId, into)
      } yield {
        moved
      }) match {
        case eb:EmptyBox =>
          val e = eb ?~! s"Error when updating the container value when moving nodes '${id.value}'"
          logger.error(e.messageChain)
          e.rootExceptionCause.foreach { ex =>
            logger.error("Exception was: ", ex)
          }
          Seq()
        case Full(diff) => diff
      }
      Seq(moved) ++ machineMoved
    }
  }
}

