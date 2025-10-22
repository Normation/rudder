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

package com.normation.rudder.services.servers

import com.normation.errors.BoxToIO
import com.normation.errors.Inconsistency
import com.normation.errors.IOResult
import com.normation.inventory.domain.AcceptedInventory
import com.normation.inventory.domain.NodeId
import com.normation.inventory.ldap.core.LDAPConstants.*
import com.normation.rudder.domain.logger.NodeLoggerPure
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.queries.CriterionComposition.Or
import com.normation.rudder.domain.queries.CriterionLine
import com.normation.rudder.domain.queries.DitQueryData
import com.normation.rudder.domain.queries.Equals
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.domain.queries.QueryReturnType.NodeReturnType
import com.normation.rudder.domain.queries.ResultTransformation
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.facts.nodes.SelectNodeStatus
import com.normation.rudder.hooks.HookEnvPairs
import com.normation.rudder.hooks.HooksLogger
import com.normation.rudder.hooks.PureHooksLogger
import com.normation.rudder.hooks.RunHooks
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.WoNodeGroupRepository
import com.normation.rudder.score.ScoreServiceManager
import com.normation.rudder.score.SystemUpdateScoreEvent
import com.normation.rudder.services.queries.QueryProcessor
import com.normation.zio.*
import com.softwaremill.quicklens.*
import zio.{System as _, *}
import zio.syntax.*

/**
 * A newNodeManager hook is a class that accept callbacks.
 */
trait NewNodePostAcceptHooks {

  def name: String
  def run(nodeId: NodeId)(implicit qc: QueryContext): IOResult[Unit]

}

trait NewNodeManagerHooks {
  /*
   * Hooks to call after the node is accepted.
   * These hooks are async and we don't wait for
   * their result. They are responsible to log
   * their errors.
   */
  def afterNodeAcceptedAsync(nodeId: NodeId)(implicit qc: QueryContext): IOResult[Unit]

  def appendPostAcceptCodeHook(hook: NewNodePostAcceptHooks): IOResult[Unit]
}

/**
 * A trait to manage the acceptation of new node in Rudder
 */
trait NewNodeManager {

  /**
   * List all pending node
   */
  def listNewNodes()(implicit qc: QueryContext): IOResult[Seq[CoreNodeFact]]

  /**
   * Accept a pending node in Rudder
   */
  def accept(id: NodeId)(implicit cc: ChangeContext): IOResult[CoreNodeFact]

  /**
   * refuse node
   * @param ids : the node id
   * @return : the srv representations of the refused node
   */
  def refuse(id: NodeId)(implicit cc: ChangeContext): IOResult[CoreNodeFact]

  /**
   * Accept a list of pending nodes in Rudder
   */
  def acceptAll(ids: Seq[NodeId])(implicit cc: ChangeContext): IOResult[Seq[CoreNodeFact]]

  /**
   * refuse a list of pending nodes
   * @param ids : node ids
   * @return : the srv representations of the refused nodes
   */
  def refuseAll(ids: Seq[NodeId])(implicit cc: ChangeContext): IOResult[Seq[CoreNodeFact]]

}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

/*
 * Logic to execute scripts in hooks.d/node-post-acceptance
 */
class PostNodeAcceptanceHookScripts(
    HOOKS_D:               String,
    HOOKS_IGNORE_SUFFIXES: List[String],
    nodeFactRepository:    NodeFactRepository
) extends NewNodePostAcceptHooks {
  override def name: String = "new-node-post-accept-hooks"

  override def run(nodeId: NodeId)(implicit qc: QueryContext): IOResult[Unit] = {
    import scala.jdk.CollectionConverters.*

    HooksLogger.debug(s"Executing node-post-acceptance hooks for node with id '${nodeId.value}'")
    val name = "node-post-acceptance"

    (for {
      systemEnv      <- IOResult.attempt(java.lang.System.getenv.asScala.toSeq).map(seq => HookEnvPairs.build(seq*))
      nodeFact       <- nodeFactRepository
                          .get(nodeId)
                          .notOptional(
                            s"Just accepted node with id '${nodeId.value}' was not found - perhaps a bug?" +
                            " Please report with /var/log/rudder/webapp/DATE_OF_DAY.stdout.log file attached"
                          )
      hookEnv         = HookEnvPairs.build(
                          ("RUDDER_NODE_ID", nodeFact.id.value),
                          ("RUDDER_NODE_HOSTNAME", nodeFact.fqdn),
                          ("RUDDER_NODE_POLICY_SERVER_ID", nodeFact.rudderSettings.policyServerId.value),
                          ("RUDDER_AGENT_TYPE", nodeFact.rudderAgent.agentType.id)
                        )
      postHooks      <- RunHooks.getHooksPure(HOOKS_D + "/" + name, HOOKS_IGNORE_SUFFIXES)
      postHooksTime0 <- currentTimeMillis
      runPostHook    <- RunHooks.asyncRun(name, postHooks, hookEnv, systemEnv, 1.minutes)
      postHooksTime1 <- currentTimeMillis
      timePostHooks   = (postHooksTime1 - postHooksTime0)
      _              <- PureHooksLogger.For(name).trace(s"node-post-acceptance scripts hooks ran in $timePostHooks ms")
    } yield ()).catchAll(err => PureHooksLogger.For(name).error(err.fullMsg))
  }
}

