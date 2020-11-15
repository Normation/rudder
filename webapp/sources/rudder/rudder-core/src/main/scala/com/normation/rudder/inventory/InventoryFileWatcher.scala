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

package com.normation.rudder.inventory

import java.io.InputStream
import java.nio.file.ClosedWatchServiceException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.util.concurrent.TimeUnit

import better.files._
import com.normation.errors.IOResult
import com.normation.errors.RudderError
import com.normation.inventory.domain.InventoryProcessingLogger
import com.normation.zio.ZioRuntime

import scala.concurrent.ExecutionContext
import zio._
import zio.syntax._
import zio.duration._
import com.normation.zio._
import org.joda.time.DateTime
import zio.clock.Clock

final class Watchers(incoming: FileMonitor, updates: FileMonitor) {
  def start(): IOResult[Unit] = {
    IOResult.effect {
      // Execution context is not used here - binding to scala default global to satisfy scalac
      incoming.start()(scala.concurrent.ExecutionContext.global)
      updates.start()(scala.concurrent.ExecutionContext.global)
      Right(())
  }
  }
  def stop(): IOResult[Unit] = {
    IOResult.effect {
      incoming.close()
      updates.close()
      Right(())
    }
  }
}
object Watchers {

  val inventoryExtentions = "gz" :: "xml" :: "ocs" :: "sign" :: Nil
  def hasValidInventoryExtension(file : File) = {
    val ext = file.extension(includeDot = false, includeAll = false).getOrElse("")
    inventoryExtentions.contains(ext)
  }
  def apply(incoming: File, updates: File, checkProcess: File => Unit): Watchers = {
    def newWatcher(directory: File): FileMonitor = {
      new FileMonitor(directory, recursive = false) {
        val maxDepth = 0 // see parent class for logic, needed to minimize change in overridden `process` method

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
          if( hasValidInventoryExtension(file) ) {
            checkProcess(file)
          } else {
            val ext = file.extension(includeDot = false, includeAll = false).getOrElse("")
            InventoryProcessingLogger.logEffect.debug(s"watcher ignored file ${file.name} (unrecognized extension: '${ext}')")
          }
        }

        // When we call "stop" on the FileMonitor, it always throws a ClosedWatchServiceException.
        // It seems to be because the "run" in start continue to try to execute its
        // action on the stop watcher. We need to catch that.
        override def start()(implicit executionContext: ExecutionContext): Unit = {
          (
            (
              IOResult.effect(this.watch(root, 0)) *>
              (for {
                k <- IOResult.effect(service.take())
                _ <- IOResult.effect(process(k))
              } yield ()).forever
            ).catchAll(err =>
              err.cause match {
                case ex: ClosedWatchServiceException if(stopRequired) => //ignored
                  InventoryProcessingLogger.debug(s"Exception ClosedWatchServiceException ignored because watcher is stopping")
                case _ =>
                  InventoryProcessingLogger.error(s"Error when processing inventory: ${err.fullMsg}")
              }
            )
          ).forkDaemon.runNow
        }

        // This is a copy/paste of parent method, we just add a check for null before using `event.context`.
        // See https://issues.rudder.io/issues/14991 and https://github.com/pathikrit/better-files/pull/392
        override def process(key: WatchKey) = {
          val path = key.watchable().asInstanceOf[Path]

          import scala.jdk.CollectionConverters._
          key.pollEvents().asScala foreach {
            case event: java.nio.file.WatchEvent[Path] @unchecked if event.context() != null =>
              val target: File = path.resolve(event.context())
              if (reactTo(target)) {
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                  val depth = root.relativize(target).getNameCount
                  watch(target, (maxDepth - depth) max 0) // auto-watch new files in a directory
                }
                onEvent(event.kind(), target, event.count())
              }
            case event => if (reactTo(path)) onUnknownEvent(event)
          }
          key.reset()
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

/*
 * A class that periodically add inventories that we likely missed by inotify
 */
class SchedulerMissedNotify(
    directories: List[File]
  , addFiles   : List[File] => UIO[Unit]
  , period     : Duration
  , zclock     : Clock
){

  /*
   * List all files that need to be add again - simply all files older than period
   */
  def listFiles(d: Duration): UIO[List[File]] = {
    IOResult.effect{
      val ageLimit = DateTime.now().minusMillis(d.toMillis.toInt)
      val filter = (f:File) => {
        Watchers.hasValidInventoryExtension(f) && (f.isRegularFile && ageLimit.isAfter(f.lastModifiedTime.toEpochMilli))
      }
      directories.flatMap(_.collectChildren(filter).toList)
    }.catchAll(err =>
      InventoryProcessingLogger.error(s"Error when looking for old inventories that weren't processed: ${err.fullMsg}")*>
      Nil.succeed
    )
  }


  val schedule = {
    def loop(d: Duration) = for {
      files <- listFiles(d)
      _     <- ZIO.when(files.nonEmpty) {
                 InventoryProcessingLogger.debug(s"Found old inventories: ${files.map(_.pathAsString).mkString(", ")}")
               }
      _     <- addFiles(files)
      _     <- UIO.unit.delay(period)
    } yield ()

    (loop(Duration.Zero) *> loop(period).forever).forkDaemon.provide(zclock)
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
  , waitForSig           : Duration
  , sigExtension         : String // signature file extension, most likely ".sign"
  , collectOldInventories: Duration // how often you check for old inventories
) {

  def logDirPerm(dir: File, name: String) = {
    if(dir.isDirectory && dir.isWriteable) {
      InventoryProcessingLogger.logEffect.debug(s"${name} inventories directory [ok]: ${dir.pathAsString}")
    } else {
      InventoryProcessingLogger.logEffect.error(s"${name} inventories directory: ${dir.pathAsString} is not writable. Please check existence and file permission.")
    }
  }

  val semaphore = Semaphore.make(1).runNow



  val incoming = File(incomingInventoryPath)
  logDirPerm(incoming, "Incoming")
  val updated = File(updatedInventoryPath)
  logDirPerm(updated, "Accepted nodes updates")
  val received = File(receivedInventoryPath)
  logDirPerm(received, "Received")
  val failed   = File(failedInventoryPath)
  logDirPerm(failed, "Failed")

  // the cron that look for old files (or existing ones on startup/watcher restart)
  val cronForMissed = new SchedulerMissedNotify(
      incoming :: updated :: Nil
    , files => ZIO.foreach_(files)(fileProcessor.addFilePure)
    , collectOldInventories
    , ZioRuntime.environment
  )
  // service that will actually process files (queue for event from inotify/cron for missed files)
  val fileProcessor = new ProcessFile(inventoryProcessor, received, failed, waitForSig, sigExtension)
  // That reference holds watcher instance and scheduler fiber to be able to stop them if asked
  val ref = RefM.make(Option.empty[(Watchers,Fiber[Nothing, Nothing])]).runNow
  def startWatcher() = semaphore.withPermit(
    ref.update(opt =>
      opt match {
        case Some(_) => // does nothing
          InventoryProcessingLogger.info(s"Starting incoming inventory watcher ignored (already started).") *>
          opt.succeed

        case None    =>
          val w = Watchers(incoming, updated, fileProcessor.addFile)
          (for {
            _ <- w.start()
                 // start scheduler for old file, it will take care of inventories
            s <- cronForMissed.schedule
            _ <- InventoryProcessingLogger.info(s"Incoming inventory watcher started - process existing inventories")
          } yield Some((w,s)) ).catchAll(err =>
            InventoryProcessingLogger.error(s"Error when trying to start incoming inventories file watcher. Reported exception was: ${err.fullMsg}") *>
            err.fail
          )
      }
    )
  ).either.runNow

  def stopWatcher() = semaphore.withPermit(
    ref.update(opt =>
      opt match {
        case None    => //ok
          InventoryProcessingLogger.info(s"Stopping incoming inventory watcher ignored (already stopped).")*>
          None.succeed

        case Some((w, f)) =>
          (for {
            _ <- ZIO.collectAll(w.stop() :: f.interrupt :: Nil)
            _ <- InventoryProcessingLogger.info(s"Incoming inventory watcher stopped")
          } yield None).catchAll(err =>
              InventoryProcessingLogger.error(s"Error when trying to stop incoming inventories file watcher. Reported exception was: ${err.fullMsg}.") *>
              // in all case, remove the previous watcher, it's most likely in a dead state
              err.fail
          )
      }
    )
  ).either.runNow
}


sealed trait WatchEvent
object WatchEvent {
  // file is written and so can be removed from Map
  final case class End(file: File) extends WatchEvent
  // file is currently modified, avoid processing
  final case class Mod(file: File) extends WatchEvent
}


final case class SaveInventoryInfo(
    fileName          : String
  , inventoryStream   : () => InputStream
  , optSignatureStream: Option[() => InputStream]
  , testExits         : UIO[Boolean]
)

/*
 * Process file: interpret the stream of file modification events to decide when a
 * file is completely written, and if so, should it be sent to the backend processor.
 * For that, it needs signature and inventory file.
 */
class ProcessFile(
    inventoryProcessor: InventoryProcessor
  , received          : File
  , failed            : File
  , waitForSig        : Duration
  , sigExtension      : String // signature file extension, most likely ".sign"
) {
  val sign = if(sigExtension.charAt(0) == '.') sigExtension else "."+sigExtension

  // time after the last mod event after which we consider that a file is written
  val fileWrittenThreshold = Duration(500, TimeUnit.MILLISECONDS)

  /*
   * We need to track file that are currently processed by the backend, which can be quite long,
   * so that if we receive an second inventory for the same node, we wait before processing it.
   */
  val locks = ZioRuntime.unsafeRun(zio.Ref.make(Set[String]()))

  /*
   * This is the map of be processed file based on received events.
   * Each time we receive a new event, we cancel the previous one and replace by the new (for the same file).
   * The task is configured to be processed after some delay.
   * We only modify that map as a result of a dequeue event.
   */
  val toBeProcessed = ZioRuntime.unsafeRun(zio.RefM.make(Map.empty[File, Fiber[RudderError, Unit]]))

  /*
   * We need a queue of add file / file written even to delimit when a file should be
   * processed.
   * We are going to receive a stream of "write file xxx" event, and updating the map accordingly
   * (resetting the task). Once the task is started, we want to free space in the map, and so enqueue
   * a "file written" event.
   */
  type WatchQueue = ZQueue[Any, Nothing, Any, Nothing, WatchEvent, WatchEvent]
  val watchEventQueue = ZioRuntime.unsafeRun(Queue.bounded[WatchEvent](16384))

  /*
   * We have a second queue that is used as buffer for the step between "file ok to be processed"
   * and "saving file" because "saving file" implies parsing XML and doing other memory and CPU
   * inefficient tasks.
   * The buffer also allows to filter inventory for identical nodes to avoid having a command to parse an inventory
   * while it was already processed earlier in the buffer (and so its files were already moved).
   * As only a small structure is saved (`SaveInventory`: a name and two function to create stream from file), buffer
   * can be big.
   * When queue is full, we prefer to drop old `SaveInventory` to new ones. In the worst case, they will be catched up
   * by the SchedulerMissedNotify and put again in the WatchEvent queue.
   */
  protected val saveInventoryBuffer = ZQueue.sliding[(File, Option[File])](1024).runNow

  /*
   * The processing logic is to continually call sendToProcessorBlocking. When we
   * take a file, we  de-duplicate files in buffer queue with the same name (ie: remove them).
   * It likely means that we add several "whatch even: add" for that file (either copied several time, touched,
   * or inotify event emission not what we thought)
   */
  val saveInventoryBufferProcessing = {
    for {
      inv <- saveInventoryBuffer.take
      // deduplicate. TakeAll is not blocking
      all <- saveInventoryBuffer.takeAll
      _   <- saveInventoryBuffer.offerAll(all.filterNot { case (f, _) => inv._1.name == f.name })
      // process
      _ <- sendToProcessorBlocking(inv._1, inv._2)
    } yield ()
  }
  saveInventoryBufferProcessing.forever.forkDaemon.runNow

  def processMessage(): UIO[Unit] = {
    watchEventQueue.take.flatMap {
      case WatchEvent.End(file) => // simple case: remove file from map
        toBeProcessed.update[Any, Nothing](s =>
          (s - file).succeed
        ).unit

      case WatchEvent.Mod(file) =>
        // look if the file is already here. If so, interrupt. In all case, create a new task.

        (toBeProcessed.update { s =>
          val newMap = {
            // the new task must :
            // - wait for 500 ms for interruption
            // - then execute on different IO scheduler
            // - if the map wasn't interrupted in the first 500 ms, it is not interruptible anymore

            val effect = ZioRuntime.blocking(
                 UIO.unit.delay(fileWrittenThreshold).provide(ZioRuntime.environment)
              *> watchEventQueue.offer(WatchEvent.End(file))
              *> processFile(file, locks).uninterruptible
            ).forkDaemon

            effect.map(e => s + (file -> e))
          }

          s.get(file) match {
            case None =>
              newMap
            case Some(existing) =>
              existing.interrupt.unit *> newMap
          }
        }).unit
    }
  }

  //start the process
  ZioRuntime.internal.unsafeRunSync(processMessage().forever.forkDaemon)

  def addFilePure(file: File): UIO[Unit] = {
    watchEventQueue.offer(WatchEvent.Mod(file)).unit
  }

  def addFile(file: File): Unit = {
    ZioRuntime.internal.unsafeRunSync(addFilePure(file))
  }


  /*
   * This is a blocking action, only call it with a non-blocking buffer in front of it.
   */

  protected def sendToProcessorBlocking(inventory: File, signature: Option[File]): UIO[Unit] = {
    // we don't manage race condition very well, so we have cases where
    // we can have two things trying to move
    def safeMove[T](chunk: =>T): IOResult[Unit] = {
      Task.effect{chunk ; ()}.catchAll {
        case ex: NoSuchFileException => // ignore
          InventoryProcessingLogger.debug(s"Ignored exception '${ex.getClass.getSimpleName} ${ex.getMessage}'. The file '${inventory.pathAsString}' was correctly handled.")
        case ex                      =>
          InventoryProcessingLogger.error(s"Exception caught when processing inventory file '${inventory.pathAsString}': ${ex.getClass.getSimpleName} ${ex.getMessage}")
      }
    }

    (for {
      processed <- inventoryProcessor.saveInventoryBlocking(SaveInventoryInfo(
                       inventory.name
                     , () => inventory.newInputStream
                     , signature.map(s => () => s.newInputStream)
                     , IOResult.effect(inventory.exists).orElseSucceed(false)
                   )).either
      move      <- processed match {
                     case Right(InventoryProcessStatus.Accepted(_,_)) =>
                       //move to received dir
                       safeMove(signature.map(s => s.moveTo(received / s.name)(File.CopyOptions(overwrite = true)))) *>
                       safeMove(inventory.moveTo(received / inventory.name)(File.CopyOptions(overwrite = true)))
                     case Right(x) =>
                       safeMove(signature.map(s => s.moveTo(failed / s.name)(File.CopyOptions(overwrite = true)))) *>
                       safeMove(inventory.moveTo(failed / inventory.name)(File.CopyOptions(overwrite = true)))
                     case Left(x) =>
                       safeMove(signature.map(s => s.moveTo(failed / s.name)(File.CopyOptions(overwrite = true)))) *>
                       safeMove(inventory.moveTo(failed / inventory.name)(File.CopyOptions(overwrite = true)))
                   }
    } yield ()).catchAll(err =>
      InventoryProcessingLogger.error(err.fullMsg)
    )
  }

  /*
   * The logic is:
   * - when a file is created, we look if it's a sig or an inventory (ends by .sign or not)
   * - if its an inventory, look for the corresponding sign. If available process, else
   *   register a timeout action that process after waitForSig duration.
   * - if its a signature, look for the corresponding inventory. If available, remove possible action
   *   timeout and process, else do nothing.
   */
  def processFile(file: File, locks: zio.Ref[Set[String]]): ZIO[Any, RudderError, Unit] = {
    // the ZIO program
    val prog = for {
        _ <- InventoryProcessingLogger.trace(s"Processing new file: ${file.pathAsString}")
      // We need to only try to do things on fully-written file.
      // The canonical way seems to be to try to get a write lock on the file and see if
      // it works. We assume that once we successfully get the lock, it means that
      // the file is written (so we can immediately release it).
      _   <- if(file.name.endsWith(".gz")) {
                val dest = File(file.parent, file.nameWithoutExtension(includeAll = false))
                IOResult.effect {
                  file.unGzipTo(dest)
                  file.delete()
                }
              } else if(file.name.endsWith(sign)) { // a signature
                val inventory = File(file.parent, file.nameWithoutExtension(includeAll = false))

                if(inventory.exists) {
                  // process !
                  InventoryProcessingLogger.info(s"Watch new inventory file '${inventory.name}' with signature available: process.") *>
                  saveInventoryBuffer.offer((inventory, Some(file)))
                } else {
                  InventoryProcessingLogger.debug(s"Watch incoming signature file '${file.pathAsString}' but no corresponding inventory available: waiting")
                }
              } else { // an inventory
                val signature = File(file.pathAsString+sign)
                if(signature.exists) {
                  // process !
                  InventoryProcessingLogger.info(s"Watch new inventory file '${file.name}' with signature available: process.") *>
                  saveInventoryBuffer.offer((file, Some(signature)))
                } else { // wait for expiration time and exec without signature
                  InventoryProcessingLogger.debug(s"Watch new inventory file '${file.name}' without signature available: wait for it ${waitForSig.asJava.getSeconds}s before processing.") *>
                  //check that the inventory file is still there and that the signature didn't come while waiting
                  (for {
                    exists <- IOResult.effect(file.exists).orElseSucceed(false)
                    sig    <- IOResult.effect(signature.exists).orElseSucceed(false)
                    _      <- (exists, sig) match {
                      case (false, _    ) => ().succeed // do nothing
                      case (true , false) => saveInventoryBuffer.offer((file, None))
                      case (true , true ) => saveInventoryBuffer.offer((file, Some(signature)))
                    }
                  } yield ()).delay(waitForSig)
                }
              }
    } yield ()

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
               prog *>
               locks.update(current => current - lockName)
             } else {
               InventoryProcessingLogger.debug(s"Not starting process of '${file.name}': already processing.")
             }
    } yield {
      ()
    }

    // run program for that input file.
    lockProg.provide(ZioRuntime.environment)
  }
}
