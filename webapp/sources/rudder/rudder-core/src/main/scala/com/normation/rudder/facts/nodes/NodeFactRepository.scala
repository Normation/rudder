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

import NodeFactSerialisation._
import better.files.File
import com.normation.errors._
import com.normation.errors.IOResult
import com.normation.inventory.domain._
import com.normation.rudder.git.GitItemRepository
import com.normation.rudder.git.GitRepositoryProvider
import com.normation.zio._
import com.softwaremill.quicklens._
import java.nio.charset.StandardCharsets
import org.eclipse.jgit.lib.PersonIdent
import zio._
import zio.concurrent.ReentrantLock
import zio.json._
import zio.stream.ZStream
import zio.syntax._

/*
 * This file contains the base to persist facts into a git repository. There is a lot of question
 * remaning, so don't take current traits/classes as an API, it *WILL* change. The basic questions to answer are:
 * - do we want one bit "FactRepo" that knows about all kind of facts and is able to persis any of them ? In that case,
 *   we will need some kind of parametrization of `persist` with a type class to teach that repo how to serialize and
 *   persist each case
 * - do we prefer lots of small repos, one by entity, which knows how to persist only that entity ?
 * - plus, we want to have some lattitude on the serialization part, and be able to use both liftjson and zio-json
 *   (because the complete migration toward zio-json won't be finish immediately)
 *
 * The "one big" repo feels more like it is what we need, since it's really just one big git repo with subcases,
 * with shared tools and specialisation by entity. But I'm not sure how to build the capacities with type class
 * until I have several examples.
 * The small repos (one by entity) is what we used to do, so we are in known territory (see archive of configuration
 * entities), even if it is not a very satisfying one. Its advantage is that it's very simple, but it leads to a lot
 * of code duplication and maintenance is complicated (and adding a new entity is basically "copy that 100 lines of
 * code, and sed things", while we would like it to be "implement just that interface")
 *
 * Finally, we some coupling between serialization and repos as they are written for now: the path can't be known
 * without some part of the entity, but we don't know which part exactly (for node, it's its uuid and status, but
 * perhaps it's an exception, and for all other it's just an ID).
 *
 * With all these unknowns, I prefer to let parametrisation as simple as possible:
 * - no abstraction for repo, we just have a "node repo" with all the concret types. It's likely to become a:
 *   ```
 *     trait FactRepo { def persist[E](e: E)(implicit Serialize[E]): IOResult[Unit])
 *   ```
 *   Or something alike, but we just don't know.
 *
 * - some abstraction for serialisation, but just to put in plain sight the fact that there a caracteristic of
 *   the entity that is not the whole entity, and more then its ID, that is needed to build where the entity
 *   will be saved.
 *
 * - a simple implementation for nodes, that will need to be refactored depending of the chosen final arch.
 *
 * And finally, to complexify a bit more the picture, we see that there is events (observations?) linked to facts
 * that can update the previous fact partially. For nodes, it's "change the status" (which is, perhaps by luck,
 * the same subpart of the entity than the one used in the more-than-just-an-id parameter of serialization).
 * I don't know for now if it's a general truth, or if it's just an happenstance, and if there is a general
 * capability (like "partialUpdate[SomeSubParOfE => E]") to define (in a pure eventstore, we would save that
 * event as if, but well we want to have readable text files for users in our git repos)
 */

