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

import java.nio.file.ClosedWatchServiceException

import better.files._
import net.liftweb.common.Full
import monix.execution.Scheduler._

import scala.concurrent.ExecutionContext
import scala.concurrent.blocking
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal


final class Watchers(incoming: FileMonitor, updates: FileMonitor) {
  def start()(implicit executionContext: ExecutionContext): Either[Throwable, Unit] = {
    try {
      incoming.start()
      updates.start()
      Right(())
    } catch {
      case NonFatal(ex) => Left(ex)
    }
  }
  def stop(): Either[Throwable, Unit] = {
    try {
      incoming.close()
      updates.close()
      Right(())
    } catch {
      case NonFatal(ex) =>
        Left(ex)
    }
  }
}

object Watchers {
  def apply(incoming: File, updates: File, processFile: File => Unit): Watchers = {
    def newWatcher(directory: File): FileMonitor = {
      new FileMonitor(directory, recursive = false) {
        private var stopRequired = false

        // we need *only* "modify" to get both create and when file
        // already exists and overwrite. If we also overwrite "onCreate", we
        // get TWO notification for each file creation :/
        override def onModify(file: File, count: Int): Unit = processFile(file)

        // When we call "stop" on the FileMonitor, it always throws a ClosedWatchServiceException.
        // It seems to be because the "run" in start continue to try to execute its
        // action on the stop watcher. We need to catch that.
        override def start()(implicit executionContext: ExecutionContext) = {
          this.watch(root, 0)
          executionContext.execute(new Runnable {
            override def run() = {
              try {
                blocking { Iterator.continually(service.take()).foreach(process) }
              } catch {
                case ex: ClosedWatchServiceException if(stopRequired) => //ignored
                  InventoryLogger.debug(s"Exception ClosedWatchServiceException ignored because watcher is stopping")
              }
            }
          })
        }
        override def close() = {
          stopRequired = true
          service.close()
        }
      }
    }
    new Watchers(newWatcher(incoming), newWatcher(updates))
  }
}

/**
 * That class is in charge of watching file creation on a repos and process
 * inventory file as soon as an inventory+signature file is created.
 */
