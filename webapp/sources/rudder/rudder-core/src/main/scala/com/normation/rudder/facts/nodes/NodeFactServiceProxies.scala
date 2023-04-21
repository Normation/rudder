/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

package com.normation.rudder.facts.nodes

import com.normation.box._
import com.normation.errors.IOResult
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.AcceptedInventory
import com.normation.inventory.domain.FullInventory
import com.normation.inventory.domain.Inventory
import com.normation.inventory.domain.InventoryError.Inconsistency
import com.normation.inventory.domain.InventoryStatus
import com.normation.inventory.domain.KeyStatus
import com.normation.inventory.domain.MachineUuid
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.NodeInventory
import com.normation.inventory.domain.RemovedInventory
import com.normation.inventory.domain.SecurityToken
import com.normation.inventory.domain.Software
import com.normation.inventory.services.core.FullInventoryRepository
import com.normation.inventory.services.core.ReadOnlySoftwareNameDAO
import com.normation.inventory.services.provisioning.PipelinedInventorySaver
import com.normation.inventory.services.provisioning.PostCommit
import com.normation.inventory.services.provisioning.PreCommit
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.nodes.NodeKind
import com.normation.rudder.domain.servers.Srv
import com.normation.rudder.repository.WoNodeRepository
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.servers.NodeSummaryService
import net.liftweb.common.Box
import zio._
import zio.stream.ZSink
import zio.syntax._

class NodeFactInventorySaver(
    backend:                NodeFactRepository,
    val preCommitPipeline:  Seq[PreCommit],
    val postCommitPipeline: Seq[PostCommit[Unit]]
) extends PipelinedInventorySaver[Unit] {

  override def commitChange(inventory: Inventory): IOResult[Unit] = {
    implicit val cc = ChangeContext.newForRudder()
    backend.updateInventory(inventory).unit
  }
}

/*
 * Proxy for node fact to full inventory / node inventory / machine inventory / node info and their repositories
 */
class NodeInfoServiceProxy(backend: NodeFactRepository) extends NodeInfoService {
  override def getNodeInfo(nodeId: NodeId): IOResult[Option[NodeInfo]] = {
    backend.getAccepted(nodeId).map(_.map(_.toNodeInfo))
  }

  override def getNodeInfos(nodeIds: Set[NodeId]): IOResult[Set[NodeInfo]] = {
    backend.getAllAccepted().collect { case n if (nodeIds.contains(n.id)) => n.toNodeInfo }.run(ZSink.collectAllToSet)
  }

  override def getNodeInfosSeq(nodeIds: Seq[NodeId]): IOResult[Seq[NodeInfo]] = {
    backend.getAllAccepted().collect { case n if (nodeIds.contains(n.id)) => n.toNodeInfo }.run(ZSink.collectAll).map(_.toSeq)
  }

  override def getNumberOfManagedNodes: IOResult[Int] = {
    backend.getAllAccepted().run(ZSink.count).map(_.toInt)
  }

  override def getAll(): IOResult[Map[NodeId, NodeInfo]] = {
    backend.getAllAccepted().map(_.toNodeInfo) run (ZSink.collectAllToMap[NodeInfo, NodeId](_.node.id)((a, b) => b))
  }

  override def getAllNodesIds(): IOResult[Set[NodeId]] = {
    backend.getAllAccepted().map(_.id).run(ZSink.collectAllToSet)
  }

  override def getAllNodes(): IOResult[Map[NodeId, Node]] = {
    backend.getAllAccepted().map(_.toNode).run(ZSink.collectAllToMap[Node, NodeId](_.id)((a, b) => b))
  }

  override def getAllNodeInfos(): IOResult[Seq[NodeInfo]] = {
    backend.getAllAccepted().map(_.toNodeInfo).run(ZSink.collectAll).map(_.toSeq)
  }

  override def getAllSystemNodeIds(): IOResult[Seq[NodeId]] = {
    backend
      .getAllAccepted()
      .collect { case n if (n.rudderSettings.kind != NodeKind.Node) => n.id }
      .run(ZSink.collectAll)
      .map(_.toSeq)
  }

  override def getPendingNodeInfos(): IOResult[Map[NodeId, NodeInfo]] = {
    backend.getAllPending().map(_.toNodeInfo).run(ZSink.collectAllToMap[NodeInfo, NodeId](_.id)((a, b) => b))
  }

  override def getPendingNodeInfo(nodeId: NodeId): IOResult[Option[NodeInfo]] = {
    backend.getPending(nodeId).map(_.map(_.toNodeInfo))
  }

  // not supported anymore
  override def getDeletedNodeInfos(): IOResult[Map[NodeId, NodeInfo]] = {
    Map().succeed
  }

  // not supported anymore
  override def getDeletedNodeInfo(nodeId: NodeId): IOResult[Option[NodeInfo]] = {
    None.succeed
  }
}

/*
 * Proxy for full node inventory.
 * We willfully chose to not implement machine repo because it doesn't make any sense with fact.
 * There is also a limit with software, since now they are directly in the node and they don't
 * have specific IDs. So they will need to be retrieved by node id.
 */
