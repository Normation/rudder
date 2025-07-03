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
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.AcceptedInventory
import com.normation.inventory.domain.InventoryStatus
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.PendingInventory
import com.normation.inventory.domain.RemovedInventory
import com.normation.rudder.batch.AsyncDeploymentActor
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.batch.UpdateDynamicGroups
import com.normation.rudder.domain.eventlog.AcceptNodeEventLog
import com.normation.rudder.domain.eventlog.DeleteNodeEventLog
import com.normation.rudder.domain.eventlog.InventoryLogDetails
import com.normation.rudder.domain.eventlog.RefuseNodeEventLog
import com.normation.rudder.domain.logger.NodeLoggerPure
import com.normation.rudder.domain.nodes.ModifyNodeDiff
import com.normation.rudder.repository.CachedRepository
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.score.ScoreService
import com.normation.rudder.score.ScoreServiceManager
import com.normation.rudder.score.SystemUpdateScoreEvent
import com.normation.rudder.services.nodes.history.impl.FactLogData
import com.normation.rudder.services.nodes.history.impl.InventoryHistoryJdbcRepository
import com.normation.rudder.services.reports.CacheComplianceQueueAction
import com.normation.rudder.services.reports.CacheExpectedReportAction
import com.normation.rudder.services.reports.InvalidateCache
import com.normation.utils.DateFormaterService
import com.normation.utils.StringUuidGenerator
import java.time.Instant
import zio.ZIO

/*
 * This file store callbacks for node events.
 * The canonical example case is event log records
 */
trait NodeFactChangeEventCallback {
  def name: String
  def run(change: NodeFactChangeEventCC): IOResult[Unit]
}

case class CoreNodeFactChangeEventCallback(
    name: String,
    exec: NodeFactChangeEventCC => IOResult[Unit]
) extends NodeFactChangeEventCallback {
  override def run(change: NodeFactChangeEventCC): IOResult[Unit] = {
    exec(change)
  }
}

/*
 * A call back that give a basic log in webapp logs with the expected log level,
 * so that other callbacks don't have to do it
 */
class AppLogNodeFactChangeEventCallback() extends NodeFactChangeEventCallback {
  override def name: String = "node-fact-cec: webapp log"

  override def run(change: NodeFactChangeEventCC): IOResult[Unit] = {
    change.event match {
      case NodeFactChangeEvent.UpdatedPending(old, next, attrs) =>
        NodeLoggerPure.debug(s"Pending node '${next.fqdn}' [${next.id.value}]' was updated")
      case NodeFactChangeEvent.Updated(old, next, attrs)        =>
        NodeLoggerPure.debug(s"Node '${next.fqdn}' [${next.id.value}]' was updated")
      case NodeFactChangeEvent.NewPending(node, attrs)          =>
        NodeLoggerPure.info(s"New pending node: '${node.fqdn}' [${node.id.value}]'")
      case NodeFactChangeEvent.Accepted(node, attrs)            =>
        NodeLoggerPure.info(s"New accepted node: '${node.fqdn}' [${node.id.value}]'")
      case NodeFactChangeEvent.Refused(node, attrs)             =>
        NodeLoggerPure.info(s"Pending node '${node.fqdn}' [${node.id.value}]' was refused")
      case NodeFactChangeEvent.Deleted(node, attrs)             =>
        NodeLoggerPure.info(s"Node '${node.fqdn}' [${node.id.value}]' was deleted")
      case NodeFactChangeEvent.Noop(nodeId, attrs)              =>
        NodeLoggerPure.debug(s"No change for node '${nodeId.value}'")
    }
  }
}

/*
 * A callback that trigger a dynamic group update on change.
 * We still start a generation after, because even without a group change, properties and other
 * things can lead to a generation update.
 */
