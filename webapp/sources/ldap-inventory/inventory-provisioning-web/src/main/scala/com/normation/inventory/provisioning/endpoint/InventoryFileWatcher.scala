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
import java.nio.file.NoSuchFileException

import better.files._
import com.normation.errors.IOResult
import com.normation.zio.ZioRuntime
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.Scheduler._
import monix.execution.cancelables.SerialCancelable

import scala.concurrent.ExecutionContext
import scala.concurrent.blocking
import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps
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

  def apply(incoming: File, updates: File, checkProcess: File => Unit): Watchers = {
    def newWatcher(directory: File): FileMonitor = {
      new FileMonitor(directory, recursive = false) {
        private var stopRequired = false

        /*
         * when a file is written, depending about how it is handle, by what process, and at which rate,
         * we can have form 1 create only to 1 create and lots of modify events.
         * And there is no "file finally close" event (see LinuxWatchService implementation to be sur).
         * So we need two things:
         * - one thing to wait for a file to be available for write
         * - one thing to filter out events for
         * This is handled by ProcessFile object
         */
        override def onCreate(file: File, count: Int): Unit = onModify(file, count)
        override def onModify(file: File, count: Int): Unit = {
          // we want to avoid processing some file
          val ext = file.extension(includeDot = false, includeAll = false).getOrElse("")
          if( ext == "gz" || ext == "xml" || ext == "ocs" || ext == "sign") {
            checkProcess(file)
          } else {
            InventoryProcessingLogger.logEffect.debug(s"watcher ignored file ${file.name} (unrecognized extension: '${ext}')")
          }
        }

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
                  InventoryProcessingLogger.logEffect.debug(s"Exception ClosedWatchServiceException ignored because watcher is stopping")
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
      InventoryProcessingLogger.logEffect.debug(s"${name} inventories directory [ok]: ${dir.pathAsString}")
    } else {
      InventoryProcessingLogger.logEffect.error(s"${name} inventories directory: ${dir.pathAsString} is not writable. Please check existense and file permission.")
    }
  }


  val incoming = File(incomingInventoryPath)
  logDirPerm(incoming, "Incoming")
  val updated = File(updatedInventoryPath)
  logDirPerm(updated, "Accepted nodes updates")
  val received = File(receivedInventoryPath)
  logDirPerm(received, "Received")
  val failed   = File(failedInventoryPath)
  logDirPerm(failed, "Failed")

  implicit val scheduler = monix.execution.Scheduler.io("inventory-wait-sig")
  val fileProcessor = new ProcessFile(inventoryProcessor, received, failed, waitForSig, sigExtension)

  var watcher = Option.empty[Watchers]

  def startWatcher(): Either[Throwable, Unit] = this.synchronized {
    watcher match {
      case Some(w) => // does nothing
        InventoryProcessingLogger.logEffect.info(s"Starting incoming inventory watcher ignored (already started).")
        Right(())

      case None    =>
        val w = Watchers(incoming, updated, fileProcessor.addFile)
        w.start() match {
          case Right(()) =>
            InventoryProcessingLogger.logEffect.info(s"Incoming inventory watcher started - process existing inventories")
            // a touch on all files should trigger a "onModify"
            incoming.collectChildren(_.isRegularFile).toList.map(_.touch())
            updated.collectChildren(_.isRegularFile).toList.map(_.touch())
            watcher = Some(w)
            Right(())
          case Left(ex) =>
            InventoryProcessingLogger.logEffect.error(s"Error when trying to start incoming inventories file watcher. Reported exception was: ${ex.getMessage()}")
            Left(ex)
        }
    }
  }

  def stopWatcher(): Either[Throwable, Unit] = this.synchronized {
    watcher match {
      case None    => //ok
        InventoryProcessingLogger.logEffect.info(s"Stoping incoming inventory watcher ignored (already stoped).")
        Right(())
      case Some(w) =>
        w.stop() match {
          case Right(()) =>
            InventoryProcessingLogger.logEffect.info(s"Incoming inventory watcher stoped")
            watcher = None
            Right(())
          case Left(ex) =>
            InventoryProcessingLogger.logEffect.error(s"Error when trying to stop incoming inventories file watcher. Reported exception was: ${ex.getMessage()}.")
            // in all case, remove the previous watcher, it's most likely in a dead state
            watcher = None
            Left(ex)
        }
    }
  }


}


