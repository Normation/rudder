/*
 *************************************************************************************
 * Copyright 2012 Normation SAS
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

package com.normation.rudder.inventory

import com.normation.errors.*
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.*
import com.normation.inventory.services.provisioning.*
import com.normation.rudder.batch.AsyncDeploymentActor
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.facts.nodes.NodeFact
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.NodeFactStorage
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.hooks.HookEnvPairs
import com.normation.rudder.hooks.PureHooksLogger
import com.normation.rudder.hooks.RunHooks
import com.normation.utils.StringUuidGenerator
import com.normation.zio.currentTimeMillis
import zio.*
import zio.syntax.*

/*
 * This file contains post commit action to
 * weave in with the inventory saver.
 */

/**
 * A post commit that start node-inventory-received-pending
 * node-inventory-received-accepted hooks
 */
class PostCommitInventoryHooks[A](
    HOOKS_D:               String,
    HOOKS_IGNORE_SUFFIXES: List[String],
    nodeFactRepo:          NodeFactRepository
) extends PostCommit[A] {
  import QueryContext.todoQC
  import scala.jdk.CollectionConverters.*
  override val name = "post_commit_inventory:run_node-inventory-received_hooks"

  override def apply(inventory: Inventory, records: A): IOResult[A] = {
    val node  = inventory.node.main
    val hooks = (for {
      systemEnv <- IOResult.attempt(java.lang.System.getenv.asScala.toSeq).map(seq => HookEnvPairs.build(seq*))
      nHooks    <- nodeFactRepo.getStatus(node.id).flatMap {
                     case PendingInventory  =>
                       val n = "node-inventory-received-pending"
                       RunHooks.getHooksPure(HOOKS_D + "/" + n, HOOKS_IGNORE_SUFFIXES).map(h => (n, h))
                     case AcceptedInventory =>
                       val n = "node-inventory-received-accepted"
                       RunHooks.getHooksPure(HOOKS_D + "/" + n, HOOKS_IGNORE_SUFFIXES).map(h => (n, h))
                     case s                 =>
                       Inconsistency(
                         s"node-inventory-received-* hooks are not supported for node '${node.hostname}' [${node.id.value}] with status '${s.name}'"
                       ).fail
                   }
      (n, hooks) = nHooks
      _         <- for {
                     timeHooks0 <- currentTimeMillis
                     res        <- RunHooks.asyncRun(
                                     n,
                                     hooks,
                                     HookEnvPairs.build(
                                       ("RUDDER_NODE_ID", node.id.value),
                                       ("RUDDER_NODE_HOSTNAME", node.hostname),
                                       ("RUDDER_NODE_POLICY_SERVER_ID", node.policyServerId.value),
                                       ("RUDDER_NODE_OS_NAME", node.osDetails.os.name),
                                       ("RUDDER_NODE_OS_VERSION", node.osDetails.version.value),
                                       ("RUDDER_NODE_OS_SP", node.osDetails.servicePack.getOrElse("")),
                                       ("RUDDER_NODE_OS_STRING", node.osDetails.fullName),
                                       ("RUDDER_NODE_IPS", inventory.node.serverIps.mkString(" ")),
                                       ("RUDDER_AGENT_TYPE", inventory.node.agents.headOption.map(_.agentType.id).getOrElse("unknown"))
                                     ),
                                     systemEnv,
                                     1.minutes // warn if a hook took more than a minute
                                   )
                     timeHooks1 <- currentTimeMillis
                     _          <- PureHooksLogger.For(n).trace(s"Inventory received hooks ran in ${timeHooks1 - timeHooks0} ms")
                   } yield ()
    } yield ()).catchAll(err => PureHooksLogger.error(err.fullMsg))

    // hooks are executed in async and don't block the following parts of node acceptation
    hooks.forkDaemon *> records.succeed
  }
}

class FactRepositoryPostCommit[A](
    nodeFactsRepository: NodeFactStorage,
    nodeFactRepository:  NodeFactRepository
) extends PostCommit[A] {
  override def name: String = "commit node in fact-repository"

  /*
   * This part is responsible of saving the inventory in the fact repository.
   * For now, it can't fail: errors are logged but don't stop inventory processing.
   */
  override def apply(inventory: Inventory, records: A): IOResult[A] = {
    (for {
      optInfo <- if (inventory.node.main.status == RemovedInventory) None.succeed
                 else
                   nodeFactRepository.getCompat(inventory.node.main.id, inventory.node.main.status)(using QueryContext.systemQC)
      _       <- optInfo match {
                   case None =>
                     InventoryProcessingLogger.info(
                       s"Node information relative to new node '${inventory.node.main.id.value}' " +
                       s"are missing, it will not be persisted in fact-repository"
                     ) *>
                     ZIO.unit // does nothing

                   case Some(nodeInfo) =>
                     nodeFactsRepository.save(
                       NodeFact.fromCompat(
                         nodeInfo.toNodeInfo,
                         Right(FullInventory(inventory.node, Some(inventory.machine))),
                         inventory.applications,
                         None
                       )
                     )
                 }
    } yield ())
      .catchAll(err => {
        InventoryProcessingLogger.info(
          s"Error when trying to persist node '${inventory.node.main.id.value}' " +
          s"into fact repository, it will be missing in it. Error: ${err.fullMsg}"
        )
      })
      .map(_ => records)

  }
}

// we should enforce that A has something to let us know if there is changes
class TriggerPolicyGenerationPostCommit[A](
    asyncGenerationActor: AsyncDeploymentActor,
    uuidGen:              StringUuidGenerator
) extends PostCommit[A] {
  override def name: String = "trigger policy generation on inventory change"

  /*
   * This part is responsible of saving the inventory in the fact repository.
   * For now, it can't fail: errors are logged but don't stop inventory processing.
   */
  override def apply(inventory: Inventory, records: A): IOResult[A] = {
    (if (inventory.node.main.status == AcceptedInventory) {
       IOResult.attempt(asyncGenerationActor ! AutomaticStartDeployment(ModificationId(uuidGen.newUuid), RudderEventActor))
     } else ZIO.unit) *> records.succeed
  }
}
