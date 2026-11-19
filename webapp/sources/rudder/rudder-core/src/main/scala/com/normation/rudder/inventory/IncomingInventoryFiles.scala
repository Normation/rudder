/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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

import better.files.*
import com.normation.errors.*
import com.normation.inventory.domain.InventoryProcessingLogger
import java.io.FileNotFoundException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.OffsetDateTime
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.TrueFileFilter
import scala.util.Try
import scala.util.Using
import scala.util.control.NonFatal
import zio.*

/*
 * That file contains managing rules about files waiting in the incoming inventory dirs, and how to decide which ones
 * Rudder processes, re-submits or deletes.
 * Rules are pure, so callers need to deal with reading the file system.
 *
 * Agents send a pair of `<inventory>` + `<inventory>.sign`. The inventory file may be gzipped after signing
 * (`x.ocs.gz` + `x.ocs.sign`). Current (9.1) agents name it `<node uuid>_<RFC 3339 date>.ocs`, so a node
 * can have several inventories waiting: only the youngest complete one is processed, older ones are obsolete.
 */

final case class InventoryFileInfo(file: File, lastModified: Instant)

final case class ToClean(file: File, why: String)

/*
 * Age limits of the incoming inventory rules, shared by the periodic scan and the processing of new inventories:
 * - purgeAfter: an inventory last modified longer ago is deleted;
 * - waitForSignature: an incomplete inventory waits that long for its missing file, then is deleted as an orphan.
 */
final case class InventoryAgeLimits(purgeAfter: Duration, waitForSignature: Duration)

object InventoryFileName {
  private val signatureExtension = ".sign"
  private val gzipExtension      = ".gz"
  private val inventoryExtension = List(".ocs", ".xml")

  def isSignature(fileName: String): Boolean = fileName.endsWith(signatureExtension)

  // get the inventory filename for that file: `x.ocs` for `x.ocs`, `x.ocs.gz` and `x.ocs.sign`
  def inventoryName(fileName: String): String = fileName.stripSuffix(signatureExtension).stripSuffix(gzipExtension)

  /*
   * Split an inventory name into node key and date:
   * - node key: the uuid for `<uuid>_<date>.ocs`, the whole name without extension for older, dateless formats.
   * - date: None when not RFC 3339.
   */
  def nodeKeyAndDate(inventoryName: String): (String, Option[Instant]) = {
    val base = inventoryExtension.find(inventoryName.endsWith).fold(inventoryName)(e => inventoryName.dropRight(e.length))
    base match {
      case InventoryMover.timestampPattern(key, suffix) =>
        (key, Option(suffix).flatMap(s => Try(OffsetDateTime.parse(s.drop(1)).toInstant).toOption))
      case _                                            => (base, None)
    }
  }

  def nodeKey(fileName: String): String = nodeKeyAndDate(inventoryName(fileName))._1
}

/*
 * Files of one inventory in one dir. Complete = signature + inventory (plain or gzipped).
 */
final case class InventoryFileGroup(
    directory:    File,
    name:         String,
    nodeKey:      String,
    nameDate:     Option[Instant],
    lastModified: Instant,
    files:        List[InventoryFileInfo] // all files related to that node "name"
) {

  // Return None while the inventory is still gzipped (not processable yet), the pair of files in other cases.
  def toPair: Option[InventoryPair] = {
    def contains(file: File): Boolean = files.exists(_.file.path == file.path)

    val inventory = directory / name
    val signature = directory / (name + ".sign")
    if (contains(inventory) && contains(signature)) Some(InventoryPair(inventory, signature)) else None
  }
}

/*
 * Data structure to store the inventory files of one node, classified once for both the periodic scan
 * and the processing of a new inventory. Each group is in exactly one list, by order of precedence:
 * - tooOld: last modified before `tooOldBefore`;
 * - orphans: incomplete and last modified before `waitingAfter`;
 * - waiting: incomplete, its missing file may still come;
 * - youngest, superseded: complete, the youngest one and the older ones.
 */
