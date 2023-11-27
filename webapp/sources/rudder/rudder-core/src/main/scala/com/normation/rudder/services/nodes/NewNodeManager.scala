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
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.logger.NodeLoggerPure
import com.normation.rudder.domain.queries.CriterionLine
import com.normation.rudder.domain.queries.DitQueryData
import com.normation.rudder.domain.queries.Equals
import com.normation.rudder.domain.queries.NodeReturnType
import com.normation.rudder.domain.queries.Or
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.domain.queries.ResultTransformation
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.facts.nodes.SelectNodeStatus
import com.normation.rudder.hooks.HookEnvPairs
import com.normation.rudder.hooks.HooksLogger
import com.normation.rudder.hooks.RunHooks
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.WoNodeGroupRepository
import com.normation.rudder.services.queries.QueryProcessor
import com.normation.zio._
import com.softwaremill.quicklens._
import zio.{System => _, _}
import zio.syntax._

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
  def listNewNodes: IOResult[Seq[CoreNodeFact]]

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
    val systemEnv = {
      import scala.jdk.CollectionConverters._
      HookEnvPairs.build(System.getenv.asScala.toSeq: _*)
    }

    HooksLogger.debug(s"Executing post-node-acceptance hooks for node with id '${nodeId.value}'")
    for {
      postHooksTime0 <- currentTimeMillis
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
      postHooks      <- RunHooks.getHooksPure(HOOKS_D + "/node-post-acceptance", HOOKS_IGNORE_SUFFIXES)
      runPostHook    <- RunHooks.asyncRun(postHooks, hookEnv, systemEnv)
      postHooksTime1 <- currentTimeMillis
      timePostHooks   = (postHooksTime1 - postHooksTime0)
      _              <- NodeLoggerPure.PendingNode.debug(s"Node-post-acceptance scripts hooks ran in ${timePostHooks} ms")
    } yield {
      ()
    }
  }
}

class NewNodeManagerHooksImpl(
    nodeFactRepository:    NodeFactRepository,
    HOOKS_D:               String,
    HOOKS_IGNORE_SUFFIXES: List[String]
) extends NewNodeManagerHooks {

  val codeHooks = Ref
    .make(
      Chunk[NewNodePostAcceptHooks](
        // by default, add the script hooks
        new PostNodeAcceptanceHookScripts(HOOKS_D, HOOKS_IGNORE_SUFFIXES, nodeFactRepository)
      )
    )
    .runNow

  override def afterNodeAcceptedAsync(nodeId: NodeId)(implicit qc: QueryContext): IOResult[Unit] = {
    codeHooks.get.flatMap(hooks => ZIO.foreach(hooks)(h => IOResult.attempt(h.run(nodeId)))).unit
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

  override def listNewNodes: IOResult[Seq[CoreNodeFact]] = {
    listNodes.listNewNodes
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
  def listNewNodes: IOResult[Seq[CoreNodeFact]]
}

class FactListNewNodes(backend: NodeFactRepository) extends ListNewNode {
  override def listNewNodes: IOResult[Seq[CoreNodeFact]] = {
    backend.getAll()(QueryContext.todoQC, SelectNodeStatus.Pending).map(_.values.toSeq)
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
  private[this] def refuseOne(cnf: CoreNodeFact)(implicit cc: ChangeContext): IOResult[Unit] = {
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
          .get(id)(QueryContext.todoQC, SelectNodeStatus.Pending)
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
      cnf       <- nodeFactRepo
                     .get(id)(QueryContext.todoQC, SelectNodeStatus.Pending)
                     .notOptional(s"Missing inventory for node with ID: '${id.value}'")
      // Pre accept it
      preAccept <- passPreAccept(cnf)
      // Accept it
      _         <- nodeFactRepo.changeStatus(id, AcceptedInventory)
      // Update hooks for the node
      _         <- hooksRunner
                     .afterNodeAcceptedAsync(id)(cc.toQuery)
                     .catchAll(err => {
                       NodeLoggerPure.PendingNode.error(
                         s"Error when executing post-acceptation hooks for node '${cnf.fqdn}' " +
                         s"[${cnf.id.value}]: ${err.fullMsg}"
                       )
                     })
    } yield cnf.modify(_.rudderSettings.status).setTo(AcceptedInventory)
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
      groupIds       <- roGroupRepo.findGroupWithAnyMember(Seq(cnf.id))
      modifiedGroups <- ZIO.foreach(groupIds) { groupId =>
                          for {
                            groupPair <- roGroupRepo.getNodeGroup(groupId)
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
    policyServerNet:          PolicyServerManagementService,
    nodeFactRepo:             NodeFactRepository,
    acceptDuplicateHostnames: IOResult[Boolean]
) extends UnitCheckAcceptInventory {

  // return the list of ducplicated hostname from user input - we want that to be empty
  private[this] def checkDuplicateString(attributes: Seq[String], attributeName: String): IOResult[Unit] = {
    val duplicates = attributes.groupBy(x => x).collect { case (k, v) if v.size > 1 => v.head }.toSeq.sorted
    ZIO
      .when(duplicates.nonEmpty) {
        Inconsistency(
          s"You can not accept two nodes with the same ${attributeName}: ${duplicates.mkString("'", "', ", "'")}"
        ).fail
      }
      .unit
  }

  // some constant data for the query about hostname on node
  private[this] val objectType       = ditQueryData.criteriaMap(OC_NODE)
  private[this] val hostnameCriteria = objectType.criteria
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
  private[this] def queryForDuplicateHostname(hostnames: Seq[String])(implicit qc: QueryContext): IOResult[Unit] = {
    def failure(duplicates: Seq[String], name: String) = {
      Inconsistency(
        s"There is already a node with ${name} ${duplicates.mkString("'", "' or '", "'")} in database. You can not add it again."
      ).fail
    }

    val hostnameCriterion = hostnames.toList.map { h =>
      CriterionLine(
        objectType = objectType,
        attribute = hostnameCriteria,
        comparator = Equals,
        value = h
      )
    }

    for {
      duplicatesH   <- BoxToIO(
                         queryProcessor.process(Query(NodeReturnType, Or, ResultTransformation.Identity, hostnameCriterion))
                       ).toIO
      // here, all nodes found are duplicate-in-being. They should be unique, but
      // if not, we don't group them that the duplicate appears in the list
      noDuplicatesH <- if (duplicatesH.isEmpty) ZIO.unit
                       else {
                         // get the hostname from nodeInfoService
                         nodeFactRepo
                           .getAll()
                           .map(_.collect { case (_, n) if (duplicatesH.contains(n.id)) => n.fqdn }.toSeq)
                           .flatMap(failure(_, "Hostname"))
                       }
    } yield ()
  }

  override def preAccept(cnf: CoreNodeFact)(implicit cc: ChangeContext): IOResult[Unit] = {
    for {
      authorizedNetworks <-
        policyServerNet
          .getAllowedNetworks(Constants.ROOT_POLICY_SERVER_ID)
          .chainError("Can not get authorized networks: check their configuration, and that rudder-init was done")
      acceptDuplicated   <- acceptDuplicateHostnames
      _                  <- ZIO.when(!acceptDuplicated) {
                              for {
                                noDuplicateHostnames <- checkDuplicateString(List(cnf.fqdn), "hostname")
                                noDuplicateInDB      <- queryForDuplicateHostname(List(cnf.fqdn))(cc.toQuery)
                              } yield ()
                            }
    } yield ()
  }
}
