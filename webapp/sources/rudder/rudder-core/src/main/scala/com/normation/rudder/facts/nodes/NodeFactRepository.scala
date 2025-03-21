/*
 *************************************************************************************
 * Copyright 2021 Normation SAS
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

import com.normation.errors.*
import com.normation.inventory.domain.*
import com.normation.inventory.services.core.ReadOnlySoftwareDAO
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.logger.NodeLoggerPure
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.tenants.DefaultTenantService
import com.normation.rudder.tenants.TenantService
import com.normation.zio.*
import com.softwaremill.quicklens.*
import scala.collection.MapView
import zio.*
import zio.concurrent.ReentrantLock
import zio.stream.ZStream
import zio.syntax.*

/*
 * NodeFactRepository is the main interface between Rudder user space and nodes. It manages
 * the whole persistence and consistency, efficient access to a (core) set of information on
 * nodes, and access permissions.
 *
 * The basic contract regarding performance is that:
 * - saving things is slow and accounts for the consistency of data view,
 * - view on the subset of node fact that matches minimal API / core node fact is fast (~in memory map)
 * - access to other data is slow and need a cold storage retrieval
 *
 *
 * The typical use case we need to be able to handle:
 * - save a new inventory (full node fact, pending)
 * - save an inventory update
 * - save the audit mode change or node scheduling
 * - save a new property
 *
 * Getting:
 * - fast access to node code info for [computing compliance, access to node main inventory variable, display node info...]
 * - get the whole inventory APART software and process (b/c too slow)
 * - get only software for the node
 *
 * Note: is it not the same to retrieve "most of nodefact" and "node fact", because we can have extreme performance
 * impacts for just some, rarely used (or use only on some nodes), information. Typically:
 * - software ;
 * - process ;
 * - some hardware information on very complex harware.
 * And in all case,
 *
 */
trait NodeFactRepository {
  /*
   * Add a call back that will be called when a change occurs.
   * The callbacks are not ordered and not blocking and will have a short time-out
   * on them, the caller will need to manage that constraint.
   */
  def registerChangeCallbackAction(callback: NodeFactChangeEventCallback): IOResult[Unit]

  /*
   * Get the status of the node, or RemovedStatus if it is
   * not found.
   */
  def getStatus(id: NodeId)(implicit qc: QueryContext): IOResult[InventoryStatus]

  /*
   * Translation between old inventory status and new SelectNodeStatus for IOResult methods
   */
  def statusCompat[A](status: InventoryStatus, f: (QueryContext, SelectNodeStatus) => IOResult[A])(implicit
      qc: QueryContext
  ): IOResult[A] = {
    status match {
      case AcceptedInventory => f(qc, SelectNodeStatus.Accepted)
      case PendingInventory  => f(qc, SelectNodeStatus.Pending)
      case RemovedInventory  => Inconsistency("Node is missing").fail
    }
  }

  /*
   * Translation between old inventory status and new SelectNodeStatus for IOStream methods
   */
  def statusStreamCompat[A](status: InventoryStatus, f: (QueryContext, SelectNodeStatus) => IOStream[A])(implicit
      qc: QueryContext
  ): IOStream[A] = {
    status match {
      case AcceptedInventory => f(qc, SelectNodeStatus.Accepted)
      case PendingInventory  => f(qc, SelectNodeStatus.Pending)
      case RemovedInventory  => ZStream.fromZIO(Inconsistency("Node is missing").fail)
    }
  }

  /*
   * Get the number of active managed nodes.
   * This should be made fast, because it's typically used in license check.
   */
  def getNumberOfManagedNodes(): IOResult[Int]

  /*
   * Get node on given status
   */
  def get(
      nodeId: NodeId
  )(implicit qc: QueryContext, status: SelectNodeStatus = SelectNodeStatus.Any): IOResult[Option[CoreNodeFact]]

  def getCompat(nodeId: NodeId, status: InventoryStatus)(implicit qc: QueryContext): IOResult[Option[CoreNodeFact]] = {
    statusCompat(status, (qc, s) => get(nodeId)(qc, s))
  }

  /*
   * Return the node fact corresponding to the given node id with
   * the fields from select mode "ignored" set to empty.
   */
  def slowGet(nodeId: NodeId)(implicit
      qc:     QueryContext,
      status: SelectNodeStatus = SelectNodeStatus.Any,
      attrs:  SelectFacts = SelectFacts.default
  ): IOResult[Option[NodeFact]]

