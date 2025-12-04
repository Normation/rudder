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

import better.files.*
import com.normation.box.IOManaged
import com.normation.errors.*
import com.normation.inventory.domain.InventoryProcessingLogger
import com.normation.rudder.ports.InventoryFileWatcherPort
import com.normation.zio.*
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.file
import java.nio.file.ClosedWatchServiceException
import java.nio.file.StandardWatchEventKinds
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.TrueFileFilter
import scala.annotation.unused
import scala.concurrent.ExecutionContext
import zio.*
import zio.syntax.*

/*
 * This file manage incoming inventories, but not their processing.
 * Incoming inventories are handled by too ways:
 * - with an inotify watcher, to react to new inventories immediately
 * - with a periodic garbage collection, that handle old inventories that inotify may
 *   have missed, or added while Rudder was shut down.
 *   That garbage collector also handle incorrect inventories, for example in cases where
 *   only one of the inventory/signature file pair was received.
 *
 *   Hooks on incoming inventories are dealt with only on save (ie for valid pairs), for
 *   both success and failure case.
 *   There is no hook on garbage collection.
 *
 * The interface toward inventory saving is `ProcessInventoryService`
 */

// utility object to hold common utilities
object InventoryProcessingUtils {

  private val inventoryExtensions: List[String] = "gz" :: "xml" :: "ocs" :: "sign" :: Nil

  val signExtension = ".sign"

  def logDirPerm(dir: File, name: String): Unit = {
    if (dir.isDirectory && dir.isWritable) {
      InventoryProcessingLogger.logEffect.debug(s"${name} inventories directory [ok]: ${dir.pathAsString}")
    } else {
      InventoryProcessingLogger.logEffect.error(
        s"${name} inventories directory: ${dir.pathAsString} is not writable. Please check existence and file permission."
      )
    }
  }

  def hasValidInventoryExtension(file: File): Boolean = {
    val ext = file.extension(includeDot = false, includeAll = false).getOrElse("")
    inventoryExtensions.contains(ext)
  }

  def makeManagedStream(file: File, kind: String = "inventory"): IOManaged[InputStream] = IOManaged.makeM[InputStream] {
    IOResult.attempt(s"Error when trying to read ${kind} file '${file.name}'")(file.newInputStream)
  }(_.close())
  def makeFileExists(file: File): IOManaged[Boolean] = IOManaged.make[Boolean](file.exists)(_ => ())
}

/**
 * That class holds the different bits of inventory processing
 */