class GenerationOnChange(
    updateDynamicGroups:  UpdateDynamicGroups,
    asyncDeploymentAgent: AsyncDeploymentActor,
    uuidGen:              StringUuidGenerator
) extends NodeFactChangeEventCallback {

  override def name: String = "node-fact-ecc: update dyn group and start-generation-on-change"

  private[nodes] def startGeneration(nodeId: NodeId): IOResult[Unit] = {
    NodeLoggerPure.info(
      s"Update in node '${nodeId.value}' inventories main information detected: triggering dynamic group update and a policy generation"
    ) *>
    IOResult.attempt(updateDynamicGroups.startManualUpdate) *>
    IOResult.attempt(
      asyncDeploymentAgent ! AutomaticStartDeployment(
        ModificationId(uuidGen.newUuid),
        com.normation.rudder.domain.eventlog.RudderEventActor
      )
    )
  }

  override def run(change: NodeFactChangeEventCC): IOResult[Unit] = {
    change.event match {
      case NodeFactChangeEvent.NewPending(node, attrs)                 => ZIO.unit
      case NodeFactChangeEvent.UpdatedPending(oldNode, newNode, attrs) => ZIO.unit
      case NodeFactChangeEvent.Accepted(node, attrs)                   => startGeneration(node.id)
      case NodeFactChangeEvent.Refused(node, attrs)                    => ZIO.unit
      case NodeFactChangeEvent.Updated(oldNode, newNode, attrs)        => startGeneration(newNode.id)
      case NodeFactChangeEvent.Deleted(node, attrs)                    => startGeneration(node.id)
      case NodeFactChangeEvent.Noop(nodeId, attrs)                     => ZIO.unit
    }
  }
}

class ScoreUpdateOnNodeFactChange(scoreServiceManager: ScoreServiceManager, scoreService: ScoreService)
    extends NodeFactChangeEventCallback {

  def run(change: NodeFactChangeEventCC): IOResult[Unit] = {
    change.event match {
      case NodeFactChangeEvent.Accepted(node, _)      =>
        scoreServiceManager.handleEvent(SystemUpdateScoreEvent(node.id, node.softwareUpdate.toList))
      case NodeFactChangeEvent.Updated(_, newNode, _) =>
        scoreServiceManager.handleEvent(SystemUpdateScoreEvent(newNode.id, newNode.softwareUpdate.toList))
      case NodeFactChangeEvent.Deleted(node, _)       => scoreService.deleteNodeScore(node.id)(change.cc.toQuery)
      case _                                          => ZIO.unit
    }
  }

  override def name: String = "trigger-score-update"

}

/*
 * Callback related to cache invalidation when a node changes
 */
class CacheInvalidateNodeFactEventCallback(
    cacheExpectedReports:   InvalidateCache[CacheExpectedReportAction],
    cacheNodeStatusReports: InvalidateCache[CacheComplianceQueueAction],
    cacheToClear:           List[CachedRepository]
) extends NodeFactChangeEventCallback {

  import com.normation.rudder.services.reports.CacheExpectedReportAction.*

  override def name: String = "node-fact-cec: invalidate caches"

  override def run(change: NodeFactChangeEventCC): IOResult[Unit] = {
    change.event match {
      case NodeFactChangeEvent.NewPending(node, attrs)                 => ZIO.unit
      case NodeFactChangeEvent.UpdatedPending(oldNode, newNode, attrs) => ZIO.unit
      case NodeFactChangeEvent.Accepted(node, attrs)                   =>
        // ping the NodeConfiguration Cache and NodeCompliance Cache about this new node
        val i = InsertNodeInCache(node.id)
        for {
          _ <- cacheNodeStatusReports
                 .invalidateWithAction(Seq((node.id, CacheComplianceQueueAction.ExpectedReportAction(i))))
                 .chainError(s"Error when adding node ${node.id.value} to node configuration cache")
          _ <- cacheExpectedReports
                 .invalidateWithAction(Seq((node.id, i)))
                 .chainError(s"Error when adding node ${node.id.value} to compliance cache")
          _ <- ZIO.foreach(cacheToClear)(c => IOResult.attempt(c.clearCache()))
        } yield {
          ()
        }
      case NodeFactChangeEvent.Refused(node, attrs)                    => ZIO.unit
      case NodeFactChangeEvent.Updated(oldNode, newNode, attrs)        => ZIO.unit
      case NodeFactChangeEvent.Deleted(node, attrs)                    =>
        val a = CacheExpectedReportAction.RemoveNodeInCache(node.id)
        for {
          _ <- NodeLoggerPure.Delete.debug(s"  - remove node ${node.id.value} from compliance and expected report cache")
          _ <-
            cacheNodeStatusReports
              .invalidateWithAction(Seq((node.id, CacheComplianceQueueAction.ExpectedReportAction(a))))
              .catchAll(err => {
                NodeLoggerPure.Delete
                  .error(s"Error when removing node ${node.id.value} from node configuration cache: ${err.fullMsg}")
              })
          _ <- cacheExpectedReports
                 .invalidateWithAction(Seq((node.id, a)))
                 .catchAll(err =>
                   NodeLoggerPure.Delete.error(s"Error when removing node ${node.id.value} from compliance cache: ${err.fullMsg}")
                 )
        } yield ()

      case NodeFactChangeEvent.Noop(nodeId, attrs) => NodeLoggerPure.debug(s"No change for node '${nodeId.value}'")
    }
  }
}

