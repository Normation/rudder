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

import com.normation.box._
import com.normation.errors._
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.AcceptedInventory
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.InventoryStatus
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.PendingInventory
import com.normation.inventory.domain.RemovedInventory
import com.normation.inventory.domain.UndefinedKey
import com.normation.inventory.domain.UnknownOS
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.inventory.ldap.core.LDAPFullInventoryRepository
import com.normation.ldap.sdk._
import com.normation.ldap.sdk.BuildFilter._
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.NodeDit
import com.normation.rudder.domain.eventlog._
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.domain.logger.NodeLoggerPure
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.facts.nodes.SelectNodeStatus
import com.normation.rudder.hooks.HookEnvPairs
import com.normation.rudder.hooks.HookReturnCode
import com.normation.rudder.hooks.RunHooks
import com.normation.rudder.reports.ReportingConfiguration
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.UpdateExpectedReportsRepository
import com.normation.rudder.repository.WoNodeGroupRepository
import com.normation.rudder.repository.ldap.ScalaReadWriteLock
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.policies.write.NodePoliciesPaths
import com.normation.rudder.services.policies.write.PathComputer
import com.normation.rudder.services.servers.DeletionResult._
import com.normation.utils.StringUuidGenerator
import com.normation.zio._
import com.unboundid.ldap.sdk.Modification
import com.unboundid.ldap.sdk.ModificationType
import com.unboundid.ldif.LDIFChangeRecord
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.function.BiPredicate
import java.util.function.Consumer
import net.liftweb.common.Box
import org.joda.time.DateTime
import zio.{System => _, _}
import zio.stream._
import zio.syntax._

sealed trait DeletionResult
object DeletionResult {
  final case class PreHookFailed(hookError: HookReturnCode.Error)  extends DeletionResult
  final case class PostHookFailed(hookError: HookReturnCode.Error) extends DeletionResult
  case object Success                                              extends DeletionResult
  final case class Error(err: RudderError)                         extends DeletionResult

  def resolve(results: List[DeletionResult]) = {
    results.accumulate(_ match {
      case Error(err)          => err.fail
      case PreHookFailed(err)  => Inconsistency(s"Pre hook error: ${err.msg}").fail
      case PostHookFailed(err) => Inconsistency(s"Post hook error: ${err.msg}").fail
      case Success             => ZIO.unit
    })
  }

}

sealed trait DeleteMode { def name: String }
object DeleteMode       {

  final case object MoveToRemoved extends DeleteMode { val name = "move"  }
  final case object Erase         extends DeleteMode { val name = "erase" }

  def all = ca.mrvisser.sealerate.values[DeleteMode]
}

/*
 * Unitary post-deletion action. They happen once the node is actually deleted and eventlog saved.
 * Typically, done for cleaning things or notifying other parts of rudder.
 *
 * We keep here action that should be executed each time we call delete, whatever if the node
 * was really deleted.
 * The other post delete hooks should be migration to CoreNodeFactRepository callbacks.
 */
trait PostNodeDeleteAction {
  // a node can have several status (if inventories already deleted, and now in pending again for ex)
  // or zero (if only some things remain)
  // and if can optionnally have a nodeInfo
  def run(nodeId: NodeId, mode: DeleteMode, info: Option[NodeInfo], status: Set[InventoryStatus])(implicit
      cc:         ChangeContext
  ): UIO[Unit]
}

object PostNodeDeleteAction {
  implicit class NodeName(info: (NodeId, Option[NodeInfo])) {
    def name: String = {
      info match {
        case (id, None)    => s"with ID '${id.value}'"
        case (id, Some(i)) => s"'${i.hostname}' [${id.value}]"
      }
    }
  }
}
import PostNodeDeleteAction._

trait RemoveNodeService {

  /**
   * Remove a pending or accepted node by moving it to the "removed" branch
   * It is not really deleted from directory
   * What it does :
   * - clean the ou=Nodes
   * - clean the groups
   * - move the node
   */

  def removeNode(nodeId: NodeId)(implicit cc: ChangeContext): Box[DeletionResult] = {
    removeNodePure(nodeId, DeleteMode.MoveToRemoved).map(_ => Success).toBox
  }