class InventoryFileWatcher(
    fileProcessor:         HandleIncomingInventoryFile,
    incomingInventoryPath: String,
    updatedInventoryPath:  String,
    maxOldInventoryAge:    Duration, // when do we delete old inventories still there

    collectOldInventoriesFrequency: Duration // how often you check for old inventories
) extends InventoryFileWatcherPort {

  private val takeCareOfUnprocessedFileAfter: Duration = 3.minutes

  private val incoming: File = File(incomingInventoryPath)
  InventoryProcessingUtils.logDirPerm(incoming, "Incoming")
  private val updated:  File = File(updatedInventoryPath)
  InventoryProcessingUtils.logDirPerm(updated, "Accepted nodes updates")

  // service for cleaning
  private val cleaner       = new CheckExistingInventoryFilesImpl(
    fileProcessor,
    incoming :: updated :: Nil,
    maxOldInventoryAge,
    takeCareOfUnprocessedFileAfter,
    InventoryProcessingUtils.hasValidInventoryExtension
  )
  // the cron that look for old files (or existing ones on startup/watcher restart)
  private val cronForMissed = new SchedulerMissedNotify(cleaner, collectOldInventoriesFrequency)

  def startGarbageCollection: Unit = {
    (for {
      // start scheduler for old file, it will take care of inventories
      _ <- cronForMissed.schedule
      _ <- InventoryProcessingLogger.info(s"Incoming inventory garbage collection started - process existing inventories")
    } yield ()).runNow
  }

  // That reference holds watcher instance to be able to stop them if asked
  val ref: Ref.Synchronized[Option[Watchers]] = Ref.Synchronized.make(Option.empty[Watchers]).runNow

  def startWatcher(): IOResult[Unit] = ref.updateZIO {
    case opt @ Some(_) => // does nothing
      InventoryProcessingLogger.info(s"Starting incoming inventory watcher ignored (already started).") *>
      opt.succeed

    case None =>
      val w = Watchers(incoming, updated, fileProcessor, cleaner)
      (for {
        _ <- w.start() // start scheduler for old file, it will take care of inventories
        _ <- InventoryProcessingLogger.info(s"Incoming inventory watcher started")
      } yield Some(w)).tapError { err =>
        InventoryProcessingLogger.error(
          s"Error when trying to start incoming inventories file watcher. Reported exception was: ${err.fullMsg}"
        )
      }
  }

  def restartWatcher(): IOResult[Unit] = ref.updateZIO {
    case None =>
      val w = Watchers(incoming, updated, fileProcessor, cleaner)
      (for {
        _ <- w.start() // start scheduler for old file, it will take care of inventories
        _ <- InventoryProcessingLogger.info(s"Incoming inventory watcher restarted")
      } yield Some(w)).tapError { err =>
        InventoryProcessingLogger.error(
          s"Error when trying to restart incoming inventories file watcher. Reported exception was: ${err.fullMsg}"
        )
      }

    case Some(w) =>
      (for {
        _ <- w.stop()
        _ <- InventoryProcessingLogger.info(s"Incoming inventory watcher stopped")
        _ <- w.start() // start scheduler for old file, it will take care of inventories
        _ <- InventoryProcessingLogger.info(s"Incoming inventory watcher restarted")
      } yield None).tapError(err => {
        InventoryProcessingLogger.error(
          s"Error when trying to restart incoming inventories file watcher. Reported exception was: ${err.fullMsg}."
        )
      })
  }

  override def stopWatcher(): IOResult[Unit] = ref.updateZIO {
    case None => // ok
      InventoryProcessingLogger.info(s"Stopping incoming inventory watcher ignored (already stopped).") *>
      None.succeed

    case Some(w) =>
      (for {
        _ <- w.stop()
        _ <- InventoryProcessingLogger.info(s"Incoming inventory watcher stopped")
      } yield None).tapError(err => {
        InventoryProcessingLogger.error(
          s"Error when trying to stop incoming inventories file watcher. Reported exception was: ${err.fullMsg}."
        )
      })
  }
}

/*
 * This class is the actual watcher for incoming files. It only manages
 * the inotify event generator, nothing else. inotify events are
 * processed in
 */
final class Watchers(incoming: FileMonitor, updates: FileMonitor) {
  def start(): IOResult[Unit] = {
    IOResult.attempt {
      // Execution context is not used here - binding to scala default global to satisfy scalac
      incoming.start()(using scala.concurrent.ExecutionContext.global)
      updates.start()(using scala.concurrent.ExecutionContext.global)
      Right(())
    }
  }
  def stop():  IOResult[Unit] = {
    IOResult.attempt {
      incoming.close()
      updates.close()
      Right(())
    }
  }
}
object Watchers                                                   {