/*
 * write node facts.
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
  def getStatus(id: NodeId): IOResult[InventoryStatus]

  /*
   * Get node on given status
   */
  def getOn(nodeId: NodeId, status: InventoryStatus): IOResult[Option[NodeFact]]

  /*
   * Get an accepted node
   */
  def getAccepted(nodeId: NodeId): IOResult[Option[NodeFact]]

  /*
   * Get a pending node
   */
  def getPending(nodeId: NodeId): IOResult[Option[NodeFact]]

  /*
   * Lookup node with that ID in either pending or accepted nodes
   */
  def lookup(nodeId: NodeId): IOResult[Option[NodeFact]]

  /*
   * get all node facts
   */
  def getAllAccepted(): IOStream[NodeFact]

  def getAllPending(): IOStream[NodeFact]

  def getAllOn(status: InventoryStatus): IOStream[NodeFact]
  ///// changes /////

  /*
   * Save (create or override) a node fact.
   * That method will force status to be `accepted`.
   * Use "updateInventory` if you want to save in pending.
   */
  def save(nodeFact: NodeFact)(implicit cc: ChangeContext): IOResult[NodeFactChangeEventCC]

  /*
   * A method that will create in new node fact in pending, or
   * update inventory part of the node with that nodeId in
   * pending or in accepted.
   */
  def updateInventory(inventory: Inventory)(implicit cc: ChangeContext): IOResult[NodeFactChangeEventCC]

  /*
   * Atomically update a node if it exists.
   * If the does not exist, it's an error (you should use save).
   * You must use changeStatus to keep consistency when changing status.
   */
  def update(nodeId: NodeId, mod: NodeFact => NodeFact)(implicit cc: ChangeContext): IOResult[NodeFactChangeEventCC]

  /*
   * Change the status of the node with given id to given status.
   * - if the node is not found, an error is raised appart if target status is "delete"
   * - if the target status is the current one, this function does nothing
   * - if target status is "removed", persisted inventory is deleted
   */
  def changeStatus(nodeId: NodeId, into: InventoryStatus)(implicit cc: ChangeContext): IOResult[NodeFactChangeEventCC]

  /*
   * Delete any reference to that node id.
   */
  def delete(nodeId: NodeId)(implicit cc: ChangeContext): IOResult[NodeFactChangeEventCC]
}

/*
 * Internal CRUD event that need to be translated into node change if needed
 */
sealed private trait InternalChangeEvent

private object InternalChangeEvent {
  final case class Create(node: NodeFact) extends InternalChangeEvent
  final case class Update(node: NodeFact) extends InternalChangeEvent
  final case class Delete(node: NodeFact) extends InternalChangeEvent
  final case class Noop(nodeId: NodeId)   extends InternalChangeEvent
}

/*
 * An In memory implementation of the NodeFactRepository that persist (for cold storage)
 * it's information in given backend.
 * The following operation are persisted and will be blocking:
 * - create a new node fact
 * - update an existing one
 * - change status of a node
 * - delete a node.
 *
 * once initialized, that repository IS the truth. No change done by
 * an other mean in the cold storage will be visible from Rudder.
 *
 * For change, that repos try to ensure that the backend does commit the
 * change before having it done in memory. That arch does not scale to
 * many backend, since once there is more than one, compensation strategy
 * must be put into action to compensate for errors (see zio-workflow for
 * that kind of things).
 *
 */
