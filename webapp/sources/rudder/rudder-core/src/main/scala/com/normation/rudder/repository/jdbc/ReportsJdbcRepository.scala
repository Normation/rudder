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

package com.normation.rudder.repository.jdbc

import cats.implicits.*
import com.normation.errors.IOResult
import com.normation.inventory.domain.NodeId
import com.normation.rudder.db.Doobie
import com.normation.rudder.db.Doobie.*
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.*
import com.normation.rudder.reports.execution.AgentRun
import com.normation.rudder.reports.execution.AgentRunId
import com.normation.rudder.repository.ReportsRepository
import doobie.*
import doobie.implicits.*
import java.sql.Timestamp
import net.liftweb.common.*
import org.joda.time.*
import org.joda.time.format.ISODateTimeFormat
import zio.interop.catz.*
import zio.syntax.*

class ReportsJdbcRepository(doobie: Doobie) extends ReportsRepository with Loggable {
  import doobie.*

  val reports = "ruddersysevents"

  private val reportsExecutionTable = "reportsexecution"
  private val common_reports_column =
    "executiondate, ruleid, directiveid, nodeid, reportid, component, keyvalue, executiontimestamp, eventtype, msg"
  // When we want reports we already know the type (request with where clause on eventtype) we do not want eventtype in request because it will be used as value for message in corresponding case class
  private val typed_reports_column  =
    "executiondate, ruleid, directiveid, nodeid, reportid, component, keyvalue, executiontimestamp, msg"

  // just an utility to remove multiple spaces in query so that we can vertically align them an see what part are changing - the use
  // of greek p is to discurage use elsewhere
  private def þ(s: String) = s.replaceAll("""\s+""", " ")
  private val baseQuery  = þ(s"select     ${common_reports_column} from RudderSysEvents         where 1=1 ")
  private val typedQuery = þ(s"select     ${typed_reports_column}  from RudderSysEvents         where 1=1 ")
  private val idQuery    = þ(s"select id, ${common_reports_column} from ruddersysevents         where 1=1 ")

  // We assume that this method is called with a limited list of runs
  override def getExecutionReports(
      runs: Set[AgentRunId]
  ): IOResult[Map[NodeId, Seq[Reports]]] = {
    runs.map(n => (n.nodeId.value, n.date)).toList.toNel match {
      case None             => Map().succeed
      case Some(nodeValues) =>
        val values = Fragments.in(fr"(nodeid, executiontimestamp)", nodeValues)
        val where  = Fragments.whereAnd(values)

        val q =
          sql"select executiondate, ruleid, directiveid, nodeid, reportid, component, keyvalue, executiontimestamp, eventtype, msg from RudderSysEvents " ++ where

        transactIOResult(s"Error when trying to get last run reports for ${runs.size} nodes")(xa =>
          q.query[Reports].to[Vector].transact(xa)
        ).map(_.groupBy(_.nodeId))
    }
  }

  override def findReportsByNode(nodeId: NodeId): Vector[Reports] = {
    val q = Query[NodeId, Reports](baseQuery + " and nodeId = ? order by id desc limit 1000", None).toQuery0(nodeId)
    // not a boxed return for that one?
    transactRunEither(xa => q.to[Vector].transact(xa)) match {
      case Right(x) => x
      case Left(ex) =>
        logger.error(s"Error when trying to find reports by node: ${ex.getMessage}")
        Vector()
    }
  }

  override def findReportsByNodeByRun(
      nodeId:  NodeId,
      runDate: DateTime
  ): Vector[Reports] = {
    val q = Query[(NodeId, DateTime), Reports](
      baseQuery +
      " and nodeId = ? and executionTimeStamp = ? ORDER BY executionTimeStamp asc",
      None
    ).toQuery0((nodeId, runDate))
    transactRunEither(xa => q.to[Vector].transact(xa)) match {
      case Right(x) => x
      case Left(ex) =>
        logger.error(s"Error when trying to find reports by node run: ${ex.getMessage}")
        Vector()
    }
  }

