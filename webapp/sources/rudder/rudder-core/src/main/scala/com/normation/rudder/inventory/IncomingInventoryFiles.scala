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
import java.time.Instant
import java.time.OffsetDateTime
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.TrueFileFilter
import scala.util.Try
import zio.*
import zio.syntax.*

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

final case class FilteredFiles(
    toClean: List[ToClean],
    toKeep:  List[File]
)

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
    files:        List[InventoryFileInfo]
) {
  def isComplete: Boolean = {
    files.exists(f => InventoryFileName.isSignature(f.file.name)) && files.exists(f =>
      !InventoryFileName.isSignature(f.file.name)
    )
  }

  def contains(file: File): Boolean = files.exists(_.file.path == file.path)

  // Return None while the inventory is still gzipped (not processable yet), the pair of file in other cases.
  def toPair: Option[InventoryPair] = {
    val inventory = directory / name
    val signature = directory / (name + ".sign")
    if (contains(inventory) && contains(signature)) Some(InventoryPair(inventory, signature)) else None
  }
}

// Data structure to store youngest complete inventory of a node + the older ones it supersedes
final case class YoungestInventory(youngest: InventoryFileGroup, superseded: List[InventoryFileGroup]) {
  def toClean: List[ToClean] = superseded.flatMap(g => {
    g.files.map(f => ToClean(f.file, s"Inventory '${g.name}' is superseded by younger inventory '${youngest.name}'"))
  })
}

object InventoryFileGroup {

  def group(files: List[InventoryFileInfo]): List[InventoryFileGroup] = {
    files
      .groupBy(f => (f.file.parent.path, InventoryFileName.inventoryName(f.file.name)))
      .toList
      .collect {
        case ((_, name), fs @ (h :: _)) =>
          val (key, date) = InventoryFileName.nodeKeyAndDate(name)
          InventoryFileGroup(h.file.parent, name, key, date, fs.map(_.lastModified).max, fs)
      }
  }

  /*
   * Youngest first. Sort key: the date in the name (set by the agent at inventory time) when all of the
   * node's inventories have one, else mtime. Path and name last, for a total order.
   */
  private def youngestFirst(groups: List[InventoryFileGroup]): List[InventoryFileGroup] = {
    val ordering: Ordering[InventoryFileGroup] = {
      if (groups.forall(_.nameDate.nonEmpty)) Ordering.by(g => (g.nameDate, g.lastModified, g.directory.pathAsString, g.name))
      else Ordering.by(g => (g.lastModified, g.directory.pathAsString, g.name))
    }
    groups.sorted(using ordering.reverse)
  }

  // return the list of complete groups. An incomplete one may still get its missing file and must not yet supersede a complete one
  def youngestPerNode(complete: List[InventoryFileGroup]): List[YoungestInventory] = {
    complete
      .groupBy(_.nodeKey)
      .values
      .toList
      .flatMap(gs => {
        youngestFirst(gs) match {
          case Nil       => None
          case y :: tail => Some(YoungestInventory(y, tail))
        }
      })
  }

  /*
   * Filter files on periodic scan and decide what to do with them:
   * - older than `maxAgeGlobal`: delete;
   * - incomplete: delete if older than `maxAgeOrphan`, else re-submit (missing file may still come);
   * - complete: re-submit the youngest of each node, delete the older ones.
   */
  def filterFiles(maxAgeGlobal: Instant, maxAgeOrphan: Instant, files: List[InventoryFileInfo]): FilteredFiles = {
    val (tooOld, others)       = files.partition(_.lastModified.isBefore(maxAgeGlobal))
    val (complete, incomplete) = group(others).partition(_.isComplete)
    val (waiting, orphans)     = incomplete.partition(_.lastModified.isAfter(maxAgeOrphan))
    val youngest               = youngestPerNode(complete)

    FilteredFiles(
      toClean =
        tooOld.map(f => ToClean(f.file, s"Inventory file '${f.file.name}' is older than max duration before cleaning")) :::
        orphans.flatMap(_.files.map(f => {
          ToClean(f.file, s"Inventory file '${f.file.name}' is not an (inventory, signature) pair, cleaning")
        })) :::
        youngest.flatMap(_.toClean),
      toKeep = (waiting ::: youngest.map(_.youngest)).flatMap(_.files.map(_.file))
    )
  }
}

object InventoryFiles {

  /*
   * Inventory files of the dirs (recursively) matching the predicate.
   * We avoid java.nio.Files.walk because it (used to) fails when a file disappears mid-walk,
   * see https://issues.rudder.io/issues/19268
   */
  def list(directories: List[File], keep: InventoryFileInfo => Boolean): UIO[List[InventoryFileInfo]] = {
    import scala.jdk.CollectionConverters.*
    def info(f: File): Option[InventoryFileInfo] = {
      if (f.isRegularFile && InventoryProcessingUtils.hasValidInventoryExtension(f)) {
        Some(InventoryFileInfo(f, f.lastModifiedTime)).filter(keep)
      } else None
    }

    (for {
      // listing error: log, return no file
      children <- ZIO.foreach(directories)(d => {
                    IOResult.attempt(FileUtils.listFilesAndDirs(d.toJava, TrueFileFilter.TRUE, TrueFileFilter.TRUE).asScala)
                  })
      // per-file error: skip the file. FileNotFound is expected: davfs temp files disappear
      infos    <- ZIO.foreach(children.flatten) { file =>
                    ZIO.attempt(info(File(file.toPath))).catchAll {
                      case _:  FileNotFoundException =>
                        InventoryProcessingLogger.trace(
                          s"Ignoring file '${file.toString}' when listing inventories: " +
                          s"FileNotFoundException (likely it disappeared between directory listing and filtering)"
                        ) *> ZIO.succeed(None)
                      case ex: Throwable             =>
                        InventoryProcessingLogger.warn(s"Error when listing inventory file '${file.toString}': ${ex.getMessage}") *>
                        ZIO.succeed(None)
                    }
                  }
    } yield infos.flatten).catchAll(err => {
      InventoryProcessingLogger.warn(s"Error when listing inventory files: ${err.fullMsg}") *> Nil.succeed
    })
  }

  def delete(files: List[ToClean]): UIO[Unit] = {
    ZIO.foreachDiscard(files) { f =>
      (ZIO.attempt(f.file.delete()) *> InventoryProcessingLogger.info(s"Deleting file '${f.file.name}': ${f.why}")).catchAll(
        err => {
          InventoryProcessingLogger.error(
            s"Error when trying to delete inventory file '${f.file.name}' (${f.why}): ${err.getMessage}"
          )
        }
      )
    }
  }
}