  def slowGetCompat(nodeId: NodeId, status: InventoryStatus, attrs: SelectFacts)(implicit
      qc: QueryContext
  ): IOResult[Option[NodeFact]] = {
    statusCompat(status, (qc, s) => slowGet(nodeId)(qc, s, attrs))
  }

  def getNodesBySoftwareName(softName: String): IOResult[List[(NodeId, Software)]]

  /*
   * get all node facts.
   * SelectStatus allows to choose which nodes are retrieved (pending, accepted, all).
   * For that methods to be the most effecient possible, it is deeply bounded to implementation
   * and will just return the underline cached immutable map.
   */
  def getAll()(implicit
      qc:     QueryContext,
      status: SelectNodeStatus = SelectNodeStatus.Accepted
  ): IOResult[MapView[NodeId, CoreNodeFact]]

  def getAllCompat(status: InventoryStatus, attrs: SelectFacts)(implicit
      qc: QueryContext
  ): IOResult[MapView[NodeId, CoreNodeFact]] = {
    statusCompat(status, (qc, s) => getAll()(qc, s))
  }

  /*
   * A version of getAll that allows to retrieve attributes out of CoreNodeFact at the
   * price of a round trip to the cold storage.
   * Implementation must be smart and ensure that if attrs == SelectFacts.none,
   * then it reverts back to the quick version.
   */
  def slowGetAll()(implicit
      qc:     QueryContext,
      status: SelectNodeStatus = SelectNodeStatus.Accepted,
      attrs:  SelectFacts = SelectFacts.default
  ): IOStream[NodeFact]

  def slowGetAllCompat(status: InventoryStatus, attrs: SelectFacts)(implicit qc: QueryContext): IOStream[NodeFact] = {
    statusStreamCompat(status, (qc, s) => slowGetAll()(qc, s, attrs))
  }

  ///// changes /////

  /*
   * Save (create or override) a core node fact
   * Use "updateInventory` if you want to save in pending, it's likely what you want.
   *
   * Not that the diff is only done on the core properties.
   * SecurityTag must not be updated with that method, but it can be initially set.
   */
  def save(
      nodeFact: NodeFact
  )(implicit cc: ChangeContext, attrs: SelectFacts = SelectFacts.all): IOResult[NodeFactChangeEventCC]

  /*
   * A simpler version of save for CoreNodeFacts
   */
  def save(nodeFact: CoreNodeFact)(implicit cc: ChangeContext): IOResult[NodeFactChangeEventCC] = {
    save(NodeFact.fromMinimal(nodeFact))(cc, SelectFacts.none)
  }

  /*
   * Define security tag for the node if change context allows it.
   */
  def setSecurityTag(nodeId: NodeId, tag: Option[SecurityTag])(implicit cc: ChangeContext): IOResult[NodeFactChangeEventCC]

  /*
   * Save the full node fact.
   * If some fields are marked as ignored, they must not be updated by the persistence layer
   * (it's up to it to do it).
   *
   * Not sure it's interesting since we have "update inventory" ?
   */
  // def saveFull[A](nodeId: NodeId, fact: NodeFact)(implicit cc: ChangeContext, s: SelectFacts = SelectFacts.all): IOResult[Unit]

  /*
   * A method that will create in new node fact in pending, or
   * update inventory part of the node with that nodeId in
   * pending or in accepted.
   */
  def updateInventory(inventory: FullInventory, software: Option[Iterable[Software]])(implicit
      cc: ChangeContext
  ): IOResult[NodeFactChangeEventCC]

  /*
   * Change the status of the node with given id to given status.
   * - if the node is not found, an error is raised apart if target status is "delete"
   * - if the target status is the current one, this function does nothing
   * - if target status is "removed", persisted inventory is deleted
   */
  def changeStatus(nodeId: NodeId, into: InventoryStatus)(implicit
      cc: ChangeContext
  ): IOResult[NodeFactChangeEventCC]

  /*
   * Delete any reference to that node id.
   */
  def delete(nodeId: NodeId)(implicit cc: ChangeContext): IOResult[NodeFactChangeEventCC]
}