object CoreNodeFactRepository {
  def make(
      storage:   NodeFactStorage,
      pending:   Map[NodeId, NodeFact],
      accepted:  Map[NodeId, NodeFact],
      callbacks: Chunk[NodeFactChangeEventCallback]
  ) = for {
    p    <- Ref.make(pending)
    a    <- Ref.make(accepted)
    lock <- ReentrantLock.make()
    cbs  <- Ref.make(callbacks)
  } yield {
    new CoreNodeFactRepository(storage, p, a, cbs, lock)
  }
}
class CoreNodeFactRepository(
    storage:       NodeFactStorage,
    pendingNodes:  Ref[Map[NodeId, NodeFact]],
    acceptedNodes: Ref[Map[NodeId, NodeFact]],
    callbacks:     Ref[Chunk[NodeFactChangeEventCallback]],
    lock:          ReentrantLock,
    cbTimeout:     zio.Duration = 5.seconds
) extends NodeFactRepository {
  import NodeFactChangeEvent._

  (for {
    p <- pendingNodes.get.map(_.values.map(_.id.value).mkString(", "))
    a <- acceptedNodes.get.map(_.values.map(_.id.value).mkString(", "))
    _ <- InventoryDataLogger.debug(s"Loaded node fact repos with: \n - pending: ${p} \n - accepted: ${a}")
  } yield ()).runNow

  override def registerChangeCallbackAction(callback: NodeFactChangeEventCallback): IOResult[Unit] = {
    callbacks.update(_.appended(callback))
  }

  override def getStatus(id: NodeId): IOResult[InventoryStatus] = {
    pendingNodes.get.flatMap { p =>
      if (p.keySet.contains(id)) PendingInventory.succeed
      else {
        acceptedNodes.get.flatMap(a => {
          if (a.keySet.contains(id)) AcceptedInventory.succeed
          else RemovedInventory.succeed
        })
      }
    }
  }

  override def getOn(nodeId: NodeId, status: InventoryStatus): IOResult[Option[NodeFact]] = {
    status match {
      case AcceptedInventory => getAccepted(nodeId)
      case PendingInventory  => getPending(nodeId)
      case RemovedInventory  => None.succeed
    }
  }

  /*
   * This method will need some thoughts:
   * - do we want to fork and timeout each callbacks ? likely so
   * - do we want to parallel exec them ? likely so, the user can build his own callback sequencer callback if he wants
   */
  private[nodes] def runCallbacks(e: NodeFactChangeEventCC): IOResult[Unit] = {
    for {
      cs <- callbacks.get
      _  <- ZIO.foreachPar(cs)(c => c.run(e)).timeout(cbTimeout) // .forkDaemon
    } yield ()
  }

  private[nodes] def getOnRef(ref: Ref[Map[NodeId, NodeFact]], nodeId: NodeId) = {
    ref.get.map(_.get(nodeId))
  }

  private[nodes] def getAllOnRef(ref: Ref[Map[NodeId, NodeFact]]): IOStream[NodeFact] = {
    ZStream.fromZIO(ref.get.map(m => m.valuesIterator)).flatMap(x => ZStream.fromIterator(x).mapError(ex => SystemError("Iterator error", ex)))
  }

  /*
   *
   */
  private def saveOn(ref: Ref[Map[NodeId, NodeFact]], nodeFact: NodeFact): IOResult[InternalChangeEvent] = {
    ref
      .getAndUpdate(_ + ((nodeFact.id, nodeFact)))
      .map { old =>
        old.get(nodeFact.id) match {
          case Some(n) =>
            if (NodeFact.same(n, nodeFact)) InternalChangeEvent.Noop(nodeFact.id)
            else InternalChangeEvent.Update(nodeFact)
          case None    => InternalChangeEvent.Create(nodeFact)
        }
      }
  }

  private def deleteOn(ref: Ref[Map[NodeId, NodeFact]], nodeId: NodeId): IOResult[InternalChangeEvent] = {
    ref
      .getAndUpdate(_.removed(nodeId))
      .map(old => {
        old.get(nodeId) match {
          case None    => InternalChangeEvent.Noop(nodeId)
          case Some(n) => InternalChangeEvent.Delete(n)
        }
      })
  }

  override def getAccepted(nodeId: NodeId): IOResult[Option[NodeFact]] = {
    getOnRef(acceptedNodes, nodeId)
  }

  override def getPending(nodeId: NodeId): IOResult[Option[NodeFact]] = {
    getOnRef(pendingNodes, nodeId)
  }

  override def lookup(nodeId: NodeId): IOResult[Option[NodeFact]] = {
    getAccepted(nodeId).flatMap(opt => opt.fold(getPending(nodeId))(Some(_).succeed))
  }

  override def getAllOn(status: InventoryStatus): IOStream[NodeFact] = {
    status match {
      case AcceptedInventory => getAllAccepted()
      case PendingInventory  => getAllPending()
      case RemovedInventory  => ZStream.empty
    }
  }

  override def getAllAccepted(): IOStream[NodeFact] = getAllOnRef(acceptedNodes)

  override def getAllPending(): IOStream[NodeFact] = getAllOnRef(pendingNodes)

  override def save(node: NodeFact)(implicit cc: ChangeContext): IOResult[NodeFactChangeEventCC] = {
    val nodeFact = NodeFact.sortAttributes(node)
    ZIO.scoped(
      for {
        _ <- lock.withLock
        _ <- storage.save(nodeFact)
        e <- nodeFact.rudderSettings.status match {
               case RemovedInventory  => // this case is ignored, we don't delete node based on status value
                 NodeFactChangeEventCC(Noop(nodeFact.id), cc).succeed
               case PendingInventory  =>
                 saveOn(pendingNodes, nodeFact).map { e =>
                   e match {
                     case InternalChangeEvent.Create(node) => NodeFactChangeEventCC(NewPending(node), cc)
                     case InternalChangeEvent.Update(node) => NodeFactChangeEventCC(UpdatedPending(node), cc)
                     case InternalChangeEvent.Delete(node) => NodeFactChangeEventCC(Deleted(node), cc)
                     case InternalChangeEvent.Noop(nodeId) => NodeFactChangeEventCC(Noop(nodeId), cc)
                   }
                 }
               case AcceptedInventory =>
                 saveOn(acceptedNodes, nodeFact).map { e =>
                   e match {
                     case InternalChangeEvent.Create(node) => NodeFactChangeEventCC(Accepted(node), cc)
                     case InternalChangeEvent.Update(node) => NodeFactChangeEventCC(Updated(node), cc)
                     case InternalChangeEvent.Delete(node) => NodeFactChangeEventCC(Deleted(node), cc)
                     case InternalChangeEvent.Noop(nodeId) => NodeFactChangeEventCC(Noop(nodeId), cc)
                   }
                 }
             }
        _ <- runCallbacks(e)
      } yield e
    )
  }

  override def update(nodeId: NodeId, mod: NodeFact => NodeFact)(implicit cc: ChangeContext): IOResult[NodeFactChangeEventCC] = {
    ZIO.scoped(for {
      _ <- lock.withLock
      x <- ZIO
             .collectFirst(List(getAccepted(nodeId), getPending(nodeId)))(identity)
             .flatMap(_ match {
               case Some(n) => n.succeed
               case None    => Inconsistency(s"Node with ID '${nodeId.value}' was not found").fail
             })
      up = mod(x)
      e <- save(up)
    } yield e)
  }

  override def changeStatus(nodeId: NodeId, into: InventoryStatus)(implicit
      cc:                           ChangeContext
  ): IOResult[NodeFactChangeEventCC] = {
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
                            deleteOn(pendingNodes, nodeId) *> NodeFactChangeEventCC(Refused(x), cc).succeed
                          case (RemovedInventory, None, Some(x))     =>
                            deleteOn(acceptedNodes, nodeId) *> NodeFactChangeEventCC(Deleted(x), cc).succeed
                          case (RemovedInventory, Some(_), Some(x))  =>
                            deleteOn(pendingNodes, nodeId) *>
                            deleteOn(acceptedNodes, nodeId) *>
                            NodeFactChangeEventCC(Deleted(x), cc).succeed
                          case (RemovedInventory, None, None)        =>
                            NodeFactChangeEventCC(Noop(nodeId), cc).succeed
                          case (_, None, None)                       =>
                            Inconsistency(
                              s"Error: node '${nodeId.value}' was not found in rudder (neither pending nor accepted nodes"
                            ).fail
                          case (AcceptedInventory, None, Some(_))    =>
                            NodeFactChangeEventCC(Noop(nodeId), cc).succeed
                          case (AcceptedInventory, Some(x), None)    =>
                            deleteOn(pendingNodes, nodeId) *> saveOn(
                              acceptedNodes,
                              x.modify(_.rudderSettings.status).setTo(AcceptedInventory)
                            ) *> NodeFactChangeEventCC(Accepted(x), cc).succeed
                          case (AcceptedInventory, Some(_), Some(_)) =>
                            deleteOn(pendingNodes, nodeId) *> NodeFactChangeEventCC(Noop(nodeId), cc).succeed
                          case (PendingInventory, None, Some(x))     =>
                            deleteOn(acceptedNodes, nodeId) *> saveOn(
                              pendingNodes,
                              x.modify(_.rudderSettings.status).setTo(PendingInventory)
                            ) *> NodeFactChangeEventCC(Deleted(x), cc).succeed // not sure about the semantic here
                          case (PendingInventory, Some(_), None)     =>
                            NodeFactChangeEventCC(Noop(nodeId), cc).succeed
                          case (PendingInventory, Some(_), Some(x))  =>
                            deleteOn(acceptedNodes, nodeId) *> NodeFactChangeEventCC(Deleted(x), cc).succeed
                        }
          } yield e
        _ <- runCallbacks(e)
      } yield e
    )
  }

  override def delete(nodeId: NodeId)(implicit cc: ChangeContext): IOResult[NodeFactChangeEventCC] = {
    ZIO.scoped(
      for {
        _ <- lock.withLock
        _ <- storage.delete(nodeId)
        p <- deleteOn(pendingNodes, nodeId)
        a <- deleteOn(acceptedNodes, nodeId)
        e  = ((p, a) match {
               case (_, InternalChangeEvent.Delete(n)) => NodeFactChangeEventCC(Deleted(n), cc)
               case (InternalChangeEvent.Delete(n), _) => NodeFactChangeEventCC(Refused(n), cc)
               case _                                  => NodeFactChangeEventCC(Noop(nodeId), cc)
             })
        _ <- runCallbacks(e)
      } yield e
    )
  }

  override def updateInventory(inventory: Inventory)(implicit cc: ChangeContext): IOResult[NodeFactChangeEventCC] = {
    val nodeId = inventory.node.main.id
    ZIO.scoped(
      for {
        _          <- lock.withLock
        optPending <- getOnRef(pendingNodes, nodeId)
        optFact    <- optPending match {
                        case Some(f) => Some(f).succeed
                        case None    => getOnRef(acceptedNodes, nodeId)
                      }
        fact        = optFact match {
                        case Some(f) => NodeFact.updateInventory(f, inventory)
                        case None    => NodeFact.newFromInventory(inventory)
                      }
        e          <- save(fact) // save already runs callbacks
      } yield e
    )
  }
}