  def removeNodePure(nodeId: NodeId, mode: DeleteMode)(implicit cc: ChangeContext): IOResult[NodeInfo]
}

trait RemoveNodeBackend {

  // find the status of node to delete. It can have multiple result if inventories exists in several status (ex: pending and deleted)
  def findNodeStatuses(nodeId: NodeId): IOResult[Set[InventoryStatus]]

  // the abstract method that actually commit in backend repo the deletion from accepted nodes
  def commitDeleteAccepted(nodeInfo: NodeInfo, mode: DeleteMode)(implicit cc: ChangeContext): IOResult[Unit]

  // the abstract method that actually commit in backend repo the deletion from accepted nodes
  def commitPurgeRemoved(nodeId: NodeId, mode: DeleteMode)(implicit cc: ChangeContext): IOResult[Unit]

}

class FactRemoveNodeBackend(backend: NodeFactRepository) extends RemoveNodeBackend {
  override def findNodeStatuses(nodeId: NodeId): IOResult[Set[InventoryStatus]] = {
    // here, we need to return "RemovedInventory" in case of missing node, so CoreNodeFactRepo #getStatus
    // is not what we want;
    backend.get(nodeId)(QueryContext.todoQC, SelectNodeStatus.Any).map {
      case None    => Set(RemovedInventory)
      case Some(x) => Set(x.rudderSettings.status)
    }
  }

  override def commitDeleteAccepted(nodeInfo: NodeInfo, mode: DeleteMode)(implicit cc: ChangeContext): IOResult[Unit] = {
    backend.delete(nodeInfo.id).unit
  }

  override def commitPurgeRemoved(nodeId: NodeId, mode: DeleteMode)(implicit cc: ChangeContext): IOResult[Unit] = {
    backend.delete(nodeId).unit
  }
}

