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
import com.normation.rudder.domain.logger.NodeLogger
import com.normation.rudder.git.GitItemRepository
import com.normation.rudder.git.GitRepositoryProvider
import java.nio.charset.StandardCharsets
import org.eclipse.jgit.lib.PersonIdent
import zio._
import zio.json._
import zio.stream.ZStream
import zio.syntax._

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
 */
class GitNodeFactRepositoryImpl(
    override val gitRepo: GitRepositoryProvider,
    groupOwner:           String,
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
    import GitNodeFactRepositoryImpl._
    NodeFactArchive(entity, fileFormat, nodeFact).toJsonPretty.succeed
  }

  private[nodes] def getAll(base: File): IOStream[NodeFact] = {
    // TODO should be from git head, not from file directory
    val stream = ZStream.fromIterator(base.collectChildren(_.extension(includeDot = true, includeAll = true) == Some(".json")))
    stream
      .mapError(ex => SystemError("Error when reading node fact persisted file", ex))
      .mapZIO(f =>
        f.contentAsString(StandardCharsets.UTF_8).fromJson[NodeFact].toIO.chainError(s"Error when decoding ${f.pathAsString}")
      )
  }

  override def getAllPending():  IOStream[NodeFact] = getAll(gitRepo.rootDirectory / relativePath / PendingInventory.name)
  override def getAllAccepted(): IOStream[NodeFact] = getAll(gitRepo.rootDirectory / relativePath / AcceptedInventory.name)

  override def save(nodeFact: NodeFact): IOResult[Unit] = {
    if (nodeFact.rudderSettings.status == RemovedInventory) {
      InventoryDataLogger.info(
        s"Not persisting deleted node '${nodeFact.fqdn}' [${nodeFact.id.value}]: it has removed inventory status"
      ) *>
      ZIO.unit
    } else {
      for {
        json <- toJson(nodeFact)
        file  = getFile(nodeFact.id, nodeFact.rudderSettings.status)
        _    <- IOResult.attempt(file.write(json))
        _    <- IOResult.attempt(file.setGroup(groupOwner))
        _    <- ZIO.when(actuallyCommit) {
                  commitAddFile(
                    committer,
                    toGitPath(file.toJava),
                    s"Save inventory facts for ${nodeFact.rudderSettings.status.name} node '${nodeFact.fqdn}' (${nodeFact.id.value})"
                  )
                }
      } yield ()
    }
  }

  // when we delete, we check for all path to also remove possible left-over
  // we may need to recreate pending/accepted directory, because git delete
  // empty directories.
  override def delete(nodeId: NodeId) = {
    ZIO.foreach(List(PendingInventory, AcceptedInventory)) { s =>
      val file = getFile(nodeId, s)
      ZIO.whenZIO(IOResult.attempt(file.exists)) {
        if (actuallyCommit) {
          commitRmFile(committer, toGitPath(file.toJava), s"Updating facts for node '${nodeId.value}': deleted")
        } else {
          IOResult.attempt(file.delete())
        }
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
        ZIO.when(actuallyCommit) {
          commitMvDirectory(
            committer,
            toGitPath(fromFile.toJava),
            toGitPath(toFile.toJava),
            s"Updating facts for node '${nodeId.value}' to status: ${to.name}"
          )
        }, // if source file does not exist, check if dest is present. If present, assume it's ok, else error

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
