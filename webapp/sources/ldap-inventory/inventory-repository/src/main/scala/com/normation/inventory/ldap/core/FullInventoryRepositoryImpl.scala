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

package com.normation.inventory.ldap.core

import cats.data.NonEmptyList
import com.normation.errors.*
import com.normation.inventory.domain.*
import com.normation.inventory.ldap.core.LDAPConstants.A_CONTAINER_DN
import com.normation.inventory.ldap.core.LDAPConstants.A_NODE_UUID
import com.normation.inventory.services.core.*
import com.normation.ldap.sdk.*
import com.normation.ldap.sdk.BuildFilter.EQ
import com.normation.ldap.sdk.LDAPIOResult.*
import com.normation.utils.HostnameRegex
import com.normation.utils.NodeIdRegex
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.Filter
import com.unboundid.ldap.sdk.Modification
import com.unboundid.ldap.sdk.ModificationType
import com.unboundid.ldif.LDIFChangeRecord
import zio.*
import zio.syntax.*

trait LDAPFullInventoryRepository extends FullInventoryRepository[Seq[LDIFChangeRecord]]

/**
 * Default implementation of a ServerAndMachine read write repository.
 */
class FullInventoryRepositoryImpl(
    inventoryDitService: InventoryDitService,
    mapper:              InventoryMapper,
    ldap:                LDAPConnectionProvider[RwLDAPConnection]
) extends MachineRepository[Seq[LDIFChangeRecord]] with LDAPFullInventoryRepository {

  /**
   * Get the expected DN of a machine from its ID and status
   */
  private def dnMachine(uuid: MachineUuid, inventoryStatus: InventoryStatus) = {
    inventoryDitService.getDit(inventoryStatus).MACHINES.MACHINE.dn(uuid)
  }
  private def dn(uuid: NodeId, inventoryStatus: InventoryStatus)             = {
    // TODO: scala3 migration - bug: https://github.com/lampepfl/dotty/issues/16467
    inventoryDitService.getDit(inventoryStatus).NODES.NODE.dn(uuid.value)
  }
  private def nodeDn(inventoryStatus: InventoryStatus)                       = {
    inventoryDitService.getDit(inventoryStatus).NODES.dn
  }

  /*
   * return the list of status for machine, in the order:
   * index 0: Accepted
   * index 1: Pending
   * index 2: Removed
   *
   */
  private def getExistingMachineDN(con: RwLDAPConnection, id: MachineUuid): LDAPIOResult[Seq[(Boolean, DN)]] = {
    val status = Seq(AcceptedInventory, PendingInventory, RemovedInventory)
    ZIO.foreach(status) { x =>
      val d = dnMachine(id, x)
      con.exists(d).map(exists => (exists, d))
    }
  }

  /*
   * find the first dn matching ID, starting with accepted, then pending, then deleted
   */
  private def findDnForId[ID](
      con: RwLDAPConnection,
      id:  ID,
      fdn: (ID, InventoryStatus) => DN
  ): LDAPIOResult[Option[(DN, InventoryStatus)]] = {
    ZIO.foldLeft(Seq(AcceptedInventory, PendingInventory, RemovedInventory))(Option.empty[(DN, InventoryStatus)]) {
      (current, inventory) =>
        current match {
          case None       =>
            val testdn = fdn(id, inventory)
            for {
              res <- con.exists(testdn)
            } yield {
              if (res) Some((testdn, inventory)) else None
            }
          case Some(pair) => Some(pair).succeed
        }

    }
  }

  def getStatus(nodeId: NodeId): IOResult[Option[InventoryStatus]] = {
    for {
      con <- ldap
      res <- findDnForId[NodeId](con, nodeId, dn)
    } yield res.map(_._2)
  }

  /**
   * Get a machine by its ID
   */
  override def getMachine(id: MachineUuid): IOResult[Option[MachineInventory]] = {
    for {
      con     <- ldap
      optDn   <- findDnForId[MachineUuid](con, id, dnMachine)
      machine <- optDn match {
                   case Some((x, _)) =>
                     for {
                       tree    <- con.getTree(x)
                       machine <- tree match {
                                    case None    => None.succeed
                                    case Some(t) => mapper.machineFromTree(t).map(Some(_))
                                  }
                     } yield {
                       machine
                     }
                   case None         =>
                     None.succeed
                 }
    } yield {
      machine
    }
  }.chainError(s"Error when getting machine with ID '${id.value}'")

  /*
   * For a given machine, find all the node that use it
   * Return node entries with only the container attribute, in a map with
   * the node status for key.
   */
  def getNodesForMachine(con: RwLDAPConnection, id: MachineUuid): LDAPIOResult[Map[InventoryStatus, Set[LDAPEntry]]] = {

    val status   = Seq(PendingInventory, AcceptedInventory, RemovedInventory)
    val orFilter = BuildFilter.OR(status.map(x => EQ(A_CONTAINER_DN, dnMachine(id, x).toString))*)

    def machineForNodeStatus(con: RwLDAPConnection, inventoryStatus: InventoryStatus) = {
      con.searchOne(nodeDn(inventoryStatus), orFilter, A_NODE_UUID).map(_.toSet)
    }

    // only add keys for non empty node list
    for {
      res <- ZIO.foreach(List(PendingInventory, AcceptedInventory, RemovedInventory)) { status =>
               machineForNodeStatus(con, status).map(r => (status, r))
             }
    } yield {
      res.filterNot(_._2.isEmpty).toMap
    }
  }

  /*
   * Update the list of node, setting the container value to the one given.
   * Delete the attribute if None.
   */
  private def updateNodes(
      con:          RwLDAPConnection,
      nodes:        Map[InventoryStatus, Set[LDAPEntry]],
      newMachineId: Option[(MachineUuid, InventoryStatus)]
  ): LDAPIOResult[List[LDIFChangeRecord]] = {
    val mod = newMachineId match {
      case None               => new Modification(ModificationType.DELETE, A_CONTAINER_DN)
      case Some((id, status)) => new Modification(ModificationType.REPLACE, A_CONTAINER_DN, dnMachine(id, status).toString)
    }

    val res: ZIO[Any, NonEmptyList[LDAPRudderError], List[LDIFChangeRecord]] =
      nodes.values.flatten.map(_.dn).accumulateNEL(dn => con.modify(dn, mod))
    res.toLdapResult
  }

  override def save(machine: MachineInventory): IOResult[Seq[LDIFChangeRecord]] = {
    for {
      con <- ldap
      res <- con.saveTree(mapper.treeFromMachine(machine), deleteRemoved = true)
    } yield res
  }.chainError(s"Error when saving machine with ID '${machine.id.value}'")

  override def delete(id: MachineUuid): IOResult[Seq[LDIFChangeRecord]] = {
    for {
      con      <- ldap
      machines <- getExistingMachineDN(con, id).map(_.collect { case (exists, dn) if exists => dn }.toList)
      res      <- machines.accumulateNEL(dn => con.delete(dn)).toLdapResult
      machine  <- getNodesForMachine(con, id)
      nodes    <- updateNodes(con, machine, None)
    } yield res.flatten ++ nodes
  }.chainError(s"Error when deleting machine with ID '${id.value}'")

  /**
   * We want to actually move the machine toward the same place
   * as the node of highest priority (accepted > pending > removed),
   * and if no node, to the asked place.
   */
  override def move(id: MachineUuid, into: InventoryStatus): IOResult[Seq[LDIFChangeRecord]] = {
    val priorityStatus = Seq(AcceptedInventory, PendingInventory, RemovedInventory)
    for {
      con              <- ldap
      nodes            <- getNodesForMachine(con, id)
      intoStatus        = priorityStatus.find(x => nodes.isDefinedAt(x) && nodes(x).nonEmpty).getOrElse(into)
      // now, check what to do:
      // if the machine is already in the target, does nothing
      // else, move
      machinePresences <- getExistingMachineDN(con, id)
      moved            <- {
        val machineToKeep = machinePresences.find(_._1).map(_._2)

        machineToKeep match {
          case None            => Seq().succeed
          case Some(machineDN) =>
            def testAndMove(i: Int) = {
              for {
                moved <- if (machinePresences(i)._1) {
                           // if there is already a machine at the destination,
                           // keep it and delete the other one
                           con.delete(machineDN)
                         } else {
                           con.move(machineDN, dnMachine(id, intoStatus).getParent).map(Seq(_))
                         }
              } yield {
                moved
              }
            }
            intoStatus match {
              case AcceptedInventory => testAndMove(0)
              case PendingInventory  => testAndMove(1)
              case RemovedInventory  => testAndMove(2)
            }
        }
      }
      nodes            <- updateNodes(con, nodes, Some((id, intoStatus)))
    } yield {
      moved ++ nodes
    }
  }.chainError(s"Error when moving machine with ID '${id.value}'")

  /**
   * Note: it may happen strange things between get:ServerAndMachine and Node if there is
   * several machine for one server, and that the first retrieved is not always the same.
   */
  override def getMachineId(id: NodeId, inventoryStatus: InventoryStatus): IOResult[Option[(MachineUuid, InventoryStatus)]] = {
    for {
      con       <- ldap
      entry     <- con.get(dn(id, inventoryStatus), A_CONTAINER_DN)
      machineId <- entry match {
                     case None    => None.succeed
                     case Some(e) => mapper.mapSeqStringToMachineIdAndStatus(e.valuesFor(A_CONTAINER_DN)).headOption.succeed
                   }
    } yield {
      machineId
    }
  }.chainError(s"Error when getting machine with ID '${id.value}' and status '${inventoryStatus.name}'")

  // utility methods to get node/machine from ldap entries from the base dn
  private[core] def machinesFromOuMachines(machinesTree: LDAPTree): IOResult[Iterable[Option[MachineInventory]]] = {
    ZIO.foreach(machinesTree.children.values) { tree =>
      mapper
        .machineFromTree(tree)
        .foldZIO(
          err =>
            InventoryDataLogger.error(
              s"Error when mapping inventory data for entry '${tree.root.rdn.map(_.toString).getOrElse("")}': ${err.fullMsg}"
            ) *> None.succeed,
          ok => Some(ok).succeed
        )
    }
  }

  private[core] def nodesFromOuNodes(nodesTree: LDAPTree): IOResult[Iterable[Option[NodeInventory]]] = {
    ZIO.foreach(nodesTree.children.values) { tree =>
      mapper
        .nodeFromTree(tree)
        .foldZIO(
          err =>
            InventoryDataLogger.error(
              s"Error when mapping inventory data for entry '${tree.root.rdn.map(_.toString).getOrElse("")}': ${err.fullMsg}"
            ) *> None.succeed,
          ok => Some(ok).succeed
        )
    }
  }

  def getInventories(inventoryStatus: InventoryStatus, nodeIds: Set[NodeId]): IOResult[Map[NodeId, FullInventory]] = {
    /*
     * We need to only get back the tree for nodes that we are looking for, we need to build filter like:
     * "(entryDN:dnSubtreeMatch:=cn=group_a,dc=abc,dc=xyz)"
     */
    def buildSubTreeFilter[A](base: DN, ids: List[A], dnToString: A => String): Filter = {
      val dnFilters = Filter.create(s"entryDN=${base.toString()}") :: ids
        .map(dnToString)
        .sorted
        .map(a => Filter.create(s"entryDN:dnSubtreeMatch:=${a}"))
      BuildFilter.OR(dnFilters*)
    }

    for {
      con       <- ldap
      dit        = inventoryDitService.getDit(inventoryStatus)
      // Get base tree, we will go into each subtree after
      nodesTree <- con
                     .getTreeFilter(
                       dit.NODES.dn,
                       // scala 3.3.3 resolve U as String in NODE unless we tell it it's NodeId
                       buildSubTreeFilter[NodeId](dit.NODES.dn, nodeIds.toList, id => (dit.NODES.NODE : UUID_ENTRY[NodeId]).dn(id).toString)
                     )
                     .notOptional(s"Missing node root tree")

      // we don't want that one error somewhere breaks everything
      nodes     <- nodesFromOuNodes(nodesTree).map(_.flatten)

      machineUuids = nodes.flatMap(_.machineId.map(_._1))

      // Get into Machines subtree
      machinesTree <-
        con
          .getTreeFilter(
            dit.MACHINES.dn,
            buildSubTreeFilter[MachineUuid](dit.MACHINES.dn, machineUuids.toList, id => dit.MACHINES.MACHINE.dn(id).toString)
          )
          .notOptional(s"Missing machine root tree")

      machines <- machinesFromOuMachines(machinesTree)
    } yield {
      val machineMap = machines.flatten.map(m => (m.id, m)).toMap
      nodes
        .map(node => {
          val machine   = node.machineId.flatMap(mid => machineMap.get(mid._1))
          val inventory = FullInventory(node, machine)
          node.main.id -> inventory
        })
        .toMap
    }
  }

  override def get(id: NodeId, inventoryStatus: InventoryStatus): IOResult[Option[FullInventory]] = {
    getWithSoftware(id, inventoryStatus, getSoftware = false).map(_.map(_._1))
  }

  // if getSoftware is true, return the seq of software UUIDs, else an empty seq in addition to inventory.
  def getWithSoftware(
      id:              NodeId,
      inventoryStatus: InventoryStatus,
      getSoftware:     Boolean
  ): IOResult[Option[(FullInventory, Seq[SoftwareUuid])]] = {
    (
      for {
        con        <- ldap
        tree       <- con.getTree(dn(id, inventoryStatus))
        server     <- tree match {
                        case Some(t) => mapper.nodeFromTree(t).map(Some(_))
                        case None    => None.succeed
                      }
        // now, try to add a machine
        optMachine <- {
          server.flatMap(_.machineId) match {
            case None                      => None.succeed
            case Some((machineId, status)) =>
              // here, we want to actually use the provided DN to:
              // 1/ not make 3 existence tests each time we get a node,
              // 2/ make the thing more debuggable. If we don't use the DN and display
              //    information taken elsewhere, future debugging will leads people to madness
              con.getTree(dnMachine(machineId, status)).flatMap {
                case None    => None.succeed
                case Some(x) => mapper.machineFromTree(x).map(Some(_))
              }
          }
        }
      } yield {
        server.map(s => {
          (
            FullInventory(s, optMachine),
            if (getSoftware) {
              tree.map(t => getSoftwareUuids(t.root)).getOrElse(Seq())
            } else Seq()
          )
        })
      }
    ).chainError(s"Error when getting node with ID '${id.value}' and status ${inventoryStatus.name}")
  }

  def getSoftwareUuids(e: LDAPEntry): Chunk[SoftwareUuid] = {
    // it's faster to get all software in one go than doing N requests, one for each DN
    e.valuesForChunk(LDAPConstants.A_SOFTWARE_DN)
      .map(dn => SoftwareUuid(new DN(dn).getRDN.getAttributeValues()(0))) // RDN has at least one value
  }

  override def get(id: NodeId): IOResult[Option[FullInventory]] = {
    for {
      con    <- ldap
      nodeDn <- findDnForId[NodeId](con, id, dn)
      inv    <- nodeDn match {
                  case None         => None.succeed
                  case Some((_, s)) => get(id, s)
                }
    } yield {
      inv
    }
  }.chainError(s"Error when getting node with ID '${id.value}'")

  override def save(inventory: FullInventory): IOResult[Seq[LDIFChangeRecord]] = {
    (for {
      // check validity of standard fields
      _          <- NodeIdRegex.checkNodeId(inventory.node.main.id.value).toIO
      _          <- HostnameRegex.checkHostname(inventory.node.main.hostname).toIO
      con        <- ldap
      resServer  <- con.saveTree(mapper.treeFromNode(inventory.node), deleteRemoved = true)
      resMachine <- inventory.machine match {
                      case None    => Seq().succeed
                      case Some(m) => this.save(m)
                    }
    } yield resServer ++ resMachine)
  }.chainError(s"Error when saving full inventory for node  with ID '${inventory.node.main.id.value}'")

  override def delete(id: NodeId, inventoryStatus: InventoryStatus): IOResult[Seq[LDIFChangeRecord]] = {
    for {
      con           <- ldap
      // if there is only one node using the machine, delete it. Continue on error, but on success log ldif records
      // if several node use it, does nothing
      optMachine    <- getMachineId(id, inventoryStatus)
      machineRecord <- optMachine match {
                         case None                 => Seq().succeed
                         case Some((machineId, _)) =>
                           (for {
                             usingIt <- getNodesForMachine(con, machineId)
                             res     <- {
                               val nodes = usingIt.values.flatten.size
                               if (nodes < 2) {
                                 this.delete(machineId)
                               } else {
                                 Seq().succeed
                               }
                             }
                           } yield {
                             res
                           }).foldZIO(
                             e => {
                               InventoryProcessingLogger.warn(
                                 s"Error when trying to delete machine for server with id '${id.value}' and inventory status '${inventoryStatus.name}'. Message was: ${e.msg}"
                               ) *>
                               Seq().succeed
                             },
                             _.succeed
                           )
                       }
      res           <- con.delete(dn(id, inventoryStatus))
    } yield {
      res ++ machineRecord
    }
  }.chainError(s"Error when deleting node with ID '${id.value}'")

  override def moveNode(id: NodeId, from: InventoryStatus, into: InventoryStatus): IOResult[Seq[LDIFChangeRecord]] = {
    if (from == into) Seq().succeed
    else {
      for {
        con   <- ldap
        dnFrom = dn(id, from)
        dnTo   = dn(id, into)
        moved <- con.move(dnFrom, dnTo.getParent)
      } yield {
        Seq(moved)
      }
    }
  }.chainError(s"Error when moving node with ID '${id.value}' from '${from.name}' to '${into.name}'")

  override def move(id: NodeId, from: InventoryStatus, into: InventoryStatus): IOResult[Seq[LDIFChangeRecord]] = {
    for {
      con          <- ldap
      dnFrom        = dn(id, from)
      dnTo          = dn(id, into)
      moved        <- con.move(dnFrom, dnTo.getParent)
      // try to move the referenced machine too, but move it to the same
      // status that the more prioritary node with
      // accepted > pending > deleted
      // logic in machine#move
      machineMoved <- (for {
                        optMachine <- getMachineId(id, into)
                        moved      <- optMachine match {
                                        case None                 => Seq().succeed
                                        case Some((machineId, _)) => move(machineId, into)
                                      }
                      } yield {
                        moved
                      }).foldZIO(
                        err =>
                          InventoryProcessingLogger.error(
                            s"Error when updating the container value when moving nodes '${id.value}': ${err.msg}"
                          ) *> Seq().succeed,
                        diff => diff.succeed
                      )
    } yield {
      Seq(moved) ++ machineMoved
    }
  }.chainError(s"Error when moving node with ID '${id.value}' from '${from.name}' to '${into.name}' ")

}
