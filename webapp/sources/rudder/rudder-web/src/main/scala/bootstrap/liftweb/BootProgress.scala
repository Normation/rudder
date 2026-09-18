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

package bootstrap.liftweb

import better.files.File
import com.normation.rudder.domain.logger.BootProgressLogger
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*
import zio.*

/*
 * Watchdog assessment done every period
 */
enum BootWatchdogVerdict {
  case Progressing // the step counter moved since the previous look
  case SameStep    // still on the same step, but within the allowed time
  case Stalled     // same step for longer than the stall timeout: boot is considered dead
}

/*
 * Coarse boot phases, in the order they happen. They are the granularity at which boot time is
 * reported, so they are meant to map to what one would understand as "boot phase" (and optimize separately).
 */
sealed trait BootPhase(val name: String)
object BootPhase {
  case object JVM                     extends BootPhase("jvm")                // first phase, jvm starting
  case class BootChecks(step: String) extends BootPhase("boot-check:" + step) // step will tell if it's early or not
  case object Git                     extends BootPhase("git-repositories")   // opening/reading the configuration and fact git repositories
  case object NodeFacts               extends BootPhase("node-facts")         // loading the node fact repository from LDAP
  case object Services                extends BootPhase("service-init")       // instantiating the rest of the Rudder services in RudderConfig
  case object Plugins                 extends BootPhase("plugins-init")       // plugin initialization
  case class Plugin(plugin: String)   extends BootPhase("plugins:" + plugin)  // a given plugin phase initialization
  case object WebApplication          extends BootPhase("web-application")    // Lift/API/sitemap setup
}

final case class BootStep(phase: BootPhase, detail: String) {
  def display: String = s"[${phase.name}] '${detail}'"
}

object BootProgressProperties {
  val logIntervalKey  = "rudder.boot.progress.logInterval"
  val stallTimeoutKey = "rudder.boot.progress.stallTimeout"

  private val defaultLogInterval  = Duration.ofSeconds(10)
  private val defaultStallTimeout = Duration.ofMinutes(10)

  private def duration(key: String, default: Duration): Duration = {
    try {
      RudderProperties.config.getDuration(key)
    } catch {
      case _: com.typesafe.config.ConfigException => default
    }
  }

  def logInterval:  Duration = duration(logIntervalKey, defaultLogInterval)
  def stallTimeout: Duration = duration(stallTimeoutKey, defaultStallTimeout)
}

final private[liftweb] case class TimedStep(
    step:     BootStep,
    duration: Duration
)

/*
 * A class used to monitor boot progress. It is apart and an object without instance, because
 * it needs to live as soon as the JVM starts, and to be independent from other runtimes like ZIO
 * (since ZIO-runtime itself can dead-lock).
 * We want to ensure that:
 *   - an observer (ops...) can actually see things (log) happen regularly, even when some boot
 *     migration are taking time,
 *   - the logger actually detect deadlock (non progress) and does not say progress in that case
 * Boot info are written both in `BootProgressLogger` and into file `/var/rudder/run/rudder-boot-progress`
 * which is deleted at end of boot (so it's absence tells that the boot is done).
 *
 * Boot steps are also timed, and the slowest ones logged as a summary once boot is done so that we
 * can know what is normal timing, and which step is eating time.
 *
 */
object BootProgress {

  // number of slow steps to log
  private val NUMBER_SLOW_STEPS_LOGGED = 10

  // no ZIO in that class, but we try to still keep things atomic.

  private val logger = BootProgressLogger.logEffect

  // where the current progress is exposed for out-of-JVM watchers (service manager wrapper,
  // monitoring, migration script). Kept in /var/rudder/run: cleaned at reboot, always writable.
  val progressFile: File = File("/var/rudder/run/rudder-boot-progress")

  private val bootStart: Instant = java.time.Instant.now()

  // number of boot steps started so far used as progress evidence
  private val steps = new AtomicInteger(0)

  // when the current step started, ie when we last got evidence of progress
  private val lastProgressAt = new AtomicReference[Instant](bootStart)

  // (phase, detail) of what boot says it is doing right now, for messages only
  private val currentLabel = new AtomicReference[BootStep](BootStep(BootPhase.JVM, "starting"))

  // Where we keep every measured step, for the end-of-boot summary.
  // Concurrent, because with ZIO and object instantiation, we prefer to be thread safe.
  private val done = new ConcurrentLinkedQueue[TimedStep]()