class RemoveNodeServiceImpl(
    backend:                   RemoveNodeBackend,
    nodeInfoService:           NodeInfoService,
    pathComputer:              PathComputer,
    newNodeManager:            NewNodeManager,
    val postNodeDeleteActions: Ref[List[PostNodeDeleteAction]],
    HOOKS_D:                   String,
    HOOKS_IGNORE_SUFFIXES:     List[String]
) extends RemoveNodeService {

  // effectually add an action
  def addPostNodeDeleteAction(action: PostNodeDeleteAction): Unit = {
    postNodeDeleteActions.update(_.prepended(action)).runNowLogError(e => ApplicationLogger.error(e.fullMsg))
  }

  /**
   * the removal of a node is a multi-step system
   * First, fetch the node, then remove it from groups, and clear all node configurations
   * Move the node to the removed inventory (and don't forget to change its container dn)
   * Then find its container, to see if it has others nodes on it
   *        if so, copy the container to the removed inventory
   *        if not, move the container to the removed inventory
   *
   * Return a couple RemoveNodeServiceImplwith 2 boxes, one about the LDIF change, and one containing the result of the clear cache
   * The main goal is to separate the clear cache as it could fail while the node is correctly deleted.
   * A failing clear cache should not be considered an error when deleting a Node.
   */
  override def removeNodePure(nodeId: NodeId, mode: DeleteMode)(implicit cc: ChangeContext): IOResult[NodeInfo] = {
    // main logic, see help function below
    nodeId match {
      case Constants.ROOT_POLICY_SERVER_ID => Inconsistency("The root node cannot be deleted.").fail
      case _                               => {
        for {
          _       <- NodeLoggerPure.Delete.debug(s"Deleting node with ID '${nodeId.value}' [mode:${mode.name}]")
          status  <- backend.findNodeStatuses(nodeId)
          -       <- NodeLoggerPure.Delete.debug(s"  - node '${nodeId.value}' has status: [${status.map(_.name).mkString(",")}]")
          info    <- Ref.make(Option.empty[NodeInfo]) // a place to store the maybe node info
          // always delete in order pending then accepted then deleted
          res1    <- if (status.contains(PendingInventory)) {
                       (for {
                         i <- nodeInfoService.getPendingNodeInfo(nodeId)
                         r <- deletePendingNode(nodeId, mode)
                         _ <- info.set(i)
                       } yield r).catchAll(err => Error(err).succeed)
                     } else Success.succeed
          res2    <- if (status.contains(AcceptedInventory)) {
                       (for {
                         i <- nodeInfoService.getNodeInfo(nodeId)
                         r <- i match {
                                case None    => Success.succeed // perhaps deleted or something
                                case Some(x) => info.set(Some(x)) *> deleteAcceptedNode(x, mode)
                              }
                       } yield r).catchAll(err => Error(err).succeed)
                     } else Success.succeed
          // if any of the previous cases were in error, we want to stop here
          res     <- DeletionResult.resolve(res1 :: res2 :: Nil)
          // in all cases, run postNodeDeletionAction
          _       <- NodeLoggerPure.Delete.debug(s"-> execute clean-up actions for node '${nodeId.value}'")
          actions <- postNodeDeleteActions.get
          optInfo <- info.get
          _       <- ZIO.foreachDiscard(actions)(_.run(nodeId, mode, optInfo, status))
          _       <- NodeLoggerPure.Delete.info(
                       s"Node '${nodeId.value}' ${optInfo.map(_.hostname).getOrElse("")} was successfully deleted"
                     )
        } yield {
          optInfo match {
            case Some(info) =>
              info
            // in that case, just return a minimal info
            case None       =>
              NodeInfo(
                new Node(
                  nodeId,
                  "",
                  "",
                  NodeState.Ignored,
                  false,
                  false,
                  new DateTime(0),
                  ReportingConfiguration(None, None, None),
                  Nil,
                  None,
                  None
                ),
                "",
                None,
                UnknownOS(),
                Nil,
                new DateTime(0),
                UndefinedKey,
                Nil,
                Constants.ROOT_POLICY_SERVER_ID,
                "",
                None,
                None,
                None
              )
          }
        }
      }
    }
  }

  ////////////////////////////////
  //// implementation details ////
  ////////////////////////////////

  // delete pending node is just refusing it
  def deletePendingNode(nodeId: NodeId, mode: DeleteMode)(implicit cc: ChangeContext): IOResult[DeletionResult] = {
    NodeLoggerPure.Delete.debug(s"-> deleting node with ID '${nodeId.value}' from pending nodes (refuse)") *>
    newNodeManager.refuse(nodeId).map(_ => DeletionResult.Success)
  }

  // this is the core delete that is run on accepted node: pre hook, post hook, move to delete or erase
  // in that case, we do have a nodeInfo
  def deleteAcceptedNode(nodeInfo: NodeInfo, mode: DeleteMode)(implicit cc: ChangeContext): IOResult[DeletionResult] = {

    for {
      _       <- NodeLoggerPure.Delete.debug(s"-> deleting node with ID '${nodeInfo.id.value}' from accepted nodes")
      hookEnv <- buildHooksEnv(nodeInfo)
      _       <- NodeLoggerPure.Delete.debug(s"  - run node pre hooks for '${nodeInfo.id.value}'")
      preRun  <- runPreHooks(hookEnv)
      res     <- preRun match {
                   case a: HookReturnCode.Error =>
                     PreHookFailed(a).succeed
                   case _ =>
                     for {
                       _       <- NodeLoggerPure.Delete.debug(s"  - delete '${nodeInfo.id.value}' in LDAP (mode='${mode.name}')")
                       _       <- backend.commitDeleteAccepted(nodeInfo, mode)
                       _       <- NodeLoggerPure.Delete.debug(s"  - run node post hooks for '${nodeInfo.id.value}'")
                       postRun <- runPostHooks(hookEnv)
                     } yield {
                       postRun match {
                         case stop: HookReturnCode.Error => PostHookFailed(stop)
                         case _ => Success
                       }
                     }
                 }
    } yield res
  }

  // delete a node for which we only have the inventory, so it's either deleted, or in accepted but somehow broken.
  def deleteDeletedNode(nodeId: NodeId, mode: DeleteMode)(implicit cc: ChangeContext): IOResult[DeletionResult] = {
    // if mode is move, done
    if (mode == DeleteMode.MoveToRemoved) {
      Success.succeed
    } else { // erase
      NodeLoggerPure.Delete.debug(s"-> erase '${nodeId.value}' from removed nodes") *>
      backend.commitPurgeRemoved(nodeId, mode).map(_ => Success)
    }
  }

  // HOOKS //
  // they are only done when the node was accepted

  // returns (hooks env, system env) for hooks
  def buildHooksEnv(nodeInfo: NodeInfo): IOResult[(HookEnvPairs, HookEnvPairs)] = {
    def getNodePath(node: NodeInfo): IOResult[NodePoliciesPaths] = {
      // accumulate all the node infos from node id to root through relay servers
      def recGetParent(node: NodeInfo): IOResult[Map[NodeId, NodeInfo]] = {
        if (node.id == Constants.ROOT_POLICY_SERVER_ID) {
          Map((node.id, node)).succeed
        } else {
          for {
            opt    <- nodeInfoService.getNodeInfo(node.policyServerId)
            parent <-
              opt.notOptional(
                s"The policy server '${node.policyServerId.value}' for node ${node.hostname} ('${node.id.value}') was not found in Rudder"
              )
            rec    <- recGetParent(parent)
          } yield {
            rec + (node.id -> node)
          }
        }
      }
      for {
        nodeInfos <- recGetParent(node)
        paths     <- pathComputer.computeBaseNodePath(node.id, Constants.ROOT_POLICY_SERVER_ID, nodeInfos).toIO
      } yield {
        paths
      }
    }

    for {
      optNodePaths <- getNodePath(nodeInfo).foldZIO(
                        err => {
                          val msg = s"Error when trying to calculate node '${nodeInfo.id.value}' policy path: ${err.fullMsg}"
                          NodeLoggerPure.Delete.warn(msg) *>
                          None.succeed
                        },
                        x => Some(x).succeed
                      )
    } yield {
      import scala.jdk.CollectionConverters._
      (
        HookEnvPairs.build(
          ("RUDDER_NODE_ID", nodeInfo.id.value),
          ("RUDDER_NODE_HOSTNAME", nodeInfo.hostname),
          ("RUDDER_NODE_POLICY_SERVER_ID", nodeInfo.policyServerId.value),
          ("RUDDER_AGENT_TYPE", nodeInfo.agentsName.headOption.map(_.agentType.id).getOrElse("")),
          ("RUDDER_NODE_KIND", nodeInfo.nodeKind.name),
          ("RUDDER_POLICIES_DIRECTORY_CURRENT", optNodePaths.map(_.baseFolder).getOrElse("")),
          ("RUDDER_POLICIES_DIRECTORY_NEW", optNodePaths.map(_.newFolder).getOrElse("")),
          ("RUDDER_POLICIES_DIRECTORY_ARCHIVE", optNodePaths.flatMap(_.backupFolder).getOrElse(""))
        ),
        HookEnvPairs.build(System.getenv.asScala.toSeq: _*)
      )
    }
  }

  // env pair: first: hookEnv, second: systemEnv
  def runPreHooks(env: (HookEnvPairs, HookEnvPairs))            = {
    runHooks("node-pre-deletion", env)
  }
  def runPostHooks(env: (HookEnvPairs, HookEnvPairs))           = {
    runHooks("node-post-deletion", env)
  }
  def runHooks(name: String, env: (HookEnvPairs, HookEnvPairs)) = {
    for {
      start <- currentTimeMillis
      hooks <- RunHooks.getHooksPure(HOOKS_D + "/" + name, HOOKS_IGNORE_SUFFIXES)
      res   <- RunHooks.asyncRun(hooks, env._1, env._2)
      end   <- currentTimeMillis
      _     <- NodeLoggerPure.Delete.debug(s"    ${name} scripts hooks ran in ${end - start} ms")
    } yield {
      res._1
    }
  }
}

