/*
*************************************************************************************
* Copyright 2019 Normation SAS
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

package com.normation.inventory.provisioning.endpoint

import better.files.File
import com.normation.errors.IOResult
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import scalaz.zio.duration._
import scalaz.zio._
import scalaz.zio.syntax._
import com.normation.zio._
import net.liftweb.common.Loggable
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import org.specs2.specification.AfterAll


@RunWith(classOf[JUnitRunner])
class InventoryFileWatcherTest extends Specification with AfterAll with Loggable {

  // we use special name for reports
  val queueFull  = "inventory-queue-full.ocs"
  val invalidSig = "inventory-invalid-signature.ocs"
  val missingSig = "inventory-missing-signature.ocs"

  val result = Ref.make[Option[(String, InventoryProcessStatus)]](None).runNow


  val testDir = File(s"/tmp/rudder-test-inventory-${DateTime.now.toString(ISODateTimeFormat.dateTime())}")
  testDir.createDirectoryIfNotExists()
  val newInventories = testDir / "new"
  newInventories.createDirectoryIfNotExists()
  val processedSuccess = testDir / "success"
  processedSuccess.createDirectoryIfNotExists()
  val processedFailed  = testDir / "failure"
  processedFailed.createDirectoryIfNotExists()

  override def afterAll() = {
    if(System.getProperty("tests.clean.tmp") != "false") {
      logger.info("Deleting test directory " + testDir.pathAsString)
      testDir.delete(true)
    }
  }

  val saveInventory: SaveInventoryInfo => IOResult[InventoryProcessStatus] = { info =>
    import InventoryProcessStatus._
    val res = info.fileName match {
      case `queueFull`  => QueueFull(null)
      case `invalidSig` => SignatureInvalid(null)
      case `missingSig` => MissingSignature(null)
      case _            => Accepted(null)
    }

    result.set(Some((info.fileName, res))) *> res.succeed
  }

  val inventoryProcessor = new ProcessFile(saveInventory, processedSuccess, processedFailed, 1.second, ".sign")

  val state = RefM.make(Map[String, Fiber[Throwable, Unit]]()).runNow

  "An inventory with signature should be processed after 500 ms" >> {

    val inventory = newInventories / "inventory.ocs"
    inventory.appendText("A new inventory")
    val signature = newInventories / "inventory.ocs.sign"
    signature.appendText("signature content")

    inventoryProcessor.addFile(inventory)

    val res0 = result.get.runNow

    // the file is not processed if we keep adding it quicker than every 500 ms
    Thread.sleep(300)
    inventoryProcessor.addFile(inventory)
    Thread.sleep(300)
    inventoryProcessor.addFile(inventory)
    Thread.sleep(300)
    inventoryProcessor.addFile(inventory)
    val res1 = result.get.runNow

    // now, we really want to have it processed
    Thread.sleep(1000)

    val res2 = result.get.runNow

    (
      (processedSuccess / inventory.name).exists must beTrue
    ) and (
      (processedSuccess / signature.name).exists must beTrue
    ) and (
      res0 must beNone
    ) and (
      res1 must beNone
    ) and (
      res2 must beSome(beAnInstanceOf[(String, InventoryProcessStatus.Accepted)])
    )
  }
}