/*
 * A partial in memory implementation of the NodeFactRepository that persist (for cold storage)
 * it's information in given backend.
 *
 * NodeFacts are split in two parts:
 * - CoreNodeFacts are kept in memory which allows for fast lookup and search on main attributes
 * - full NodeFacts are retrieved from cold storage on demand.
 *
 * The following operation are always persisted in cold storage and will be blocking:
 * - create a new node fact
 * - update an existing one
 * - change status of a node
 * - delete a node.
 *
 * Core node facts info are always saved.
 * To be more precise on what is retrieved or saved for non-core nodeFact, you can use the `SelectFacts`
 * parametrization which will restraint get/save only the specified info.
 *
 * Once initialized, that repository IS the truth for CoreNodeFact info. No change done by
 * an other mean in the cold storage will be visible from Rudder without an explicit
 * `fetchAndSync` call.
 * Moreover, that repository is in charge to ensure consistency of states for nodes.
 * Consequently, any change in a nodes must go through that repository, from inventory updates to
 * node acceptation or properties setting.
 *
 * For change, that repos try to ensure that the backend does commit the change before having it done
 * in memory. That arch does not scale to many backend, since once there is more than one, compensation
 * strategy must be put into action to compensate for errors (see zio-workflow for that kind of things).
 *
 */
object CoreNodeFactRepository {
  val defaultSavePreChecks = {
    def checkRootProperties(node: NodeFact): IOResult[Unit] = {
      // use cats validation
      import cats.data.*
      import cats.implicits.*

      type ValidationResult = ValidatedNel[String, Unit]
      val ok = ().validNel

      def validateRoot(node: NodeFact): IOResult[Unit] = {
        // transform a validation result to a Full | Failure
        implicit class toIOResult(validation: ValidatedNel[String, List[Unit]]) {
          def toZIO: IOResult[Unit] = {
            validation.fold(
              nel => Inconsistency(nel.toList.mkString("; ")).fail,
              _ => ZIO.unit
            )
          }
        }

        val checks: List[NodeFact => ValidationResult] = List(
          (node: NodeFact) => { // root is enabled
            if (node.rudderSettings.state == NodeState.Enabled) ok
            else s"Root node must always be in '${NodeState.Enabled.name}' lifecycle state.".invalidNel
          },
          (node: NodeFact) => { // root is PolicyServer
            if (node.isPolicyServer) ok
            else "You can't change the 'policy server' nature of Root policy server".invalidNel
          },
          (node: NodeFact) => { // rootIsSystem
            if (node.isSystem) ok
            else "You can't change the 'system' nature of Root policy server".invalidNel
          },
          (node: NodeFact) => { // rootIsAccepted
            if (node.rudderSettings.status == AcceptedInventory) ok
            else "You can't change the 'status' of Root policy server, it must be accepted".invalidNel
          }
        )

        checks.traverse(_(node)).toZIO
      }

      ZIO.when(node.id == Constants.ROOT_POLICY_SERVER_ID)(validateRoot(node)).unit
    }

    def checkAgentKey(node: NodeFact): IOResult[Unit] = {
      node.rudderAgent.securityToken match {
        case Certificate(value) => SecurityToken.checkCertificateForNode(node.id, Certificate(value))
        case _                  => Unexpected(s"only certificate are supported for agent security token since Rudder 7.0").fail
      }
    }

    Chunk(checkRootProperties(_), checkAgentKey(_))
  }

  def make(
      storage:       NodeFactStorage,
      softByName:    GetNodesBySoftwareName,
      tenants:       TenantService,
      callbacks:     Chunk[NodeFactChangeEventCallback],
      savePreChecks: Chunk[NodeFact => IOResult[Unit]] = defaultSavePreChecks // that should really be used apart in some tests
  ): IOResult[CoreNodeFactRepository] = for {
    _        <- InventoryDataLogger.debug("Getting pending node info for node fact repos")
    pending  <- storage.getAllPending()(SelectFacts.none).map(f => (f.id, f.toCore)).runCollect.map(_.toMap)
    _        <- InventoryDataLogger.debug("Getting accepted node info for node fact repos")
    accepted <- storage.getAllAccepted()(SelectFacts.none).map(f => (f.id, f.toCore)).runCollect.map(_.toMap)
    _        <- InventoryDataLogger.debug("Creating node fact repos")
    repo     <- make(storage, softByName, tenants, pending, accepted, callbacks, savePreChecks)
  } yield {
    repo
  }

  def make(
      storage:       NodeFactStorage,
      softByName:    GetNodesBySoftwareName,
      tenants:       TenantService,
      pending:       Map[NodeId, CoreNodeFact],
      accepted:      Map[NodeId, CoreNodeFact],
      callbacks:     Chunk[NodeFactChangeEventCallback],
      savePreChecks: Chunk[NodeFact => IOResult[Unit]]
  ): UIO[CoreNodeFactRepository] = for {
    p    <- Ref.make(pending)
    a    <- Ref.make(accepted)
    lock <- ReentrantLock.make()
    cbs  <- Ref.make(callbacks)
  } yield {
    new CoreNodeFactRepository(storage, softByName, tenants, p, a, cbs, savePreChecks, lock)
  }

