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
import com.normation.inventory.domain.PendingInventory
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
import com.normation.rudder.repository.WoNodeRepository
import com.normation.rudder.services.nodes.NodeInfoService
import com.softwaremill.quicklens._
import org.joda.time.DateTime
import zio._
import zio.syntax._

/*
 * This service is only used in InventoryProcessor. It is not so much a proxy than an actual implementation,
 * but it is a major port/change from 8.0 and feels right here.
 */
class NodeFactInventorySaver(
    backend:                NodeFactRepository,
    val preCommitPipeline:  Seq[PreCommit],
    val postCommitPipeline: Seq[PostCommit[Unit]]
) extends PipelinedInventorySaver[Unit] {

  override def commitChange(inventory: Inventory): IOResult[Unit] = {
    implicit val cc = ChangeContext.newForRudder()
    backend.updateInventory(FullInventory(inventory.node, Some(inventory.machine)), Some(inventory.applications)).unit
  }
}

/*
 * Proxy for node fact to full inventory / node inventory / machine inventory / node info and their repositories.
 */
class NodeInfoServiceProxy(backend: NodeFactRepository) extends NodeInfoService {
  import QueryContext.todoQC

  override def getNodeInfo(nodeId: NodeId): IOResult[Option[NodeInfo]] = {
    backend.get(nodeId)(todoQC, SelectNodeStatus.Accepted).map(_.map(_.toNodeInfo))
  }

  override def getNodeInfosSeq(nodeIds: Seq[NodeId]): IOResult[Seq[NodeInfo]] = {
    backend
      .getAll()(todoQC, SelectNodeStatus.Accepted)
      .map(_.collect { case (id, n) if (nodeIds.contains(id)) => n.toNodeInfo }.toSeq)
  }

  // used in all plugins for checking license
  override def getNumberOfManagedNodes: IOResult[Int] = {
    backend.getAll()(QueryContext.systemQC, SelectNodeStatus.Accepted).map(_.size)
  }

  override def getAll(): IOResult[Map[NodeId, NodeInfo]] = {
    backend.getAll()(todoQC, SelectNodeStatus.Accepted).map(_.mapValues(_.toNodeInfo).toMap)
  }

  // plugins: only use in tests
  // 4 usages in rudder
  override def getAllNodesIds(): IOResult[Set[NodeId]] = {
    backend.getAll()(todoQC, SelectNodeStatus.Accepted).map(_.keySet.toSet)
  }

  // only used in plugin: rudder-plugins/datasources/src/main/scala/com/normation/plugins/datasources/api/DataSourceApiImpl.scala l214
  override def getAllNodes(): IOResult[Map[NodeId, Node]] = {
    backend.getAll()(todoQC, SelectNodeStatus.Accepted).map(_.mapValues(_.toNode).toMap)
  }

  // only use in tests in plugins
  override def getAllNodeInfos(): IOResult[Seq[NodeInfo]] = {
    backend.getAll()(todoQC, SelectNodeStatus.Accepted).map(_.map(_._2.toNodeInfo).toSeq)
  }

  override def getAllSystemNodeIds(): IOResult[Seq[NodeId]] = {
    // for this one, it seems OK to use `systemQC`
    backend
      .getAll()(QueryContext.systemQC, SelectNodeStatus.Accepted)
      .map(_.collect { case (_, n) if (n.rudderSettings.kind != NodeKind.Node) => n.id }.toSeq)
  }

  // plugins: only use in test
  // rudder: 3 usages
  override def getPendingNodeInfos(): IOResult[Map[NodeId, NodeInfo]] = {
    backend.getAll()(todoQC, SelectNodeStatus.Pending).map(_.mapValues(_.toNodeInfo).toMap)
  }

  // plugins: only use in tests
  // rudder: 2 usages
  override def getPendingNodeInfo(nodeId: NodeId): IOResult[Option[NodeInfo]] = {
    backend.get(nodeId)(todoQC, SelectNodeStatus.Pending).map(_.map(_.toNodeInfo))
  }

}