class UpdateScorePostAcceptance(scoreServiceManager: ScoreServiceManager, nodeFactRepository: NodeFactRepository)
    extends NewNodePostAcceptHooks {
  override def name:                                           String         = "trigger-system-update-score-on-acceptance"
  override def run(nodeId: NodeId)(implicit qc: QueryContext): IOResult[Unit] = {
    for {
      fact <- nodeFactRepository.get(nodeId).notOptional("")
      _    <- scoreServiceManager.handleEvent(SystemUpdateScoreEvent(nodeId, fact.softwareUpdate.toList))
    } yield {}
  }
}
class NewNodeManagerHooksImpl(
    nodeFactRepository:    NodeFactRepository,
    HOOKS_D:               String,
    HOOKS_IGNORE_SUFFIXES: List[String]
) extends NewNodeManagerHooks {

  val codeHooks: Ref[Chunk[NewNodePostAcceptHooks]] = Ref
    .make(
      Chunk[NewNodePostAcceptHooks](
        // by default, add the script hooks
        new PostNodeAcceptanceHookScripts(HOOKS_D, HOOKS_IGNORE_SUFFIXES, nodeFactRepository)
      )
    )
    .runNow

  override def afterNodeAcceptedAsync(nodeId: NodeId)(implicit qc: QueryContext): IOResult[Unit] = {
    codeHooks.get.flatMap(hooks => ZIO.foreach(hooks)(h => h.run(nodeId))).unit
  }

  def appendPostAcceptCodeHook(hook: NewNodePostAcceptHooks): IOResult[Unit] = {
    this.codeHooks.update(_.appended(hook))
  }

}

/**
 * Default implementation: a new server manager composed with a sequence of
 * "unit" accept, one by main goals of what it means to accept a server;
 * Each unit accept provides its main logic of accepting a server, optionally
 * a global post accept task, and a rollback mechanism.
 * Rollback is always a "best effort" task.
 */
class NewNodeManagerImpl[A](
    composedNewNodeManager: ComposedNewNodeManager[A],
    listNodes:              ListNewNode
) extends NewNodeManager {

  override def listNewNodes()(implicit qc: QueryContext): IOResult[Seq[CoreNodeFact]] = {
    listNodes.listNewNodes()
  }

  override def accept(id: NodeId)(implicit cc: ChangeContext): IOResult[CoreNodeFact] = {
    composedNewNodeManager.accept(id)
  }

  override def refuse(id: NodeId)(implicit cc: ChangeContext): IOResult[CoreNodeFact] = {
    composedNewNodeManager.refuse(id)
  }

  override def acceptAll(ids: Seq[NodeId])(implicit cc: ChangeContext): IOResult[Seq[CoreNodeFact]] = {
    composedNewNodeManager.acceptAll(ids)
  }

  override def refuseAll(id: Seq[NodeId])(implicit cc: ChangeContext): IOResult[Seq[CoreNodeFact]] = {
    composedNewNodeManager.refuseAll(id)
  }
}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait ListNewNode {
  def listNewNodes()(implicit qc: QueryContext): IOResult[Seq[CoreNodeFact]]
}

class FactListNewNodes(backend: NodeFactRepository) extends ListNewNode {
  override def listNewNodes()(implicit qc: QueryContext): IOResult[Seq[CoreNodeFact]] = {
    backend.getAll()(using qc, SelectNodeStatus.Pending).map(_.values.toSeq)
  }
}

trait UnitRefuseInventory {

  def name: String