  private val bootDone = new AtomicBoolean(false)

  // Notify the start of a new phase and increment counter
  private def progressed(step: BootStep): Instant = {
    val now = Instant.now()
    lastProgressAt.set(now)
    currentLabel.set(step)
    steps.incrementAndGet()
    logger.debug(s"boot step [${steps.get()}] ${step.display}")
    now
  }

  /*
   * Tell that boot moved forward to a new step and record its start time, and what that step is.
   *
   * Prefer the `step` method that will only time exactly a scoped code fragment. Use this one
   * when you see when the thing starts, but not really when it ends.
   */
  def advance(step: BootStep): Unit = {
    progressed(step)
  }

  // threshold above which we write an info log for a step once boot is done.
  // It can help detect long steps that should only happen during boot but are redone regularly.
  private val bootLogThreshold = Duration.ofMillis(1000)

  /*
   * Record a step whose duration was measured by the caller (typically a zio.timed)
   *
   * Only boot steps are kept for the end-of-boot summary.
   * If the same record runs after boot, we just log them above given threshold to avoid making an infinite list.
   */
  def record(step: BootStep, duration: Duration): Unit = {
    if (bootDone.get()) {
      if (duration.compareTo(bootLogThreshold) >= 0) logger.info(s"${step.display} took ${fmt(duration)}")
      else logger.debug(s"${step.display} took ${fmt(duration)}")
    } else {
      done.add(TimedStep(step, duration))
    }
  }

  /*
   * Record a step whose duration will be timed by that method.
   *
   * Use `record` so only steps before end of boot are accumulated.
   */
  def step[A](phase: BootPhase, detail: String)(effect: => A): A = {
    val step  = BootStep(phase, detail)
    val start = progressed(step)
    try {
      effect
    } finally {
      record(step, Duration.between(start, Instant.now()))
    }
  }

  /*
   * Same as `step`, for a ZIO effect: the effect has to be timed when it runs, not when it is
   * built.
   */
  def stepZIO[R, E, A](phase: BootPhase, detail: String)(effect: ZIO[R, E, A]): ZIO[R, E, A] = {
    val step = BootStep(phase, detail)
    ZIO.succeed(progressed(step)) *> effect.timed.map {
      case (duration, a) =>
        record(step, duration)
        a
    }
  }

  def elapsed: Duration = Duration.between(bootStart, Instant.now())

  /*
   * Called once boot is complete at the end of `bootstrap.liftweb.Boot.boot`.
   * Stops the watchdog and logs where the time went.
   */
  def finished(): Unit = {
    def log(logIt: (=> String) => Unit, steps: List[TimedStep]) = steps.foreach {
      case TimedStep(step, duration) => logIt(s"  ${fmt(duration)}\t${step.display}")
    }

    if (bootDone.compareAndSet(false, true)) {
      val all             = done.asScala.toList
      val measured        = Duration.ofMillis(all.map(_.duration.toMillis).sum)
      val (slowest, next) = slowestSteps(NUMBER_SLOW_STEPS_LOGGED)
      logger.info(
        s"Rudder booted in ${fmt(elapsed)}: ${all.size} steps, ${fmt(measured)} of measured steps. Slowest steps:"
      )
      log(logger.info, slowest)
      log(logger.debug, next)
      deleteProgressFile()
    }
  }

  /*
   * The `max` slowest recorded steps, longest first, and the `2 x max` next slowest.
   */
  private[liftweb] def slowestSteps(max: Int): (List[TimedStep], List[TimedStep]) = {
    val sorted = done.asScala.toList.sortBy(-_.duration.toNanos)
    (sorted.take(max), sorted.slice(max, max + 2 * max))
  }

  // iso format is not very readable
  private def fmt(duration: Duration): String = {
    val millis = duration.toMillis
    if (millis < 1000) s"${millis}ms"
    else if (millis < 60000) f"${millis / 1000d}%.1fs"
    else s"${millis / 60000}m${(millis % 60000) / 1000}s"
  }

