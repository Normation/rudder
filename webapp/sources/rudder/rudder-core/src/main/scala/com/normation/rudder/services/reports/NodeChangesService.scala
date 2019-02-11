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

import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import org.joda.time.DateTime
import org.joda.time.Interval
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.ResultRepairedReport
import com.normation.rudder.repository.CachedRepository
import com.normation.rudder.repository.ReportsRepository
import net.liftweb.common._
import net.liftweb.common.Box
import net.liftweb.common.Loggable
import net.liftweb.http.js.JE._
import com.normation.rudder.domain.reports.ResultRepairedReport

/**
 * This service is responsible to make available node changes for nodes.
 *
 * The changes are grouped by rules and by the intervals of time.
 * The intervals of time are always the 4 following, for each days:
 * - [0h-6h[ , [6h-12h[ , [12h-18h[, [18h-24[.
 */

trait NodeChangesService {
  type ChangesByRule  = Map[RuleId, Map[Interval, Int]]

  /*
   * Max number of days to look back for changes.
   */
  def changesMaxAge: Int

  /**
   * Get all changes for the last "changesMaxAge" and
   * regroup them by rule, and by interval of 6 hours.
   */
  def countChangesByRuleByInterval() : Box[ChangesByRule]

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

  private[this] final def getInterval(since: DateTime, to: DateTime): List[Interval] = {
    if(to.isBefore(since)) {
      Nil
    } else {
      // We want our first interval limit to be 00:00 | 06:00 | 12:00 | 18:00

      //utility that create an interval from the given date to date+6hours
      def sixHours(t: DateTime): Interval = {
        //6 hours in milliseconds
        new Interval(t, new DateTime(t.getMillis + 6l * 3600 * 1000))
      }

      //find the starting time, set minute/seconds/millis to 0
      val startTime = since.withTimeAtStartOfDay.withHourOfDay( since.getHourOfDay / 6 * 6)

      // generate the stream of intervals, and stop when "to" is after
      // start time of interval (as they are sorted)
      Stream.iterate(sixHours(startTime)){ previousInterval =>
        sixHours(previousInterval.getEnd)
      }.takeWhile { i => i.getStart.isBefore(to) }.toList
    }
  }
}

