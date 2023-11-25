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
import com.normation.inventory.ldap.core.FullInventoryRepositoryImpl
import com.normation.inventory.ldap.core.InventoryDitService
import com.normation.inventory.ldap.core.InventoryMapper
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.inventory.services.core.ReadOnlySoftwareDAO
import com.normation.inventory.services.provisioning.SoftwareDNFinderAction
import com.normation.ldap.sdk.BuildFilter._
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.LDAPEntry
import com.normation.ldap.sdk.One
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.rudder.domain.NodeDit
import com.normation.rudder.domain.logger.NodeLogger
import com.normation.rudder.domain.logger.NodeLoggerPure
import com.normation.rudder.domain.nodes.MachineInfo
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.facts.nodes.LdapNodeFactStorage.needsSoftware
import com.normation.rudder.git.GitItemRepository
import com.normation.rudder.git.GitRepositoryProvider
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.rudder.repository.ldap.ScalaReadWriteLock
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.utils.StringUuidGenerator
import com.normation.zio._
import com.softwaremill.quicklens._
import com.unboundid.ldap.sdk.DN
import java.nio.charset.StandardCharsets
import org.eclipse.jgit.lib.PersonIdent
import org.joda.time.DateTime
import scala.annotation.nowarn
import zio._
import zio.json._
import zio.stream.ZStream
import zio.syntax._

/*
 * This file contains the base to persist facts into a git repository. There is a lot of question
 * remaining, so don't take current traits/classes as an API, it *WILL* change. The basic questions to answer are:
 * - do we want one bit "FactRepo" that knows about all kind of facts and is able to persis any of them ? In that case,
 *   we will need some kind of parametrization of `persist` with a type class to teach that repo how to serialize and
 *   persist each case
 * - do we prefer lots of small repos, one by entity, which knows how to persist only that entity ?
 * - plus, we want to have some latitude on the serialization part, and be able to use both liftjson and zio-json
 *   (because the complete migration toward zio-json won't be finish immediately)
 *
 * The "one big" repo feels more like it is what we need, since it's really just one big git repo with sub-cases,
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
 * - no abstraction for repo, we just have a "node repo" with all the concrete types. It's likely to become a:
 *   ```
 *     trait FactRepo { def persist[E](e: E)(implicit Serialize[E]): IOResult[Unit])
 *   ```
 *   Or something alike, but we just don't know.
 *
 * - some abstraction for serialisation, but just to put in plain sight the fact that there a characteristic of
 *   the entity that is not the whole entity, and more then its ID, that is needed to build where the entity
 *   will be saved.
 *
 * - a simple implementation for nodes, that will need to be refactored depending of the chosen final arch.
 *
 * And finally, to complexity a bit more the picture, we see that there is events (observations?) linked to facts
 * that can update the previous fact partially. For nodes, it's "change the status" (which is, perhaps by luck,
 * the same subpart of the entity than the one used in the more-than-just-an-id parameter of serialization).
 * I don't know for now if it's a general truth, or if it's just an happenstance, and if there is a general
 * capability (like "partialUpdate[SomeSubParOfE => E]") to define (in a pure events-tore, we would save that
 * event as if, but well we want to have readable text files for users in our git repos)
 */

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

/*
 * Storage have a very simple change event output datastructure. This is because they don't know
 * about business logic, just about the action done to the serialized data.
 *
 * SelectFacts informs about what the storage know about the change.
 * For ex, if SelectFacts.software is "Ignore" and the change event is Noop, it only
 * says that things other than software changed and nothing about software (it may or may
 * not have changed).
 * This is also a way to limit the quantity of data returned in the change event: NodeFact
 * will (must be) masked. Finally, storage must not change the attrs requirement given in
 * parameter and must do whatever is needed to honor the requirement.
 *
 * When no requirement is done in the parameters for SelectFacts, storage can return
 * what it wants, always trying to maximize perf / minimize data transfered in that case .
 */
sealed trait StorageChangeEvent
// save
sealed trait StorageChangeEventSave extends StorageChangeEvent
object StorageChangeEventSave {
  final case class Created(node: NodeFact, attrs: SelectFacts)                       extends StorageChangeEventSave
  final case class Updated(oldNode: NodeFact, newNode: NodeFact, attrs: SelectFacts) extends StorageChangeEventSave
  final case class Noop(nodeId: NodeId, attrs: SelectFacts)                          extends StorageChangeEventSave