/*
 * Serialize a fact type (to/from JSON), for example nodes.
 * The format is versioned so that we are able to unserialize old files into newer domain representation.
 *
 * We store a fileFormat and the serialized object type.
 * To let more space for evolution, file format will be a string even if it should be parsed as an int.
 *
 * There's two parameter, one minimal (A) that allows to identify where the fact should be store (typically, it's a
 * kind of ID), and (B) which the whole fact to serialize. There should exists a constraint of derivability from B to A,
 * but it's not modeled.
 */
trait SerializeFacts[A, B] {

  def fileFormat: String
  def entity:     String

  def toJson(data: B): IOResult[String]

  // this is just a relative path from a virtual root, for example for node it will be: "accepted/node-uuid.json"
  def getEntityPath(id: A): String

}

trait NodeFactStorage {

  /*
   * Save node fact in the status given in the corresponding attribute.
   * No check will be done.
   */
  def save(nodeFact: NodeFact): IOResult[Unit]

  /*
   * Change the status of the node with given id to given status.
   * - if the node is not found, an error is raised apart if target status is "delete"
   * - if the target status is the current one, this function does nothing
   * - if target status is "removed", persisted inventory is deleted
   */
  def changeStatus(nodeId: NodeId, status: InventoryStatus): IOResult[Unit]

