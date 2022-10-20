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

import better.files.File
import com.normation.errors._
import com.normation.errors.IOResult
import com.normation.inventory.domain._
import com.normation.rudder.apidata.FullDetailLevel
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.git.GitItemRepository
import com.normation.rudder.git.GitRepositoryProvider
import com.softwaremill.quicklens._
import net.liftweb.json.JsonDSL._
import net.liftweb.json.prettyRender
import org.eclipse.jgit.lib.PersonIdent
import zio._
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

  def persist(nodeInfo: NodeInfo, inventory: FullInventory, software: Seq[Software]): IOResult[Unit]

  /*
   * Change the status of the node with given id to given status.
   * - if the node is not found, an error is raised appart if target status is "delete"
   * - if the target status is the current one, this function does nothing
   * - if target status is "removed", persisted inventory is deleted
   */
  def changeStatus(nodeId: NodeId, status: InventoryStatus): IOResult[Unit]

}

/*
 * Serialize a fact type (to/from JSON), for example nodes.
 * The format is versionned so that we are able to unserialize old files into newer domain representation.
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
 * We have only one git for all fact repositories. This is the one managing semaphore, init, etc.
 * All fact repositories will be a subfolder on it:
 * - /var/rudder/fact-repository/nodes
 * - /var/rudder/fact-repository/rudder-config
 * - /var/rudder/fact-repository/groups
 * etc
 */

class GitNodeFactRepository(
    override val gitRepo: GitRepositoryProvider,
    groupOwner:           String
) extends NodeFactRepository with GitItemRepository
    with SerializeFacts[(NodeId, InventoryStatus), (NodeInfo, FullInventory, Seq[Software])] {

  override val relativePath = "nodes"
  override val entity:     String = "node"
  override val fileFormat: String = "1"
  val commiter = new PersonIdent("rudder-fact", "email not set")

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
  def toJson(data: (NodeInfo, FullInventory, Seq[Software])): IOResult[String] = {
    val (nodeInfo, inventory, software) = data
    // we want to store objects alphabetically
    val inv                             = inventory
      .modify(_.node.accounts)
      .using(_.sorted)
      .modify(_.node.customProperties)
      .using(_.sortBy(_.name))
      .modify(_.node.environmentVariables)
      .using(_.sortBy(_.name))
      .modify(_.node.fileSystems)
      .using(_.sortBy(_.name))
      .modify(_.node.networks)
      .using(_.sortBy(_.name))
      .modify(_.node.processes)
      .using(_.sortBy(_.commandName))
      .modify(_.machine.each.bios)
      .using(_.sortBy(_.name))
      .modify(_.machine.each.controllers)
      .using(_.sortBy(_.name))
      .modify(_.machine.each.memories)
      .using(_.sortBy(_.name))
      .modify(_.machine.each.ports)
      .using(_.sortBy(_.name))
      .modify(_.machine.each.processors)
      .using(_.sortBy(_.name))
      .modify(_.machine.each.slots)
      .using(_.sortBy(_.name))
      .modify(_.machine.each.sounds)
      .using(_.sortBy(_.name))
      .modify(_.machine.each.storages)
      .using(_.sortBy(_.name))
      .modify(_.machine.each.videos)
      .using(_.sortBy(_.name))

    val json =
      FullDetailLevel.toJson(nodeInfo, inventory.node.main.status, None, Some(inv), software.sortBy(_.name.getOrElse("")))
    // add entity type and file format at the begining
    ("entity" -> entity) ~ ("fileFormat" -> fileFormat) ~ json

    // save in human readable format (ie with indentation and git diff compatible)
    // prettyRender throws exception when it encounters "JNothing"
    IOResult.attempt(prettyRender(json))
  }

  override def persist(nodeInfo: NodeInfo, inventory: FullInventory, software: Seq[Software]): IOResult[Unit] = {
    for {
      json   <- toJson((nodeInfo, inventory, software))
      file    = getFile(nodeInfo.id, inventory.node.main.status)
      _      <- IOResult.attempt(file.write(json))
      _      <- IOResult.attempt(file.setGroup(groupOwner))
      gitPath = toGitPath(file.toJava)
      saved  <- commitAddFile(
                  commiter,
                  gitPath,
                  s"Save inventory facts for ${inventory.node.main.status.name} node '${nodeInfo.hostname}' (${nodeInfo.id.value})"
                )
    } yield ()
  }

  override def changeStatus(nodeId: NodeId, status: InventoryStatus): IOResult[Unit] = {
    // pending and accepted are symetric, utility function for the two cases
    def move(from: InventoryStatus) = {
      val to = if (from == AcceptedInventory) PendingInventory else AcceptedInventory

      val fromFile = getFile(nodeId, from)
      val toFile   = getFile(nodeId, to)
      // check if fact already where it should
      ZIO.ifZIO(IOResult.attempt(fromFile.exists))(
        // however toFile exists, move, because if present it may be because a deletion didn't work and
        // we need to overwritte
        IOResult.attempt(fromFile.moveTo(toFile)(File.CopyOptions(overwrite = true))) *>
        commitMvDirectory(
          commiter,
          toGitPath(fromFile.toJava),
          toGitPath(toFile.toJava),
          s"Updating facts for node '${nodeId.value}': accepted"
        ), // if source file does not exist, check if dest is present. If present, assume it's ok, else error

        ZIO.whenZIO(IOResult.attempt(!toFile.exists)) {
          Inconsistency(
            s"Error when trying to move fact for node '${nodeId.value}' from '${fromFile.pathAsString}' to '${toFile.pathAsString}': missing files"
          ).fail
        }
      )
    }

    // when we delete, we check for all path to also remove possible left-over
    // we may need to recreate pending/accepted directory, because git delete
    // empty directories.
    def delete() = {
      ZIO.foreach(List(PendingInventory, AcceptedInventory)) { s =>
        val file = getFile(nodeId, s)
        ZIO.whenZIO(IOResult.attempt(file.exists)) {
          commitRmFile(commiter, toGitPath(file.toJava), s"Updating facts for node '${nodeId.value}': deleted")
        }
      } *> checkInit()
    }

    (status match {
      case RemovedInventory => delete()
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
