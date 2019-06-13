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

import java.sql.Timestamp


import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports._
import com.normation.rudder.domain.reports.Reports
import com.normation.rudder.reports.execution.AgentRun
import com.normation.rudder.reports.execution.AgentRunId
import com.normation.rudder.repository.ReportsRepository
import org.joda.time._
import net.liftweb.common._
import doobie._, doobie.implicits._
import cats.implicits._
import com.normation.rudder.db.Doobie._
import com.normation.rudder.db.Doobie

class ReportsJdbcRepository(doobie: Doobie) extends ReportsRepository with Loggable {
  import doobie._

  val reports = "ruddersysevents"
  val archiveTable = "archivedruddersysevents"

  private[this] val reportsExecutionTable = "reportsexecution"
  private[this] val common_reports_column = "executiondate, ruleid, directiveid, nodeid, serial, component, keyvalue, executiontimestamp, eventtype, msg"
  // When we want reports we already know the type (request with where clause on eventtype) we do not want eventtype in request because it will be used as value for message in corresponding case class
  private[this] val typed_reports_column = "executiondate, ruleid, directiveid, nodeid, serial, component, keyvalue, executiontimestamp, msg"

  //just an utility to remove multiple spaces in query so that we can vertically align them an see what part are changing - the use
  //of greek p is to discurage use elsewhere
  private[this] def þ(s: String) = s.replaceAll("""\s+""", " ")
  private[this] val baseQuery         = þ(s"select     ${common_reports_column} from RudderSysEvents         where 1=1 ")
  private[this] val typedQuery        = þ(s"select     ${typed_reports_column}  from RudderSysEvents         where 1=1 ")
  private[this] val idQuery           = þ(s"select id, ${common_reports_column} from ruddersysevents         where 1=1 ")

  override def getExecutionReports(runs: Set[AgentRunId], filterByRules: Set[RuleId]): Box[Map[NodeId, Seq[Reports]]] = {
   if(runs.isEmpty) Full(Map())
   else {
    val nodeParam = runs.map(x => s"('${x.nodeId.value}','${new Timestamp(x.date.getMillis)}'::timestamp)" ).mkString(",")
    val ruleClause = if(filterByRules.isEmpty) ""
                    else s"and ruleid in ${filterByRules.map(_.value).mkString("('", "','" , "')")}"
    /*
     * be careful in the number of parenthesis for "in values", it is:
     * ... in (VALUES ('a', 'b') );
     * ... in (VALUES ('a', 'b'), ('c', 'd') );
     * etc. No more, no less.
     */
    query[Reports](
      s"""select ${common_reports_column}
          from RudderSysEvents
          where (nodeid, executiontimestamp) in (VALUES ${nodeParam})
      """ + ruleClause).to[Vector].transact(xa).attempt.unsafeRunSync.map( _.groupBy( _.nodeId)) ?~!
      s"Error when trying to get last run reports for ${runs.size} nodes"
    }
  }

  override def findReportsByNode(nodeId   : NodeId) : Vector[Reports] = {
    val q = Query[NodeId, Reports](baseQuery + " and nodeId = ? order by id desc limit 1000", None).toQuery0(nodeId)
    // not a boxed return for that one?
    q.to[Vector].transact(xa).unsafeRunSync
  }

  override def findReportsByNodeOnInterval(
      nodeId: NodeId
    , start : DateTime
    , end   : DateTime
  ) : Vector[Reports] = {
    val q = Query[(NodeId, DateTime, DateTime), Reports](baseQuery +
        " and nodeId = ? and executionTimeStamp >= ?  and executionTimeStamp < ? ORDER BY executionTimeStamp asc"
      , None).toQuery0((nodeId, start, end))
    q.to[Vector].transact(xa).unsafeRunSync
  }

  override def getReportsInterval(): Box[(Option[DateTime], Option[DateTime])] = {
    (for {
      oldest <- query[DateTime]("""select executiontimestamp from ruddersysevents
                                   order by executionTimeStamp asc  limit 1""").option
      newest <- query[DateTime]("""select executiontimestamp from ruddersysevents
                                   order by executionTimeStamp desc limit 1""").option
    } yield {
      (oldest, newest)
    }).transact(xa).attempt.unsafeRunSync ?~! "Could not fetch the reports interval from the database."
  }

