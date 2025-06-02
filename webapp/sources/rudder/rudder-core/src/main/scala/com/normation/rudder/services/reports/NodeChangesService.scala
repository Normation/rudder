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

package com.normation.rudder.services.reports

import com.normation.box.*
import com.normation.errors.*
import com.normation.rudder.domain.logger.ReportLoggerPure
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.ResultRepairedReport
import com.normation.rudder.repository.CachedRepository
import com.normation.rudder.repository.ReportsRepository
import com.normation.zio.*
import net.liftweb.common.*
import net.liftweb.http.js
import net.liftweb.http.js.JE.*
import org.joda.time.DateTime
import org.joda.time.Interval
import zio.{System as _, *}
import zio.syntax.*

/**
 * This service is responsible to make available node changes for nodes.
 *
 * The changes are grouped by rules and by the intervals of time.
 * The intervals of time are always the 4 following, for each days:
 * - [0h-6h[ , [6h-12h[ , [12h-18h[, [18h-24[.
 */

trait NodeChangesService {
  type ChangesByRule = Map[RuleId, Map[Interval, Int]]

  /*
   * Max number of days to look back for changes.
   */
  def changesMaxAge: Int

  /**
   * Get all changes for the last "changesMaxAge" and
   * regroup them by rule, and by interval of 6 hours.
   */
  def countChangesByRuleByInterval(): Box[(Long, ChangesByRule)]

  /**
   * Get the changes for the given interval, which must be one of the
   * interval returned by getCurrentValidIntervals methods.
   * Optionally give a limit number of report to return (0 or negative number
   * will be considered as None)
   */
  def getChangesForInterval(ruleId: RuleId, interval: Interval, limit: Option[Int]): Box[Seq[ResultRepairedReport]]

  /**
   * For the given service, what are the intervals currently valid?
   * Optionnally give a starting date, or else the service is in charge
   * to provide a default value.
   * The resulting list is sorted by interval start time, so that head is
   * before tail.
   *
   * This is success algo, so make all implementation use the same.
   *
   */
  final def getCurrentValidIntervals(since: Option[DateTime]): List[Interval] = {
    val startTime = since.getOrElse(DateTime.now.minusDays(changesMaxAge))
    val endTime   = DateTime.now
    getInterval(startTime, endTime)
  }

  /*
   * The following method is success algorithm methods and we want that all
   * implementation of getChangesByInterval use them.
   */

  /**
   * Stable computation of intervals of 6h starting at 0,6,12 or 18
   * between two dates.
   *
   * The date are always included in the resulting interval
   * sequence, which is also ordered by date.
   */

  final private def getInterval(since: DateTime, to: DateTime): List[Interval] = {
    if (to.isBefore(since)) {
      Nil
    } else {
      // We want our first interval limit to be 00:00 | 06:00 | 12:00 | 18:00

      // utility that create an interval from the given date to date+6hours
      def sixHours(t: DateTime): Interval = {
        // 6 hours in milliseconds
        new Interval(t, new DateTime(t.getMillis + 6L * 3600 * 1000))
      }

      // find the starting time, set minute/seconds/millis to 0
      val startTime = since.withTimeAtStartOfDay.withHourOfDay(since.getHourOfDay / 6 * 6)

      // generate the stream of intervals, and stop when "to" is after
      // start time of interval (as they are sorted)
      LazyList
        .iterate(sixHours(startTime))(previousInterval => sixHours(previousInterval.getEnd))
        .takeWhile(i => i.getStart.isBefore(to))
        .toList
    }
  }
}

class NodeChangesServiceImpl(
    val reportsRepository:      ReportsRepository,
    override val changesMaxAge: Int = 3 // in days
) extends NodeChangesService with Loggable {

  /**
   * Get all changes for the last "reportsRepository.maxChangeTime" and
   * regroup them by rule, and by interval of 6 hours.
   */
  override def countChangesByRuleByInterval(): Box[(Long, ChangesByRule)] = {
    logger.debug(s"Get all changes on all rules")
    val beginTime = System.currentTimeMillis()
    val intervals = getCurrentValidIntervals(None)
    // Regroup changes by interval
    for {
      changes <- reportsRepository.countChangeReportsByBatch(intervals)
    } yield {
      val endTime = System.currentTimeMillis()
      logger.debug(s"Fetched all changes for all rules in ${(endTime - beginTime)} ms")
      changes
    }
  }

  override def getChangesForInterval(ruleId: RuleId, interval: Interval, limit: Option[Int]): Box[Seq[ResultRepairedReport]] = {
    for {
      changes <- reportsRepository.getChangeReportsByRuleOnInterval(ruleId, interval, limit)
    } yield {
      changes
    }
  }
}

