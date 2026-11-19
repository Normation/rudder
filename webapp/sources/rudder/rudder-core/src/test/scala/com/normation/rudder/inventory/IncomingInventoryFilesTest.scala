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
import com.normation.errors.IOResult
import com.normation.errors.effectUioUnit
import com.normation.zio.*
import java.time.Instant
import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import zio.*
import zio.test.*
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class IncomingInventoryFilesTest extends ZIOSpecDefault {

  private val incoming = File("/inventories/incoming")
  private val updates  = File("/inventories/accepted-nodes-updates")
  private val now      = Instant.parse("2026-11-12T12:00:00Z")
  private val uuid1    = "736320a2-998b-45f2-9bad-864b01d48d88"
  private val uuid2    = "0f6b7c1e-3b61-4c35-8c8e-6b0b2a1e8a77"

  private def minutesAgo(m: Long): Instant = now.minusSeconds(m * 60)

  // inventory + signature, both modified at `mtime`
  private def pair(dir: File, name: String, mtime: Instant): List[InventoryFileInfo] = {
    List(InventoryFileInfo(dir / name, mtime), InventoryFileInfo(dir / (name + ".sign"), mtime))
  }

  private def classify(files: List[InventoryFileInfo]): List[NodeInventoryFiles] = {
    NodeInventoryFiles.byNode(files, minutesAgo(2 * 24 * 60), minutesAgo(3))
  }

  private def node(files: List[InventoryFileInfo]): NodeInventoryFiles = {
    NodeInventoryFiles.forNode(uuid1, files, minutesAgo(2 * 24 * 60), minutesAgo(3))
  }

  private def names(files: List[File]): Set[String] = files.map(f => s"${f.parent.name}/${f.name}").toSet

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Incoming inventory files")(
    suite("name parsing")(
      test("current format gives node uuid and date") {
        assertTrue(
          InventoryFileName.nodeKeyAndDate(s"${uuid1}_2026-11-12T00:28:21+00:00.ocs") ==
          (uuid1, Some(Instant.parse("2026-11-12T00:28:21Z")))
        )
      },
      test("old format, with dots in host name, has no date") {
        assertTrue(
          InventoryFileName.nodeKeyAndDate(s"node1.example.com-${uuid1}.ocs") == (s"node1.example.com-${uuid1}", None)
        )
      },
      test("an unparsable date is absent") {
        assertTrue(InventoryFileName.nodeKeyAndDate(s"${uuid1}_2026-11-12Tsomething.ocs") == (uuid1, None))
      },
      test("signature and gzipped inventory belong to the inventory") {
        val name = s"${uuid1}_2026-11-12T00:28:21+00:00.ocs"
        assertTrue(
          InventoryFileName.inventoryName(name + ".gz") == name,
          InventoryFileName.inventoryName(name + ".sign") == name,
          InventoryFileName.nodeKey(name + ".gz") == uuid1
        )
      }
    ),
    suite("classification of a node's files")(
      test("a gzipped inventory and its signature are complete, but not ready to process") {
        val name = s"${uuid1}_2026-11-12T00:28:21+00:00.ocs"
        val res  =
          node(List(InventoryFileInfo(incoming / (name + ".gz"), now), InventoryFileInfo(incoming / (name + ".sign"), now)))
        assertTrue(res.youngest.map(_.name) == Some(name), res.readyToProcessOne.isEmpty, res.toClean.isEmpty)
      },
      test("a plain pair is ready to process") {
        val name = s"${uuid1}_2026-11-12T00:28:21+00:00.ocs"
        assertTrue(
          node(pair(incoming, name, now)).readyToProcessOne == Some(InventoryPair(incoming / name, incoming / s"${name}.sign"))
        )
      },
      test("the date in names takes precedence over modification time") {
        val older   = s"${uuid1}_2026-11-12T00:00:00+00:00.ocs"
        val younger = s"${uuid1}_2026-11-12T06:00:00+00:00.ocs"
        // the younger inventory reached the disk first
        val res     = node(pair(incoming, older, minutesAgo(10)) ::: pair(incoming, younger, minutesAgo(20)))
        assertTrue(res.youngest.map(_.name) == Some(younger), res.superseded.map(_.name) == List(older))
      },
      test("modification time is used when a name has no date") {
        val dated   = s"${uuid1}_2026-11-12T06:00:00+00:00.ocs"
        val undated = s"${uuid1}.ocs"
        val res     = node(pair(incoming, dated, minutesAgo(20)) ::: pair(incoming, undated, minutesAgo(10)))
        assertTrue(res.youngest.map(_.name) == Some(undated))
      },
      test("the same inventory in two directories: one is superseded") {
        val name = s"${uuid1}_2026-11-12T00:28:21+00:00.ocs"
        val res  = node(pair(incoming, name, minutesAgo(10)) ::: pair(updates, name, minutesAgo(10)))
        assertTrue(res.youngest.nonEmpty, res.superseded.size == 1, res.toClean.size == 2)
      },
      test("an incomplete younger inventory does not supersede a complete one") {
        val complete   = s"${uuid1}_2026-11-12T00:00:00+00:00.ocs"
        val incomplete = InventoryFileInfo(incoming / s"${uuid1}_2026-11-12T11:59:00+00:00.ocs", minutesAgo(1))
        val res        = node(incomplete :: pair(incoming, complete, minutesAgo(600)))
        assertTrue(
          res.youngest.map(_.name) == Some(complete),
          res.waiting.size == 1,
          res.toClean.isEmpty,
          res.toResubmit.size == 3
        )
      },
      test("an incomplete inventory is an orphan after the signature delay, waiting before") {
        val old   = InventoryFileInfo(incoming / s"${uuid1}_2026-11-12T00:00:00+00:00.ocs", minutesAgo(10))
        val young = InventoryFileInfo(incoming / s"${uuid1}_2026-11-12T11:59:00+00:00.ocs", minutesAgo(1))
        val res   = node(List(old, young))
        assertTrue(res.toClean.map(_.file) == List(old.file), res.toResubmit == List(young.file), res.readyToProcessOne.isEmpty)
      },
      test("a too old inventory is deleted with its signature, even when complete") {
        val name = s"${uuid1}_2026-11-01T00:00:00+00:00.ocs"
        val res  = node(pair(incoming, name, minutesAgo(3 * 24 * 60)))
        assertTrue(res.tooOld.size == 1, res.toClean.size == 2, res.youngest.isEmpty, res.readyToProcessOne.isEmpty)
      },
      test("an inventory is too old only when its newest file is") {
        val name = s"${uuid1}_2026-11-01T00:00:00+00:00.ocs"
        val res  = node(
          List(
            InventoryFileInfo(incoming / name, minutesAgo(3 * 24 * 60)),
            InventoryFileInfo(incoming / s"${name}.sign", minutesAgo(10))
          )
        )
        assertTrue(res.tooOld.isEmpty, res.youngest.map(_.name) == Some(name))
      },
      test("files of other nodes are ignored") {
        val res = node(pair(incoming, s"${uuid2}_2026-11-12T00:00:00+00:00.ocs", minutesAgo(10)))
        assertTrue(res.youngest.isEmpty, res.toClean.isEmpty, res.toResubmit.isEmpty)
      }
    ),
    suite("periodic scan")(
      test("nodes are classified independently") {
        val res = classify(
          pair(incoming, s"${uuid1}_2026-11-12T00:00:00+00:00.ocs", minutesAgo(10)) :::
          pair(incoming, s"${uuid2}_2026-11-11T00:00:00+00:00.ocs", minutesAgo(10))
        )
        assertTrue(res.size == 2, res.forall(_.youngest.nonEmpty), res.forall(_.toClean.isEmpty))
      },
      test("only the youngest inventory of a node is re-submitted, the older ones are deleted") {
        val older   = s"${uuid1}_2026-11-12T00:00:00+00:00.ocs"
        val younger = s"${uuid1}_2026-11-12T06:00:00+00:00.ocs"
        val res     = classify(pair(incoming, older, minutesAgo(600)) ::: pair(updates, younger, minutesAgo(300)))
        assertTrue(
          names(res.flatMap(_.toResubmit)) == Set(
            s"accepted-nodes-updates/${younger}",
            s"accepted-nodes-updates/${younger}.sign"
          ),
          names(res.flatMap(_.toClean.map(_.file))) == Set(s"incoming/${older}", s"incoming/${older}.sign")
        )
      }
    ),
    suite("listing")(
      test("listNode returns the inventory files of the node at the top of the dirs only") {
        val root = File.newTemporaryDirectory("rudder-test-inventory-list-")
        val inc  = (root / "incoming").createDirectories()
        val upd  = (root / "accepted-nodes-updates").createDirectories()
        val n1   = s"${uuid1}_2026-11-12T00:00:00+00:00.ocs"
        (for {
          _   <- ZIO.attempt {
                   (inc / n1).write("inv")
                   (inc / s"${n1}.sign").write("sig")
                   (upd / s"${uuid1}_2026-11-12T06:00:00+00:00.ocs.gz").write("gz")
                   (inc / s"${uuid1}.log").write("not an inventory")
                   (inc / s"${uuid2}_2026-11-12T00:00:00+00:00.ocs").write("other node")
                   ((inc / "sub").createDirectories() / s"${uuid1}_2026-11-12T07:00:00+00:00.ocs").write("in a sub dir")
                 }
          res <- InventoryFiles.listNode(List(inc, upd), uuid1)
        } yield assertTrue(
          names(res.map(_.file)) == Set(
            s"incoming/${n1}",
            s"incoming/${n1}.sign",
            s"accepted-nodes-updates/${uuid1}_2026-11-12T06:00:00+00:00.ocs.gz"
          )
        )).ensuring(ZIO.succeed(root.delete(swallowIOExceptions = true)))
      },
      test("a missing dir is skipped, the other ones are listed") {
        val root = File.newTemporaryDirectory("rudder-test-inventory-list-")
        val inc  = (root / "incoming").createDirectories()
        val n1   = s"${uuid1}_2026-11-12T00:00:00+00:00.ocs"
        (for {
          _   <- ZIO.attempt((inc / n1).write("inv"))
          res <- InventoryFiles.listNode(List(root / "missing", inc), uuid1)
        } yield assertTrue(res.map(_.file.name) == List(n1))).ensuring(ZIO.succeed(root.delete(swallowIOExceptions = true)))
      }
    ),
    suite("watcher")(
      test("a restarted watcher processes files added while stopped, and new files") {
        val root = File.newTemporaryDirectory("rudder-test-inventory-watcher-")
        val inc  = (root / "incoming").createDirectories()
        val upd  = (root / "accepted-nodes-updates").createDirectories()

        // poll until the watcher submitted the file, 10 s max
        def submitted(seen: Ref[Set[String]], name: String): UIO[Boolean] = {
          seen.get.map(_.contains(name)).repeatUntil(identity).timeout(10.seconds).map(_.getOrElse(false))
        }

        (for {
          seen         <- Ref.make(Set.empty[String])
          handler       = new HandleIncomingInventoryFile {
                            override def addFilePure(file: File): IOResult[Unit] = seen.update(_ + file.name)
                            override def addFile(file:     File): Unit           = addFilePure(file).runNow
                          }
          watcher       = new InventoryFileWatcher(
                            handler,
                            inc.pathAsString,
                            upd.pathAsString,
                            InventoryAgeLimits(purgeAfter = 2.days, waitForSignature = 3.minutes),
                            5.minutes
                          )
          _            <- watcher.startWatcher()
          _            <- watcher.stopWatcher()
          _            <- ZIO.attempt((inc / s"${uuid1}_2026-11-12T00:00:00+00:00.ocs").write("whilestopped"))
          _            <- watcher.restartWatcher()
          whileStopped <- submitted(seen, s"${uuid1}_2026-11-12T00:00:00+00:00.ocs")
          _            <- ZIO.attempt((inc / s"${uuid2}_2026-11-12T00:00:00+00:00.ocs").write("afterrestart"))
          afterRestart <- submitted(seen, s"${uuid2}_2026-11-12T00:00:00+00:00.ocs")
          running      <- watcher.ref.get.map(_.nonEmpty)
          _            <- watcher.stopWatcher()
        } yield assertTrue(whileStopped, afterRestart, running))
          .ensuring(effectUioUnit(if (java.lang.System.getProperty("tests.clean.tmp") != "false") {
            FileUtils.deleteDirectory(root.toJava)
          }))
      } @@ TestAspect.withLiveClock
    )
  )
}