  override def getArchivedReportsInterval() : Box[(Option[DateTime], Option[DateTime])] = {
    (for {
      oldest <- query[DateTime]("""select executiontimestamp from archivedruddersysevents
                                   order by executiontimestamp asc limit 1""").option
      newest <- query[DateTime]("""select executiontimestamp from archivedruddersysevents
                                   order by executionTimeStamp desc limit 1""").option
    } yield {
      (oldest, newest)
    }).transact(xa).attempt.unsafeRunSync ?~! "Could not fetch the reports interval from the database."
  }

  override def getDatabaseSize(databaseName:String) : Box[Long] = {
    val q = query[Long](s"""select pg_total_relation_size('${databaseName}') as "size" """).unique
    q.transact(xa).attempt.unsafeRunSync ?~! "Could not compute the size of the database"
  }

  override def archiveEntries(date : DateTime) : Box[Int] = {
    val dateAt_0000 = date.toString("yyyy-MM-dd")

    // First, get the bounds for archiving reports
    (for {
      highestArchivedReport <- getHighestArchivedReports()
      lowestReport          <- getLowestReports()
      highestIdBeforeDate   <- getHighestIdBeforeDate(date)
    } yield {
      // compute the lower id to archive
      val lowestToArchive = highestArchivedReport.map { highest => lowestReport match {
            case Some(value) => Math.max(highest + 1, value) // highest +1, so that we don't take the one existing in archived reports
            case _           => highest + 1
         }
      }

      val lowerBound = lowestToArchive.map { x => s" and id >= ${x} " }.getOrElse("")

      // If highestIdBeforeDate is None, then it means we don't have to archive anything, we can skip all this part
      highestIdBeforeDate match {
        case None =>
          logger.debug(s"No reports to archive before ${dateAt_0000}; skipping")
          Full(0)
        case Some(id) =>
          val higherBound = s" and id <= ${id} "


          val archiveQuery =  s"""
              insert into ${archiveTable}
                    (id, ${common_reports_column})
              (select id, ${common_reports_column} from ${reports}
                      where 1=1 ${lowerBound} ${higherBound}
              )
              """

          val deleteQuery = s"""delete from ${reports} where 1=1 ${higherBound}"""


          val vacuum = s"vacuum ${reports}"

          logger.debug(s"""Archiving and deleting reports with SQL query: [[
                 | ${archiveQuery}
                 | ${deleteQuery}
                 |]]""".stripMargin)

          (for {
            i <- (archiveQuery :: deleteQuery :: Nil).traverse(q => Update0(q, None).run).transact(xa).attempt.unsafeRunSync
            _ = logger.debug("Archiving and deleting done, starting to vacuum reports table")
            // Vacuum cannot be run in a transaction block, it has to be in an autoCommit block
            _ <- (FC.setAutoCommit(true) *> Update0(vacuum, None).run <* FC.setAutoCommit(false)).transact(xa).attempt.unsafeRunSync
          } yield {
            i
          }) match {
            case Left(ex) =>
              val msg ="Could not archive entries in the database, cause is " + ex.getMessage()
              logger.error(msg)
              Failure(msg, Full(ex), Empty)
            case Right(i)  => Full(i.sum)
          }
      }
    }) match {
      case Full(f) => f
      case f@Failure(_,_,_) => f
      case Empty => Empty
    }
  }

  // Utilitary methods for reliable archiving of reports
  private[this] def getHighestArchivedReports() : Box[Option[Long]] = {
    query[Long]("select id from archivedruddersysevents order by id desc limit 1").option.transact(xa).attempt.unsafeRunSync ?~!"Could not fetch the highest archived report in the database"
  }

  private[this] def getLowestReports() : Box[Option[Long]] = {
    query[Long]("select id from ruddersysevents order by id asc limit 1").option.transact(xa).attempt.unsafeRunSync ?~! "Could not fetch the lowest report in the database"
  }

  private[this] def getHighestIdBeforeDate(date : DateTime) : Box[Option[Long]] = {
    query[Long](s"select id from ruddersysevents where executionTimeStamp < '${date.toString("yyyy-MM-dd")}' order by id desc limit 1").option.transact(xa).attempt.unsafeRunSync ?~! s"Could not fetch the highest id before date ${date.toString("yyyy-MM-dd")} in the database"
  }