class LdapRemoveNodeBackend(
    nodeDit:      NodeDit,
    pendingDit:   InventoryDit,
    acceptedDit:  InventoryDit,
    deletedDit:   InventoryDit,
    ldap:         LDAPConnectionProvider[RwLDAPConnection],
    fullNodeRepo: LDAPFullInventoryRepository,
    nodeLibMutex: ScalaReadWriteLock // that's a scala-level mutex to have some kind of consistency with LDAP
) extends RemoveNodeBackend {

  override def findNodeStatuses(nodeId: NodeId): IOResult[Set[InventoryStatus]] = {
    for {
      con <- ldap
      res <- con.search(deletedDit.BASE_DN.getParent, Sub, AND(IS(OC_NODE), EQ(A_NODE_UUID, nodeId.value)), A_NODE_UUID)
    } yield {
      List((pendingDit, PendingInventory), (acceptedDit, AcceptedInventory), (deletedDit, RemovedInventory)).map {
        case (dit, status) =>
          res.collect { case e if (dit.NODES.NODE.dn(nodeId.value) == e.dn) => status }.headOption
      }.flatten.toSet
    }
  }

  override def commitPurgeRemoved(nodeId: NodeId, mode: DeleteMode)(implicit cc: ChangeContext): IOResult[Unit] = {
    fullNodeRepo.delete(nodeId, RemovedInventory).unit
  }

  // the part that just move/delete node
  override def commitDeleteAccepted(nodeInfo: NodeInfo, mode: DeleteMode)(implicit cc: ChangeContext): IOResult[Unit] = {
    for {
      _ <-
        nodeLibMutex.writeLock(atomicDelete(nodeInfo.id, mode)).chainError("Error when deleting a node")
    } yield ()
  }

  def atomicDelete(nodeId: NodeId, mode: DeleteMode): IOResult[Seq[LDIFChangeRecord]] = {
    for {
      cleanNode         <- deleteFromNodes(nodeId).chainError(s"Could not remove the node '${nodeId.value}' from base")
      moveNodeInventory <- mode match {
                             case DeleteMode.MoveToRemoved => fullNodeRepo.move(nodeId, AcceptedInventory, RemovedInventory)
                             case DeleteMode.Erase         => fullNodeRepo.delete(nodeId, AcceptedInventory)
                           }
    } yield {
      cleanNode ++ moveNodeInventory
    }
  }

  /**
   * Deletes from ou=Node
   */
  def deleteFromNodes(nodeId: NodeId): IOResult[Seq[LDIFChangeRecord]] = {
    for {
      _      <- NodeLoggerPure.Delete.debug(s"  - remove node ${nodeId.value} from ou=Nodes,cn=rudder-configuration")
      con    <- ldap
      dn      = nodeDit.NODES.NODE.dn(nodeId.value)
      result <- con.delete(dn)
    } yield {
      result
    }
  }

}

