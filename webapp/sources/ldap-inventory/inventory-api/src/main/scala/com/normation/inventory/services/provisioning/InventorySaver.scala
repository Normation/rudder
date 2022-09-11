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

package com.normation.inventory.services.provisioning

import com.normation.errors._
import com.normation.inventory.domain.Inventory
import com.normation.inventory.domain.InventoryProcessingLogger
import net.liftweb.common.Loggable
import zio._

/**
 *
 * This service is in charge to persist
 * parsed and merged inventories.
 *
 * In particular, this service has to deal with
 * consistency problems (no transaction on
 * LDAP backends...)
 *
 * Most likely, implementations of that service
 * have to be pipelined so that pre-, post-,
 * deffered- and triggered- action may be performed
 * (logging comes to mind)
 *
 * The R parameter is the return type of the back-end.
 * Ideally, it should be only diff actually applied to the back-end,
 * but it could be the new entity is the store can not provide
 * better information (LDAP can).
 */
trait InventorySaver[R] {

  def save(inventory: Inventory): IOResult[R]

}

/**
 * Propose a standard implementation type fory InventorySaver
 * based on pre- and post- process, and a "save" op in the
 * middle
 */

trait PipelinedInventorySaver[R] extends InventorySaver[R] with Loggable {

  val preCommitPipeline:  Seq[PreCommit]
  val postCommitPipeline: Seq[PostCommit[R]]

  /**
   * Here comes the logic to actually save change in the Directory
   */
  def commitChange(inventory: Inventory): IOResult[R]

  override def save(inventory: Inventory): IOResult[R] = {

    val t0 = System.currentTimeMillis

    for {
      /*
       * Firstly, we let the chance to third part contributor to
       * modify the inventory to be save, make additional synchro,
       * etc.
       *
       * An error here leads to the stop of the inventory saving
       * process, so be *really* careful about your error management
       */
      postPreCommitInventory <- ZIO.foldLeft(preCommitPipeline)(inventory) { (currentInventory, preCommit) =>
                                  (for {
                                    t0  <- UIO(System.currentTimeMillis)
                                    res <- preCommit(currentInventory).chainError(
                                             s"Error in preCommit pipeline with processor '${preCommit.name}', abort"
                                           )
                                    t1  <- UIO(System.currentTimeMillis)
                                    _   <- InventoryProcessingLogger.timing.trace(s"Precommit '${preCommit.name}': ${t1 - t0} ms")
                                  } yield {
                                    res
                                  }).chainError(s"Exception in preCommit pipeline with processor '${preCommit.name}}', abort")
                                }
      /*
       * commit change - no rollback !
       */

      t1 <- UIO(System.currentTimeMillis)
      _  <- InventoryProcessingLogger.timing.trace(s"Pre commit inventory: ${t1 - t0} ms")

      commitedChange <- commitChange(postPreCommitInventory).chainError("Exception when commiting inventory, abort.")

      t2 <- UIO(System.currentTimeMillis)
      _  <- InventoryProcessingLogger.timing.trace(s"Commit inventory: ${t2 - t1} ms")

      /*
       * now, post process inventory with third-party actions
       */
      postPostCommitInventory <- ZIO.foldLeft(postCommitPipeline)(commitedChange) { (currentChanges, postCommit) =>
                                   postCommit(postPreCommitInventory, currentChanges).chainError(
                                     s"Error in postCommit pipeline with processor '${postCommit.name}'. The commit was done, we may be in a inconsistent state."
                                   )
                                 }

      t3 <- UIO(System.currentTimeMillis)
      _  <- InventoryProcessingLogger.timing.trace(s"Post commit inventory: ${t3 - t2} ms")
    } yield {
      postPostCommitInventory
    }
  }

}