/*
 * Manage event logs related to nodes: register a change in properties, a node acceptation, etc
 */
class EventLogsNodeFactChangeEventCallback(
    eventLogRepository: EventLogRepository
) extends NodeFactChangeEventCallback {
  override def name: String = "node-fact-cec: register even log"

  override def run(change: NodeFactChangeEventCC): IOResult[Unit] = {
    def modifyEventLog(
        cc:   ChangeContext,
        old:  MinimalNodeFactInterface,
        next: MinimalNodeFactInterface
    ): IOResult[Unit] = {
      val diff = ModifyNodeDiff.fromFacts(old, next)
      eventLogRepository.saveModifyNode(cc.modId, cc.actor, diff, cc.message, cc.eventDate).unit
    }

    change.event match {
      case NodeFactChangeEvent.UpdatedPending(old, next, attrs) => modifyEventLog(change.cc, old, next)
      case NodeFactChangeEvent.Updated(old, next, attrs)        => modifyEventLog(change.cc, old, next)
      case NodeFactChangeEvent.NewPending(node, attrs)          => ZIO.unit
      case NodeFactChangeEvent.Accepted(node, attrs)            =>
        val log = AcceptNodeEventLog.fromInventoryLogDetails(
          principal = change.cc.actor,
          creationDate = change.cc.eventDate,
          inventoryDetails = InventoryLogDetails(
            nodeId = node.id,
            inventoryVersion = node.lastInventoryDate.getOrElse(node.factProcessedDate),
            hostname = node.fqdn,
            fullOsName = node.os.fullName,
            actorIp = change.cc.actorIp.getOrElse("actor ip unknown")
          )
        )
        eventLogRepository
          .saveEventLog(change.cc.modId, log)
          .tapBoth(
            error =>
              NodeLoggerPure.PendingNode
                .warn(s"Node '${node.fqdn}' [${node.id.value}] accepted, but the action couldn't be logged"),
            ok => NodeLoggerPure.PendingNode.debug(s"Successfully accepted node '${node.fqdn}' [${node.id.value}]")
          )
          .unit
      case NodeFactChangeEvent.Refused(node, attrs)             =>
        val log = RefuseNodeEventLog.fromInventoryLogDetails(
          principal = change.cc.actor,
          creationDate = change.cc.eventDate,
          inventoryDetails = InventoryLogDetails(
            nodeId = node.id,
            inventoryVersion = node.lastInventoryDate.getOrElse(node.factProcessedDate),
            hostname = node.fqdn,
            fullOsName = node.os.fullName,
            actorIp = change.cc.actorIp.getOrElse("actor ip unknown")
          )
        )
        eventLogRepository
          .saveEventLog(change.cc.modId, log)
          .tapBoth(
            error =>
              NodeLoggerPure.PendingNode
                .warn(s"Node '${node.fqdn}' [${node.id.value}] refused, but the action couldn't be logged"),
            ok => NodeLoggerPure.PendingNode.debug(s"Successfully refused node '${node.fqdn}' [${node.id.value}]")
          )
          .unit
      case NodeFactChangeEvent.Deleted(node, attrs)             =>
        val log = DeleteNodeEventLog.fromInventoryLogDetails(
          None,
          principal = change.cc.actor,
          creationDate = change.cc.eventDate,
          inventoryDetails = InventoryLogDetails(
            node.id,
            node.lastInventoryDate.getOrElse(node.factProcessedDate),
            node.fqdn,
            node.os.fullName,
            change.cc.actorIp.getOrElse("actor ip unknown")
          )
        )
        eventLogRepository
          .saveEventLog(change.cc.modId, log)
          .tapBoth(
            error =>
              NodeLoggerPure.PendingNode
                .warn(s"Node '${node.fqdn}' [${node.id.value}] deleted, but the action couldn't be stored in eventlogs"),
            ok => NodeLoggerPure.debug(s"Successfully deleted node '${node.fqdn}' [${node.id.value}]")
          )
          .unit

      case NodeFactChangeEvent.Noop(nodeId, attrs) => ZIO.unit
    }
  }
}