final case class NodeInventoryFiles private (
    nodeKey:    String,
    tooOld:     List[InventoryFileGroup],
    orphans:    List[InventoryFileGroup],
    waiting:    List[InventoryFileGroup],
    youngest:   Option[InventoryFileGroup],
    superseded: List[InventoryFileGroup]
) {
  // files to delete, with the reason logged on deletion
  def toClean: List[ToClean] = {
    def clean(groups: List[InventoryFileGroup], why: InventoryFileGroup => String) = {
      groups.flatMap(g => g.files.map(f => ToClean(f.file, why(g))))
    }
    clean(tooOld, g => s"Inventory '${g.name}' is older than max duration before cleaning") :::
    clean(orphans, g => s"Inventory '${g.name}' is not an (inventory, signature) pair, cleaning") :::
    clean(superseded, g => s"Inventory '${g.name}' is superseded by younger inventory '${youngest.fold("")(_.name)}'")
  }

  // files the periodic scan re-submits: the youngest, and the incomplete ones that may still get their missing file
  def toResubmit: List[File] = (waiting ::: youngest.toList).flatMap(_.files.map(_.file))

  // Return the youngest pair if exists or None when there is no complete inventory or when the youngest is
  // still gzipped (the event for the gunzipped file processes it).
  def readyToProcessOne: Option[InventoryPair] = youngest.flatMap(_.toPair)

  // names of the inventories processed or deleted, so that the save buffer can drop them
  def handledNames: Set[String] = (youngest.toList ::: superseded ::: tooOld ::: orphans).map(_.name).toSet
}

object NodeInventoryFiles {

  // Classify the files of node `nodeKey`, ignoring the files of other nodes.
  def forNode(
      nodeKey:      String,
      files:        List[InventoryFileInfo],
      tooOldBefore: Instant,
      waitingAfter: Instant
  ): NodeInventoryFiles = {
    processNodeFiles(nodeKey, groupByNode(files).getOrElse(nodeKey, Nil), tooOldBefore, waitingAfter)
  }

  def byNode(files: List[InventoryFileInfo], tooOldBefore: Instant, waitingAfter: Instant): List[NodeInventoryFiles] = {
    groupByNode(files).toList.map { case (key, groups) => processNodeFiles(key, groups, tooOldBefore, waitingAfter) }
  }

  private def processNodeFiles(
      nodeKey:      String,
      groups:       List[InventoryFileGroup],
      tooOldBefore: Instant,
      waitingAfter: Instant
  ): NodeInventoryFiles = {

    // utility to check if an inventory has all file needed to be processed (ie an inventory file + a signature)
    extension (x: InventoryFileGroup) {
      def isComplete: Boolean = {
        x.files.exists(f => InventoryFileName.isSignature(f.file.name)) && x.files.exists(f =>
          !InventoryFileName.isSignature(f.file.name)
        )
      }
    }

    // several partitions, but all boolean logic is here. Modify with care.
    // remember: partition(predicate) result is pair: (predicate is true, predicate is false)
    val (tooOld, others)       = groups.partition(_.lastModified.isBefore(tooOldBefore))
    val (complete, incomplete) = others.partition(_.isComplete)
    val (waiting, orphans)     = incomplete.partition(_.lastModified.isAfter(waitingAfter))
    val youngest               = complete.maxOption(using youngestLast(complete))
    val superseded             = complete.filterNot(g => youngest.contains(g))
    NodeInventoryFiles(nodeKey, tooOld, orphans, waiting, youngest, superseded)
  }

  // Return the inventory groups of each node, by node key.
  private def groupByNode(files: List[InventoryFileInfo]): Map[String, List[InventoryFileGroup]] = {
    files
      .groupBy(f => (f.file.parent.path, InventoryFileName.inventoryName(f.file.name)))
      .toList
      .collect {
        case ((_, name), fs @ (h :: _)) =>
          val (key, date) = InventoryFileName.nodeKeyAndDate(name)
          InventoryFileGroup(h.file.parent, name, key, date, fs.map(_.lastModified).max, fs)
      }
      .groupBy(_.nodeKey)
  }