  override def deleteEntries(date : DateTime) : Box[Int] = {

    val dateAt_0000 = date.toString("yyyy-MM-dd")
    val d1 = s"delete from ${reports} where executionTimeStamp < '${dateAt_0000}'"
    val d2 = s"delete from ${archiveTable} where executionTimeStamp < '${dateAt_0000}'"
    val d3 = s"delete from ${reportsExecutionTable} where date < '${dateAt_0000}'"

    val v1 = s"vacuum ${reports}"
    val v2 = s"vacuum full ${archiveTable}"
    val v3 = s"vacuum ${reportsExecutionTable}"

    logger.debug(s"""Deleting report with SQL query: [[
                   | ${d1}
                   |]] and: [[
                   | ${d2}
                   |]] and: [[
                   | ${d3}
                   |]]""".stripMargin)

    (for {
      i <- (d1 :: d2 :: d3 :: Nil).traverse(q => Update0(q, None).run).transact(xa).attempt.unsafeRunSync
           // Vacuum cannot be run in a transaction block, it has to be in an autoCommit block
      _ <- { (v1 :: v2 :: v3 :: Nil).map { vacuum =>
                (FC.setAutoCommit(true) *> Update0(vacuum, None).run <* FC.setAutoCommit(false)).transact(xa).attempt.unsafeRunSync }.sequence
           }
    } yield {
      i
    }) match  {
      case Left(ex) =>
        val msg ="Could not delete entries in the database, cause is " + ex.getMessage()
        logger.error(msg)
        Failure(msg, Full(ex), Empty)
      case Right(i)  => Full(i.sum)
    }
  }

  override def getHighestId() : Box[Long] = {
    query[Long](s"select id from RudderSysEvents order by id desc limit 1").unique.transact(xa).attempt.unsafeRunSync
  }

  override def getLastHundredErrorReports(kinds:List[String]) : Box[Seq[(Long, Reports)]] = {
    val events = kinds.map(k => s"eventtype='${k}'").mkString(" or ")
    val q = query[(Long, Reports)](s"${idQuery} and (${events}) order by executiondate desc limit 100")

    q.to[Vector].transact(xa).attempt.unsafeRunSync match {
      case Left(e)    =>
          val msg = s"Could not fetch last hundred reports in the database. Reason is : ${e.getMessage}"
          logger.error(msg)
          Failure(msg, Full(e), Empty)
      case Right(list) => Full(list)
    }
  }

  override def getReportsWithLowestId : Box[Option[(Long, Reports)]] = {
    val q = query[(Long, Reports)](s"${idQuery} order by id asc limit 1")
    q.option.transact(xa).attempt.unsafeRunSync match {
      case Left(e)    =>
          Failure(e.getMessage, Full(e), Empty)
      case Right(option) => Full(option)

    }
  }

  def getReportsWithLowestIdFromDate(from:DateTime) : Box[Option[(Long, Reports)]] = {
    val q = query[(Long, Reports)](s"${idQuery} and executionTimeStamp >= '${new Timestamp(from.getMillis)}' order by id asc limit 1")
    q.option.transact(xa).attempt.unsafeRunSync match {
      case Left(e)    =>
        Failure(e.getMessage, Full(e), Empty)
      case Right(option) => Full(option)

    }
  }

  /**
    *  utilitary methods
    */

  // Get max ID before a datetime
  def getMaxIdBeforeDateTime(fromId: Long, before: DateTime): Box[Long] = {
    (query[Long](s"select max(id) as id from RudderSysEvents where where id > ${fromId} and executionTimeStamp <  '${new Timestamp(before.getMillis)}'").
      option.transact(xa).attempt.unsafeRunSync ?~! s"Could not fetch the highest id before date ${before.toString} in the database").map(_.getOrElse(fromId))
  }