  def apply(
      incoming: File,
      updates:  File,
      checkNew: HandleIncomingInventoryFile,
      checkOld: CheckExistingInventoryFiles
  ): Watchers = {
    def newWatcher(directory: File): FileMonitor = {
      new FileMonitor(directory, recursive = false) {
        private var stopRequired = false

        // a one element queue to tempo overflow events
        val tempoOverflow = ZioRuntime.unsafeRun(Queue.dropping[Unit](1))

        // process overflow
        val overflowFiber = ZioRuntime.unsafeRun((for {
          _ <- tempoOverflow.take
          // if we are overflowing, we got at least a couple hundred inventories. Wait one minute before continuing
          _ <- InventoryProcessingLogger.info(
                 "Inotify watcher event overflow: waiting a minute before checking what inventories need to be processed"
               )
          _ <- ZIO.unit.delay(1.minutes)
          // clean-up other overflow that happened during that time
          _ <- tempoOverflow.takeAll
          _ <- checkOld.checkFilesOlderThan(0.milli)
        } yield ()).forever.forkDaemon.provideLayer(ZioRuntime.layers))

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
          if (InventoryProcessingUtils.hasValidInventoryExtension(file)) {
            checkNew.addFile(file)
          } else {
            val ext = file.extension(includeDot = false, includeAll = false).getOrElse("")
            InventoryProcessingLogger.logEffect.debug(s"watcher ignored file ${file.name} (unrecognized extension: '${ext}')")
          }
        }

        override def onUnknownEvent(event: file.WatchEvent[?]): Unit = {
          event.kind() match {
            case StandardWatchEventKinds.OVERFLOW =>
              tempoOverflow.offer(()).runNow

            case _ =>
              InventoryProcessingLogger.logEffect.debug(
                s"Inotify sent an unknown event: ${event.getClass.getName}: ${event.kind()} ; ${event.context()}"
              )
          }
        }

        override def onException(exception: Throwable): Unit = {
          InventoryProcessingLogger.logEffect.error(
            s"Error with inotify inventory watcher. If new inventory are not immediately processed," +
            s" you may need to restart watcher (see https://docs.rudder.io/api/#tag/Inventories/operation/fileWatcherRestart):" +
            s"${exception.getClass.getName}: ${exception.getMessage}"
          )
        }

        // When we call "stop" on the FileMonitor, it always throws a ClosedWatchServiceException.
        // It seems to be because the "run" in start continue to try to execute its
        // action on the stop watcher. We need to catch that.
        override def start()(implicit @unused executionContext: ExecutionContext): Unit = {
          (
            (
              IOResult.attempt(this.watch(root, 0)) *>
              (for {
                k <- IOResult.attempt(service.take())
                _ <- IOResult.attempt(process(k))
              } yield ()).forever
            ).catchAll(err => {
              err.cause match {
                case ex: ClosedWatchServiceException if (stopRequired) => // ignored
                  InventoryProcessingLogger.debug(s"Exception ClosedWatchServiceException ignored because watcher is stopping")
                case _ =>
                  InventoryProcessingLogger.error(s"Error when processing inventory: ${err.fullMsg}")
              }
            })
          ).forkDaemon
            .runNow
        }

        override def close() = {
          ZioRuntime.unsafeRun(overflowFiber.interrupt)
          stopRequired = true
          service.close()
        }
      }
    }
    new Watchers(newWatcher(incoming), newWatcher(updates))
  }
}

/*
 * The service that need to be notified to check if a file need to be processed, be it from an event or
 * the garbage collector or anything else.
 * This event assume the file if fully written and avoid the inotify event queue.
 */
trait HandleIncomingInventoryFile {
  def addFilePure(file: File): IOResult[Unit]

  def addFile(file: File): Unit
}

/*
 * The garbage collection
 */

final case class ToClean(file: File, why: String)
final case class FilteredFiles(
    toIgnore: List[File],
    toClean:  List[ToClean],
    toKeep:   List[File]
)

// A class that filter and add back inventory files
trait CheckExistingInventoryFiles {
  def checkFilesOlderThan(d: Duration): UIO[Unit]
}