sealed trait ChangesUpdate
object ChangesUpdate {
  case object Init                                      extends ChangesUpdate
  final case class For(lowestId: Long, highestId: Long) extends ChangesUpdate
}

final case class ChangesCache(
    highestId: Long, // the highest id in the cache

    changes: Map[RuleId, Map[Interval, Int]] // the map of aggregated changes by rules and by interval
)

/**
 * A cached version of NodeChangeService that:
 * - get its config (interval length) and first results from an other service
 * - is able to update a cache from other
 */
class CachedNodeChangesServiceImpl(
    changeService:        NodeChangesServiceImpl,
    computeChangeEnabled: () => Box[Boolean]
) extends NodeChangesService with CachedRepository {

  override val changesMaxAge = changeService.changesMaxAge

  private val cache = Ref.make[Option[ChangesCache]](None).runNow // this is a bug if it does not work

  // when the class is instanciated, ask for a cache init
  ZioRuntime.runNow((for {
    _ <- ReportLoggerPure.Changes.debug("Initialize rule changes caches")
    _ <- this.addUpdate(ChangesUpdate.Init)
  } yield ()).delay(10.seconds).forkDaemon)

  /*
   * The cache can only be modified through a queue of updates.
   * There is two type of updates:
   * - a init update, which tells the cache to be initied if it wasn't already (and is a noop else),
   * - an update between interval, which is ignored if the cache is not initialized (init is done)
   */

  object QueuedChanges {

    /*
     * From a list of changes, compute the resulting change with the logic:
     * - if there is at least one "Interval", the result is an interval else it's an Init,
     * - if the result is interval, it's a new Interval with the lowest ID of all interval as
     *   lowestId and the highest ID if all interval for highestId
     */
    @scala.annotation.tailrec
    private def mergeUpdates(current: ChangesUpdate, updates: Chunk[ChangesUpdate]): ChangesUpdate = {
      updates.headOption match {
        case None    => current
        case Some(h) =>
          import ChangesUpdate.*
          val c = (h, current) match {
            case (Init, Init)           => Init
            case (Init, For(a, b))      => For(a, b)
            case (For(a, b), Init)      => For(a, b)
            case (For(a, b), For(c, d)) => For(Math.min(a, c), Math.max(b, d))
          }
          mergeUpdates(c, updates.tail)
      }
    }

    def consumeOne(queue: Queue[ChangesUpdate]): ZIO[Any, Nothing, Unit] = {
      for {
        // takeAll doesn't wait for items, so we wait for at least one and then take all other
        one <- queue.take
        _   <- ReportLoggerPure.Changes.debug(s"At least one new changes cache update available (${one}), start updating cache.")
        all <- queue.takeAll
        _   <- ReportLoggerPure.Changes.trace(s"Actually ${all.size + 1} updates!")
        // update the cache, log and never fail
        fib <- ZIO.blocking(updateCache(mergeUpdates(one, all))).fork
        _   <- fib.join
      } yield ()
    }

    /*
     * We want the queue to have a finite size to consume bouded resources.
     * Since we have at most one element every 5s (interval for node run batch),
     * a size of 1024 means that we are running more than 1h late in changes cache update.
     * In that case, it means that we really don't seems to be able to cope with
     * changes throughout, and we accept to to loose the oldest non processed changed
     * in favor of the more recent.
     *
     * The queue need to be unsafeRun so that the `offer` is available.
     */
    val queue: Queue[ChangesUpdate] = ZioRuntime.runNow(Queue.sliding[ChangesUpdate](1024))

    // start infinite loop
    ZioRuntime.runNow(
      for {
        _ <- ReportLoggerPure.Changes.debug(s"Start waiting for rule changes update")
        _ <- (consumeOne(queue) *> ReportLoggerPure.Changes.trace(s"done, looping")).forever.forkDaemon
      } yield ()
    )
  }

  private def cacheToLog(changes: ChangesByRule): String = {
    changes.map {
      case (ruleId, x) =>
        val byInt = x.map {
          case (int, size) =>
            s" ${int}:${size}"
        }.mkString(" ", "\n ", "")

        s"${ruleId.serialize}\n${byInt}"
    }.mkString("\n")
  }

  /**
   * For intervals "on", merge change1 and change2 (respective to rules). Intervals not in "on"
   * are removed from the result.
   * For a given rule, on a given interval, changes from change1 and change2 are added.
   */
  private def merge(on: Seq[Interval], changes1: ChangesByRule, changes2: ChangesByRule): ChangesByRule = {
    // shortcut that get map(k1)(k2) and return an empty seq if key not found
    def get(changes: ChangesByRule, ruleId: RuleId, i: Interval): Int = {
      changes.getOrElse(ruleId, Map()).getOrElse(i, 0)
    }

    (changes1.keySet ++ changes2.keySet).map { ruleId =>
      (
        ruleId,
        on.map { i =>
          val updated = get(changes1, ruleId, i) + get(changes2, ruleId, i)
          (i, updated)
        }.toMap
      )
    }.toMap
  }

  /**
   * Update the cache with the list interval
   * The method may fail because in case the cache wasn't
   * initialized, we have to do so.
   *
   * Note that an alternative technique would have to only
   * update cache on read, but generally, read are linked
   * to a synchrone user action, and so will make the user
   * wait. On the other hand, update are driven by batch&
   * background processes where throughout is more
   * important than responsiveness.
   */
  def update(lowestId: Long, highestId: Long): Box[Unit] = {
    if (lowestId < highestId) {
      addUpdate(ChangesUpdate.For(lowestId, highestId)).toBox
    } else Full(())
  }

  def addUpdate(update: ChangesUpdate): IOResult[Unit] = {
    IOResult.attempt(computeChangeEnabled().getOrElse(true)).flatMap { enabled => // always true by default
      if (enabled) {
        for {
          _  <- ReportLoggerPure.Changes.debug(s"Add changes cache update ${update} on queue")
          ok <- QueuedChanges.queue.offer(update)
          s  <- QueuedChanges.queue.size
          _  <- ZIO.when(!ok) {
                  ReportLoggerPure.Changes
                    .warn(s"Error when notifying rule changes cache of an update. Changes may not be up to date")
                }
          _  <-
            ReportLoggerPure.Changes.debug(s"Current rule changes queue update status: ${s + 1}/${QueuedChanges.queue.capacity}")
        } yield ()
      } else {
        ReportLoggerPure.Changes
          .debug(s"Not updating changes by rule - disabled by configuration setting 'rudder_compute_changes' (set by REST API)")
      }
    }
  }

  /*
   * Get changes and update cache accordingly.
   * This method must be accessed in a monothreaded way.
   */
  protected def updateCache(update: ChangesUpdate): UIO[Unit] = {
    IOResult
      .attempt(computeChangeEnabled().getOrElse(true))
      .flatMap { enabled => // always true by default
        if (enabled) {
          (for {
            time1   <- currentTimeMillis
            opt     <- cache.get
            updated <- opt match {
                         case None    => initCache()
                         case Some(c) =>
                           update match {
                             case ChangesUpdate.Init      =>
                               ReportLoggerPure.Changes.trace("Rule changes cache already initialized: not doing it again") *>
                               c.succeed
                             case ChangesUpdate.For(l, h) => syncUpdate(c, l, h)
                           }
                       }
            _       <- ReportLoggerPure.Changes.trace("step 4")
            _       <- cache.set(Some(updated))
            time2   <- currentTimeMillis
            _       <- ReportLoggerPure.Changes.debug(
                         s"Cache for changes by rule updated in ${time2 - time1} ms after new run(s) received"
                       )
          } yield ()).chainError("An error occurred when trying to update the cache of last changes")
        } else {
          ReportLoggerPure.Changes.debug(
            s"Not updating changes by rule - disabled by configuration setting 'rudder_compute_changes' (set by REST API)"
          )
        }
      }
      .catchAll(err => ReportLoggerPure.error(err.fullMsg))
  }

  /**
   * This method get initial values for the cache, ie it does
   * get all changes for the max accepted time and aggregate them by part.
   * It also return the corresponding highest ID for a change.
   * This method will be long and consume lots of memory.
   */
  protected def initCache(): IOResult[ChangesCache] = {
    for {
      _       <- ReportLoggerPure.Changes.debug("Rule Changes cache initialization...")
      changes <- (try {
                   changeService.countChangesByRuleByInterval()
                 } catch {
                   case ex: OutOfMemoryError =>
                     val msg = {
                       "Rule Changes cache can not be updated du to OutOfMemory error. That mean that either your installation is missing " +
                       "RAM (see: https://docs.rudder.io/reference/current/administration/performance.html#_java_out_of_memory_error) or that the number of recent changes is " +
                       "overwhelming, and you hit: https://issues.rudder.io/issues/7735. Look here for workaround"
                     }
                     ReportLoggerPure.Changes.logEffect.error(msg)
                     Failure(msg)
                 }).toIO
      _       <- ReportLoggerPure.Changes.debug("NodeChanges cache initialized")
      _       <- ReportLoggerPure.Changes.trace("NodeChanges cache content: " + cacheToLog(changes._2))
    } yield {
      ChangesCache(changes._1, changes._2)
    }
  }

  /**
   * This method update a cache for a new interval of changes. It merge existing values with new ones as needed,
   * and remove no more used intervals.
   */
  protected def syncUpdate(previous: ChangesCache, lowestId: Long, highestId: Long): IOResult[ChangesCache] = {
    for {
      _         <- ReportLoggerPure.Changes.debug(s"Rule Changes cache updating for changes between ID '${lowestId}' and '${highestId}'")
      time0     <- currentTimeMillis
      changes   <- changeService.reportsRepository.getChangeReportsOnInterval(Math.max(lowestId, previous.highestId), highestId)
      intervals <- IOResult.attempt(changeService.getCurrentValidIntervals(None))
      newChanges = changes.groupBy(_.ruleId).map {
                     case (id, ch) =>
                       val c = intervals.map(interval => (interval, ch.filter(interval contains _.executionTimestamp).size))
                       (id, c.toMap)
                   }
      time1     <- currentTimeMillis
      _         <- ReportLoggerPure.Changes.debug(s"Rule Changes cache updated in ${time1 - time0} ms")
      updates    = merge(intervals, previous.changes, newChanges)
      _         <- ReportLoggerPure.Changes.trace("NodeChanges cache content: " + cacheToLog(updates))
    } yield {
      ChangesCache(highestId, updates)
    }
  }

  /**
   * Clear cache. Register a reload asynchronously, disregarding
   * the result
   */
  override def clearCache(): Unit = {
    val clear = {
      for {
        _ <- cache.set(None)
        _ <- ReportLoggerPure.Changes.debug("NodeChange cache cleared")
        _ <- addUpdate(ChangesUpdate.Init)
      } yield ()
    }

    ZioRuntime.runNow(clear)
  }

  /*
   * It's actually just using the cache. It may not be up to date
   * if the cache wasn't initialized
   */
  override def countChangesByRuleByInterval(): Box[(Long, ChangesByRule)] = {
    val prog = for {
      opt <- cache.get
    } yield {
      opt match {
        case Some(c) => (c.highestId, c.changes)
        case None    => (-1L, Map[RuleId, Map[Interval, Int]]())
      }
    }

    prog.toBox
  }

  /**
   * Get the changes for the given interval, without using the cache.
   */
  override def getChangesForInterval(ruleId: RuleId, interval: Interval, limit: Option[Int]): Box[Seq[ResultRepairedReport]] = {
    changeService.getChangesForInterval(ruleId, interval, limit)
  }

}

object NodeChanges {
  // a format for interval like "2016-01-27 06:00 - 12:00"
  val day         = "yyyy-MM-dd"
  val startFormat = "HH:mm"
  val endFormat   = "- HH:mm"

  private def displayPeriod(interval: Interval) = {
    JsArray(
      Str(interval.getStart().toString(day)) :: Str(interval.getStart().toString(startFormat)) :: Str(
        interval.getEnd().toString(endFormat)
      ) :: Nil
    )
  }

  /**
   * Display the list of intervals, sorted by start time, and for each put
   * the number of changes from changes.
   * Intervals must be equals in changes and intervals.
   */
  def json(changes: Map[Interval, Int], intervals: List[Interval]): js.JsObj = {

    // sort intervals, get number of changes for each (or 0)
    val data = intervals.sortBy(_.getStartMillis).map(i => (displayPeriod(i), changes.getOrElse(i, 0), i.getStartMillis))

    JsObj(
      ("labels" -> JsArray(data.map(a => a._1))),
      ("values" -> JsArray(data.map(a => Num(a._2)))),
      ("t"      -> JsArray(data.map(a => Num(a._3))))
    )
  }
}