  /*
   * Expose progress to out-of-JVM watchers in `progressFile`
   * Written atomically (with a .tmp+mv).
   * Format is one "key=value" per line, `state` being one of booting|stalled|booted.
   */
  private def writeProgressFile(state: String, step: BootStep, stalledFor: Duration): Unit = {
    try {
      val content = List(
        s"state=${state}",
        s"steps=${steps.get()}",
        s"phase=${step.phase.name}",
        s"detail=${step.detail}",
        s"elapsed_sec=${elapsed.toSeconds}",
        s"no_progress_sec=${stalledFor.toSeconds}"
      ).mkString("", "\n", "\n")
      val tmp     = File(progressFile.path.resolveSibling(progressFile.pathAsString + ".tmp"))
      progressFile.createDirectories()
      tmp.write(content)
      tmp.moveTo(progressFile)(using File.CopyOptions(overwrite = true) ++ File.CopyOptions.atomically)
    } catch {
      // never let progress reporting break boot
      case ex: Exception => logger.debug(s"Can not write boot progress file ${progressFile}: ${ex.getMessage}")
    }
  }

  private def deleteProgressFile(): Unit = {
    try progressFile.delete(swallowIOExceptions = false)
    catch { case ex: Exception => logger.debug(s"Can not delete boot progress file ${progressFile}: ${ex.getMessage}") }
  }

  private def threadDump(): String = {
    val mx = java.lang.management.ManagementFactory.getThreadMXBean
    mx.dumpAllThreads(true, true).map(_.toString).mkString("\n")
  }

  /*
   * What the watchdog concludes when it looks at the progress counter.
   */
  private[liftweb] def verdict(
      currentSteps:  Long,
      lastSeenSteps: Long,
      noProgressFor: Duration,
      stallTimeout:  Duration
  ): BootWatchdogVerdict = {
    if (currentSteps != lastSeenSteps) BootWatchdogVerdict.Progressing
    else if (stallTimeout.toMillis > 0 && noProgressFor.compareTo(stallTimeout) > 0) BootWatchdogVerdict.Stalled
    else BootWatchdogVerdict.SameStep
  }

  /*
   * Start watching boot progress.
   * `logInterval` is how often we report, `stallTimeout` how long boot may stay on the same step before being declared
   *  dead (0 disables that check, for the cases where an ops knowingly runs a very long one-shot migration).
   *
   * This is a plain daemon independent thread on purpose so that we also can surveil ZIO dead-lock.
   */
  def startWatchdog(logInterval: Duration, stallTimeout: Duration): Unit = {
    val t = new Thread(
      () => {
        var lastSteps = -1L
        while (!bootDone.get()) {
          Thread.sleep(logInterval.toMillis)
          if (!bootDone.get()) {
            val step          = currentLabel.get()
            val currentSteps  = steps.get()
            val noProgressFor = Duration.between(lastProgressAt.get(), Instant.now())

            verdict(currentSteps, lastSteps, noProgressFor, stallTimeout) match {
              case BootWatchdogVerdict.Progressing =>
                // boot moved since last time we looked: that is real progress, report it
                lastSteps = currentSteps
                writeProgressFile("booting", step, noProgressFor)
                logger.info(
                  s"Rudder is still booting, please wait: ${currentSteps} steps done in ${fmt(elapsed)}, " +
                  s"currently in phase ${step.display}"
                )

              case BootWatchdogVerdict.SameStep =>
                writeProgressFile("booting", step, noProgressFor)
                logger.warn(
                  s"Rudder boot has been on the same step for ${fmt(noProgressFor)}: phase ${step.display} " +
                  s"(${fmt(elapsed)} since start)"
                )

              case BootWatchdogVerdict.Stalled =>
                // no progress at all for too long: say so, dump what everyone is doing, and stop.
                // An infinite "still booting" on a dead boot is what we are trying to avoid here.
                writeProgressFile("stalled", step, noProgressFor)
                logger.error(
                  s"Rudder boot made no progress for ${fmt(noProgressFor)} while in phase ${step.display} " +
                  s"(${fmt(elapsed)} since start): boot is considered stalled and Rudder will stop now. " +
                  s"Thread dump follows, it should show what boot is waiting for. This check can be tuned with " +
                  s"property '${BootProgressProperties.stallTimeoutKey}' ('0' disables it)."
                )
                logger.error(threadDump())
                // halt and not exit: shutdown hooks would themselves wait on the wedged services
                java.lang.Runtime.getRuntime.halt(1)
            }
          }
        }
        deleteProgressFile()
      },
      "rudder-boot-progress"
    )
    t.setDaemon(true)
    t.start()
  }
}