/**
 * Look for the groups containing this node in their nodes list, and remove the node
 * from the list
 */
class RemoveNodeFromGroups(
    roNodeGroupRepository: RoNodeGroupRepository,
    woNodeGroupRepository: WoNodeGroupRepository,
    uuidGen:               StringUuidGenerator
) extends PostNodeDeleteAction {
  override def run(
      nodeId:    NodeId,
      mode:      DeleteMode,
      info:      Option[NodeInfo],
      status:    Set[InventoryStatus]
  )(implicit cc: ChangeContext): UIO[Unit] = {
    (for {
      _            <- NodeLoggerPure.Delete.debug(s"  - remove node ${nodeId.value} from his groups")
      nodeGroupIds <- roNodeGroupRepository.findGroupWithAnyMember(Seq(nodeId))
      _            <- ZIO.foreach(nodeGroupIds) { nodeGroupId =>
                        val msg = Some("Automatic update of group due to deletion of node " + nodeId.value)
                        woNodeGroupRepository
                          .updateDiffNodes(
                            nodeGroupId,
                            add = Nil,
                            delete = List(nodeId),
                            ModificationId(uuidGen.newUuid),
                            RudderEventActor,
                            msg
                          )
                          .chainError(
                            s"Could not update group '${nodeGroupId.serialize}' to remove node '${nodeId.value}'"
                          )
                      }
    } yield ()).catchAll(err =>
      NodeLoggerPure.Delete.error(s"Error when cleaning node with ID '${nodeId.value}' from groups: ${err.fullMsg}")
    )
  }
}

/*
 * Close expected reports for node.
 * Also delete nodes_info for that node.
 */