class InventoryFileWatcher(
    inventoryProcessor   : InventoryProcessor
  , incomingInventoryPath: String
  , updatedInventoryPath : String
  , receivedInventoryPath: String
  , failedInventoryPath  : String
  , waitForSig           : FiniteDuration
  , sigExtension         : String // signature file extension, most likely ".sign"
) {

  def logDirPerm(dir: File, name: String) = {
    if(dir.isDirectory && dir.isWriteable) {
      InventoryLogger.debug(s"${name} inventories directory [ok]: ${dir.pathAsString}")
    } else {
      InventoryLogger.error(s"${name} inventories directory: ${dir.pathAsString} is not writable. Please check existense and file permission.")
    }
  }

  val sign = if(sigExtension.charAt(0) == '.') sigExtension else "."+sigExtension

  val incoming = File(incomingInventoryPath)
  logDirPerm(incoming, "Incoming")
  val updated = File(updatedInventoryPath)
  logDirPerm(updated, "Accepted nodes updates")
  val received = File(receivedInventoryPath)
  logDirPerm(received, "Received")
  val failed   = File(failedInventoryPath)
  logDirPerm(failed, "Failed")

  implicit val scheduler = monix.execution.Scheduler.io("inventory-wait-sig")

  var watcher = Option.empty[Watchers]

  def startWatcher(): Either[Throwable, Unit] = this.synchronized {
    watcher match {
      case Some(w) => // does nothing
         InventoryLogger.info(s"Starting incoming inventory watcher ignored (already started).")
        Right(())

      case None    =>
        val w = Watchers(incoming, updated, processFile)
        w.start() match {
          case Right(()) =>
            InventoryLogger.info(s"Incoming inventory watcher started - process existing inventories")
            // a touch on all files should trigger a "onModify"
            incoming.collectChildren(_.isRegularFile).toList.map(_.touch())
            updated.collectChildren(_.isRegularFile).toList.map(_.touch())
            watcher = Some(w)
            Right(())
          case Left(ex) =>
            InventoryLogger.error(s"Error when trying to start incoming inventories file watcher. Reported exception was: ${ex.getMessage()}")
            Left(ex)
        }
    }
  }

  def stopWatcher(): Either[Throwable, Unit] = this.synchronized {
    watcher match {
      case None    => //ok
        InventoryLogger.info(s"Stoping incoming inventory watcher ignored (already stoped).")
        Right(())
      case Some(w) =>
        w.stop() match {
          case Right(()) =>
            InventoryLogger.info(s"Incoming inventory watcher stoped")
            watcher = None
            Right(())
          case Left(ex) =>
            InventoryLogger.error(s"Error when trying to stop incoming inventories file watcher. Reported exception was: ${ex.getMessage()}.")
            // in all case, remove the previous watcher, it's most likely in a dead state
            watcher = None
            Left(ex)
        }
    }
  }

  /*
   * The logic is:
   * - when a file is created, we look if it's a sig or an inventory (ends by .sign or not)
   * - if its an inventory, look for the corresponding sign. If available process, else
   *   register a timeout action that process after waitForSig duration.
   * - if its a signature, look for the corresponding inventory. If available, remove possible action
   *   timeout and process, else do nothing.
   */
  def processFile(file: File): Unit = {
    InventoryLogger.trace(s"Processing new file: ${file.pathAsString}")
    if(file.name.endsWith(sign)) { // a signature
      val p = file.pathAsString
      val inventory = File(p.substring(0, p.length - sign.size))
      if(inventory.exists && inventory.isRegularFile) {
        // process !
        InventoryLogger.info(s"Watch new inventory file '${inventory.name}' with signature available: process.")
        sendToProcessor(inventory, Some(file))
      } else {
        InventoryLogger.debug(s"Watch incoming signature file '${file.pathAsString}' but no corresponding inventory available: waiting")
      }
    } else { // an inventory
      val signature = File(file.pathAsString+sign)
      if(signature.exists) {
        // process !
        InventoryLogger.info(s"Watch new inventory file '${file.name}' with signature available: process.")
        sendToProcessor(file, Some(signature))
      } else { // wait for expiration time and exec without signature
        InventoryLogger.debug(s"Watch new inventory file '${file.name}' without signature available: wait for it ${waitForSig.toSeconds}s before processing.")
        scheduler.scheduleOnce(waitForSig) {
          //check that the inventory file is still there and that the signature didn't come while waiting
          if(!signature.exists && file.exists) {
            sendToProcessor(file, None)
          }
        }
      }
    }
  }

  def sendToProcessor(inventory: File, signature: Option[File]): Unit = {
    // we don't manage race condition very well, so we have cases where
    // we can have two things trying to move
    def safeMove[T](chunk: =>T): Unit = {
      try {
        chunk
      } catch {
        case ex: NoSuchElementException => // ignore
          InventoryLogger.debug(s"Ignored exception '${ex.getClass.getSimpleName} ${ex.getMessage}'. The was already deleted.")
      }
    }
    inventoryProcessor.saveInventory(() => inventory.newInputStream, inventory.name, signature.map(s => () => s.newInputStream)) match {
      case Full(InventoryProcessStatus.Accepted(report)) =>
        //move to received dir
        safeMove(signature.map(s => s.moveTo(received / s.name, overwrite = true)))
        safeMove(inventory.moveTo(received / inventory.name, overwrite = true))
      case _ => // error (no need to log: already done in the processor)
        safeMove(signature.map(s => s.moveTo(failed / s.name, overwrite = true)))
        safeMove(inventory.moveTo(failed / inventory.name, overwrite = true))
    }
  }

}