  /*
   * Delete the node. Storage need to loop for any status and delete
   * any reference to that node.
   */
  def delete(nodeId: NodeId): IOResult[Unit]

  def getAllPending():  IOStream[NodeFact]
  def getAllAccepted(): IOStream[NodeFact]
}

/*
 * Implementaton that store nothing and that can be used in tests or when a pure
 * in-memory version of the nodeFactRepos is needed.
 */
object NoopFactStorage extends NodeFactStorage {
  override def save(nodeFact: NodeFact):                              IOResult[Unit]     = ZIO.unit
  override def changeStatus(nodeId: NodeId, status: InventoryStatus): IOResult[Unit]     = ZIO.unit
  override def delete(nodeId: NodeId):                                IOResult[Unit]     = ZIO.unit
  override def getAllPending():                                       IOStream[NodeFact] = ZStream.empty
  override def getAllAccepted():                                      IOStream[NodeFact] = ZStream.empty
}

/*
 * We have only one git for all fact repositories. This is the one managing semaphore, init, etc.
 * All fact repositories will be a subfolder on it:
 * - /var/rudder/fact-repository/nodes
 * - /var/rudder/fact-repository/rudder-config
 * - /var/rudder/fact-repository/groups
 * etc
 */

object GitNodeFactRepositoryImpl {