  /**
   * From an id and an end date, return a list of AgentRun, and the max ID that has been considered
   */
  override def getReportsfromId(lastProcessedId: Long, endDate: DateTime): Box[(Seq[AgentRun], Long)] = {

    def getMaxId(fromId: Long, before: DateTime): ConnectionIO[Long] = {
      val queryForMaxId = "select max(id) as id from RudderSysEvents where id > ? and executionTimeStamp < ?"

      (for {
        res <- Query[(Long, DateTime), Option[Long]](queryForMaxId, None).toQuery0((fromId, endDate)).unique
      } yield {
        //sometimes, max on postgres return 0
        scala.math.max(fromId, res.getOrElse(0L))
      })
    }

    def getRuns(fromId: Long, toId: Long): ConnectionIO[Vector[AgentRun]] = {
      if(fromId >= toId) {
        Vector.empty[AgentRun].pure[ConnectionIO]
      } else {
        /*
         * Here, we use a special mapping for configurationid, because there is a bunch
         * of case where they are not correct in the reports, we need to sort the correct
         * one from the others.
         */
        type T = (String, DateTime, Option[String], Boolean, Long)

        //we want to match: """End execution with config [75rz605art18a05]"""
        // the (?s) allows . to match any characters, even non displayable ones
        implicit val ReportComposite: Composite[AgentRun] = {
           Composite[T].imap(
               (t: T       ) => {
                 val optNodeConfigId = t._3.flatMap(version => version match {
                   case "" => None
                   case v  => Some(NodeConfigId(v))

                 })
                 AgentRun(AgentRunId(NodeId(t._1), t._2), optNodeConfigId, t._4, t._5)
               })(
               (x: AgentRun) => ( x.agentRunId.nodeId.value, x.agentRunId.date
                                , x.nodeConfigVersion.map(_.value), x.isCompleted, x.insertionId)
           )
         }
        val getRunsQuery = """select distinct
                            |  T.nodeid, T.executiontimestamp, coalesce(C.keyvalue, '') as nodeconfigid, coalesce(C.iscomplete, false) as complete, T.insertionid
                            |from
                            |  (select nodeid, executiontimestamp, min(id) as insertionid from ruddersysevents where id > ? and id <= ? group by nodeid, executiontimestamp) as T
                            |left join
                            |  (select
                            |    true as iscomplete, nodeid, executiontimestamp, keyvalue
                            |  from
                            |    ruddersysevents where id > ? and id <= ? and
                            |    eventtype = 'control' and
                            |    component = 'end'
                            |  ) as C
                            |on T.nodeid = C.nodeid and T.executiontimestamp = C.executiontimestamp""".stripMargin

        Query[(Long, Long, Long, Long), AgentRun](getRunsQuery, None).toQuery0((fromId, toId, fromId, toId)).to[Vector]
      }
    }

    /*
     * here, we may have several runs with same nodeId/timestamp
     * In that case, we need to keep the one with a configId, if such
     * exists.
     */
    def distinctRuns(seq: Seq[AgentRun]): Seq[AgentRun] = {
      //that one is for a list of agentRun with same id
      def recDisctinct(runs: List[AgentRun]): AgentRun = {
        runs match {
          case Nil => throw new IllegalArgumentException("Error in code: distinctRuns methods should never call the recDistinct one with an empty list")
          //easy, most common case
          case h :: Nil => h
          case a :: b :: t =>
            if(a == b) {
              recDisctinct(a :: t)
            } else (a, b) match {
              //by default, take the one with a configId.
              case (AgentRun(_, Some(idA), _, _), AgentRun(_, None, _, _)) => recDisctinct(a :: t)
              case (AgentRun(_, None, _, _), AgentRun(_, Some(idB), _, _)) => recDisctinct(b :: t)
              //this one, with two config id, should never happen, but still...
              //we don't care if they are the same, because we still prefer the one completed, and
              //the one with the higher serial.
              case (AgentRun(_, Some(idA), isCompleteA, serialA), AgentRun(_, Some(idB), isCompleteB, serialB)) =>
                if(isCompleteA && !isCompleteB) {
                  recDisctinct(a :: t)
                } else if(!isCompleteA && isCompleteB) {
                  recDisctinct(b :: t)
                } else { //ok.. use serial...
                  if(serialA <= serialB) {
                    recDisctinct(a :: t)
                  } else {
                    recDisctinct(b :: t)
                  }
                }
            }
        }
      }

      seq.groupBy { run => run.agentRunId }.mapValues { runs => recDisctinct(runs.toList) }.values.toSeq
    }

    //actual logic for getReportsfromId
    (for {
      toId    <- getMaxId(lastProcessedId, endDate)
      reports <- getRuns(lastProcessedId, toId)
    } yield {
      (distinctRuns(reports), toId)
    }).transact(xa).attempt.unsafeRunSync ?~! s"Could not fetch the last completed runs from database."
  }

  /**
    * returns the changes between startTime and now, using intervalInHour interval size
    */