  implicit class StorageChangeEventExtensions(e: StorageChangeEventSave) {
    def toChangeEvent(
        nodeId: NodeId,
        s:      InventoryStatus,
        cc:     ChangeContext
    ): NodeFactChangeEventCC = {
      import NodeFactChangeEvent.{Noop => NoopCE}
      import NodeFactChangeEvent.{Updated => UpdatedCE, _}
      s match {
        case RemovedInventory  => // this case is ignored, we don't delete node based on status value
          NodeFactChangeEventCC(NoopCE(nodeId, SelectFacts.all), cc)
        case PendingInventory  =>
          e match {
            case StorageChangeEventSave.Created(node, attrs)      => NodeFactChangeEventCC(NewPending(node, attrs), cc)
            case StorageChangeEventSave.Updated(old, next, attrs) => NodeFactChangeEventCC(UpdatedPending(old, next, attrs), cc)
            case StorageChangeEventSave.Noop(nodeId, attrs)       => NodeFactChangeEventCC(NoopCE(nodeId, attrs), cc)
          }
        case AcceptedInventory =>
          e match {
            case StorageChangeEventSave.Created(node, attrs)      => NodeFactChangeEventCC(Accepted(node, attrs), cc)
            case StorageChangeEventSave.Updated(old, next, attrs) => NodeFactChangeEventCC(UpdatedCE(old, next, attrs), cc)
            case StorageChangeEventSave.Noop(nodeId, attrs)       => NodeFactChangeEventCC(NoopCE(nodeId, attrs), cc)
          }
      }
    }

    def updateWith(b: StorageChangeEventSave): StorageChangeEventSave = {
      def update(nfa: NodeFact, nfb: NodeFact, attrs: SelectFacts) = SelectFacts.merge(nfa, Some(nfb))(attrs.invert)

      (e, b) match {
        case (Noop(_, _), x)                                  => x
        case (x, Noop(_, _))                                  => x
        case (Created(a1, _), Created(a2, attrs))             => Created(update(a1, a2, attrs), attrs)
        case (Created(a1, _), Updated(ob, nb, attrs))         => Updated(ob, update(a1, nb, attrs), attrs)
        case (Updated(oa, na, _), Created(b1, attrs))         => Updated(oa, update(na, b1, attrs), attrs)
        case (Updated(ob1, nb1, _), Updated(ob2, nb2, attrs)) =>
          Updated(
            update(ob1, ob2, attrs),
            update(nb1, nb1, attrs),
            attrs
          )
      }
    }
  }
}

sealed trait StorageChangeEventStatus extends StorageChangeEvent
object StorageChangeEventStatus {
  final case class Done(nodeId: NodeId) extends StorageChangeEventStatus
  final case class Noop(nodeId: NodeId) extends StorageChangeEventStatus
}

sealed trait StorageChangeEventDelete extends StorageChangeEvent
object StorageChangeEventDelete {
  final case class Deleted(node: NodeFact, attrs: SelectFacts) extends StorageChangeEventDelete
  // this one is to signal something was deleted, but we weren't able to retrieve node info
  final case class DeletedNoInfo(nodeId: NodeId)               extends StorageChangeEventDelete
  final case class Noop(nodeId: NodeId)                        extends StorageChangeEventDelete

  implicit class StorageChangeEventExtensions(e: StorageChangeEventDelete) {
    def toChangeEvent(
        cnf: CoreNodeFact,
        s:   InventoryStatus,
        cc:  ChangeContext
    ): NodeFactChangeEventCC = {
      import NodeFactChangeEvent.{Noop => NoopCE, Deleted => DeletedCE, _}

      def patternMatch(
          event:   StorageChangeEventDelete,
          toEvent: (NodeFact, SelectFacts) => NodeFactChangeEvent
      ): NodeFactChangeEventCC = {
        event match {
          case Deleted(node, attrs) => NodeFactChangeEventCC(toEvent(node, attrs), cc)
          case DeletedNoInfo(_)     => NodeFactChangeEventCC(toEvent(NodeFact.fromMinimal(cnf), SelectFacts.none), cc)
          case Noop(_)              => NodeFactChangeEventCC(NoopCE(cnf.id, SelectFacts.all), cc)
        }
      }

      s match {
        case RemovedInventory  => NodeFactChangeEventCC(NoopCE(cnf.id, SelectFacts.all), cc)
        case PendingInventory  => patternMatch(e, Refused(_, _))
        case AcceptedInventory => patternMatch(e, DeletedCE(_, _))
      }
    }

    def updateWith(b: StorageChangeEventDelete): StorageChangeEventDelete = {
      def update(nfa: NodeFact, nfb: NodeFact, attrs: SelectFacts) = SelectFacts.merge(nfa, Some(nfb))(attrs.invert)

      (e, b) match {
        case (Noop(_), x)                         => x
        case (x, Noop(_))                         => x
        case (DeletedNoInfo(_), x)                => x
        case (x, DeletedNoInfo(_))                => x
        case (Deleted(a1, _), Deleted(a2, attrs)) => Deleted(update(a1, a2, attrs), attrs)
      }
    }
  }
}