  override def findReportsByNodeOnInterval(
      nodeId: NodeId,
      start:  Option[DateTime],
      end:    Option[DateTime]
  ): Vector[Reports] = {

    val q = (start, end) match {
      case (Some(start), Some(end)) =>
        Query[(NodeId, DateTime, DateTime), Reports](
          baseQuery +
          " and nodeId = ? and executionTimeStamp >= ?  and executionTimeStamp < ? ORDER BY executionTimeStamp asc",
          None
        ).toQuery0((nodeId, start, end))
      case (None, Some(end))        =>
        Query[(NodeId, DateTime), Reports](
          baseQuery +
          " and nodeId = ? and executionTimeStamp <= ? ORDER BY executionTimeStamp asc",
          None
        ).toQuery0((nodeId, end))
      case (Some(start), None)      =>
        Query[(NodeId, DateTime), Reports](
          baseQuery +
          " and nodeId = ? and executionTimeStamp >= ? ORDER BY executionTimeStamp asc",
          None
        ).toQuery0((nodeId, start))
      case (None, None)             =>
        Query[NodeId, Reports](
          baseQuery +
          " and nodeId = ? ORDER BY executionTimeStamp asc",
          None
        ).toQuery0(nodeId)
    }

    transactRunEither(xa => q.to[Vector].transact(xa)) match {
      case Right(x) => x
      case Left(ex) =>
        logger.error(s"Error when trying to find reports by node on an interval: ${ex.getMessage}")
        Vector()
    }
  }

  override def getReportsInterval(): Box[(Option[DateTime], Option[DateTime])] = {
    transactRunBox(xa => {
      (for {
        oldest <- query[DateTime]("""select executiontimestamp from ruddersysevents
                                   order by executionTimeStamp asc  limit 1""").option
        newest <- query[DateTime]("""select executiontimestamp from ruddersysevents
                                   order by executionTimeStamp desc limit 1""").option
      } yield {
        (oldest, newest)
      }).transact(xa)
    }) ?~! "Could not fetch the reports interval from the database."
  }

  override def getDatabaseSize(databaseName: String): Box[Long] = {
    val q = query[Long](s"""select pg_total_relation_size('${databaseName}') as "size" """).unique
    transactRunBox(xa => q.transact(xa)) ?~! "Could not compute the size of the database"
  }

  override def deleteEntries(date: DateTime): Box[Int] = {

    val dateAt_0000 = date.toString("yyyy-MM-dd")
    val d1          = s"delete from ${reports} where executionTimeStamp < '${dateAt_0000}'"
    val d3          = s"delete from ${reportsExecutionTable} where date < '${dateAt_0000}'"

    val v1 = s"vacuum ${reports}"
    val v3 = s"vacuum ${reportsExecutionTable}"

    logger.debug(s"""Deleting report with SQL query: [[
                    | ${d1}
                    |]] and: [[
                    | ${d3}
                    |]]""".stripMargin)

    (for {
      i <- transactRunEither(xa => (d1 :: d3 :: Nil).traverse(q => Update0(q, None).run).transact(xa))
      // Vacuum cannot be run in a transaction block, it has to be in an autoCommit block
      _ <- {
        (v1 :: v3 :: Nil).map { vacuum =>
          transactRunEither(xa => (FC.setAutoCommit(true) *> Update0(vacuum, None).run <* FC.setAutoCommit(false)).transact(xa))
        }.sequence
      }
    } yield {
      i
    }) match {
      case Left(ex) =>
        val msg = "Could not delete entries in the database, cause is " + ex.getMessage()
        logger.error(msg)
        Failure(msg, Full(ex), Empty)
      case Right(i) => Full(i.sum)
    }
  }

  override def deleteLogReports(date: DateTime): Box[Int] = {
    val dateAt = date.toString(ISODateTimeFormat.dateTimeNoMillis())
    val q      = s"delete from ${reports} where executionTimeStamp < '${dateAt}' and eventtype like 'log_%'"

    logger.debug(s"""Deleting log reports with SQL query: [[${q}]]""")
    transactRunBox(xa => Update0(q, None).run.transact(xa))
  }