  // a version for tests
  def makeNoop(
      accepted:      Map[NodeId, CoreNodeFact],
      pending:       Map[NodeId, CoreNodeFact] = Map(),
      callbacks:     Chunk[NodeFactChangeEventCallback] = Chunk.empty,
      savePreChecks: Chunk[NodeFact => IOResult[Unit]] = Chunk.empty
  ): UIO[CoreNodeFactRepository] = for {
    t <- DefaultTenantService.make(Nil)
    r <- make(NoopFactStorage, NoopGetNodesBySoftwareName, t, pending, accepted, callbacks, savePreChecks)
  } yield r
}

// we have some specialized services / materialized view for complex queries. Implementation can manage cache and
// react to callbacks (update events) to manage consistency
trait GetNodesBySoftwareName {
  def apply(softName: String): IOResult[List[(NodeId, Software)]]
}

object NoopGetNodesBySoftwareName extends GetNodesBySoftwareName {
  override def apply(softName: String): IOResult[List[(NodeId, Software)]] = Nil.succeed
}

// default implementation is just a proxy on top of software dao
class SoftDaoGetNodesBySoftwareName(val softwareDao: ReadOnlySoftwareDAO) extends GetNodesBySoftwareName {
  override def apply(softName: String): IOResult[List[(NodeId, Software)]] = {
    softwareDao.getNodesBySoftwareName(softName)
  }
}

/*
 * The core node fact repository save:
 * - CoreNodeFact in a local map that is always in sync with persisted layers
 * - extension data (for inventory) in external caches
 *
 * It also provide et default implementation for getting/saving CoreNodeFact and Full facts
 * thanks to the provided NodeFactStorage. Other getter/saver will need to be implemented
 * by your own.
 *
 * Rudder server (id=root) is special among nodes. It can be disabled, non system, deleted, etc.
 */