trait NodeFactStorage {

  /*
   * Save node fact in the status given in the corresponding attribute.
   * No check will be done.
   */
  def save(nodeFact: NodeFact)(implicit attrs: SelectFacts = SelectFacts.all): IOResult[StorageChangeEventSave]

  /*
   * Change the status of the node with given id to given status.
   * - if the node is not found, an error is raised apart if target status is "delete"
   * - if the target status is the current one, this function does nothing
   * - if target status is "removed", persisted inventory is deleted
   */
  def changeStatus(nodeId: NodeId, status: InventoryStatus): IOResult[StorageChangeEventStatus]

  /*
   * Delete the node. Storage need to loop for any status and delete
   * any reference to that node.
   */
  def delete(nodeId: NodeId)(implicit attrs: SelectFacts): IOResult[StorageChangeEventDelete]

  def getPending(nodeId:  NodeId)(implicit attrs: SelectFacts = SelectFacts.default): IOResult[Option[NodeFact]]
  def getAccepted(nodeId: NodeId)(implicit attrs: SelectFacts = SelectFacts.default): IOResult[Option[NodeFact]]

  def getAllPending()(implicit attrs:  SelectFacts = SelectFacts.default): IOStream[NodeFact]
  def getAllAccepted()(implicit attrs: SelectFacts = SelectFacts.default): IOStream[NodeFact]
}

/*
 * Implementation that store nothing and that can be used in tests or when a pure
 * in-memory version of the nodeFactRepos is needed.
 */
object NoopFactStorage extends NodeFactStorage {
  override def save(nodeFact: NodeFact)(implicit attrs: SelectFacts = SelectFacts.default): IOResult[StorageChangeEventSave]   =
    StorageChangeEventSave.Noop(nodeFact.id, attrs).succeed
  override def changeStatus(nodeId: NodeId, status: InventoryStatus):                       IOResult[StorageChangeEventStatus] =
    StorageChangeEventStatus.Noop(nodeId).succeed
  override def delete(nodeId: NodeId)(implicit attrs: SelectFacts):                         IOResult[StorageChangeEventDelete] =
    StorageChangeEventDelete.Noop(nodeId).succeed
  @nowarn("msg=parameter attrs in method getAllPending is never used")
  override def getAllPending()(implicit attrs: SelectFacts = SelectFacts.default):          IOStream[NodeFact]                 = ZStream.empty
  @nowarn("msg=parameter attrs in method getAllAccepted is never used")
  override def getAllAccepted()(implicit attrs: SelectFacts = SelectFacts.default):         IOStream[NodeFact]                 = ZStream.empty
  override def getPending(nodeId: NodeId)(implicit attrs: SelectFacts):                     IOResult[Option[NodeFact]]         = None.succeed
  override def getAccepted(nodeId: NodeId)(implicit attrs: SelectFacts):                    IOResult[Option[NodeFact]]         = None.succeed
}

/*
 * We have only one git for all fact repositories. This is the one managing semaphore, init, etc.
 * All fact repositories will be a subfolder on it:
 * - /var/rudder/fact-repository/nodes
 * - /var/rudder/fact-repository/rudder-config
 * - /var/rudder/fact-repository/groups
 * etc
 */