  override def getHighestId(): Box[Long] = {
    transactRunBox(xa => query[Long](s"""SELECT last_value FROM serial""").unique.transact(xa))
  }

  override def getLastHundredErrorReports(kinds: List[String]): Box[Seq[(Long, Reports)]] = {
    val events = kinds.map(k => s"eventtype='${k}'").mkString(" or ")
    val q      = query[(Long, Reports)](s"${idQuery} and (${events}) order by executiondate desc limit 100")

    transactRunEither(xa => q.to[Vector].transact(xa)) match {
      case Left(e)     =>
        val msg = s"Could not fetch last hundred reports in the database. Reason is : ${e.getMessage}"
        logger.error(msg)
        Failure(msg, Full(e), Empty)
      case Right(list) => Full(list)
    }
  }

  override def getReportsWithLowestId: Box[Option[(Long, Reports)]] = {
    val q = query[(Long, Reports)](s"${idQuery} order by id asc limit 1")
    transactRunBox(xa => q.option.transact(xa))
  }

  def getReportsWithLowestIdFromDate(from: DateTime): Box[Option[(Long, Reports)]] = {
    val q =
      query[(Long, Reports)](s"${idQuery} and executionTimeStamp >= '${new Timestamp(from.getMillis)}' order by id asc limit 1")
    transactRunBox(xa => q.option.transact(xa))
  }

  /**
    *  utilitary methods
    */

  // Get max ID before a datetime
  def getMaxIdBeforeDateTime(fromId: Long, before: DateTime): Box[Option[Long]] = {
    val q = query[Long](
      s"select max(id) as id from RudderSysEvents where id > ${fromId} and executionTimeStamp <  '${new Timestamp(before.getMillis)}'"
    )
    (transactRunBox(xa =>
      q.option.transact(xa)
    ) ?~! s"Could not fetch the highest id before date ${before.toString} in the database")
  }