  def refuseOne(cnf: CoreNodeFact)(implicit cc: ChangeContext): IOResult[Unit]
}

/*
 * Checks running before actually accepting the inventory.
 * If one is in error, acceptation of node fails.
 */
trait UnitCheckAcceptInventory {

  /**
   * A name to describe the role of that acceptor
   */
  def name: String

  /**
   * An action to execute before the whole batch
   */
  def preAccept(cnf: CoreNodeFact)(implicit cc: ChangeContext): IOResult[Unit]
}

class ComposedNewNodeManager[A](
    nodeFactRepo:  NodeFactRepository,
    unitAcceptors: Seq[UnitCheckAcceptInventory],
    unitRefusors:  Seq[UnitRefuseInventory],
    hooksRunner:   NewNodeManagerHooks
) {
  ////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////////////// Refuse //////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////////

  /**
   * Refuse one server
   */
  private def refuseOne(cnf: CoreNodeFact)(implicit cc: ChangeContext): IOResult[Unit] = {
    ZIO
      .foreach(unitRefusors)(r => {
        r.refuseOne(cnf)
          .tapBoth(
            err => NodeLoggerPure.PendingNode.error(s"Error when refusing node '${cnf.id.value}': ${err.fullMsg}"),
            _ => NodeLoggerPure.PendingNode.debug(s"Refusing node '${cnf.id.value}' step '${r.name}': OK")
          )
      })
      .unit
  }

  def refuse(id: NodeId)(implicit cc: ChangeContext): IOResult[CoreNodeFact] = {
    for {
      cnf <-
        nodeFactRepo
          .get(id)(using cc.toQuery, SelectNodeStatus.Pending)
          .notOptional(s"Node with id '${id.value}' was not found in pending nodes")
      _   <- refuseOne(cnf)
      _   <- nodeFactRepo.delete(id)
    } yield cnf
  }

  def refuseAll(ids: Seq[NodeId])(implicit cc: ChangeContext): IOResult[Seq[CoreNodeFact]] = {
    ZIO.foreachPar(ids)(id => refuse(id))
  }

  ////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////////////// Accept //////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////////

  def accept(id: NodeId)(implicit cc: ChangeContext): IOResult[CoreNodeFact] = {
    // validate pre acceptance for a Node, if an error occurs, stop everything on that node.
    def passPreAccept(nodeFact: CoreNodeFact) = {
      ZIO.foreachDiscard(unitAcceptors) { a =>
        a.preAccept(nodeFact)
          .tapBoth(
            err =>
              NodeLoggerPure.PendingNode.debug(
                s"Node with id '${nodeFact.id.value}' was refused during 'pre-accepting' step of phase '${a.name}'"
              ) *>
              NodeLoggerPure.PendingNode
                .error(s"Error when trying to accept node with id '${nodeFact.id.value}': ${err.fullMsg}"),
            _ => NodeLoggerPure.PendingNode.debug(s"Pre acceptance phase: '${a.name}' OK for node ${nodeFact.id.value}")
          )
      }
    }

    for {
      // Get inventory og the node
      cnf         <- nodeFactRepo
                       .get(id)(using cc.toQuery, SelectNodeStatus.Pending)
                       .notOptional(s"Missing inventory for node with ID: '${id.value}'")
      // Pre accept it
      preAccept   <- passPreAccept(cnf)
      // Accept it
      _           <- nodeFactRepo.changeStatus(id, AcceptedInventory)
      // Update hooks for the node
      _           <- hooksRunner
                       .afterNodeAcceptedAsync(id)(using cc.toQuery)
                       .catchAll(err => {
                         NodeLoggerPure.PendingNode.error(
                           s"Error when executing post-acceptation hooks for node '${cnf.fqdn}' " +
                           s"[${cnf.id.value}]: ${err.fullMsg}"
                         )
                       })
      // Retrieve the cnf again to make sure that the data is up to date
      // since pre acceptance logic can modify the node data (like the policy mode and state)
      upToDateCnf <- nodeFactRepo
                       .get(id)(using cc.toQuery, SelectNodeStatus.Accepted)
                       .notOptional(s"Missing inventory for node with ID: '${id.value}'")
    } yield upToDateCnf
  }

  def acceptAll(ids: Seq[NodeId])(implicit cc: ChangeContext): IOResult[Seq[CoreNodeFact]] = {
    ZIO.foreachPar(ids)(id => accept(id))
  }

}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

