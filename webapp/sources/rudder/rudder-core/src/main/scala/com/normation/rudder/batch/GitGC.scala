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
import org.eclipse.jgit.lib.ProgressMonitor
import org.joda.time.Duration
import zio.*

/**
 * A scheduler which run a git gc every day.
 * It use a cron-like config parsed by cron4s.
 *
 * Code derived from https://github.com/centic9/jgit-cookbook/blob/master/src/main/java/org/dstadler/jgit/porcelain/CollectGarbage.java
 */
class GitGC(
    gitRepo: GitRepositoryProvider,
    optCron: Option[CronExpr]
) {

  val logger = GitRepositoryLogger

  // this class is necessary to report information about gitgc processing that can be long
  class LogProgressMonitor extends ProgressMonitor {
    def start(totalTasks: Int): Unit = {
      logger.debug(s"Start git-gc on ${gitRepo.rootDirectory.name} for ${totalTasks} tasks")
    }

    def beginTask(title: String, totalWork: Int): Unit = {
      logger.debug(s"Start git-gc on ${gitRepo.rootDirectory.name} for ${title}: ${totalWork}")
    }

    def update(completed: Int): Unit = {
      logger.trace(s"git-gc completed on ${gitRepo.rootDirectory.name}: ${completed}")
    }

    def endTask(): Unit = {
      logger.trace(s"git-gc on ${gitRepo.rootDirectory.name}: task done")
    }

    def isCancelled = false

    // Added in recent jgit version (at least 6.5.0.202303070854-r)
    // It can be used to display the duration of logs but we don't it just keep it
    // If we really want to do it, then we would add a private var and use it in other methods
    def showDuration(enabled: Boolean): Unit = ()
  }

  // must not fail, will be in a cron
  val gitgc: UIO[Unit] = for {
    duration <- gitRepo.semaphore
                  .withPermit(IOResult.attempt {
                    gitRepo.git.gc().setProgressMonitor(new LogProgressMonitor()).call
                  })
                  .catchAll(err => logger.error(s"Error when performing git-gc on ${gitRepo.rootDirectory.name}: ${err.fullMsg}"))
                  .timed
                  .map { case (duration, _) => duration }
    _        <- logger.info(s"git-gc performed on ${gitRepo.rootDirectory.name} in $duration")
  } yield ()

  // create the schedule gitgc cron or nothing if disabled.
  // Must not fail.
  val prog: UIO[Unit] = optCron match {
    case None       =>
      logger.info(s"Disable automatic git-gc on ${gitRepo.rootDirectory.name} (schedule: '${DISABLED}')")
    case Some(cron) =>
      val schedule = cron.toSchedule
      logger.info(
        s"Automatic git-gc starts on ${gitRepo.rootDirectory.name} (schedule 'sec min h dayMonth month DayWeek': '${cron.toString}')"
      ) *>
      gitgc.schedule(schedule).unit
  }

  // start cron
  def start(): Fiber.Runtime[Nothing, Unit] = {
    ZioRuntime.unsafeRun(prog.forkDaemon)
  }
}
