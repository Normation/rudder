/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
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


/**
 * This service is responsible to make available node changes for nodes.
 *
 * The changes are grouped by rules and by the intervals of time.
 * The intervals of time are always the 4 following, for each days:
 * - [0h-6h[ , [6h-12h[ , [12h-18h[, [18h-24[.
 */
trait NodeChangesService {

  type ChangesByRule  = Map[RuleId, Map[Interval,Seq[ResultRepairedReport]]]

  /**
   * Get all changes since a date specified as parameter and regroup them by interval of hours.
   *
   * If no starting date is specified, a default duration will be used to compute it.
   *
   */
  def getChangesByInterval(since: Option[DateTime]) : Box[ChangesByRule]


  /**
   * For the given service, what are the intervals currently valid?
   * Optionnally give a starting date, or else the service is in charge
   * to provide a default value.
   */
  def getCurrentValidIntervals(since: Option[DateTime]): Seq[Interval]

  /*
   * The two following methods are pure algorithm methods and we want that all
   * implementation of getChangesByInterval use them.
   */

  /**
   * Group the given set of reports by rules and based on the allowed intervals
   * given in parameter. All report outside any of the intervals is discared.
   */
  def filterGroupByInterval(intervals: Seq[Interval], changes: Seq[ResultRepairedReport]): ChangesByRule = {
    changes.groupBy(_.ruleId).mapValues { ch =>
      ( for {
         interval <- intervals
      } yield {
        (interval,ch.filter(interval contains _.executionTimestamp))
      } ).toMap
    }
  }


  /**
   * Stable computation of intervals of 6h starting at 0,6,12 or 18
   * between two dates.
   *
   * The date are always included in the resulting interval
   * sequence, which is also ordered by date.
   */
  def getInterval(since: DateTime, to: DateTime): Seq[Interval] = {
    // We want our first interval limit to be 00:00 | 06:00 | 12:00 | 18:00

    val firstInterval = {
      val firstHour =
        if ( to isBefore to.withHourOfDay(6) ) {
          to.withTimeAtStartOfDay()
        } else {
          if (to isBefore to.withHourOfDay(12)) {
            to.withTimeAtStartOfDay().withHourOfDay(6)
          } else {
            if (to isBefore to.withHourOfDay(18)) {
              to.withTimeAtStartOfDay().withHourOfDay(12)
            } else {
              to.withTimeAtStartOfDay().withHourOfDay(18)
        } } }
      // Fix minutes to 0
      new Interval(firstHour, firstHour.plusHours(6))
    }

    // Compute intervals of changes recursively from the last interval lower
    def computeIntervals(previousLowerBound: DateTime): List[Interval] = {
      if ( previousLowerBound isBefore since) {
        // Our last bound is before the limit, do not create a interval
        Nil
      } else {
        val nextLowerBound = previousLowerBound.minusHours(6)
        val interval = new Interval(nextLowerBound,previousLowerBound)
        interval :: computeIntervals(nextLowerBound)
      }
    }

    firstInterval :: computeIntervals(firstInterval.getStart)
  }
}


class NodeChangesServiceImpl(
    reportsRepository: ReportsRepository
  , changesMaxAge    : Int = 3 // in days
) extends NodeChangesService with Loggable {


  override def getCurrentValidIntervals(since: Option[DateTime]): Seq[Interval] = {
    // Limit of changes.
    val limit = since.getOrElse(DateTime.now.minusDays(changesMaxAge))
    val now = DateTime.now

    // compute intervals
    getInterval(limit, now)
  }


  /**
   * Get all changes until a date specified as parameter and regroup them by interval of hours.
   *
   * Interval are 6 hour longs.
   */
  override def getChangesByInterval(since: Option[DateTime]) = {

    // Regroup changes by interval
    for {
       changes <- reportsRepository.getChangeReports(since.getOrElse(DateTime.now.minusDays(changesMaxAge)))
    } yield {
      filterGroupByInterval(getCurrentValidIntervals(None), changes)
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

  private[this] var cache = Option.empty[ChangesByRule]

  private[this] def cacheToLog(changes: ChangesByRule): String = {
    changes.map { case(ruleId, x) =>
      val byInt = x.map { case (int, ch) =>

        val changeArray = ch.mkString("  ", "\n  ", "")

        s" ${int}\n${changeArray}"
      }.mkString(" ", "\n ", "")

      s"${ruleId.value}\n${byInt}"
    }.mkString("\n")
  }

  /**
   * The only method authorised to directly look at
   * the content of cache (other method can update the cache).
   */
  private[this] def initCache() : Box[ChangesByRule] = this.synchronized {
    cache match {
      case None =>
        for {
          changes <- changeService.getChangesByInterval(None)
        } yield {
          cache = Some(changes)
          logger.debug("NodeChanges cache initialized")
          logger.trace("NodeChanges cache content: " + cacheToLog(changes))
          changes
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
    def get(changes: ChangesByRule, ruleId: RuleId, i: Interval): Seq[ResultRepairedReport] = {
      changes.getOrElse(ruleId, Map()).getOrElse(i, Seq())
    }

    (changes1.keySet ++ changes2.keySet).map { ruleId =>
      (ruleId,
        on.map { i =>
          val updated = get(changes1, ruleId, i) ++ get(changes2, ruleId, i)
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
    if(changes.isEmpty) {
      Full(())
    } else {
      for {
        existing <- initCache()
      } yield {
        val intervals = changeService.getCurrentValidIntervals(None)
        val newChanges = this.filterGroupByInterval(intervals, changes)
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
    future {
      initCache()
    }
    ()
  }

  //just delegate to the actual service
  override def getCurrentValidIntervals(since: Option[DateTime]) = {
    changeService.getCurrentValidIntervals(since)
  }


  override def getChangesByInterval(since: Option[DateTime]) = {
    for {
      changes <- initCache()
    } yield {
      changes
    }
  }
}


object NodeChanges {

  // Filter changes on based on rules
  def changesOnRule(ruleId : RuleId)(changes  : Map[Interval,Seq[ResultRepairedReport]]) = {
    changes.mapValues(_.filter(_.ruleId == ruleId))
  }

  def displayPeriod(interval : Interval, now : DateTime) = {

    val period = new Interval(interval.getStart, now).toPeriod()
    //a format for time like "1d 5h ago"
    val format = "yyyy-MM-dd HH:mm"
    val endFormat = " - HH:mm"

    interval.getStart().toString(format) + interval.getEnd().toString(endFormat)
  }


  def json (changes : Map[Interval,Seq[ResultRepairedReport]]) = {

  // Order changes by interval start, then modify interval so we can have interval from now a
  def changesByPeriod (changes : Map[Interval,Seq[ResultRepairedReport]]) : List[(String,Seq[ResultRepairedReport])] = {

    val now = DateTime.now

    changes.toList.sortWith((a,b) => dateOrdering(a._1.getStart,b._1.getStart)).map { case(interval, v) =>
      (displayPeriod(interval,now),v)
    }
  }

  def dateOrdering (d1 : DateTime, d2 : DateTime) = {
    d1 isBefore d2
  }



  val data = changesByPeriod(changes).map(a => (a._1,a._2.size))
    JsObj(
        ("x" -> JsArray(data.map(a => Str(a._1))))
      , ("y" -> JsArray(data.map(a => Num(a._2))))
    )
  }
}