class RefuseGroups(
    override val name: String,
    roGroupRepo:       RoNodeGroupRepository,
    woGroupRepo:       WoNodeGroupRepository
) extends UnitRefuseInventory {

  //////////// refuse ////////////
  override def refuseOne(cnf: CoreNodeFact)(implicit cc: ChangeContext): IOResult[Unit] = {
    // remove server id in all groups
    for {
      groupIds <- roGroupRepo.findGroupWithAnyMember(Seq(cnf.id))
      _        <- ZIO.foreach(groupIds) { groupId =>
                    for {
                      groupPair <- roGroupRepo.getNodeGroup(groupId)(using cc.toQuery)
                      modGroup   = groupPair._1.copy(serverList = groupPair._1.serverList - cnf.id)
                      msg        = Some("Automatic update of groups due to refusal of node " + cnf.id.value)
                      saved     <- {
                        val res = if (modGroup.isSystem) {
                          woGroupRepo.updateSystemGroup(modGroup, cc.modId, cc.actor, cc.message)
                        } else {
                          woGroupRepo.update(modGroup, cc.modId, cc.actor, cc.message)
                        }
                        res
                      }
                    } yield {
                      saved
                    }
                  }
    } yield ()
  }
}

/**
 * We don't want to have node with the same hostname.
 * That means don't accept two nodes with the same hostname, and don't
 * accept a node with a hostname already existing in database.
 */
class AcceptHostnameAndIp(
    override val name:        String,
    queryProcessor:           QueryProcessor,
    ditQueryData:             DitQueryData,
    acceptDuplicateHostnames: IOResult[Boolean]
) extends UnitCheckAcceptInventory {

  // some constant data for the query about hostname on node
  private val objectType       = ditQueryData.criteriaMap(OC_NODE)
  private val hostnameCriteria = objectType.criteria
    .find(c => c.name == A_HOSTNAME)
    .getOrElse(
      throw new IllegalArgumentException(
        "Data model inconsistency: missing '%s' criterion in object type '%s'".format(A_HOSTNAME, OC_NODE)
      )
    )

  /*
   * search in database nodes having the same hostname as one provided.
   * Only return existing hostname (and so again, we want that to be empty)
   */
  private def queryForDuplicateHostname(hostnames: Seq[String])(implicit qc: QueryContext): IOResult[Unit] = {
    val hostnameCriterion = hostnames.toList.map { h =>
      CriterionLine(
        objectType = objectType,
        attribute = hostnameCriteria,
        comparator = Equals,
        value = h
      )
    }

    for {
      duplicatesH <- queryProcessor.process(Query(NodeReturnType, Or, ResultTransformation.Identity, hostnameCriterion)).toIO
      // here, all nodes found are duplicate-in-being. They should be unique, but
      // if not, we don't group them that the duplicate appears in the list
      _           <- if (duplicatesH.isEmpty) ZIO.unit
                     else {
                       val startMessage =
                         if (duplicatesH.size >= 2) s"There are already ${duplicatesH.size} nodes" else "There is already a node"
                       Inconsistency(
                         s"${startMessage} with hostname '${hostnames.mkString(", ")}' in Rudder. You can not add it again."
                       ).fail
                     }
    } yield ()
  }

  override def preAccept(cnf: CoreNodeFact)(implicit cc: ChangeContext): IOResult[Unit] = {
    for {
      acceptDuplicated <- acceptDuplicateHostnames
      _                <- ZIO.when(!acceptDuplicated) {
                            for {
                              _ <- queryForDuplicateHostname(List(cnf.fqdn))(using cc.toQuery)
                            } yield ()
                          }
    } yield ()
  }
}

class DefaultStateAndPolicyMode(
    override val name:         String,
    nodeFactRepo:              NodeFactRepository,
    defaultPolicyModeOnAccept: IOResult[Option[PolicyMode]],
    defaultStateOnAccept:      IOResult[NodeState]
) extends UnitCheckAcceptInventory {
  override def preAccept(cnf: CoreNodeFact)(implicit cc: ChangeContext): IOResult[Unit] = {
    for {
      policyMode <- defaultPolicyModeOnAccept
      nodeState  <- defaultStateOnAccept
      newCnf      = cnf
                      .modify(_.rudderSettings.state)
                      .setTo(nodeState)
                      .modify(_.rudderSettings.policyMode)
                      .setTo(policyMode)
      _          <- nodeFactRepo.save(newCnf)
    } yield ()
  }
}