object GitNodeFactStorageImpl {

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
 */
class GitNodeFactStorageImpl(
    override val gitRepo: GitRepositoryProvider,
    groupOwner:           Option[String],
    actuallyCommit:       Boolean
) extends NodeFactStorage with GitItemRepository with SerializeFacts[(NodeId, InventoryStatus), NodeFact] {

  override val relativePath = "nodes"
  override val entity:     String = "node"
  override val fileFormat: String = "10"
  val committer = new PersonIdent("rudder-fact", "email not set")

  if (actuallyCommit) {
    NodeLogger.info(s"Nodes changes will be historized in Git in ${gitRepo.rootDirectory.pathAsString}/nodes")
  } else {
    NodeLogger.info(
      s"Nodes changes won't be historized in Git, only last state is stored in ${gitRepo.rootDirectory.pathAsString}/nodes"
    )
  }

  if (actuallyCommit) {
    NodeLogger.info(s"Nodes changes will be historized in Git in ${gitRepo.rootDirectory.pathAsString}/nodes")
  } else {
    NodeLogger.info(
      s"Nodes changes won't be historized in Git, only last state is stored in ${gitRepo.rootDirectory.pathAsString}/nodes"
    )
  }

  override def getEntityPath(id: (NodeId, InventoryStatus)): String = {
    s"${id._2.name}/${id._1.value}.json"
  }

  def getFile(id: NodeId, status: InventoryStatus): File = {
    gitRepo.rootDirectory / relativePath / getEntityPath((id, status))
  }

  /*
   * serialize the inventory into a normalized JSON string.
   * As we want it to be human readable and searchable, we will use an indented format.
   */
  def toJson(nodeFact: NodeFact): IOResult[String] = {
    import GitNodeFactStorageImpl._
    val node = nodeFact
      .modify(_.accounts)
      .using(_.sorted)
      .modify(_.properties)
      .using(_.sortBy(_.name))
      .modify(_.environmentVariables)
      .using(_.sortBy(_._1))
      .modify(_.fileSystems)
      .using(_.sortBy(_.name))
      .modify(_.networks)
      .using(_.sortBy(_.name))
      .modify(_.processes)
      .using(_.sortBy(_.commandName))
      .modify(_.bios)
      .using(_.sortBy(_.name))
      .modify(_.controllers)
      .using(_.sortBy(_.name))
      .modify(_.memories)
      .using(_.sortBy(_.name))
      .modify(_.ports)
      .using(_.sortBy(_.name))
      .modify(_.processors)
      .using(_.sortBy(_.name))
      .modify(_.slots)
      .using(_.sortBy(_.name))
      .modify(_.sounds)
      .using(_.sortBy(_.name))
      .modify(_.storages)
      .using(_.sortBy(_.name))
      .modify(_.videos)
      .using(_.sortBy(_.name))

    NodeFactArchive(entity, fileFormat, node).toJsonPretty.succeed
  }

  // we don't want to write the codec to "not unserialize" given SelectFacts right now, so we are
  // just masking
  private[nodes] def fileToNode(f: File)(implicit attrs: SelectFacts): IOResult[NodeFact] = {
    for {
      c <- IOResult.attempt(s"Error reading file: ${f.pathAsString}")(f.contentAsString(StandardCharsets.UTF_8))
      j <- c.fromJson[NodeFact].toIO.chainError(s"Error when decoding ${f.pathAsString}")
    } yield {
      j.maskWith(attrs)
    }
  }

  private[nodes] def get(nodeId: NodeId, status: InventoryStatus)(implicit attrs: SelectFacts): IOResult[Option[NodeFact]] = {
    val f = getFile(nodeId, status)
    if (f.exists) fileToNode(f).map(Some(_))
    else None.succeed
  }

  private[nodes] def getAll(base: File)(implicit attrs: SelectFacts): IOStream[NodeFact] = {
    // TODO should be from git head, not from file directory
    val stream = ZStream.fromIterator(base.collectChildren(_.extension(includeDot = true, includeAll = true) == Some(".json")))
    stream
      .mapError(ex => SystemError("Error when reading node fact persisted file", ex))
      .mapZIO(f => fileToNode(f))
  }

  override def getPending(nodeId: NodeId)(implicit attrs: SelectFacts): IOResult[Option[NodeFact]] = {
    get(nodeId, PendingInventory)
  }

  override def getAccepted(nodeId: NodeId)(implicit attrs: SelectFacts): IOResult[Option[NodeFact]] = {
    get(nodeId, AcceptedInventory)
  }

  override def getAllPending()(implicit attrs: SelectFacts):  IOStream[NodeFact] = getAll(
    gitRepo.rootDirectory / relativePath / PendingInventory.name
  )
  override def getAllAccepted()(implicit attrs: SelectFacts): IOStream[NodeFact] = getAll(
    gitRepo.rootDirectory / relativePath / AcceptedInventory.name
  )

  // We saving, we must ignore attrs that are "ignored" - ie in that case, if the source list is empty, we take the existing one
  // Save does not know about status change. If it's called with a status change, this leads to duplicated data.
  override def save(nodeFact: NodeFact)(implicit attrs: SelectFacts): IOResult[StorageChangeEventSave] = {
    if (nodeFact.rudderSettings.status == RemovedInventory) {
      InventoryDataLogger.info(
        s"Not persisting deleted node '${nodeFact.fqdn}' [${nodeFact.id.value}]: it has removed inventory status"
      ) *> StorageChangeEventSave.Noop(nodeFact.id, attrs).succeed
    } else {
      val file = getFile(nodeFact.id, nodeFact.rudderSettings.status)
      for {
        old   <- fileToNode(file).map(Some(_)).catchAll(_ => None.succeed)
        merged = SelectFacts.merge(nodeFact, old)
        json  <- toJson(merged)
        _     <- IOResult.attempt(file.write(json))
        _     <- groupOwner match {
                   case None     => ZIO.unit
                   case Some(go) => IOResult.attempt(file.setGroup(go))
                 }
        _     <- ZIO.when(actuallyCommit) {
                   commitAddFile(
                     committer,
                     toGitPath(file.toJava),
                     s"Save inventory facts for ${merged.rudderSettings.status.name} node '${merged.fqdn}' (${merged.id.value})"
                   )
                 }
      } yield {
        old match {
          case Some(o) =>
            StorageChangeEventSave.Updated(o, nodeFact, attrs)
          case None    =>
            StorageChangeEventSave.Created(nodeFact, attrs)
        }
      }
    }
  }

  // when we delete, we check for all path to also remove possible left-over
  // we may need to recreate pending/accepted directory, because git delete
  // empty directories.
  override def delete(nodeId: NodeId)(implicit attrs: SelectFacts): IOResult[StorageChangeEventDelete] = {
    def exists(nodeId: NodeId, status: InventoryStatus): IOResult[Option[File]] = {
      val file = getFile(nodeId, status)
      IOResult.attempt(file.exists).map {
        case true  => Some(file)
        case false => None
      }
    }
    def delete(file: File) = {
      (if (actuallyCommit) {
         commitRmFile(committer, toGitPath(file.toJava), s"Updating facts for node '${nodeId.value}': deleted")
       } else {
         IOResult.attempt(file.delete())
       }).flatMap(_ => fileToNode(file)(attrs).map(Some(_)).catchAll(_ => None.succeed)).map {
        case Some(n) => StorageChangeEventDelete.Deleted(n, attrs)
        case None    => StorageChangeEventDelete.Noop(nodeId)
      }
    }

    (for {
      p <- exists(nodeId, PendingInventory)
      a <- exists(nodeId, AcceptedInventory)
      c <- (p, a) match {
             case (None, None) => StorageChangeEventDelete.Noop(nodeId).succeed
             case (_, Some(f)) => delete(f)
             case (Some(f), _) => delete(f)
           }
    } yield c)
      .tap(_ => checkInit())
  }

  override def changeStatus(nodeId: NodeId, toStatus: InventoryStatus): IOResult[StorageChangeEventStatus] = {
    // pending and accepted are symmetric, utility function for the two cases
    def move(to: InventoryStatus): IOResult[StorageChangeEventStatus] = {
      val from = if (to == AcceptedInventory) PendingInventory else AcceptedInventory

      val fromFile = getFile(nodeId, from)
      val toFile   = getFile(nodeId, to)
      // check if fact already where it should
      ZIO.ifZIO(IOResult.attempt(fromFile.exists))(
        // however toFile exists, move, because if present it may be because a deletion didn't work and
        // we need to overwrite
        IOResult.attempt(fromFile.moveTo(toFile)(File.CopyOptions(overwrite = true))) *>
        ZIO.when(actuallyCommit) {
          commitMvDirectory(
            committer,
            toGitPath(fromFile.toJava),
            toGitPath(toFile.toJava),
            s"Updating facts for node '${nodeId.value}' to status: ${to.name}"
          )
        } *> fileToNode(toFile)(SelectFacts.none).map(Some(_)).catchAll(_ => None.succeed).map {
          case Some(n) => StorageChangeEventStatus.Done(n.id)
          case None    => StorageChangeEventStatus.Noop(nodeId)
        },
        // if source file does not exist, check if dest is present. If present, assume it's ok, else error
        ZIO
          .whenZIO(IOResult.attempt(!toFile.exists)) {
            Inconsistency(
              s"Error when trying to move fact for node '${nodeId.value}' from '${fromFile.pathAsString}' to '${toFile.pathAsString}': missing files"
            ).fail
          }
          .map(_ => StorageChangeEventStatus.Noop(nodeId))
      )
    }

    toStatus match {
      case RemovedInventory =>
        delete(nodeId)(SelectFacts.none).map {
          case StorageChangeEventDelete.Deleted(node, attrs) => StorageChangeEventStatus.Done(nodeId)
          case StorageChangeEventDelete.DeletedNoInfo(node)  => StorageChangeEventStatus.Done(nodeId)
          case StorageChangeEventDelete.Noop(nodeId)         => StorageChangeEventStatus.Noop(nodeId)
        }
      case x                => move(x)
    }
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
                   groupOwner.map(go => d.setGroup(go))
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

/*
 * An LDAP implementation for NodeFactStorage.
 * It takes most of its code from old FullLdapInventory/NodeInfoService
 */

object LdapNodeFactStorage {

  /*
   * We have 3 main places where facts can be stored:
   * - in ou=Nodes,cn=rudder-configuration
   *     for settings, properties, state, etc
   * - in ou=[Nodes, Machines],ou=[Accepted|Pengin] Inventories,ou=Inventories,cn=rudder-configuration
   *     (and sub entries) for os, ram, swap, machine type, CPU, etc
   *     The mapping is very complicated, and we just want to reuse inventory repository for that
   * - in ou=Software, ou=Inventories,cn=rudder-configuration
   *     For software.
   *
   * So we need for each element of SelectFact to know what part of entries it need
   */

  def needsSoftware(selectFacts: SelectFacts): Boolean = {
    selectFacts.software.mode == SelectMode.Retrieve
  }

  def inventoryFacts(s: SelectFacts) = {
    List(
      s.swap,
      s.accounts,
      s.bios,
      s.controllers,
      s.environmentVariables,
      s.fileSystems,
      s.inputs,
      s.localGroups,
      s.localUsers,
      s.logicalVolumes,
      s.memories,
      s.networks,
      s.physicalVolumes,
      s.ports,
      s.processes,
      s.processors,
      s.slots,
      s.softwareUpdate,
      s.sounds,
      s.storages,
      s.videos,
      s.vms
    )
  }

  def needsInventory(selectFacts: SelectFacts): Boolean = {
    inventoryFacts(selectFacts).exists(_.mode == SelectMode.Retrieve)
  }

}

class LdapNodeFactStorage(
    ldap:                    LDAPConnectionProvider[RwLDAPConnection],
    nodeDit:                 NodeDit,
    inventoryDitService:     InventoryDitService,
    nodeMapper:              LDAPEntityMapper,
    inventoryMapper:         InventoryMapper,
    nodeLibMutex:            ScalaReadWriteLock, // that's a scala-level mutex to have some kind of consistency with LDAP
    fullInventoryRepository: FullInventoryRepositoryImpl,
    softwareGet:             ReadOnlySoftwareDAO,
    softwareSave:            SoftwareDNFinderAction,
    uuidGen:                 StringUuidGenerator
) extends NodeFactStorage {

  // for save, we always store the full node. Since we don't know how to restrict attributes to save
  // for the inventory part (node part is always complete), we do retrieve then merge, avoiding software if possible
  override def save(nodeFact: NodeFact)(implicit attrs: SelectFacts): IOResult[StorageChangeEventSave] = {
    nodeLibMutex.writeLock(for {
      con        <- ldap
      _          <- con
                      .save(nodeMapper.nodeToEntry(nodeFact.toNode))
                      .chainError(s"Cannot save node with id '${nodeFact.id.value}' in LDAP")
      mergedSoft <- if (LdapNodeFactStorage.needsSoftware(attrs)) {
                      softwareSave.tryWith(nodeFact.software.map(_.toSoftware).toSet).map(Some(_))
                    } else None.succeed
      newSoftIds <- mergedSoft match {
                      case None         => None.succeed
                      case Some(merged) =>
                        val newSoft = merged.newSoftware.toSeq.map(_.modify(_.id.value).setTo(uuidGen.newUuid))
                        for {
                          _ <- ZIO.foreach(newSoft)(x => con.save(inventoryMapper.entryFromSoftware(x)))
                        } yield Some(newSoft.map(_.id) ++ merged.alreadySavedSoftware.map(_.id))
                    }
      optOld     <- getNodeFact(nodeFact.id, nodeFact.rudderSettings.status, SelectFacts.noSoftware).map(
                      _.map { nf =>
                        // add back software if needed
                        val optSoft = if (LdapNodeFactStorage.needsSoftware(attrs)) {
                          mergedSoft.map(m => Chunk.fromIterable(m.alreadySavedSoftware).flatMap(s => SoftwareFact.fromSoftware(s)))
                        } else {
                          None
                        }
                        nf.modify(_.software).setToIfDefined(optSoft)
                      }
                    )
      inv         = SelectFacts
                      .merge(nodeFact, optOld)(attrs)
                      .toFullInventory
                      .modify(_.node.softwareIds)
                      .setToIfDefined(newSoftIds)
      _          <- fullInventoryRepository.save(inv)
    } yield {
      optOld match {
        case Some(old) =>
          if (NodeFact.same(old, nodeFact)) StorageChangeEventSave.Noop(nodeFact.id, attrs)
          else StorageChangeEventSave.Updated(old, nodeFact, attrs)
        case None      =>
          StorageChangeEventSave.Created(nodeFact, attrs)
      }
    })
  }

  override def changeStatus(nodeId: NodeId, status: InventoryStatus): IOResult[StorageChangeEventStatus] = {
    for {
      s <- fullInventoryRepository.getStatus(nodeId).notOptional(s"Error: node with ID '${nodeId.value}' was not found'")
      _ <- if (s == status) ZIO.unit
           else if (s == RemovedInventory) {
             Inconsistency(
               s"Error: node with ID '${nodeId.value}' is deleted, can not change its status to '${status.name}''"
             ).fail
           } else fullInventoryRepository.move(nodeId, s, status)
    } yield StorageChangeEventStatus.Done(nodeId)
  }

  override def delete(nodeId: NodeId)(implicit attrs: SelectFacts): IOResult[StorageChangeEventDelete] = {
    def cleanLdapData(): UIO[Boolean] = {
      val cleanNode = (
        for {
          con <- ldap
          d1  <- nodeLibMutex.writeLock(con.delete(nodeDit.NODES.NODE.dn(nodeId.value)))
        } yield d1.nonEmpty
      ).catchAll { err =>
        NodeLoggerPure.Delete.error(
          s"Error when deleting node main information in LDAP, please try to call the delete API with " +
          s"node ID parameter: ${nodeId.value} to correct it; Error: ${err.fullMsg}"
        ) *> true.succeed // there was things in LDAP, they may have changed
      }

      def cleanInventory(s: InventoryStatus): UIO[Boolean] = {
        fullInventoryRepository.delete(nodeId, s).map(_.nonEmpty).catchAll { err =>
          NodeLoggerPure.Delete.error(
            s"Error when deleting node inventory information in LDAP '${s.name}', please try to call the delete API with " +
            s"node ID parameter: ${nodeId.value} to correct it; Error: ${err.fullMsg}"
          ) *> true.succeed
        }
      }

      ZIO
        .foreachPar(List(cleanNode, cleanInventory(PendingInventory), cleanInventory(AcceptedInventory)))(identity)
        .map(_.exists(_ == true))
    }

    for {
      // get information for change event
      p   <- getNodeFact(nodeId, PendingInventory, attrs)
      a   <- getNodeFact(nodeId, AcceptedInventory, attrs)
      // in all case, delete everywhere
      mod <- cleanLdapData()
    } yield {
      (p, a) match {
        case (_, Some(x))    =>
          StorageChangeEventDelete.Deleted(x, attrs)
        case (Some(x), None) =>
          StorageChangeEventDelete.Deleted(x, attrs)
        case (None, None)    =>
          if (mod) {
            StorageChangeEventDelete.Noop(nodeId)
          } else {
            StorageChangeEventDelete.DeletedNoInfo(nodeId)
          }
      }
    }
  }

  /*
   * Get node fact with trying to make the minimum data retieval from ldap (the granularity is coarse: we only check if
   * we need full inventory or just node info, and software or not)
   */
  private[nodes] def getNodeFact(nodeId: NodeId, status: InventoryStatus, attrs: SelectFacts): IOResult[Option[NodeFact]] = {

    def getNodeEntry(con: RwLDAPConnection, id: NodeId): IOResult[Option[LDAPEntry]] = {
      con.get(nodeDit.NODES.NODE.dn(nodeId.value), NodeInfoService.nodeInfoAttributes: _*)
    }
    def getSoftware(
        con:          RwLDAPConnection,
        ids:          Seq[SoftwareUuid],
        needSoftware: Boolean
    ): IOResult[Seq[Software]] = {
      if (needSoftware && ids.nonEmpty) {
        softwareGet.getSoftware(ids)
      } else Seq().succeed
    }
    def getFromFullInventory(
        con:          RwLDAPConnection,
        nodeId:       NodeId,
        nodeEntry:    LDAPEntry,
        needSoftware: Boolean
    ): IOResult[Option[NodeFact]] = {
      for {
        node    <- nodeMapper.entryToNode(nodeEntry).toIO
        // handle security tag mapping here
        optInvS <- fullInventoryRepository.getWithSoftware(nodeId, status, needSoftware)
        softs   <- getSoftware(con, optInvS.map(_._2).getOrElse(Seq()), needSoftware)
      } yield {
        optInvS.map {
          case (inv, _) =>
            val info = NodeInfo(
              node,
              inv.node.main.hostname,
              inv.machine.map(m => MachineInfo(m.id, m.machineType, m.systemSerialNumber, m.manufacturer)),
              inv.node.main.osDetails,
              inv.node.serverIps.toList,
              inv.node.inventoryDate.getOrElse(DateTime.now),
              inv.node.main.keyStatus,
              inv.node.agents,
              inv.node.main.policyServerId,
              inv.node.main.rootUser,
              inv.node.archDescription,
              inv.node.ram,
              inv.node.timezone
            )
            NodeFact.fromCompat(info, Right(inv), softs)
        }
      }
    }

    def getFromLdapInfo(
        con:          RwLDAPConnection,
        nodeId:       NodeId,
        nodeEntry:    LDAPEntry,
        status:       InventoryStatus,
        needSoftware: Boolean
    ): IOResult[Option[NodeFact]] = {
      // mostly copied from com.normation.rudder.services.nodes.NodeInfoServiceCachedImpl # getBackendLdapNodeInfo
      val ldapAttrs = (if (needSoftware) Seq(A_SOFTWARE_DN) else Seq()) ++ NodeInfoService.nodeInfoAttributes

      con.get(inventoryDitService.getDit(status).NODES.NODE.dn(nodeId.value), ldapAttrs: _*).flatMap {
        case None      => // end of game, no node here
          None.succeed
        case Some(inv) =>
          for {
            optM <- inv(A_CONTAINER_DN) match {
                      case None    => None.succeed
                      case Some(m) => con.get(new DN(m), ldapAttrs: _*)
                    }
            info <- nodeMapper.convertEntriesToNodeInfos(nodeEntry, inv, optM)
            soft <- getSoftware(con, fullInventoryRepository.getSoftwareUuids(inv), needSoftware)
          } yield Some(NodeFact.fromCompat(info, Left(status), soft))
      }
    }

    for {
      t0      <- currentTimeMillis
      _       <-
        NodeLoggerPure.debug(
          s"Getting node '${nodeId.value}' with inventory: ${SelectFacts
              .retrieveInventory(attrs)}; software: ${attrs.software.mode == SelectMode.Retrieve}"
        )
      con     <- ldap
      optNode <- getNodeEntry(con, nodeId)
      res     <- optNode match {
                   case None            => None.succeed
                   case Some(nodeEntry) =>
                     if (LdapNodeFactStorage.needsInventory(attrs)) {
                       getFromFullInventory(con, nodeId, nodeEntry, needsSoftware(attrs))
                     } else {
                       getFromLdapInfo(con, nodeId, nodeEntry, status, needsSoftware(attrs))
                     }
                 }
      t1      <- currentTimeMillis
      _       <- NodeLoggerPure.Metrics.debug(s"node '${nodeId.value}' retrieved in ${t1 - t0} ms")
      _       <- NodeLoggerPure.Details.trace(s"node '${nodeId.value}' fetched details: ${res}")
    } yield res
  }

  override def getPending(nodeId: NodeId)(implicit attrs: SelectFacts): IOResult[Option[NodeFact]] = {
    getNodeFact(nodeId, PendingInventory, attrs)
  }

  override def getAccepted(nodeId: NodeId)(implicit attrs: SelectFacts): IOResult[Option[NodeFact]] = {
    getNodeFact(nodeId, AcceptedInventory, attrs)
  }

  private[nodes] def getNodeIds(baseDN: DN): IOResult[Seq[NodeId]] = {
    for {
      con         <- ldap
      nodeEntries <- con.search(baseDN, One, ALL, "1.1")
    } yield {
      nodeEntries.flatMap(e => e(A_NODE_UUID).map(NodeId(_)))
    }
  }

  private[nodes] def getAllNodeFacts(baseDN: DN, getOne: NodeId => IOResult[Option[NodeFact]]): IOStream[NodeFact] = {
    ZStream
      .fromZIO(getNodeIds(baseDN))
      .tap(ids => NodeLoggerPure.Metrics.debug(s"Getting ${ids.size} nodes}"))
      .flatMap(ids => ZStream.fromIterable(ids))
      .mapZIO(getOne)
      .flatMap(ZStream.fromIterable(_))
  }

  override def getAllPending()(implicit attrs: SelectFacts): IOStream[NodeFact] = {
    getAllNodeFacts(inventoryDitService.getDit(PendingInventory).NODES.dn, getPending(_))
  }

  override def getAllAccepted()(implicit attrs: SelectFacts): IOStream[NodeFact] = {
    getAllNodeFacts(nodeDit.NODES.dn, getAccepted)
  }
}