class CloseNodeConfiguration(expectedReportsRepository: UpdateExpectedReportsRepository) extends PostNodeDeleteAction {
  override def run(
      nodeId:    NodeId,
      mode:      DeleteMode,
      info:      Option[NodeInfo],
      status:    Set[InventoryStatus]
  )(implicit cc: ChangeContext): UIO[Unit] = {
    for {
      _ <- NodeLoggerPure.Delete.debug(s"  - close expected reports for '${nodeId.value}'")
      _ <- expectedReportsRepository
             .closeNodeConfigurationsPure(nodeId)
             .catchAll(err => NodeLoggerPure.Delete.error(s"Error when closing expected reports for node ${(nodeId, info).name}"))
             .unit
      _ <- expectedReportsRepository.deleteNodeInfos(nodeId).catchAll(err => NodeLoggerPure.Delete.error(err.msg))
    } yield ()
  }
}

// when the node is a policy server, delete directive/rule/group related to it
class DeletePolicyServerPolicies(policyServerManagement: PolicyServerManagementService) extends PostNodeDeleteAction {
  override def run(
      nodeId:    NodeId,
      mode:      DeleteMode,
      info:      Option[NodeInfo],
      status:    Set[InventoryStatus]
  )(implicit cc: ChangeContext): UIO[Unit] = {
    // we can avoid to do LDAP requests if we are sure the node wasn't a policy server
    info.map(_.isPolicyServer) match {
      case Some(false) =>
        ZIO.unit
      case _           =>
        NodeLoggerPure.Delete.debug(s"  - delete relay related policies in LDAP'${nodeId.value}'") *>
        policyServerManagement
          .deleteRelaySystemObjects(nodeId)
          .catchAll(err => {
            NodeLoggerPure.Delete.error(
              s"Error when deleting system objects (groups, directives, rules) related to relay server for node ${(nodeId, info).name}: ${err.fullMsg}"
            )
          })
    }
  }
}

// clean up certification key status (only in move mode, not erase)
class ResetKeyStatus(ldap: LDAPConnectionProvider[RwLDAPConnection], deletedDit: InventoryDit) extends PostNodeDeleteAction {
  override def run(
      nodeId:    NodeId,
      mode:      DeleteMode,
      info:      Option[NodeInfo],
      status:    Set[InventoryStatus]
  )(implicit cc: ChangeContext): UIO[Unit] = {
    if (mode == DeleteMode.MoveToRemoved) {
      NodeLoggerPure.Delete.debug(s"  - reset node key certification status for '${nodeId.value}'") *>
      (for {
        con <- ldap
        res <- con.modify(
                 deletedDit.NODES.NODE.dn(nodeId.value),
                 new Modification(ModificationType.REPLACE, A_KEY_STATUS, UndefinedKey.value)
               )
      } yield ()).catchAll(err => {
        NodeLoggerPure.Delete.error(
          s"Error when removing the certification status of node key ${(nodeId, info).name}: ${err.fullMsg}"
        )
      })
    } else ZIO.unit
  }
}

// clean-up cfengine key - only possible if we still have an inventory
class CleanUpCFKeys extends PostNodeDeleteAction {
  override def run(
      nodeId:    NodeId,
      mode:      DeleteMode,
      info:      Option[NodeInfo],
      status:    Set[InventoryStatus]
  )(implicit cc: ChangeContext): UIO[Unit] = {
    info match {
      case Some(i) =>
        val agentTypes = i.agentsName.map(_.agentType).toSet
        if (agentTypes.contains(AgentType.CfeCommunity)) {
          NodeLoggerPure.Delete.debug(s"  - delete CFEngine keys for '${nodeId.value}'") *>
          deleteCfengineKey(i)
        } else ZIO.unit
      case _       => ZIO.unit
    }
  }

  /*
   * Delete cfengine key. We need to do it here b/c we don't have a variable with the
   * key hash to use in a hook.
   */
  def deleteCfengineKey(nodeInfo: NodeInfo): UIO[Unit] = {
    nodeInfo.keyHashCfengine match {
      case null | "" => // no key or not a cfengine agent
        ZIO.unit
      case key       =>
        // key name looks like: root-MD5=8d3270d42486e8d6436d06ed5cc5034f.pub
        IOResult
          .attempt(
            Files
              .find(
                Paths.get("/var/rudder/cfengine-community/ppkeys"),
                1,
                new BiPredicate[Path, BasicFileAttributes] {
                  override def test(keyName: Path, u: BasicFileAttributes): Boolean = keyName.toString().endsWith(key + ".pub")
                }
              )
              .forEach(new Consumer[Path] {
                override def accept(p: Path): Unit = {
                  try {
                    Files.delete(p)
                  } catch {
                    case ex: Exception =>
                      NodeLoggerPure.Delete.logEffect.info(
                        s"Error when trying to clean-up CFEngine key for node '${nodeInfo.id.value}' at path: '${p.toString}': ${ex.getMessage}"
                      )
                  }
                }
              })
          )
          .catchAll(err => {
            NodeLoggerPure.Delete.info(
              s"Error when cleaning-up cfengine key for node ${nodeInfo.hostname} (${nodeInfo.id.value}), some files may be remaining."
            )
          })
    }
  }
}