  /**
   * From an id and an end date, return a list of AgentRun, and the max ID that has been considered
   */
  override def getReportsFromId(lastProcessedId: Long, endDate: DateTime): Box[(Seq[AgentRun], Long)] = {

    def getMaxId(fromId: Long, before: DateTime): ConnectionIO[Long] = {
      val queryForMaxId = "select max(id) as id from RudderSysEvents where id > ? and executionTimeStamp < ?"

      (for {
        res <- Query[(Long, DateTime), Option[Long]](queryForMaxId, None).toQuery0((fromId, endDate)).unique
      } yield {
        // sometimes, max on postgres return 0
        scala.math.max(fromId, res.getOrElse(0L))
      })
    }

    def getRuns(fromId: Long, toId: Long): ConnectionIO[Vector[AgentRun]] = {
      if (fromId >= toId) {
        Vector.empty[AgentRun].pure[ConnectionIO]
      } else {
        /*
         * Here, we use a special mapping for configurationid, because there is a bunch
         * of case where they are not correct in the reports, we need to sort the correct
         * one from the others.
         */
        type T = (String, DateTime, Option[String], Long)

        // we want to match: """End execution with config [75rz605art18a05]"""
        // the (?s) allows . to match any characters, even non displayable ones
        implicit val ReportRead: Read[AgentRun] = {
          Read[T].map((t: T) => {
            val optNodeConfigId = t._3.flatMap(version => {
              version match {
                case "" => None
                case v  => Some(NodeConfigId(v))

              }
            })
            AgentRun(AgentRunId(NodeId(t._1), t._2), optNodeConfigId, t._4)
          })
        }

        val getRunsQuery = """select
                             |  nodeid, executiontimestamp, coalesce(keyvalue, '') as nodeconfigid, id as insertionid
                             |from ruddersysevents where id > ? and id <= ? and
                             |    eventtype = 'control' and
                             |    component = 'end'""".stripMargin
        Query[(Long, Long), AgentRun](getRunsQuery, None).toQuery0((fromId, toId)).to[Vector]
      }
    }

    /*
     * here, we may have several runs with same nodeId/timestamp
     * In that case, we need to keep the one with a configId, if such
     * exists.
     */
    def distinctRuns(seq: Seq[AgentRun]): Seq[AgentRun] = {
      // that one is for a list of agentRun with same id
      @scala.annotation.tailrec
      def recDisctinct(runs: List[AgentRun]): AgentRun = {
        runs match {
          case Nil         =>
            throw new IllegalArgumentException(
              "Error in code: distinctRuns methods should never call the recDistinct one with an empty list"
            )
          // easy, most common case
          case h :: Nil    => h
          case a :: b :: t =>
            if (a == b) {
              recDisctinct(a :: t)
            } else {
              (a, b) match {
                // by default, take the one with a configId.
                case (AgentRun(_, None, _), AgentRun(_, None, _))                       => recDisctinct(a :: t)
                case (AgentRun(_, Some(idA), _), AgentRun(_, None, _))                  => recDisctinct(a :: t)
                case (AgentRun(_, None, _), AgentRun(_, Some(idB), _))                  => recDisctinct(b :: t)
                // this one, with two config id, should never happen, but still...
                // we don't care if they are the same, because we still prefer the one completed, and
                // the one with the higher serial.
                case (AgentRun(_, Some(idA), serialA), AgentRun(_, Some(idB), serialB)) =>
                  // ok.. use serial...
                  if (serialA <= serialB) {
                    recDisctinct(a :: t)
                  } else {
                    recDisctinct(b :: t)
                  }
              }
            }
        }
      }

      seq.groupBy(run => run.agentRunId).view.mapValues(runs => recDisctinct(runs.toList)).values.toSeq
    }

    // actual logic for getReportsfromId
    transactRunBox(xa => {
      (for {
        toId    <- getMaxId(lastProcessedId, endDate)
        reports <- getRuns(lastProcessedId, toId)
      } yield {
        (distinctRuns(reports), toId)
      }).transact(xa)
    }) ?~! s"Could not fetch the last completed runs from database."
  }

  /**
    * returns the changes between startTime and now, using intervalInHour interval size
    */

  override def countChangeReportsByBatch(intervals: List[Interval]): Box[(Long, Map[RuleId, Map[Interval, Int]])] = {
    import com.normation.utils.Control.traverse
    logger.debug(s"Fetching all changes for intervals ${intervals.mkString(",")}")
    val beginTime = System.currentTimeMillis()
    val box: Box[Seq[Vector[(RuleId, Interval, Int, Long)]]] = traverse(intervals) { interval =>
      (transactRunBox(xa => {
        query[(RuleId, Int, Long)](
          s"""select ruleid, count(*) as number, max(id)
          from ruddersysevents
          where eventtype = 'result_repaired' and executionTimeStamp > '${new Timestamp(
              interval.getStartMillis
            )}' and executionTimeStamp <= '${new Timestamp(interval.getEndMillis)}'
          group by ruleid;
      """
        ).to[Vector].transact(xa)
      }) ?~! s"Error when trying to retrieve change reports on interval ${interval.toString}").map { res =>
        res.map { case (ruleid, count, highestId) => (ruleid, interval, count, highestId) }
      }
    }
    val endQuery = System.currentTimeMillis()
    logger.debug(s"Fetched all changes in intervals in ${(endQuery - beginTime)} ms")

    for {
      all <- box.map(_.flatten)
    } yield {
      if (all.isEmpty) {
        (0, Map())
      } else {
        val highest = all.iterator.map(_._4).max
        val byRules = all.groupBy(_._1).map {
          case (id, seq) =>
            (
              id,
              seq.groupBy(_._2).map {
                case (int, seq2) =>
                  (int, seq.map(_._3).head) // seq == 1 by query
              }
            )
        }
        (highest, byRules)
      }
    }
  }