class ProcessFile(
    inventoryProcessor: InventoryProcessor
  , received          : File
  , failed            : File
  , waitForSig        : FiniteDuration
  , sigExtension      : String // signature file extension, most likely ".sign"
)(implicit scheduler: Scheduler) {
  import scala.concurrent.duration._

  val sign = if(sigExtension.charAt(0) == '.') sigExtension else "."+sigExtension

  // accept that
  val locks = ZioRuntime.unsafeRun(scalaz.zio.Ref.make(Set[String]()))

  /*
   * This is the map of be processed file based on received events.
   * Each time we receive a new event, we cancel the previous one and replace by the new (for the same file).
   * The task is configured to be processed after some delay.
   *
   * That's a var, must be accessed synchroniously: only change it through `remove` and `addFile`
   */
  private var toBeProcessed = Map.empty[File, SerialCancelable]

  private def remove(file: File): Unit = synchronized {
    toBeProcessed = (toBeProcessed - file)
  }

  val newTask = (f:File) => Task (processFile(f, locks) ).delayExecution(500 millis).doOnFinish (_ => Task (remove(f)))

  def addFile(file: File): Unit =  synchronized {
    toBeProcessed.get(file) match {
      case None =>
          // the delay here is to let some time pass to let the file be fully written
          // the timeout is the max time allowed for file to be full written. Not that
          // these files will be proccessed by agent run latter.
          toBeProcessed = (toBeProcessed + (file -> SerialCancelable(newTask(file).runAsync)))

        case Some(ref) =>
          ref := newTask(file).runAsync

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
  def processFile(file: File, locks: scalaz.zio.Ref[Set[String]]): Unit = {
    // the part that deals with sending to processor and then deplacing files
    // where they belong
    import scalaz.zio.{scheduler => _, Task => ZioTask, _}
    import scalaz.zio.syntax._

    def sendToProcessor(inventory: File, signature: Option[File]): IOResult[Unit] = {
      // we don't manage race condition very well, so we have cases where
      // we can have two things trying to move
      def safeMove[T](chunk: =>T): IOResult[Unit] = {
        ZioTask.effect{chunk ; ()}.catchAll {
          case ex: NoSuchFileException => // ignore
            InventoryProcessingLogger.debug(s"Ignored exception '${ex.getClass.getSimpleName} ${ex.getMessage}'. The was file was correctly handled.")
          case ex                      =>
            InventoryProcessingLogger.error(s"Exception caught when processing inventory file '${file.pathAsString}': ${ex.getClass.getSimpleName} ${ex.getMessage}")
        }
      }

      for {
        processed <- inventoryProcessor.saveInventory(() => inventory.newInputStream, inventory.name, signature.map(s => () => s.newInputStream)).either
        move      <- processed match {
                       case Right(InventoryProcessStatus.Accepted(report)) =>
                         //move to received dir
                         safeMove(signature.map(s => s.moveTo(received / s.name, overwrite = true))) *>
                         safeMove(inventory.moveTo(received / inventory.name, overwrite = true))
                       case Right(x) =>
                         InventoryProcessingLogger.error(s"Error when processing inventory '${inventory.name}', status: ${x.getClass.getSimpleName}") *>
                         safeMove(signature.map(s => s.moveTo(failed / s.name, overwrite = true))) *>
                         safeMove(inventory.moveTo(failed / inventory.name, overwrite = true))
                       case Left(x) =>
                         InventoryProcessingLogger.error(x.fullMsg) *>
                         safeMove(signature.map(s => s.moveTo(failed / s.name, overwrite = true))) *>
                         safeMove(inventory.moveTo(failed / inventory.name, overwrite = true))
                     }
      } yield {
        ()
      }
    }

    // the ZIO program
    val prog = for {
        _ <- InventoryProcessingLogger.trace(s"Processing new file: ${file.pathAsString}")
      // We need to only try to do things on fully-written file.
      // The canocic way seems to be to try to get a write lock on the file and see if
      // it works. We assume that once we successfully get the lock, it means that
      // the file is written (so we can immediately release it).
      done <- if(file.name.endsWith(".gz")) {
                val dest = File(file.parent, file.nameWithoutExtension(includeAll = false))
                ZioTask.effect {
                  file.unGzipTo(dest)
                  file.delete()
                }
              } else if(file.name.endsWith(sign)) { // a signature
                val inventory = File(file.parent, file.nameWithoutExtension(includeAll = false))

                if(inventory.exists) {
                  // process !
                  InventoryProcessingLogger.info(s"Watch new inventory file '${inventory.name}' with signature available: process.") *>
                  sendToProcessor(inventory, Some(file))
                } else {
                  InventoryProcessingLogger.debug(s"Watch incoming signature file '${file.pathAsString}' but no corresponding inventory available: waiting")
                }
              } else { // an inventory
                val signature = File(file.pathAsString+sign)
                if(signature.exists) {
                  // process !
                  InventoryProcessingLogger.info(s"Watch new inventory file '${file.name}' with signature available: process.") *>
                  sendToProcessor(file, Some(signature))
                } else { // wait for expiration time and exec without signature
                  InventoryProcessingLogger.debug(s"Watch new inventory file '${file.name}' without signature available: wait for it ${waitForSig.toSeconds}s before processing.") *>
                  ZioTask.effect(scheduler.scheduleOnce(waitForSig) {
                    //check that the inventory file is still there and that the signature didn't come while waiting
                    if(!signature.exists && file.exists) {
                      sendToProcessor(file, None)
                    }
                  })
                }
              }
    } yield {
      done
    }

    final case class Todo(canProcess: Boolean, lockName: String)

    val lockName = if(file.name.endsWith(".gz") | file.name.endsWith(sign)) {
      file.nameWithoutExtension(includeAll = false)
    } else {
      file.name
    }

    // limit one parallel exec of the program for a given inventory
    val lockProg = for {
      td <-  locks.modify( current => (current.contains(lockName), current + lockName) ).map(b => Todo(!b, lockName))

      res <- if(td.canProcess) {
               prog
             } else {
               InventoryProcessingLogger.debug(s"Not starting process of '${file.name}': already processing.") *>
               locks.update(current => current - lockName)
             }
    } yield {
      ()
    }

    // run program for that input file.
    ZioRuntime.unsafeRun(lockProg)
  }
}