class CoreNodeFactRepository(
    storage:        NodeFactStorage,
    softwareByName: GetNodesBySoftwareName,
    tenantService:  TenantService,
    pendingNodes:   Ref[Map[NodeId, CoreNodeFact]],
    acceptedNodes:  Ref[Map[NodeId, CoreNodeFact]],
    callbacks:      Ref[Chunk[NodeFactChangeEventCallback]],
    savePreChecks:  Chunk[NodeFact => IOResult[Unit]],
    lock:           ReentrantLock,
    cbTimeout:      zio.Duration = 5.seconds
) extends NodeFactRepository {
  import NodeFactChangeEvent.*

  // number of accepted, enambled node
  private def coundEnabled(nodes: Map[NodeId, CoreNodeFact]) = nodes.count(_._2.rudderSettings.state.isEnabled)
  private val enabledNodes:         Ref[Int]  = (for {
    n <- acceptedNodes.get
    r <- Ref.make(coundEnabled(n))
  } yield r).runNow
  private def updateEnabledCount(): UIO[Unit] = {
    for {
      n <- acceptedNodes.get
      r <- enabledNodes.set(coundEnabled(n))
    } yield r
  }

  // debug log
//  (for {
//    p <- pendingNodes.get.map(_.values.map(_.id.value).mkString(", "))
//    a <- acceptedNodes.get.map(_.values.map(_.id.value).mkString(", "))
//    _ <- InventoryDataLogger.debug(s"Loaded node fact repos with: \n - pending: ${p} \n - accepted: ${a}")
//  } yield ()).runNow

  override def registerChangeCallbackAction(
      callback: NodeFactChangeEventCallback
  ): IOResult[Unit] = {
    callbacks.update(_.appended(callback))
  }

  /*
   * This method will need some thoughts:
   * - do we want to fork and timeout each callbacks ? likely so
   * - do we want to parallel exec them ? likely so, the user can build his own callback sequencer callback if he wants
   */
  private[nodes] def runCallbacks(e: NodeFactChangeEventCC): UIO[Unit] = {
    for {
      cs <- callbacks.get
      _  <- ZIO
              .foreachParDiscard(cs) { cb =>
                cb.run(e)
                  .timeout(cbTimeout)
                  .catchAll(ex => {
                    NodeLoggerPure
                      .error(
                        s"Error when executing callback '${cb.name}' on node change event ${e.event.debugString}': ${ex.fullMsg}"
                      )
                  })
              }
              .forkDaemon
    } yield ()
  }

  override def getStatus(id: NodeId)(implicit qc: QueryContext): IOResult[InventoryStatus] = {
    getOnRef(acceptedNodes, id).flatMap {
      case None =>
        getOnRef(pendingNodes, id).flatMap {
          case None => RemovedInventory.succeed
          case _    => PendingInventory.succeed
        }
      case _    => AcceptedInventory.succeed
    }
  }

  private[nodes] def getOnRef(ref: Ref[Map[NodeId, CoreNodeFact]], nodeId: NodeId)(implicit
      qc: QueryContext
  ): IOResult[Option[CoreNodeFact]] = {
    tenantService.nodeGetMapView(ref, nodeId)
  }

  /*
   * Require to re-sync from cold storage cache info.
   * It will lead to a diff and subsequent callbacks for any changes
   */
  def fetchAndSync(nodeId: NodeId)(implicit cc: ChangeContext): IOResult[NodeFactChangeEventCC] = {
    implicit val attrs: SelectFacts = SelectFacts.default
    if (cc.nodePerms.isNone) NodeFactChangeEventCC(NodeFactChangeEvent.Noop(nodeId, attrs), cc).succeed
    else {
      for {
        a    <- storage.getAccepted(nodeId)
        p    <- storage.getPending(nodeId)
        c    <- get(nodeId)(cc.toQuery, SelectNodeStatus.Any)
        diff <- (a, p, c) match {
                  case (None, None, _)    => delete(nodeId)
                  case (None, Some(x), _) =>
                    saveOn(pendingNodes, x.toCore).map { e =>
                      e.updateWith(StorageChangeEventSave.Created(x, attrs))
                        .toChangeEvent(nodeId, PendingInventory, cc)
                    }
                  case (Some(x), _, _)    =>
                    saveOn(acceptedNodes, x.toCore).map { e =>
                      e.updateWith(StorageChangeEventSave.Created(x, attrs))
                        .toChangeEvent(nodeId, AcceptedInventory, cc)
                    }
                }
      } yield diff
    }
  }

  override def getNumberOfManagedNodes(): IOResult[Int] = enabledNodes.get

  override def get(
      nodeId: NodeId
  )(implicit qc: QueryContext, status: SelectNodeStatus = SelectNodeStatus.Any): IOResult[Option[CoreNodeFact]] = {
    status match {
      case SelectNodeStatus.Pending  =>
        getOnRef(pendingNodes, nodeId)
      case SelectNodeStatus.Accepted =>
        getOnRef(acceptedNodes, nodeId)
      case SelectNodeStatus.Any      =>
        getOnRef(acceptedNodes, nodeId).flatMap(opt => opt.fold(getOnRef(pendingNodes, nodeId))(Some(_).succeed))
    }
  }

  override def slowGet(
      nodeId: NodeId
  )(implicit qc: QueryContext, status: SelectNodeStatus, attrs: SelectFacts): IOResult[Option[NodeFact]] = {
    for {
      optCNF <- get(nodeId)(qc, status)
      res    <- optCNF match {
                  case None    => None.succeed
                  case Some(v) =>
                    val fact = NodeFact.fromMinimal(v)
                    if (attrs == SelectFacts.none) {
                      Some(fact).succeed
                    } else {
                      (status match {
                        case SelectNodeStatus.Pending  => storage.getPending(nodeId)(attrs)
                        case SelectNodeStatus.Accepted => storage.getAccepted(nodeId)(attrs)
                        case SelectNodeStatus.Any      =>
                          storage.getAccepted(nodeId)(attrs).flatMap {
                            case Some(x) => Some(x).succeed
                            case None    => storage.getPending(nodeId)(attrs)
                          }
                      }).flatMap {
                        case None    =>
                          // here, we have the value in cache but not in cold storage.
                          // This is an inconsistency and likely going to pause problem latter on
                          // perhaps we should compensate, CoreNodeFactRepo should be the reference.
                          // At least log.
                          NodeLoggerPure.warn(
                            s"Inconsistency: node '${fact.fqdn}' [${fact.id.value}] was found in Rudder memory base but not in cold storage. " +
                            s"This is not supposed to be, perhaps cold storage was modified not through Rudder. This is likely to lead to consistency problem. " +
                            s"You should use Rudder API."
                          ) *> // in that case still return core fact
                          Some(fact).succeed
                        case Some(b) =>
                          Some(SelectFacts.mergeCore(v, b)(attrs)).succeed
                      }
                    }
                }
      sec    <- tenantService.nodeFilter(res)
    } yield sec
  }

  private[nodes] def getAllOnRef[A](
      ref: Ref[Map[NodeId, CoreNodeFact]]
  )(implicit qc: QueryContext): IOResult[MapView[NodeId, CoreNodeFact]] = {
    tenantService.nodeFilterMapView(ref)
  }

  override def getAll()(implicit qc: QueryContext, status: SelectNodeStatus): IOResult[MapView[NodeId, CoreNodeFact]] = {
    status match {
      case SelectNodeStatus.Pending  => getAllOnRef(pendingNodes)
      case SelectNodeStatus.Accepted => getAllOnRef(acceptedNodes)
      case SelectNodeStatus.Any      =>
        for {
          a <- getAllOnRef(pendingNodes)
          b <- getAllOnRef(acceptedNodes)
        } yield (a ++ b).toMap.view
    }
  }

  override def slowGetAll()(implicit qc: QueryContext, status: SelectNodeStatus, attrs: SelectFacts): IOStream[NodeFact] = {
    tenantService.nodeFilterStream(
      if (attrs == SelectFacts.none) {
        ZStream.fromIterableZIO(getAll()(qc, status).map(_.map(cnf => NodeFact.fromMinimal(cnf._2))))
      } else {
        status match {
          // here, the filtering could be forwarded to storage, but core node fact must check it in
          // all case, it's its responsibility
          case SelectNodeStatus.Pending  => storage.getAllPending()(attrs)
          case SelectNodeStatus.Accepted => storage.getAllAccepted()(attrs)
          case SelectNodeStatus.Any      => storage.getAllPending()(attrs) ++ storage.getAllAccepted()(attrs)
        }
      }
    )
  }

  override def getNodesBySoftwareName(softName: String): IOResult[List[(NodeId, Software)]] = {
    softwareByName(softName)
  }

  /*
   * Save on the given ref the given CoreNodeFact. This method is dumb and won't
   * check for tenants, which should have been done before hand.
   */
  private def saveOn(ref: Ref[Map[NodeId, CoreNodeFact]], nodeFact: CoreNodeFact): IOResult[StorageChangeEventSave] = {
    ref
      .getAndUpdate(_ + ((nodeFact.id, nodeFact)))
      .map { old =>
        old.get(nodeFact.id) match {
          case Some(n) =>
            if (CoreNodeFact.same(n, nodeFact)) StorageChangeEventSave.Noop(nodeFact.id, SelectFacts.none)
            else StorageChangeEventSave.Updated(NodeFact.fromMinimal(n), NodeFact.fromMinimal(nodeFact), SelectFacts.none)
          case None    =>
            StorageChangeEventSave.Created(NodeFact.fromMinimal(nodeFact), SelectFacts.none)
        }
      }
  }

  private def deleteOn(ref: Ref[Map[NodeId, CoreNodeFact]], nodeId: NodeId)(implicit
      cc: ChangeContext
  ): IOResult[StorageChangeEventDelete] = {
    tenantService
      .getTenants()
      .flatMap(tenants => {
        ref
          .modify(nodes => {
            nodes.get(nodeId) match {
              case None           => (Right(StorageChangeEventDelete.Noop(nodeId)), nodes)
              case Some(existing) =>
                tenantService.checkDelete(existing, cc, tenants) match {
                  case Left(err) => (Left(err), nodes)
                  case Right(n)  =>
                    val e = StorageChangeEventDelete.Deleted(NodeFact.fromMinimal(n), SelectFacts.none)
                    (Right(e), nodes.removed(nodeId))
                }
            }
          })
          .flatMap(ZIO.fromEither(_))
      })
  }

  /*
   * Save can be used in two fashion: either for setting tenants
   * or for save. In the second case, we must ensure tenants don't change.
   * In the first case, we use the existing coreNodeFact for save.
   */
  private def internalSave(
      node: Either[NodeFact, (NodeId, Option[SecurityTag])]
  )(implicit cc: ChangeContext, attrs: SelectFacts): IOResult[NodeFactChangeEventCC] = {
    ZIO.scoped(
      for {
        _                   <- lock.withLock
        core                <- get(node.fold(_.id, _._1))(cc.toQuery)
        pair                <- (node, core) match {
                                 // first case: we are saving a new node fact, tenant can be set
                                 // we keep attrs from request
                                 case (Left(x), None)          => (x, attrs).succeed
                                 // second case: updating a node fact, we need to keep existing tenant
                                 // we also keep status
                                 // we keep attrs from request
                                 case (Left(x), Some(y))       =>
                                   (
                                     x
                                       .modify(_.rudderSettings.security)
                                       .setTo(y.rudderSettings.security)
                                       .modify(_.rudderSettings.status)
                                       .setTo(y.rudderSettings.status),
                                     attrs
                                   ).succeed
                                 // third case: we are updating tenants and we don't have a node fact in the query (just nodeId)
                                 // => SelectedFacts must be "none"
                                 case (Right((_, t)), Some(x)) =>
                                   (NodeFact.fromMinimal(x.modify(_.rudderSettings.security).setTo(t)), SelectFacts.none).succeed
                                 case (Right((id, _)), None)   =>
                                   Inconsistency(s"Error: can not change tenant of missing node with Id '${id.value}'").fail
                               }
        (nodeFact, selected) = pair
        es                  <- tenantService.manageUpdate(core, nodeFact, cc) { updated =>
                                 for {
                                   // first we persist on cold storage, which is more likely to fail. Plus, for history reason, some
                                   // mapping are not exactly isomorphic, and some normalization can happen - for ex, for missing machine.
                                   s <- storage.save(updated)(selected)
                                   // then, we get the actual thing that was saved from the save event
                                   up = s match {
                                          case StorageChangeEventSave.Created(node, _)             =>
                                            node
                                          case StorageChangeEventSave.Updated(oldNode, newNode, _) =>
                                            newNode
                                          case StorageChangeEventSave.Noop(nodeId, _)              =>
                                            updated
                                        }
                                   // then, we save that as the core node fact reference
                                   e <- nodeFact.rudderSettings.status match {
                                          case RemovedInventory  => // this case is ignored, we don't delete node based on status value
                                            StorageChangeEventSave.Noop(up.id, attrs).succeed
                                          case PendingInventory  =>
                                            saveOn(pendingNodes, up.toCore)
                                          case AcceptedInventory =>
                                            saveOn(acceptedNodes, up.toCore)
                                        }
                                   // finally, merge both events and return the final change event and run call backs on it
                                   es = s.updateWith(e).toChangeEvent(up.id, up.rudderSettings.status, cc)
                                   _ <- NodeLoggerPure.Details.Write.trace(s"Saved node event: ${es.event.debugDiff}")
                                   _ <- runCallbacks(es)
                                 } yield es
                               }
        // update number of active nodes
        _                   <- updateEnabledCount()
      } yield es
    )
  }

  override def setSecurityTag(nodeId: NodeId, tag: Option[SecurityTag])(implicit
      cc: ChangeContext
  ): IOResult[NodeFactChangeEventCC] = {
    internalSave(Right((nodeId, tag)))(cc, SelectFacts.none)
  }

  override def save(nodeFact: NodeFact)(implicit cc: ChangeContext, attrs: SelectFacts): IOResult[NodeFactChangeEventCC] = {
    ZIO.foreachDiscard(savePreChecks)(_(nodeFact)) *>
    internalSave(Left(nodeFact))
  }

  override def changeStatus(nodeId: NodeId, into: InventoryStatus)(implicit
      cc: ChangeContext
  ): IOResult[NodeFactChangeEventCC] = {
    if (nodeId == Constants.ROOT_POLICY_SERVER_ID && into != AcceptedInventory) {
      Inconsistency(s"Rudder server (id='root' must be accepted").fail
    } else {
      implicit val qc: QueryContext = cc.toQuery
      ZIO.scoped(
        for {
          _ <- lock.withLock
          _ <- storage.changeStatus(nodeId, into)
          e <-
            for {
              pending  <- getOnRef(pendingNodes, nodeId)
              accepted <- getOnRef(acceptedNodes, nodeId)
              e        <- (into, pending, accepted) match {
                            case (RemovedInventory, Some(x), None)     =>
                              deleteOn(pendingNodes, nodeId) *> NodeFactChangeEventCC(
                                Refused(NodeFact.fromMinimal(x), SelectFacts.none),
                                cc
                              ).succeed
                            case (RemovedInventory, None, Some(x))     =>
                              deleteOn(acceptedNodes, nodeId) *> NodeFactChangeEventCC(
                                Deleted(NodeFact.fromMinimal(x), SelectFacts.none),
                                cc
                              ).succeed
                            case (RemovedInventory, Some(_), Some(x))  =>
                              deleteOn(pendingNodes, nodeId) *>
                              deleteOn(acceptedNodes, nodeId) *>
                              NodeFactChangeEventCC(Deleted(NodeFact.fromMinimal(x), SelectFacts.none), cc).succeed
                            case (RemovedInventory, None, None)        =>
                              NodeFactChangeEventCC(Noop(nodeId, SelectFacts.none), cc).succeed
                            case (_, None, None)                       =>
                              Inconsistency(
                                s"Error: node '${nodeId.value}' was not found in rudder (neither pending nor accepted nodes"
                              ).fail
                            case (AcceptedInventory, None, Some(_))    =>
                              NodeFactChangeEventCC(Noop(nodeId, SelectFacts.none), cc).succeed
                            case (AcceptedInventory, Some(x), None)    =>
                              deleteOn(pendingNodes, nodeId) *> saveOn(
                                acceptedNodes,
                                x.modify(_.rudderSettings.status).setTo(AcceptedInventory)
                              ) *> NodeFactChangeEventCC(Accepted(NodeFact.fromMinimal(x), SelectFacts.none), cc).succeed
                            case (AcceptedInventory, Some(_), Some(_)) =>
                              deleteOn(pendingNodes, nodeId) *> NodeFactChangeEventCC(Noop(nodeId, SelectFacts.none), cc).succeed
                            case (PendingInventory, None, Some(x))     =>
                              deleteOn(acceptedNodes, nodeId) *> saveOn(
                                pendingNodes,
                                x.modify(_.rudderSettings.status).setTo(PendingInventory)
                              ) *> NodeFactChangeEventCC(
                                Deleted(NodeFact.fromMinimal(x), SelectFacts.none),
                                cc
                              ).succeed // not sure about the semantic here
                            case (PendingInventory, Some(_), None)     =>
                              NodeFactChangeEventCC(Noop(nodeId, SelectFacts.none), cc).succeed
                            case (PendingInventory, Some(_), Some(x))  =>
                              deleteOn(acceptedNodes, nodeId) *> NodeFactChangeEventCC(
                                Deleted(NodeFact.fromMinimal(x), SelectFacts.none),
                                cc
                              ).succeed
                          }
            } yield e
          _ <- runCallbacks(e)
        } yield e
      )
    }
  }

  override def delete(nodeId: NodeId)(implicit cc: ChangeContext): IOResult[NodeFactChangeEventCC] = {
    ZIO.scoped(
      for {
        _   <- lock.withLock
        cnf <- get(nodeId)(cc.toQuery, SelectNodeStatus.Any)
        s   <- storage.delete(nodeId)(SelectFacts.all)
        e   <- cnf match {
                 case Some(n) =>
                   if (n.rudderSettings.status == PendingInventory) {
                     deleteOn(pendingNodes, nodeId).map(_.updateWith(s).toChangeEvent(n, PendingInventory, cc))
                   } else {
                     deleteOn(acceptedNodes, nodeId).map(_.updateWith(s).toChangeEvent(n, AcceptedInventory, cc))
                   }
                 case None    => NodeFactChangeEventCC(NodeFactChangeEvent.Noop(nodeId, SelectFacts.all), cc).succeed
               }
        _   <- runCallbacks(e)
      } yield e
    )
  }

  override def updateInventory(inventory: FullInventory, software: Option[Iterable[Software]])(implicit
      cc: ChangeContext
  ): IOResult[NodeFactChangeEventCC] = {
    val nodeId = inventory.node.main.id
    implicit val attrs: SelectFacts  = if (software.isEmpty) SelectFacts.noSoftware else SelectFacts.all
    implicit val qc:    QueryContext = cc.toQuery
    ZIO.scoped(
      for {
        _          <- lock.withLock
        optPending <- getOnRef(pendingNodes, nodeId)
        optFact    <- optPending match {
                        case Some(f) => Some(f).succeed
                        case None    => getOnRef(acceptedNodes, nodeId)
                      }
        fact        = optFact match {
                        case Some(f) =>
                          Some(NodeFact.updateFullInventory(NodeFact.fromMinimal(f), inventory, software))
                        case None    =>
                          // only people with full node rights can create node for now
                          if (cc.nodePerms == NodeSecurityContext.All) {
                            Some(NodeFact.newFromFullInventory(inventory, software))
                          } else {
                            None
                          }
                      }
        e          <- fact match {
                        case Some(f) => save(f) // save already runs callbacks
                        case None    =>
                          NodeLoggerPure.Security.warn(
                            s"Actor '${cc.actor.name}' does not have sufficient permission to create new nodes"
                          ) *>
                          NodeFactChangeEventCC(NodeFactChangeEvent.Noop(nodeId, attrs), cc).succeed
                      }
      } yield e
    )
  }
}