class NodeChangesServiceImpl(
    reportsRepository         : ReportsRepository
  , override val changesMaxAge: Int = 3 // in days
) extends NodeChangesService with Loggable {

  /**
   * Get all changes for the last "reportsRepository.maxChangeTime" and
   * regroup them by rule, and by interval of 6 hours.
   */
  override def countChangesByRuleByInterval(): Box[ChangesByRule] = {
    val start = getCurrentValidIntervals(None).map(_.getStart).minBy(_.getMillis)
    // Regroup changes by interval
    for {
      changes <- reportsRepository.countChangeReports(start, 6)
    } yield {
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

/**
 * A cached version of NodeChangeService that:
 * - get its config (interval lenght) and first results from an other service
 * - is able to update a cache from other
 */
class CachedNodeChangesServiceImpl(
    changeService: NodeChangesService
) extends NodeChangesService with CachedRepository with Loggable {

  override val changesMaxAge = changeService.changesMaxAge
  private[this] var cache = Option.empty[ChangesByRule]

  private[this] def cacheToLog(changes: ChangesByRule): String = {
    changes.map { case(ruleId, x) =>
      val byInt = x.map { case (int, size) =>

        s" ${int}:${size}"
      }.mkString(" ", "\n ", "")

      s"${ruleId.value}\n${byInt}"
    }.mkString("\n")
  }

  /**
   * The only method authorised to directly look at
   * the content of cache (other method can update the cache).
   */
  private[this] def initCache() : Box[ChangesByRule] = this.synchronized {
    logger.debug("NodeChanges cache initialization...")
    cache match {
      case None =>
        try {
          for {
            changes <- changeService.countChangesByRuleByInterval()
          } yield {
            cache = Some(changes)
            logger.debug("NodeChanges cache initialized")
            logger.trace("NodeChanges cache content: " + cacheToLog(changes))
            changes
          }
        } catch {
          case ex: OutOfMemoryError =>
            val msg = "NodeChanges cache can not be updated du to OutOfMemory error. That mean that either your installation is missing " +
              "RAM (see: https://docs.rudder.io/reference/current/administration/performance.html#_java_out_of_memory_error) or that the number of recent changes is " +
              "overwhelming, and you hit: http://www.rudder-project.org/redmine/issues/7735. Look here for workaround"
            logger.error(msg)
            Failure(msg)
        }
      case Some(changes) =>
        logger.debug("NodeChanges cache hit")
        logger.trace("NodeChanges cache content: " + cacheToLog(changes))
        Full(changes)
    }
  }

  /**
   * For intervals "on", merge change1 and change2 (respective to rules). Intervals not in "on"
   * are removed from the result.
   * For a given rule, on a given interval, changes from change1 and change2 are added.
   */
  private[this] def merge(on: Seq[Interval], changes1: ChangesByRule, changes2: ChangesByRule): ChangesByRule = {
    //shortcut that get map(k1)(k2) and return an empty seq if key not found
    def get(changes: ChangesByRule, ruleId: RuleId, i: Interval): Int = {
      changes.getOrElse(ruleId, Map()).getOrElse(i, 0)
    }

    (changes1.keySet ++ changes2.keySet).map { ruleId =>
      (ruleId,
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
  def update(changes: Seq[ResultRepairedReport]): Box[Unit] = this.synchronized {
    logger.debug(s"NodeChanges cache updating with ${changes.size} new changes...")
    if(changes.isEmpty) {
      Full(())
    } else {
      for {
        existing <- initCache()
      } yield {
        val intervals = changeService.getCurrentValidIntervals(None)
        val newChanges = changes.groupBy(_.ruleId).mapValues { ch =>
          ( for {
             interval <- intervals
          } yield {
            (interval, ch.filter(interval contains _.executionTimestamp).size )
          } ).toMap
        }
        logger.debug("NodeChanges cache updated")
        cache = Some(merge(intervals, existing, newChanges))
        logger.trace("NodeChanges cache content: " + cacheToLog(cache.get))
        ()
      }
    }
  }

  /**
   * Clear cache. Try a reload asynchronously, disregarding
   * the result
   */
  override def clearCache(): Unit = this.synchronized {
    cache = None
    logger.debug("NodeChange cache cleared")
    Future {
      initCache()
    }
    ()
  }

  /*
   * It's actually just using the cache
   */
  override def countChangesByRuleByInterval() = {
    initCache()
  }

  /**
   * Get the changes for the given interval, without using the cache.
   */
  override def getChangesForInterval(ruleId: RuleId, interval: Interval, limit: Option[Int]): Box[Seq[ResultRepairedReport]] = {
    changeService.getChangesForInterval(ruleId, interval, limit)
  }

}

object NodeChanges {
  //a format for interval like "2016-01-27 06:00 - 12:00"
  val day = "yyyy-MM-dd"
  val startFormat = "HH:mm"
  val endFormat = "- HH:mm"

  private[this] def displayPeriod(interval: Interval) = {
    JsArray(Str(interval.getStart().toString(day)) ::Str(interval.getStart().toString(startFormat)) :: Str(interval.getEnd().toString(endFormat)) :: Nil)
  }

  /**
   * Display the list of intervals, sorted by start time, and for each put
   * the number of changes from changes.
   * Intervals must be equals in changes and intervals.
   */
  def json (changes: Map[Interval, Int], intervals: List[Interval]) = {

    //sort intervals, get number of changes for each (or 0)
    val data = intervals.sortBy(_.getStartMillis).map { i =>
      ( displayPeriod(i), changes.getOrElse(i, 0), i.getStartMillis )
    }

    JsObj(
        ("labels" -> JsArray(data.map(a => a._1)))
      , ("values" -> JsArray(data.map(a => Num(a._2))))
      , ("t" -> JsArray(data.map(a => Num(a._3))))
    )
  }
}