/*
 * Proxy for full node inventory.
 * It is only used in mock and for testing compatibility with old data structures.
 *
 * We willfully chose to not implement machine repo because it doesn't make any sense with fact.
 * There is also a limit with software, since now they are directly in the node and they don't
 * have specific IDs. So they will need to be retrieved by node id.
 */
class MockNodeFactFullInventoryRepositoryProxy(backend: NodeFactRepository)
    extends FullInventoryRepository[Unit] with ReadOnlySoftwareNameDAO {
  import QueryContext.todoQC

  override def get(id: NodeId, inventoryStatus: InventoryStatus): IOResult[Option[FullInventory]] = {
    backend.slowGetCompat(id, inventoryStatus, SelectFacts.noSoftware).map(_.map(_.toFullInventory))
  }

  override def get(id: NodeId): IOResult[Option[FullInventory]] = {
    get(id, AcceptedInventory)
  }

  override def getMachineId(id: NodeId, inventoryStatus: InventoryStatus): IOResult[Option[(MachineUuid, InventoryStatus)]] = {
    (Some((NodeFact.toMachineId(id), inventoryStatus))).succeed
  }

  override def save(serverAndMachine: FullInventory): IOResult[Unit] = {
    // we must know if it's new or not to get back the correct facts.
    // if the fact exists, we keep its status (use move to change it).
    // if it does not yet, we use the given status BUT know that you should not
    // use that to save node in accepted status directly.
    backend.updateInventory(serverAndMachine, None)(ChangeContext.newForRudder()).unit
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

  /*
   * Used in:
   * - CVE plugin: Services.scala l255
   */
  override def getSoftwareByNode(nodeIds: Set[NodeId], status: InventoryStatus): IOResult[Map[NodeId, Seq[Software]]] = {
    def getAll(s: SelectNodeStatus): IOResult[Map[NodeId, Chunk[Software]]] = {
      implicit val attrs = SelectFacts.none.copy(software = SelectFacts.all.software)

      ZIO
        .foreach(nodeIds.toList) {
          case id =>
            backend.slowGet(id)(todoQC, s, attrs).map(_.map(n => (n.id, n.software.map(_.toSoftware))))
        }
        .map(_.flatten.toMap)
    }

    status match {
      case AcceptedInventory => getAll(SelectNodeStatus.Accepted)
      case PendingInventory  => getAll(SelectNodeStatus.Pending)
      case RemovedInventory  => Map().succeed
    }
  }

  override def getNodesbySofwareName(softName: String): IOResult[List[(NodeId, Software)]] = {
    backend.getNodesbySofwareName(softName)
  }
}

/*
 * This one is only used in plugins, so we are not exposing QueryContext for now.
 * We removed its usage in rudder-rest NodeAPI and replaced it by bar NodeFactRepository
 */
class WoFactNodeRepositoryProxy(backend: NodeFactRepository) extends WoNodeRepository {
  import QueryContext.todoQC

  override def updateNode(node: Node, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[Node] = {
    for {
      opt  <- backend.get(node.id)(todoQC, SelectNodeStatus.Any)
      fact <- opt match {
                case None       => Inconsistency(s"Node with id '${node.id.value}' was not found").fail
                case Some(fact) => CoreNodeFact.updateNode(fact, node).succeed
              }
      _    <- backend.save(NodeFact.fromMinimal(fact))(
                ChangeContext(modId, actor, DateTime.now(), reason, None, todoQC.nodePerms),
                SelectFacts.none
              )
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
  ): IOResult[Unit] = {
    if (agentKey.isEmpty && agentKeyStatus.isEmpty) ZIO.unit
    else {
      for {
        node   <- backend.get(nodeId).notOptional(s"Cannot update node with id ${nodeId.value}: there is no node with that id")
        newNode = node
                    .modify(_.rudderAgent.securityToken)
                    .setToIfDefined(agentKey)
                    .modify(_.rudderSettings.keyStatus)
                    .setToIfDefined(agentKeyStatus)
        _      <- backend.save(NodeFact.fromMinimal(newNode))(
                    ChangeContext(modId, actor, DateTime.now(), reason, None, todoQC.nodePerms),
                    SelectFacts.none
                  )
      } yield ()
    }
  }
}
