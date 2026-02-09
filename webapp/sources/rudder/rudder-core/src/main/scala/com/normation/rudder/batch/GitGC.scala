/*
 *************************************************************************************
 * Copyright 2021 Normation SAS
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

package com.normation.rudder.batch

import com.normation.errors.IOResult
import com.normation.rudder.domain.logger.GitRepositoryLogger
import com.normation.rudder.git.GitRepositoryProvider
import com.normation.utils.CronParser.*
import com.normation.zio.*
import cron4s.CronExpr
import java.util.Properties
import org.eclipse.jgit.lib.ProgressMonitor
import zio.*

/**
 * A scheduler which run a git gc every day.
 * It use a cron-like config parsed by cron4s.
 *
 * Code derived from https://github.com/centic9/jgit-cookbook/blob/master/src/main/java/org/dstadler/jgit/porcelain/CollectGarbage.java
 */
class GitGC(
    gitRepo: GitRepositoryProvider,
    optCron: Option[CronExpr],
    monitor: () => ProgressMonitor
) {

  private[batch] def doGC: Properties = {
    gitRepo.git.gc().setProgressMonitor(monitor()).call
  }

  // must not fail, will be in a cron
  private[batch] val gitgc: UIO[Unit] = for {
    (duration, _) <-
      gitRepo.semaphore
        .withPermit(IOResult.attempt(doGC))
        .unit
        .catchAll(err =>
          GitRepositoryLogger.error(s"Error when performing git-gc on ${gitRepo.rootDirectory.name}: ${err.fullMsg}", err.cause)
        )
        .timed
    _             <- GitRepositoryLogger.info(s"git-gc performed on ${gitRepo.rootDirectory.name} in ${duration}")
  } yield ()

  // create the schedule gitgc cron or nothing if disabled.
  // Must not fail.
  val prog: UIO[Unit] = optCron match {
    case None       =>
      GitRepositoryLogger.info(s"Disable automatic git-gc on ${gitRepo.rootDirectory.name} (schedule: '${DISABLED}')")
    case Some(cron) =>
      val schedule = cron.toSchedule
      GitRepositoryLogger.info(
        s"Automatic git-gc starts on ${gitRepo.rootDirectory.name} (schedule 'sec min h dayMonth month DayWeek': '${cron.toString}')"
      ) *>
      gitgc.schedule(schedule).unit
  }

  // start cron
  def start(): Fiber.Runtime[Nothing, Unit] = {
    ZioRuntime.unsafeRun(prog.forkDaemon)
  }
}

object GitGC {

  // this class is necessary to report information about gitgc processing that can be long
  class LogProgressMonitor(path: String) extends ProgressMonitor {
    override def start(totalTasks: Int): Unit = {
      GitRepositoryLogger.logEffect.debug(s"Start git-gc on ${path} for ${totalTasks} tasks")
    }

    override def beginTask(title: String, totalWork: Int): Unit = {
      GitRepositoryLogger.logEffect.debug(s"Start git-gc on ${path} for ${title}: ${totalWork}")
    }

    override def update(completed: Int): Unit = {
      GitRepositoryLogger.logEffect.trace(s"git-gc completed on ${path}: ${completed}")
    }

    override def endTask(): Unit = {
      GitRepositoryLogger.logEffect.trace(s"git-gc on ${path}: task done")
    }

    override def isCancelled = false

    // Added in recent jgit version (at least 6.5.0.202303070854-r)
    // It can be used to display the duration of logs but we don't it just keep it
    // If we really want to do it, then we would add a private var and use it in other methods
    override def showDuration(enabled: Boolean): Unit = ()
  }

  def make(gitRepo: GitRepositoryProvider, optCron: Option[CronExpr]) = {
    new GitGC(gitRepo, optCron, () => new LogProgressMonitor(gitRepo.rootDirectory.pathAsString))
  }
}