class CheckExistingInventoryFilesImpl(
    fileProcessor: HandleIncomingInventoryFile,
    directories:   List[File],
    purgeAfter:    Duration, // time after which old inventories are deleted

    waitingSignatureTime: Duration,
    hasValidExtension:    File => Boolean
) extends CheckExistingInventoryFiles {

  def processOldFiles(files: List[File]): UIO[Unit] = {
    val now           = Instant.now
    val purgeTime     = now.minus(purgeAfter)
    val orphanTime    = now.minus(waitingSignatureTime)
    val filteredFiles = filterFiles(purgeTime, orphanTime, files)

    deleteFiles(filteredFiles.toClean) *> addFiles(filteredFiles.toKeep)
  }

  def addFiles(files: List[File]): UIO[Unit] = {
    ZIO.foreachDiscard(files)(file => {
      fileProcessor
        .addFilePure(file)
        .catchAll(err =>
          InventoryProcessingLogger.error(s"Error when processing old inventory file '${file.path}': ${err.fullMsg}")
        )
    })
  }

  def deleteFiles(files: List[ToClean]): UIO[Unit] = {
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

  // we only want to keep:
  // - file without supported extensions
  // - then, we delete all inventories files older than our threshold
  // - and the one that are not in pair (if older than waitingSignatureTime
  def filterFiles(maxAgeGlobal: Instant, maxAgeOrphan: Instant, files: List[File]): FilteredFiles = {
    import com.softwaremill.quicklens.*
    val (filtered, pairs) = files.foldLeft((FilteredFiles(Nil, Nil, Nil), Map[String, List[File]]())) {
      case ((filteredFiles, pairs), file) =>
        if (hasValidExtension(file)) {
          val fileLastModTime = file.lastModifiedTime
          // if file is very recent, just ignore, it can still be processed
          if (maxAgeOrphan.isBefore(fileLastModTime)) { // too yound, ignore
            (filteredFiles.modify(_.toIgnore).using(file :: _), pairs)
          } else {
            // is file to old ?
            if (maxAgeGlobal.isAfter(fileLastModTime)) { // too old
              (
                filteredFiles
                  .modify(_.toClean)
                  .using(ToClean(file, s"Inventory file '${file.name}' is older than max duration before cleaning") :: _),
                pairs
              )
            } else {
              // add the file to file that need pairing. key is filename without extension
              val key     = file.nameWithoutExtension(true)
              val exiting = pairs.get(key) match {
                case None    => Nil
                case Some(l) => l
              }
              (filteredFiles, pairs + ((key, file :: exiting)))
            }
          }
        } else (filteredFiles.modify(_.toIgnore).using(file :: _), pairs)
    }

    // now, try to group potential pairs into actual pairs and remove orphans
    pairs.foldLeft(filtered) {
      case (filteredFinal, (filename, files)) =>
        files match {
          case Nil         => filteredFinal // should not happens
          case file :: Nil =>               // it's an old orphans, delete it
            filteredFinal
              .modify(_.toClean)
              .using(ToClean(file, s"Inventory file '${file.name}' is not an (inventory, signature) pair, cleaning") :: _)
          case files       =>
            // count signature files, it should have at least one and less than all
            val sigs = files.filter(_.name.contains(".sign"))
            val n    = sigs.length
            if (n > 0 && n < files.length) { // ok
              filteredFinal.modify(_.toKeep).using(files ::: _)
            } else { // we only have signatures or inventories, deleting
              files.foldLeft(filteredFinal) {
                case (c, file) =>
                  c.modify(_.toClean)
                    .using(ToClean(file, s"Inventory file '${file.name}' is not an (inventory, signature) pair, cleaning") :: _)
              }
            }
        }
    }
  }

  /*
   * List all files that need to be added again - simply all files older than period.
   * Be careful that files can disappear in the middle of a java.nio.Files.walk, so just
   * don't use that (or better files methods that use that)
   * See: https://issues.rudder.io/issues/19268
   */
  def listFiles(d: Duration): UIO[List[File]] = {
    import scala.jdk.CollectionConverters.*
    (for {
      // if that fails, just exit
      now      <- Clock.instant
      ageLimit <- IOResult.attempt(now.minus(d))
      filter    = (f: File) => {
                    if (
                      f.exists && InventoryProcessingUtils
                        .hasValidInventoryExtension(f) && (f.isRegularFile && ageLimit.isAfter(f.lastModifiedTime))
                    ) Some(f)
                    else None
                  }
      // if the listing fails, just exit
      children <- ZIO.foreach(directories)(d =>
                    IOResult.attempt(FileUtils.listFilesAndDirs(d.toJava, TrueFileFilter.TRUE, TrueFileFilter.TRUE).asScala)
                  )
      // filter file by file. In case of error, just skip it. Specifically ignore FileNotFound (davfs temp files disapear)
      filtered <- ZIO.foreach(children.flatten) { file =>
                    ZIO.attempt(filter(File(file.toPath))).catchAll {
                      case _:  FileNotFoundException => // just ignore
                        InventoryProcessingLogger.trace(
                          s"Ignoring file '${file.toString}' when processing old inventories: " +
                          s"FileNotFoundException (likely it disappeared between directory listing and filtering)"
                        ) *> ZIO.succeed(None)
                      case ex: Throwable             => // log and switch to the next
                        InventoryProcessingLogger
                          .warn(s"Error when processing file in old inventories: '${file.toString}': ${ex.getMessage}") *>
                        ZIO.succeed(None)
                    }
                  }
    } yield (filtered.flatten)).catchAll(err => {
      InventoryProcessingLogger.warn(s"Error when looking for old inventories that weren't processed: ${err.fullMsg}") *>
      Nil.succeed
    })
  }

  def checkFilesOlderThan(d: Duration): UIO[Unit] = for {
    files <- listFiles(d)
    _     <- ZIO.when(files.nonEmpty) {
               InventoryProcessingLogger.debug(s"Found old inventories: ${files.map(_.pathAsString).mkString(", ")}")
             }
    _     <- addFiles(files)
  } yield ()
}

/*
 * A class that periodically add inventories that we likely missed by inotify
 */
class SchedulerMissedNotify(
    checker: CheckExistingInventoryFiles,
    period:  Duration
) {
  val schedule: URIO[Any, Fiber.Runtime[Nothing, Nothing]] = {
    def loop(d: Duration) = for {
      _ <- checker.checkFilesOlderThan(d)
      _ <- ZIO.clockWith(_.sleep(period))
    } yield ()

    (loop(Duration.Zero) *> loop(period).forever).forkDaemon
  }
}

// utility data structure for inotify events
sealed trait WatchEvent
object WatchEvent {
  // file is written and so can be removed from Map
  final case class End(file: File) extends WatchEvent
  // file is currently modified, avoid processing
  final case class Mod(file: File) extends WatchEvent
}

object ProcessFile {

  // time after the last mod event after which we consider that a file is written
  private val fileWrittenThreshold: Duration = Duration(500, TimeUnit.MILLISECONDS)

  def start(inventoryProcessor: ProcessInventoryService, prioIncomingDirPath: String): UIO[ProcessFile] = {

    for {
      /*
       * We need a queue of add file / file written even to delimit when a file should be
       * processed.
       * We are going to receive a stream of "write file xxx" event, and updating the map accordingly
       * (resetting the task). Once the task is started, we want to free space in the map, and so enqueue
       * a "file written" event.
       */
      watchEventQueue <- Queue.bounded[WatchEvent](16384)

      /*
       * This is the map of be processed file based on received events.
       * Each time we receive a new event, we cancel the previous one and replace by the new (for the same file).
       * The task is configured to be processed after some delay.
       * We only modify that map as a result of a dequeue event.
       */
      toBeProcessed <- zio.Ref.Synchronized.make(Map.empty[File, Fiber[RudderError, Unit]])

      /*
       * Process fully written files
       * ---------------------------
       * We have a second queue that is used as buffer for the step between "file ok to be processed"
       * and "saving file" because "saving file" implies parsing XML and doing other memory and CPU
       * inefficient tasks.
       * The buffer also allows to filter inventory for identical nodes to avoid having a command to parse an inventory
       * while it was already processed earlier in the buffer (and so its files were already moved).
       * As only a small structure is saved (`SaveInventory`: a name and two function to create stream from file), buffer
       * can be big.
       * When queue is full, we prefer to drop old `SaveInventory` to new ones. In the worst case, they will be caught up
       * by the SchedulerMissedNotify and put again in the WatchEvent queue.
       */
      saveInventoryBuffer <- Queue.sliding[InventoryPair](1024)

      prioIncomingDir = File(prioIncomingDirPath)
      sign            = if (InventoryProcessingUtils.signExtension.charAt(0) == '.') {
                          InventoryProcessingUtils.signExtension
                        } else {
                          "." + InventoryProcessingUtils.signExtension
                        }
      processFile     = new ProcessFile(
                          inventoryProcessor = inventoryProcessor,
                          prioIncomingDir = prioIncomingDir,
                          sign = sign,
                          watchEventQueue = watchEventQueue,
                          toBeProcessed = toBeProcessed,
                          saveInventoryBuffer = saveInventoryBuffer
                        )
      _              <- processFile.start
    } yield processFile
  }

}

/*
 * Process file: interpret the stream of file modification events to decide when a
 * file is completely written, and if so, should it be sent to the backend processor.
 * For that, it needs signature and inventory file.
 */
class ProcessFile(
    inventoryProcessor:  ProcessInventoryService,
    prioIncomingDir:     File,
    sign:                String,
    watchEventQueue:     Queue[WatchEvent],
    toBeProcessed:       Ref.Synchronized[Map[File, Fiber[RudderError, Unit]]],
    saveInventoryBuffer: Queue[InventoryPair]
) extends HandleIncomingInventoryFile {

  import com.normation.rudder.inventory.ProcessFile.fileWrittenThreshold

  /*
   * Detect new files with inotify events
   * ------------------------------------
   *
   * This is a bit complicated because inotify sends a lot of event for one
   * file, and none is "file is now written". So we need to use an heuristic
   * which is: "if we don't receive inotify events for a file after a given
   * threshold, it likely means it's fully written".
   *
   * For that, when we get the first inotify event for a new file, we store a
   * trigger that is automatically fired after that threshold, and it's that
   * trigger which actually say "hey, look, we have a new file!"
   */

  def addFilePure(file: File): IOResult[Unit] = {
    watchEventQueue.offer(WatchEvent.Mod(file)).unit
  }

  def addFile(file: File): Unit = {
    ZioRuntime.unsafeRun(addFilePure(file))
  }

  // start the process, must be called only once
  private def start: UIO[Unit] = for {
    _ <- processMessage().forever.forkDaemon
    _ <- saveInventoryBufferProcessing().forever.forkDaemon
  } yield ()

  private def processMessage(): UIO[Unit] = {
    watchEventQueue.take.flatMap {
      case WatchEvent.End(file) => // simple case: remove file from map
        toBeProcessed.update(s => (s - file)).unit

      case WatchEvent.Mod(file) =>
        // look if the file is already here. If so, interrupt. In all case, create a new task.

        (toBeProcessed.updateZIO { s =>
          val newMap = {
            // the new task must :
            // - wait for 500 ms for interruption
            // - then execute on different IO scheduler
            // - if the map wasn't interrupted in the first 500 ms, it is not interruptible anymore

            val effect = (
              ZIO.unit.delay(fileWrittenThreshold)
                *> (watchEventQueue.offer(WatchEvent.End(file))
                *> processFile(file).catchAll(err => {
                  InventoryProcessingLogger.error(
                    s"Error when adding new inventory file '${file.path}' to processing: ${err.fullMsg}"
                  )
                })).uninterruptible
            ).forkDaemon

            effect.map(e => s + (file -> e))
          }

          s.get(file) match {
            case None           =>
              newMap
            case Some(existing) =>
              existing.interrupt.unit *> newMap
          }
        }).unit
    }
  }

  /*
   * The logic is:
   * - when a file is created, we look if it's a sig or an inventory (ends by .sign or not)
   * - if its an inventory, look for the corresponding sign. If available process, else
   *   register a timeout action that process after waitForSig duration.
   * - if it's a signature, look for the corresponding inventory. If available, remove possible action
   *   timeout and process, else do nothing.
   */
  private def processFile(file: File): ZIO[Any, RudderError, Unit] = {
    for {
      _ <- InventoryProcessingLogger.trace(s"Processing new file: ${file.pathAsString}")
      // We need to only try to do things on fully-written file.
      // The canonical way seems to be to try to get a write lock on the file and see if
      // it works. We assume that once we successfully get the lock, it means that
      // the file is written (so we can immediately release it).
      _ <- if (file.name.endsWith(".gz")) {
             val dest = File(file.parent, file.nameWithoutExtension(includeAll = false))
             InventoryProcessingLogger.debug(s"Dealing with zip file '${file.name}'") *>
             IOResult.attempt {
               file.unGzipTo(dest)
             }.unit.catchAll { err => // if the gz file is corrupted, we still want to delete it in the next instruction, but log
               InventoryProcessingLogger.error(
                 Chained(s"gz archive '${file.pathAsString}' is corrupted: deleting it.", err).fullMsg
               )
             } *>
             IOResult.attempt {
               file.delete()
             }.unit
           } else if (file.name.endsWith(sign)) { // a signature
             val inventory = File(file.parent, file.nameWithoutExtension(includeAll = false))

             if (inventory.exists) {
               // process !
               InventoryProcessingLogger.debug(
                 s"Watch new inventory file '${inventory.name}' with signature available: process."
               ) *>
               saveInventoryBuffer.offer(InventoryPair(inventory, file))
             } else {
               InventoryProcessingLogger.debug(
                 s"Watch incoming signature file '${file.pathAsString}' but no corresponding inventory available: waiting"
               )
             }
           } else { // an inventory
             val signature = File(file.pathAsString + sign)
             if (signature.exists) {
               // process !
               InventoryProcessingLogger.debug(s"Watch new inventory file '${file.name}' with signature available: process.") *>
               saveInventoryBuffer.offer(InventoryPair(file, signature))
             } else { // wait for expiration time and exec without signature
               InventoryProcessingLogger.debug(
                 s"Watch incoming inventory file '${file.pathAsString}' but no corresponding signature available: waiting"
               )
             }
           }
    } yield ()
  }

  /*
   * The processing logic is to continually call sendToProcessorBlocking. When we
   * take a file, we  de-duplicate files in buffer queue with the same name (ie: remove them).
   * It likely means that we add several "watch event: add" for that file (either copied several time,
   * touched, or inotify event emission not what we thought)
   */
  private def saveInventoryBufferProcessing(): ZIO[Any, Nothing, Unit] = {
    import com.normation.rudder.inventory.StatusLog.*
    for {
      fst  <- saveInventoryBuffer.take
      // deduplicate and prioritize file in 'incoming', which are new inventories. TakeAll is not blocking
      all  <- saveInventoryBuffer.takeAll
      inv   = all.prepended(fst).find(_.inventory.parent == prioIncomingDir).getOrElse(fst)
      // process
      _    <-
        InventoryProcessingLogger.info(s"Received new inventory file '${inv.inventory.name}' with signature available: process.")
      res  <- inventoryProcessor.saveInventoryBlocking(inv)
      _    <- res match {
                case r: InventoryProcessStatus.Saved => InventoryProcessingLogger.debug(r.msg)
                case r => InventoryProcessingLogger.error(r.msg)
              }
      all2 <- saveInventoryBuffer.takeAll // perhaps lots of new events came for that inventory
      _    <- saveInventoryBuffer.offerAll((all ++ all2).filterNot(inv.inventory.name == _.inventory.name))
    } yield ()
  }
}