// clean-up node files on FS
class CleanUpNodePolicyFiles(varRudderShare: String) extends PostNodeDeleteAction {
  import better.files._
  import better.files.File._

  override def run(
      nodeId:    NodeId,
      mode:      DeleteMode,
      info:      Option[NodeInfo],
      status:    Set[InventoryStatus]
  )(implicit cc: ChangeContext): UIO[Unit] = {
    NodeLoggerPure.Delete.debug(s"  - clean-up node '${nodeId.value}' policy files in /var/rudder/share") *>
    cleanPoliciesRec(nodeId, File(varRudderShare)).runDrain.catchAll(err => {
      NodeLoggerPure.Delete.info(
        s"Error when cleaning-up policy files for node ${(nodeId, info).name}, some files may be remaining: ${err.fullMsg}"
      )
    })
  }

  /*
   * We want to delete the policy directory for node.
   * We want to look in all policy servers, b/c rudder used to let files everywhere.
   * To be more resilient, we only look at directory structure, not what rudder believes.
   * Structure looks like that:
   * /var/rudder/share
   *   |- 72F5478E-8214-4B90-8923-D1B1D944DFF5  // simple node
   *   |    `- rules
   *   |- a271f3af-0f2b-4538-a1da-0f7b1b1900f2  // relay
   *        |- rules // relay own policies
   *        `- share // relay nodes
   *             |- ca549eb1-6b4b-4823-b7ec-fc5a818893d0 // simple node
   *             |    `- rules
   *             `- b8dcd41d-be6f-40e5-9b4f-f494fdeda113 // sub-relay
   *                  |- rules
   *                  `- share
   *                       |- 93e375c4-914e-49b0-98d2-c2aa3b3a6095
   *                       ... etc ..
   *
   * So, algo structure is recursive by nature, and we do
   * - start at /var/rudder/share [policy server root directory]
   * - look for node ID directory, delete it
   * - select all relays by looking for /share, recurse.
   *
   * That means that we will have to walk all node directory at least once.
   * This can be costly when there is 20 000 nodes but:
   * - we can do that in an iterator and only consume one fd at a time
   * - it only happens when a node is deleted, so that should not even been in the
   *   "several time per minute" range.
   */
  def cleanPoliciesRec(nodeId: NodeId, file: File): ZStream[Any, RudderError, File] = {
    ZStream
      .fromZIO(
        IOResult.attempt(file.exists).catchAll(err => NodeLoggerPure.Delete.error(err.fullMsg) *> false.succeed)
      )
      .flatMap(cond => {
        // here file is either root or a rec call with "path/share". If it doesn't exists,
        // it's because the node wasn't a relay, so processing for that branch.
        if (!cond) ZStream.empty
        else {
          ZStream
            .fromIterator(file.children)
            .mapError(
              SystemError(s"Error when listing children of file '${file.pathAsString}' when cleaning node '${nodeId.value}'", _)
            )
            .tap(_ => {
              NodeLoggerPure.Delete.ifTraceEnabled {
                (ZIO.attempt {
                  (root / "proc" / "self").children.size
                } catchAll { _ => (-1).succeed })
                  .flatMap(openfd => NodeLoggerPure.Delete.trace(s"Currently ${openfd} open files by Rudder Server process"))
              }
            })
            .flatMap { nodeFolder =>
              if (nodeId == NodeId(nodeFolder.name)) {
                ZStream.fromZIO(IOResult.attempt(nodeFolder.delete()) *> nodeFolder.succeed)
              } else {
                cleanPoliciesRec(nodeId, nodeFolder / "share")
              }
            }
        }
      })
  }
}
