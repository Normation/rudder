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
import org.joda.time.DateTime
import com.normation.rudder.domain.reports.ResultRepairedReport
import net.liftweb.common.Box
import com.normation.rudder.repository.ReportsRepository
import org.joda.time.Interval
import com.normation.rudder.domain.reports.ResultRepairedReport
import net.liftweb.common.Loggable
import com.normation.rudder.domain.reports.ResultRepairedReport
import com.normation.rudder.domain.policies.RuleId
import org.joda.time.format.PeriodFormatterBuilder
import net.liftweb.http.js.JE._

/**
 * This service is responsible to make available node changes for nodes.
 * The hard part is on implementation, to make it efficient.
 */
trait NodeChangesService {

  /**
   * Get all changes until a date specified as parameter
   */
  def getChanges(notBefore: Option[DateTime] = None): Box[Seq[ResultRepairedReport]]

  /**
   * Get all changes until a date specified as parameter and regroup them by interval of hours
   */
  def getChangesByInterval(intervalLength : Int = 6, notBefore: Option[DateTime]  = None) : Box[Map[Interval,Seq[ResultRepairedReport]]]
}


class NodeChangesServiceImpl(
    reportsRepository: ReportsRepository
  , changesMaxAge    : Int = 3 // in days
) extends NodeChangesService with Loggable {

  override def getChanges(notBefore: Option[DateTime]): Box[Seq[ResultRepairedReport]] = {
    reportsRepository.getChangeReports(notBefore.getOrElse(DateTime.now.minusDays(changesMaxAge)))
  }

  /**
   * Get all changes until a date specified as parameter and regroup them by interval of hours
   */
  override def getChangesByInterval(intervalLength : Int = 6, notBefore: Option[DateTime]  = None)={

    // Limit of changes.
    val limit = notBefore.getOrElse(DateTime.now.minusDays(changesMaxAge))
    val now = DateTime.now

    // We want our first interval limit to be 00:00 | 06:00 | 12:00 o| 18:00
    val firstInterval = {
      val firstHour =
        if ( now isBefore now.withHourOfDay(6) ) {
          now.withHourOfDay(0)
        } else {
          if (now isBefore now.withHourOfDay(12)) {
            now.withHourOfDay(6)
          } else {
            if (now isBefore now.withHourOfDay(18)) {
              now.withHourOfDay(12)
            } else {
              now.withHourOfDay(18)
        } } }
      // Fix minutes to 0
      firstHour.withMinuteOfHour(0)
    }

    // Compute intervals of changes recursively from the last interval lower
    def computeIntervals(previousLowerBound : DateTime)  : List[Interval] = {
      if ( previousLowerBound isBefore limit) {
        // Our last bound is before the limit, do not create a interval
        Nil
      } else {
        val nextLowerBound = previousLowerBound.minusHours(intervalLength)
        val interval = new Interval(nextLowerBound,previousLowerBound)
        interval :: computeIntervals(nextLowerBound)
      }
    }

    // compute intervals
    val intervals = new Interval(firstInterval,now) :: computeIntervals(firstInterval )


    // Regroup changes by interval
    for {
       changes <- getChanges(notBefore)
    } yield {
      ( for {
         interval <- intervals
      } yield {
        (interval,changes.filter(interval contains _.executionTimestamp))
      } ).toMap
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

case class NodeChanges (
  changes : Seq[ResultRepairedReport]
) {
  def changesByInterval (intervalLentgth : Int) = {

  }
}