  override def countChangeReports(startTime: DateTime, intervalInHour: Int): Box[Map[RuleId, Map[Interval, Int]]] = {
    // special mapper to retrieve correct interval. It is dependant of starttime / intervalInHour
    implicit val intervalMeta: Get[Interval] = Get[Int].tmap(
      // the query will return interval number in the "interval" column. So interval=0 mean
      // interval from startTime to startTime + intervalInHour hours, etc.
      // here is the mapping to build an interval from its number
      num => new Interval(startTime.plusHours(num * intervalInHour), startTime.plusHours((num + 1) * intervalInHour))
    )

    // be careful, extract from 'epoch' gives seconds, not millis
    val mod   = intervalInHour * 3600
    val start = startTime.getMillis / 1000
    (
      (transactRunBox(xa => {
        query[(RuleId, Int, Interval)](
          s"""select ruleid, count(*) as number, ( extract('epoch' from executiontimestamp)::bigint - ${start})/${mod} as interval
          from ruddersysevents
          where eventtype = 'result_repaired' and executionTimeStamp > '${new Timestamp(startTime.getMillis)}'::timestamp
          group by ruleid, interval;
      """
        ).to[Vector].transact(xa)
      }) ?~! "Error when trying to retrieve change reports").map { res =>
        val groups = {
          res
            .groupBy(_._1)
            .view
            .mapValues(_.groupMapReduce(_._3)(_._2)((a, b) => a))
            .toMap // head non empty due to groupBy, and seq == 1 by query
        }
        groups
      },
      intervalMeta
    )._1 // tricking scalac for false positive unused warning on intervalMeta.
  }

  override def getChangeReportsOnInterval(lowestId: Long, highestId: Long): IOResult[Seq[ChangeForCache]] = {
    transactIOResult(s"Error we getting change reports on [${lowestId}, ${highestId}]")(xa => {
      query[ChangeForCache](s"""select ruleid, executiontimestamp from ruddersysevents where
          eventtype='${Reports.RESULT_REPAIRED}' and id >= ${lowestId} and id <= ${highestId}
       """).to[Vector].transact(xa)
    })
  }

  override def getChangeReportsByRuleOnInterval(
      ruleId:   RuleId,
      interval: Interval,
      limit:    Option[Int]
  ): Box[Seq[ResultRepairedReport]] = {
    val l = limit match {
      case Some(i) if (i > 0) => s"limit ${i}"
      case _                  => ""
    }
    transactRunBox(xa => query[ResultRepairedReport](s"""
      ${typedQuery} and eventtype='${Reports.RESULT_REPAIRED}' and ruleid='${ruleId.serialize}'
      and executionTimeStamp >  '${new Timestamp(interval.getStartMillis)}'::timestamp
      and executionTimeStamp <= '${new Timestamp(interval.getEndMillis)}'::timestamp order by executionTimeStamp asc ${l}
    """).to[Vector].transact(xa))
  }

  override def getReportsByKindBetween(
      lower: Long,
      upper: Option[Long],
      limit: Int,
      kinds: List[String]
  ): Box[Seq[(Long, Reports)]] = {
    upper match {
      case Some(upper) if lower >= upper =>
        Full(Nil)
      case None                          =>
        val q =
          s"${idQuery} and id >= '${lower}' and (${kinds.map(k => s"eventtype='${k}'").mkString(" or ")}) order by id asc limit ${limit}"
        transactRunBox(xa =>
          query[(Long, Reports)](q).to[Vector].transact(xa)
        ) ?~! s"Could not fetch reports between ids ${lower} and ${upper} in the database."
      case Some(upper)                   =>
        val q =
          s"${idQuery} and id between '${lower}' and '${upper}' and (${kinds.map(k => s"eventtype='${k}'").mkString(" or ")}) order by id asc limit ${limit}"
        transactRunBox(xa =>
          query[(Long, Reports)](q).to[Vector].transact(xa)
        ) ?~! s"Could not fetch reports between ids ${lower} and ${upper} in the database."
    }
  }
}