  /*
   * Order from oldest to youngest. Sort key: the date in the name (set by the agent at inventory time) when
   * all of the node's inventories have one, else mtime. Path and name last, for a total order.
   */
  private def youngestLast(groups: List[InventoryFileGroup]): Ordering[InventoryFileGroup] = {
    if (groups.forall(_.nameDate.nonEmpty)) Ordering.by(g => (g.nameDate, g.lastModified, g.directory.pathAsString, g.name))
    else Ordering.by(g => (g.lastModified, g.directory.pathAsString, g.name))
  }
}

object InventoryFiles {

  /*
   * All inventory files of the dirs, recursively, for the periodic scan.
   * We avoid java.nio.Files.walk because it (used to) fail when a file disappears mid-walk,
   * see https://issues.rudder.io/issues/19268
   */
  def listAll(directories: List[File]): UIO[List[InventoryFileInfo]] = {
    import scala.jdk.CollectionConverters.*
    list(directories, d => FileUtils.listFiles(d.toJava, TrueFileFilter.TRUE, TrueFileFilter.TRUE).asScala.map(_.toPath).toList)
  }

  /*
   * Inventory files of node `nodeKey` at the top of the dirs, for the processing of each new inventory.
   * The name filter runs during the directory read, before any stat, because the dirs can hold many files.
   */
  def listNode(directories: List[File], nodeKey: String): UIO[List[InventoryFileInfo]] = {
    import scala.jdk.CollectionConverters.*
    val sameNode: DirectoryStream.Filter[Path] = p => p.getFileName.toString.startsWith(nodeKey)
    list(directories, d => Using.resource(Files.newDirectoryStream(d.path, sameNode))(_.asScala.toList))
  }

  /*
   * One blocking effect per dir, and one stat per inventory file. An error on a dir or a file is logged and
   * the dir or file skipped. A file can disappear between the listing and its stat (davfs temp files, a
   * concurrent processing): that's expected and skipped silently.
   */
  private def list(directories: List[File], paths: File => List[Path]): UIO[List[InventoryFileInfo]] = {
    def info(p: Path): Either[String, Option[InventoryFileInfo]] = {
      if (!InventoryProcessingUtils.hasValidInventoryExtension(p.getFileName.toString)) Right(None)
      else {
        try {
          val attrs = Files.readAttributes(p, classOf[BasicFileAttributes])
          Right(if (attrs.isRegularFile) Some(InventoryFileInfo(File(p), attrs.lastModifiedTime.toInstant)) else None)
        } catch {
          case _: NoSuchFileException | _: FileNotFoundException => Right(None)
          case NonFatal(ex)                                      => Left(s"'${p}': ${ex.getClass.getSimpleName}: ${ex.getMessage}")
        }
      }
    }

    ZIO
      .foreach(directories) { d =>
        IOResult
          .attempt(s"Error when listing inventory files in '${d.pathAsString}'")(paths(d).map(info).partitionMap(identity))
          .flatMap {
            case (errors, infos) =>
              ZIO.foreachDiscard(errors)(e => InventoryProcessingLogger.warn(s"Error when reading inventory file ${e}")) *>
              ZIO.succeed(infos.flatten)
          }
          .catchAll(err => InventoryProcessingLogger.warn(err.fullMsg) *> ZIO.succeed(Nil))
      }
      .map(_.flatten)
  }

  def delete(files: List[ToClean]): UIO[Unit] = {
    ZIO.foreachDiscard(files) { f =>
      (IOResult.attempt(f.file.delete()) *> InventoryProcessingLogger.info(s"Deleting file '${f.file.name}': ${f.why}")).catchAll(
        err => {
          InventoryProcessingLogger.error(
            s"Error when trying to delete inventory file '${f.file.name}' (${f.why}): ${err.fullMsg}"
          )
        }
      )
    }
  }
}