/*
 * Keep a trace of the full inventory of the node where we need to.
 * This happens on acceptation/refusal
 */
class HistorizeNodeState(
    historyRepos:       InventoryHistoryJdbcRepository,
    sourceFactStorage:  NodeFactStorage,
    gitFactStorage:     NodeFactStorage,
    cleanUpImmediately: Boolean
) extends NodeFactChangeEventCallback {

  override def name: String = "node-fact-ecc: historize node fact on choice"

  override def run(change: NodeFactChangeEventCC): IOResult[Unit] = {

    def save(node: MinimalNodeFactInterface, eventDate: Instant, alsoJDBC: Boolean, status: InventoryStatus): IOResult[Unit] = {
      // we want to save the fact with everything
      implicit val attrs = SelectFacts.all
      if (gitFactStorage == NoopFactStorage && !alsoJDBC) ZIO.unit
      else {
        (if (status == PendingInventory) sourceFactStorage.getPending(node.id)
         else sourceFactStorage.getAccepted(node.id)).flatMap { res =>
          val nf = res match {
            case Some(x) => x
            case None    => // in case of refuse event, node is already deleted
              NodeFact.fromMinimal(node)
          }
          for {
            _ <- ZIO.when(alsoJDBC)(
                   historyRepos.save(
                     node.id,
                     FactLogData(nf, change.cc.actor, AcceptedInventory),
                     DateFormaterService.toDateTime(eventDate)
                   )
                 )
            _ <- gitFactStorage.save(nf)
          } yield ()
        }
      }
    }

    def delete(nodeId: NodeId): IOResult[Unit] = {
      /*
       * This hook registers the deletion events into postgresql `nodefacts` table so that the inventory accept/refuse
       * fact can be latter cleaned-up.
       */
      (
        (
          if (cleanUpImmediately) {
            historyRepos.delete(nodeId)
          } else { // save delete event, clean-up will be automatically done by script
            historyRepos.saveDeleteEvent(nodeId, DateFormaterService.toDateTime(change.cc.eventDate), change.cc.actor)
          }
        ).catchAll(err => {
          NodeLoggerPure
            .warn(s"Error when updating node '${nodeId.value}' historical inventory information in base: ${err.fullMsg}")
        })
      ) *>
      NodeLoggerPure.Delete.debug(s"  - delete fact about node '${nodeId.value}'") *>
      gitFactStorage
        .changeStatus(nodeId, RemovedInventory)
        .unit
        .catchAll(err =>
          NodeLoggerPure.info(s"Error when trying to update fact when deleting node '${nodeId.value}': ${err.fullMsg}")
        )
    }

    change.event match {
      case NodeFactChangeEvent.NewPending(node, attrs)                 =>
        NodeLoggerPure.debug(s"Save new  in node fact fs") *>
        save(node, change.cc.eventDate, alsoJDBC = false, status = PendingInventory)
      case NodeFactChangeEvent.UpdatedPending(oldNode, newNode, attrs) =>
        NodeLoggerPure.debug(s"Update pending in node fact fs") *>
        save(newNode, change.cc.eventDate, alsoJDBC = false, status = PendingInventory)
      case NodeFactChangeEvent.Accepted(node, attrs)                   =>
        NodeLoggerPure.debug(s"Accept in node fact fs and postgres") *>
        save(node, change.cc.eventDate, alsoJDBC = true, status = AcceptedInventory) // callback done post accept
      case NodeFactChangeEvent.Refused(node, attrs)                    =>
        NodeLoggerPure.debug(s"Refused in node fact fs and postgres") *>
        save(node, change.cc.eventDate, alsoJDBC = true, status = AcceptedInventory)
      case NodeFactChangeEvent.Updated(oldNode, newNode, attrs)        =>
        NodeLoggerPure.debug(s"Update in node fact fs") *>
        save(newNode, change.cc.eventDate, alsoJDBC = false, status = AcceptedInventory)
      case NodeFactChangeEvent.Deleted(node, attrs)                    =>
        NodeLoggerPure.debug(s"Delete in node fact fs") *>
        delete(node.id)
      case NodeFactChangeEvent.Noop(nodeId, attrs)                     =>
        NodeLoggerPure.debug(s"noop") *>
        ZIO.unit
    }
  }
}