  override def countChangeReportsByBatch(intervals : List[Interval]) : Box[Map[RuleId, Map[Interval, Int]]] = {
    import com.normation.utils.Control.sequence
    logger.debug(s"Fetching all changes for intervals ${intervals.mkString(",")}")
    val beginTime = System.currentTimeMillis()
    val rules: Box[Seq[Vector[(RuleId, Interval, Int)]]] = sequence(intervals) { interval =>
      (query[(RuleId, Int)](
        s"""select ruleid, count(*) as number
          from ruddersysevents
          where eventtype = 'result_repaired' and executionTimeStamp > '${new Timestamp(interval.getStartMillis)}' and executionTimeStamp <= '${new Timestamp(interval.getEndMillis)}'
          group by ruleid;
      """
      ).to[Vector].transact(xa).attempt.unsafeRunSync ?~! s"Error when trying to retrieve change reports on interval ${interval.toString}").map { res =>

        res.map { case (ruleid, count) => (ruleid, interval, count) }
      }
    }
    val  endQuery = System.currentTimeMillis()
    logger.debug(s"Fetched all changes in intervals in ${(endQuery - beginTime)} ms")
    rules.map(x => x.flatten.groupBy(_._1).mapValues( _.groupBy(_._2).mapValues(_.map ( _._3).head)))  //head non empty due to groupBy, and seq == 1 by query
  }

  override def countChangeReports(startTime: DateTime, intervalInHour: Int): Box[Map[RuleId, Map[Interval, Int]]] = {
    //special mapper to retrieve correct interval. It is dependant of starttime / intervalInHour
    implicit val intervalMeta: Meta[Interval] = Meta[Int].xmap(
        //the query will return interval number in the "interval" column. So interval=0 mean
        //interval from startTime to startTime + intervalInHour hours, etc.
        //here is the mapping to build an interval from its number
        num => new Interval(startTime.plusHours(num*intervalInHour), startTime.plusHours((num+1)*intervalInHour))
      , itv => 0 //that should never be used, as it doesn't really map anything
    )

    //be careful, extract from 'epoch' gives seconds, not millis
    val mod = intervalInHour * 3600
    val start = startTime.getMillis / 1000



    ((query[(RuleId, Int, Interval)](
      s"""select ruleid, count(*) as number, ( extract('epoch' from executiontimestamp)::bigint - ${start})/${mod} as interval
          from ruddersysevents
          where eventtype = 'result_repaired' and executionTimeStamp > '${new Timestamp(startTime.getMillis)}'::timestamp
          group by ruleid, interval;
      """
    ).to[Vector].transact(xa).attempt.unsafeRunSync ?~! "Error when trying to retrieve change reports").map { res =>
      val groups = res.groupBy(_._1).mapValues( _.groupBy(_._3).mapValues(_.map( _._2).head)) //head non empty due to groupBy, and seq == 1 by query
      groups
    }, intervalMeta)._1 //tricking scalac for false positive unused warning on intervalMeta.
  }

  override def getChangeReportsOnInterval(lowestId: Long, highestId: Long): Box[Seq[ResultRepairedReport]] = {
    query[ResultRepairedReport](s"""
      ${typedQuery} and eventtype='${Reports.RESULT_REPAIRED}' and id >= ${lowestId} and id <= ${highestId}
      order by executionTimeStamp asc
    """).to[Vector].transact(xa).attempt.unsafeRunSync
  }

  override def getChangeReportsByRuleOnInterval(ruleId: RuleId, interval: Interval, limit: Option[Int]): Box[Seq[ResultRepairedReport]] = {
    val l = limit match {
      case Some(i) if(i > 0) => s"limit ${i}"
      case _                 => ""
    }
    query[ResultRepairedReport](s"""
      ${typedQuery} and eventtype='${Reports.RESULT_REPAIRED}' and ruleid='${ruleId.value}'
      and executionTimeStamp >  '${new Timestamp(interval.getStartMillis)}'::timestamp
      and executionTimeStamp <= '${new Timestamp(interval.getEndMillis)  }'::timestamp order by executionTimeStamp asc ${l}
    """).to[Vector].transact(xa).attempt.unsafeRunSync
  }

  override def getReportsByKindBeetween(lower: Long, upper: Long, limit: Int, kinds: List[String]) : Box[Seq[(Long,Reports)]] = {
    if (lower>=upper)
      Full(Nil)
    else{
      val q = s"${idQuery} and id between '${lower}' and '${upper}' and (${kinds.map(k => s"eventtype='${k}'").mkString(" or ")}) order by id asc limit ${limit}"
      query[(Long, Reports)](q).to[Vector].transact(xa).attempt.unsafeRunSync ?~! s"Could not fetch reports between ids ${lower} and ${upper} in the database."
    }
  }
}