  final case class NodeFactArchive(
      entity:     String,
      fileFormat: String,
      node:       NodeFact
  )

  implicit val codecNodeFactArchive: JsonCodec[NodeFactArchive] = DeriveJsonCodec.gen
}

/*
 * Nodes are stored in the git facts repo under the relative path "nodes".
 * They are then stored:
 * - under nodes/pending or nodes/accepted given their status (which means that changing status of a node is
 *   a special operation)
 * -
 *
 */
class GitNodeFactRepositoryImpl(
    override val gitRepo: GitRepositoryProvider,
    groupOwner:           String
) extends NodeFactStorage with GitItemRepository with SerializeFacts[(NodeId, InventoryStatus), NodeFact] {

  override val relativePath = "nodes"
  override val entity:     String = "node"
  override val fileFormat: String = "10"
  val committer = new PersonIdent("rudder-fact", "email not set")

  // name of sub-directories for common nodes, ie the one with a standard UUID.
  val shardingDirNames = ('0' to '9') ++ ('a' to 'f')

  override def getEntityPath(id: (NodeId, InventoryStatus)): String = {
    // head can fail if value is empty, which is forbidden and should have fail before that. Else, an error
    // is what we want, to know that we are in a bad state and things are broken.
    val mid = id._1.value.head.toLower
    if (shardingDirNames.contains(mid)) {
      println(s"**** save node ${id._1.value} in subdir ${mid}")
      s"${id._2.name}/${mid}/${id._1.value}.json"
    } else {
      println(s"**** save node ${id._1.value} in root because ${mid} is not in ${shardingDirNames}")
      s"${id._2.name}/${id._1.value}.json"
    }
  }

  def getFile(id: NodeId, status: InventoryStatus): File = {
    gitRepo.rootDirectory / relativePath / getEntityPath((id, status))
  }

  /*
   * serialize the inventory into a normalized JSON string.
   * As we want it to be human readable and searchable, we will use an indented format.
   */
  def toJson(nodeFact: NodeFact): IOResult[String] = {
    import GitNodeFactRepositoryImpl._
    NodeFactArchive(entity, fileFormat, nodeFact).toJsonPretty.succeed
  }

  private[nodes] def getAll(base: File): IOStream[NodeFact] = {
    // TODO should be from git head, not from file directory
    val stream = ZStream.fromIterator(base.collectChildren(_.extension(includeDot = true, includeAll = true) == Some(".json")))
    stream
      .mapError(ex => SystemError("Error when reading node fact persisted file", ex))
      .mapZIO(f => f.contentAsString(StandardCharsets.UTF_8).fromJson[NodeFact].toIO.chainError(s"Error when decoding ${f.pathAsString}"))
  }

  override def getAllPending():  IOStream[NodeFact] = getAll(gitRepo.rootDirectory / relativePath / PendingInventory.name)
  override def getAllAccepted(): IOStream[NodeFact] = getAll(gitRepo.rootDirectory / relativePath / AcceptedInventory.name)

  override def save(nodeFact: NodeFact): IOResult[Unit] = {
    if (nodeFact.rudderSettings.status == RemovedInventory) {
      InventoryDataLogger.info(s"Not persisting deleted node '${nodeFact.fqdn}' [${nodeFact.id.value}]: it has removed inventory status") *>
      ZIO.unit
    } else {
      for {
        json   <- toJson(nodeFact)
        file    = getFile(nodeFact.id, nodeFact.rudderSettings.status)
        _      <- IOResult.attempt(file.write(json))
        _      <- IOResult.attempt(file.setGroup(groupOwner))
        gitPath = toGitPath(file.toJava)
        saved  <-
          commitAddFile(
            committer,
            gitPath,
            s"Save inventory facts for ${nodeFact.rudderSettings.status.name} node '${nodeFact.fqdn}' (${nodeFact.id.value})"
          )
      } yield ()
    }
  }

  // when we delete, we check for all path to also remove possible left-over
  // we may need to recreate pending/accepted directory, because git delete
  // empty directories.
  override def delete(nodeId: NodeId): IOResult[Unit] = {
    ZIO.foreach(List(PendingInventory, AcceptedInventory)) { s =>
      val file = getFile(nodeId, s)
      ZIO.whenZIO(IOResult.attempt(file.exists)) {
        commitRmFile(committer, toGitPath(file.toJava), s"Updating facts for node '${nodeId.value}': deleted")
      }
    } *> checkInit()
  }

  override def changeStatus(nodeId: NodeId, toStatus: InventoryStatus): IOResult[Unit] = {
    // pending and accepted are symmetric, utility function for the two cases
    def move(to: InventoryStatus) = {
      val from = if (to == AcceptedInventory) PendingInventory else AcceptedInventory

      val fromFile = getFile(nodeId, from)
      val toFile   = getFile(nodeId, to)
      // check if fact already where it should
      ZIO.ifZIO(IOResult.attempt(fromFile.exists))(
        // however toFile exists, move, because if present it may be because a deletion didn't work and
        // we need to overwrite
        IOResult.attempt(fromFile.moveTo(toFile)(File.CopyOptions(overwrite = true))) *>
        commitMvDirectory(
          committer,
          toGitPath(fromFile.toJava),
          toGitPath(toFile.toJava),
          s"Updating facts for node '${nodeId.value}' to status: ${to.name}"
        ), // if source file does not exist, check if dest is present. If present, assume it's ok, else error

        ZIO.whenZIO(IOResult.attempt(!toFile.exists)) {
          Inconsistency(
            s"Error when trying to move fact for node '${nodeId.value}' from '${fromFile.pathAsString}' to '${toFile.pathAsString}': missing files"
          ).fail
        }
      )
    }

    (toStatus match {
      case RemovedInventory => delete(nodeId)
      case x                => move(x)
    }).unit
  }

  /*
   * check that everything is ok for that repo entities (typically: subfolder created, perm ok, etc)
   */
  def checkInit(): IOResult[Unit] = {
    val dirs = List(AcceptedInventory.name, PendingInventory.name)
    dirs.accumulate { dir =>
      val d = gitRepo.rootDirectory / relativePath / dir
      for {
        _ <- ZIO
               .whenZIO(IOResult.attempt(d.notExists)) {
                 IOResult.attempt {
                   d.createDirectories()
                   d.setGroup(groupOwner)
                 }
               }
               .chainError(s"Error when creating directory '${d.pathAsString}' for historising inventories: ${}")
        _ <- ZIO.whenZIO(IOResult.attempt(!d.isOwnerWritable)) {
               Inconsistency(
                 s"Error, directory '${d.pathAsString}' must be a writable directory to allow inventory historisation"
               ).fail
             }
      } yield ()
    }.unit
  }
}

// TODO Access node facts: history, for a date, etc
