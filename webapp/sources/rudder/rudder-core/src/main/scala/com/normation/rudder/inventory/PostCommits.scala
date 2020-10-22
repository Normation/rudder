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

import com.normation.errors._
import com.normation.inventory.domain.InventoryReport
import com.normation.inventory.domain._
import com.normation.inventory.services.provisioning._
import com.normation.rudder.hooks.HookEnvPairs
import com.normation.rudder.hooks.PureHooksLogger
import com.normation.rudder.hooks.RunHooks
import com.normation.zio.currentTimeMillis
import com.unboundid.ldif.LDIFChangeRecord
import zio.syntax._

/*
 * This file contains post commit action to
 * weave in with the report saver.
 */


/**
 * A post commit that start node-inventory-received-pending
 * node-inventory-received-accepted hooks
 */
class PostCommitInventoryHooks(
    HOOKS_D              : String
  , HOOKS_IGNORE_SUFFIXES: List[String]
) extends PostCommit[Seq[LDIFChangeRecord]] {
  import scala.jdk.CollectionConverters._
  import zio.duration._

  override val name = "post_commit_inventory:run_node-inventory-received_hooks"

  override def apply(report: InventoryReport, records: Seq[LDIFChangeRecord]) : IOResult[Seq[LDIFChangeRecord]] = {
    val node = report.node.main
    val hooks = (for {
      systemEnv <- IOResult.effect(System.getenv.asScala.toSeq).map(seq => HookEnvPairs.build(seq:_*))
      hooks     <- if(node.status == PendingInventory) {
                     RunHooks.getHooksPure(HOOKS_D + "/node-inventory-received-pending", HOOKS_IGNORE_SUFFIXES)
                   } else if(node.status == AcceptedInventory)  {
                     RunHooks.getHooksPure(HOOKS_D + "/node-inventory-received-accepted", HOOKS_IGNORE_SUFFIXES)
                   } else {
                     Inconsistency(s"node-inventory-received-* hooks are not supported for node '${node.hostname}' [${node.id.value}] wiht status '${node.status.name}'").fail
                   }
      _         <- for {
                     timeHooks0 <- currentTimeMillis
                     res        <- RunHooks.asyncRun(
                                       hooks
                                     , HookEnvPairs.build(
                                         ("RUDDER_NODE_ID"              , node.id.value)
                                       , ("RUDDER_NODE_HOSTNAME"        , node.hostname)
                                       , ("RUDDER_NODE_POLICY_SERVER_ID", node.policyServerId.value)
                                       , ("RUDDER_NODE_OS_NAME"         , node.osDetails.os.name)
                                       , ("RUDDER_NODE_OS_VERSION"      , node.osDetails.version.value)
                                       , ("RUDDER_NODE_OS_SP"           , node.osDetails.servicePack.getOrElse(""))
                                       , ("RUDDER_NODE_OS_STRING"       , node.osDetails.fullName)
                                       , ("RUDDER_NODE_IPS"             , report.node.serverIps.mkString(" "))
                                       , ("RUDDER_AGENT_TYPE"           , report.node.agents.headOption.map(_.agentType.id).getOrElse("unknown"))
                                     )
                                     , systemEnv
                                     , 1.minutes // warn if a hook took more than a minute
                                   )
                     timeHooks1 <- currentTimeMillis
                     _          <- PureHooksLogger.trace(s"Inventory received hooks ran in ${timeHooks1 - timeHooks0} ms")
                   } yield ()
    } yield ()).catchAll(err => PureHooksLogger.error(err.fullMsg))

    // hooks are executed in async and don't block the following parts of node acceptation
    hooks.forkDaemon *> records.succeed
  }
}