class NodeFactFullInventoryRepository(backend: NodeFactRepository)
    extends FullInventoryRepository[Unit] with ReadOnlySoftwareNameDAO {

  override def get(id: NodeId, inventoryStatus: InventoryStatus): IOResult[Option[FullInventory]] = {
    backend.getOn(id, inventoryStatus).map(_.map(_.toFullInventory))
  }

  override def get(id: NodeId): IOResult[Option[FullInventory]] = {
    get(id, AcceptedInventory)
  }

  override def getMachineId(id: NodeId, inventoryStatus: InventoryStatus): IOResult[Option[(MachineUuid, InventoryStatus)]] = {
    (Some((NodeFact.toMachineId(id), inventoryStatus))).succeed
  }

  override def getAllInventories(inventoryStatus: InventoryStatus): IOResult[Map[NodeId, FullInventory]] = {
    backend.getAllAccepted().map(_.toFullInventory).run(ZSink.collectAllToMap[FullInventory, NodeId](_.node.main.id)((a, _) => a))
  }

  override def getAllNodeInventories(inventoryStatus: InventoryStatus): IOResult[Map[NodeId, NodeInventory]] = {
    backend.getAllAccepted().map(_.toNodeInventory).run(ZSink.collectAllToMap[NodeInventory, NodeId](_.main.id)((a, _) => a))
  }
  override def getInventories(
      inventoryStatus: com.normation.inventory.domain.InventoryStatus,
      nodeIds:         Set[com.normation.inventory.domain.NodeId]
  ): IOResult[Map[NodeId, FullInventory]] = {
    backend
      .getAllAccepted()
      .collect { case n if (nodeIds.contains(n.id)) => n.toFullInventory }
      .run(ZSink.collectAllToMap[FullInventory, NodeId](_.node.main.id)((a, _) => a))
  }

  override def save(serverAndMachine: FullInventory): IOResult[Unit] = {
    // we must know if it's new or not to get back the correct facts.
    // if the fact exists, we keep its status (use move to change it).
    // if it does not yet, we use the given status BUT know that you should not
    // use that to save node in accepted status directly.
    for {
      opt <- backend.lookup(serverAndMachine.node.main.id)
      fact = opt match {
               case None       => NodeFact.newFromFullInventory(serverAndMachine, None)
               case Some(fact) => NodeFact.updateFullInventory(fact, serverAndMachine, None)
             }
      _   <- backend.save(fact)(ChangeContext.newForRudder())
    } yield ()
  }

  override def delete(id: NodeId, inventoryStatus: InventoryStatus): IOResult[Unit] = {
    // we need to only delete if the status is the one asked
    backend.getStatus(id).flatMap { s =>
      s match {
        case RemovedInventory => ZIO.unit
        case s                => ZIO.when(s == inventoryStatus)(backend.delete(id)(ChangeContext.newForRudder())).unit
      }
    }
  }

  override def move(id: NodeId, from: InventoryStatus, into: InventoryStatus): IOResult[Unit] = {
    backend.changeStatus(id, into)(ChangeContext.newForRudder()).unit
  }

  override def moveNode(id: NodeId, from: InventoryStatus, into: InventoryStatus): IOResult[Unit] = {
    backend.changeStatus(id, into)(ChangeContext.newForRudder()).unit
  }

  override def getSoftwareByNode(nodeIds: Set[NodeId], status: InventoryStatus): IOResult[Map[NodeId, Seq[Software]]] = {
    backend
      .getAllOn(status)
      .collect { case n if (nodeIds.contains(n.id)) => (n.id, n.software.toList.map(_.toSoftware)) }
      .run(ZSink.collectAll)
      .map(_.toMap)
  }

  override def getNodesbySofwareName(softName: String): IOResult[List[(NodeId, Software)]] = {
    backend
      .getAllAccepted()
      .collect {
        case n if (n.software.exists(_.name == softName)) => n.software.find(_.name == softName).map(s => (n.id, s.toSoftware))
      }
      .run(ZSink.collectAll)
      .map(_.flatten.toList)
  }
}

class FactNodeSummaryService(backend: NodeFactRepository) extends NodeSummaryService {
  override def find(status: InventoryStatus, ids: NodeId*): Box[Seq[Srv]] = {
    backend.getAllOn(status).collect { case n if ids.contains(n.id) => n.toSrv }.run(ZSink.collectAll).toBox
  }
}

class WoFactNodeRepository(backend: NodeFactRepository) extends WoNodeRepository {
  override def updateNode(node: Node, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[Node] = {
    for {
      opt  <- backend.lookup(node.id)
      fact <- opt match {
                case None       => Inconsistency(s"Node with id '${node.id.value}' was not found").fail
                case Some(fact) => NodeFact.updateNode(fact, node).succeed
              }
      _    <- backend.save(fact)(ChangeContext(modId, actor, reason))
    } yield fact.toNode
  }

  override def deleteNode(node: Node, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[Node] = ???

  override def createNode(node: Node, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[Node] = ???

  override def updateNodeKeyInfo(
      nodeId:         NodeId,
      agentKey:       Option[SecurityToken],
      agentKeyStatus: Option[KeyStatus],
      modId:          ModificationId,
      actor:          EventActor,
      reason:         Option[String]
  ): IOResult[Unit] = ???
}
