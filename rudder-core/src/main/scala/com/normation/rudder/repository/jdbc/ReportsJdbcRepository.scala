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

import java.sql.ResultSet
import java.sql.Timestamp

import scala.collection.JavaConverters.asScalaBufferConverter

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports._
import com.normation.rudder.domain.reports.Reports
import com.normation.rudder.reports.execution.AgentRun
import com.normation.rudder.reports.execution.AgentRunId
import com.normation.rudder.repository.ReportsRepository

import org.joda.time._
import org.slf4j.{Logger,LoggerFactory}
import org.springframework.jdbc.core._

import net.liftweb.common._
import net.liftweb.common.Box._
import org.springframework.dao.DataAccessException

class ReportsJdbcRepository(jdbcTemplate : JdbcTemplate) extends ReportsRepository with Loggable {

  val reports = "ruddersysevents"
  val archiveTable = "archivedruddersysevents"

  private[this] val reportsExecutionTable = "reportsexecution"
  private[this] val common_reports_column = "executiondate, nodeid, ruleid, directiveid, serial, component, keyvalue, executiontimestamp, eventtype, policy, msg"

  //just an utility to remove multiple spaces in query so that we can vertically align them an see what part are changing - the use
  //of greek p is to discurage use elsewhere
  private[this] def þ(s: String) = s.replaceAll("""\s+""", " ")
  private[this] val baseQuery         = þ(s"select     ${common_reports_column} from RudderSysEvents         where 1=1 ")
  private[this] val idQuery           = þ(s"select id, ${common_reports_column} from ruddersysevents         where 1=1 ")
  private[this] val baseArchivedQuery = þ(s"select     ${common_reports_column} from archivedruddersysevents where 1=1 ")

  // find the last full run per node
  // we are not looking for older request than interval minutes
  private[this] def lastQuery(interval: Int)       = þ(s"select nodeid as Node, max(date) as Time from reportsexecution where date > (now() - interval '${interval} minutes') and complete = true                group by nodeid")
  private[this] def lastQueryByNode(interval: Int) = þ(s"select nodeid as Node, max(date) as Time from reportsexecution where date > (now() - interval '${interval} minutes') and nodeid = ? and complete = true group by nodeid")
  private[this] def joinQuery(interval: Int)       = þ(s"select ${common_reports_column} from RudderSysEvents join (" + lastQuery(interval) +" )       as Ordering on Ordering.Node = nodeid and executionTimeStamp = Ordering.Time where 1=1 ")
  private[this] def joinQueryByNode(interval: Int) = þ(s"select ${common_reports_column} from RudderSysEvents join (" + lastQueryByNode(interval) +" ) as Ordering on Ordering.Node = nodeid and executionTimeStamp = Ordering.Time where 1=1 ")

  private[this] def boxed[A](name: String)(body: => A): Box[A] = {
    try {
      Full(body)
    } catch {
      case ex: Exception =>
        val msg = "Error when trying to " + name
        logger.error(msg, ex)
        Failure(msg, Full(ex), Empty)
    }
  }

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
    val query =
      s"""select ${common_reports_column}
          from RudderSysEvents
          where (nodeid, executiontimestamp) in (VALUES ${nodeParam})
      """ + ruleClause

      boxed(s"get last run reports for ${runs.size} nodes")(jdbcTemplate.query(query, ReportsMapper).asScala.groupBy( _.nodeId))
    }
  }

  override def findReportsByNode(nodeId   : NodeId) : Seq[Reports] = {
    jdbcTemplate.query(
        baseQuery + " and nodeId = ?  ORDER BY id desc LIMIT 1000"
      , Array[AnyRef](nodeId.value)
      , ReportsMapper
    ).asScala
  }

  override def findReportsByNode(
      nodeId   : NodeId
    , ruleId   : RuleId
    , serial   : Int
    , beginDate: DateTime
    , endDate  : Option[DateTime]
  ): Seq[Reports] = {
    import scala.collection.mutable.Buffer
    var query = baseQuery + " and nodeId = ?  and ruleId = ? and serial = ? and executionTimeStamp >= ?"
    var array = Buffer[AnyRef](nodeId.value,
        ruleId.value,
        new java.lang.Integer(serial),
        new Timestamp(beginDate.getMillis))

    endDate match {
      case None =>
      case Some(date) => query = query + " and executionTimeStamp < ?"; array += new Timestamp(date.getMillis)
    }

    query = query + " ORDER BY executionTimeStamp asc"
    jdbcTemplate.query(query,
          array.toArray[AnyRef],
          ReportsMapper).asScala

  }

  override def findExecutionTimeByNode(
      nodeId   : NodeId
    , beginDate: DateTime
    , endDate  : Option[DateTime]
  ) : Seq[DateTime] = {

    val array : List[AnyRef] = nodeId.value :: new Timestamp(beginDate.getMillis) :: endDate.map( endDate => new Timestamp(endDate.getMillis) :: Nil).getOrElse(Nil)

    val endQuery  : String =  endDate.map{_ => "and date < ?" }.getOrElse("")

    val query = s"select distinct date from reportsexecution where nodeId = ? and date >= ? ${endQuery} order by date"

    jdbcTemplate.query(
        query
      , array.toArray[AnyRef]
      , ExecutionTimeMapper
    ).asScala
  }

  override def getOldestReports() : Box[Option[Reports]] = {
    jdbcTemplate.query(baseQuery + " order by executionTimeStamp asc limit 1",
          ReportsMapper).asScala match {
      case seq if seq.size > 1 => Failure("Too many answer for the latest report in the database")
      case seq => Full(seq.headOption)

    }
  }

  override def getOldestArchivedReports() : Box[Option[Reports]] = {
    jdbcTemplate.query(baseArchivedQuery + " order by executionTimeStamp asc limit 1",
          ReportsMapper).asScala match {
      case seq if seq.size > 1 => Failure("Too many answer for the latest report in the database")
      case seq => Full(seq.headOption)

    }
  }

  override def getNewestReports() : Box[Option[Reports]] = {
    jdbcTemplate.query(baseQuery + " order by executionTimeStamp desc limit 1",
          ReportsMapper).asScala match {
      case seq if seq.size > 1 => Failure("Too many answer for the latest report in the database")
      case seq => Full(seq.headOption)

    }
  }

  override def getNewestArchivedReports() : Box[Option[Reports]] = {
    jdbcTemplate.query(baseArchivedQuery + " order by executionTimeStamp desc limit 1",
          ReportsMapper).asScala match {
      case seq if seq.size > 1 => Failure("Too many answer for the latest report in the database")
      case seq => Full(seq.headOption)

    }
  }

  override def getDatabaseSize(databaseName:String) : Box[Long] = {
    try {
      jdbcTemplate.query(
          s"""SELECT pg_total_relation_size('${databaseName}') as "size" """
        , DatabaseSizeMapper
      ).asScala match {
        case seq if seq.size > 1 => Failure(s"Too many answer for the latest report in the database '${databaseName}'")
        case seq  => seq.headOption ?~! s"The query used to find database '${databaseName}' size did not return any tuple"

      }
    } catch {
      case e: DataAccessException =>
        val msg ="Could not compute the size of the database, cause is " + e.getMessage()
        logger.error(msg)
        Failure(msg,Full(e),Empty)
    }
  }

  override def archiveEntries(date : DateTime) : Box[Int] = {
    try{
      val migrate = jdbcTemplate.execute(s"""
          insert into %s
                (id, ${common_reports_column})
          (select id, ${common_reports_column} from %s
        where executionTimeStamp < '%s')
        """.format(archiveTable,reports,date.toString("yyyy-MM-dd") )
       )

      logger.debug(s"""Archiving report with SQL query: [[
                   | insert into %s (id, ${common_reports_column})
                   | (select id, ${common_reports_column} from %s
                   | where executionTimeStamp < '%s')
                   |]]""".stripMargin.format(archiveTable,reports,date.toString("yyyy-MM-dd")))

      val delete = jdbcTemplate.update("""
        delete from %s  where executionTimeStamp < '%s'
        """.format(reports,date.toString("yyyy-MM-dd") )
      )

      jdbcTemplate.execute("vacuum %s".format(reports))

      Full(delete)
    } catch {
       case e: DataAccessException =>
         val msg ="Could not archive entries in the database, cause is " + e.getMessage()
         logger.error(msg)
         Failure(msg,Full(e),Empty)
     }

  }

  override def deleteEntries(date : DateTime) : Box[Int] = {

    logger.debug("""Deleting report with SQL query: [[
                   | delete from %s  where executionTimeStamp < '%s'
                   |]] and: [[
                   | delete from %s  where executionTimeStamp < '%s'
                   |]] and: [[
                   | delete from %s  where date < '%s'
                   |]]""".stripMargin.format(reports, date.toString("yyyy-MM-dd")
                                           , archiveTable, date.toString("yyyy-MM-dd")
                                           , reportsExecutionTable, date.toString("yyyy-MM-dd")))
    try{

      val delete = jdbcTemplate.update("""
          delete from %s where executionTimeStamp < '%s'
          """.format(reports,date.toString("yyyy-MM-dd") )
      ) + jdbcTemplate.update("""
          delete from %s  where executionTimeStamp < '%s'
          """.format(archiveTable,date.toString("yyyy-MM-dd") )
      )+ jdbcTemplate.update("""
          delete from %s  where date < '%s'
          """.format(reportsExecutionTable,date.toString("yyyy-MM-dd") )
      )

      jdbcTemplate.execute("vacuum %s".format(reports))
      jdbcTemplate.execute("vacuum full %s".format(archiveTable))
      jdbcTemplate.execute("vacuum %s".format(reportsExecutionTable))

      Full(delete)
    } catch {
       case e: DataAccessException =>
         val msg ="Could not delete entries in the database, cause is " + e.getMessage()
         logger.error(msg)
         Failure(msg,Full(e),Empty)
     }
  }

  override def getHighestId() : Box[Long] = {
    val query = s"select id from RudderSysEvents order by id desc limit 1"
    try {
      jdbcTemplate.query(query, IdMapper).asScala match {
        case seq if seq.size > 1 => Failure("Too many answer for the highest id in the database")
        case seq                 => seq.headOption ?~! "No report where found in database (and so, we can not get highest id)"
      }
    } catch {
      case e:DataAccessException =>
        logger.error("Could not fetch highest id in the database. Reason is : %s".format(e.getMessage()))
        Failure(e.getMessage())
    }
  }

  override def getLastHundredErrorReports(kinds:List[String]) : Box[Seq[(Long, Reports)]] = {
    val query = "%s and (%s) order by executiondate desc limit 100".format(idQuery,kinds.map("eventtype='%s'".format(_)).mkString(" or "))
      try {
        Full(jdbcTemplate.query(query,ReportsWithIdMapper).asScala)
      } catch {
        case e:DataAccessException =>
        logger.error("Could not fetch last hundred reports in the database. Reason is : %s".format(e.getMessage()))
        Failure("Could not fetch last hundred reports in the database. Reason is : %s".format(e.getMessage()))
      }
  }

  override def getReportsWithLowestId : Box[Option[(Long, Reports)]] = {
    jdbcTemplate.query(s"${idQuery} order by id asc limit 1",
          ReportsWithIdMapper).asScala match {
      case seq if seq.size > 1 => Failure("Too many answer for the latest report in the database")
      case seq => Full(seq.headOption)

    }
  }

  /**
   * From an id and an end date, return a list of AgentRun, and the max ID that has been considered
   */
  override def getReportsfromId(lastProcessedId: Long, endDate: DateTime): Box[(Seq[AgentRun], Long)] = {

    def getMaxId(fromId: Long, before: DateTime): Box[Long] = {

        val queryForMaxId = "select max(id) as id from RudderSysEvents where id > ? and executionTimeStamp < ?"
        val params = Array[AnyRef](new java.lang.Long(fromId), new Timestamp(endDate.getMillis))

        try {
           jdbcTemplate.query(queryForMaxId, params, IdMapper).asScala match {
             case seq if seq.size > 1 => Failure("Too many answer for the highest id in the database")
             case seq =>
               //sometimes, max on postgres return 0
               val newId = scala.math.max(fromId, seq.headOption.getOrElse(0L))
               Full(newId)
           }
         } catch {
           case e:DataAccessException =>
             val msg = s"Could not fetch max id for execution in the database. Reason is : ${e.getMessage}"
             logger.error(msg)
             Failure(msg, Full(e), Empty)
         }
    }

    def getRuns(fromId: Long, toId: Long): Box[Seq[AgentRun]] = {
      import java.lang.{ Long => jLong }
      val getRunsQuery = """select distinct
                          |  T.nodeid, T.executiontimestamp, coalesce(C.iscomplete, false) as complete, coalesce(C.msg, '') as nodeconfigid, T.insertionid
                          |from
                          |  (select nodeid, executiontimestamp, min(id) as insertionid from ruddersysevents where id > ? and id <= ? group by nodeid, executiontimestamp) as T
                          |left join
                          |  (select
                          |    true as isComplete, nodeid, executiontimestamp, msg
                          |  from
                          |    ruddersysevents where id > ? and id <= ? and
                          |    ruleId like 'hasPolicyServer%' and
                          |    component = 'common' and keyValue = 'EndRun'
                          |  ) as C
                          |on T.nodeid = C.nodeid and T.executiontimestamp = C.executiontimestamp""".stripMargin
      if(fromId >= toId) {
        Full(Seq())
      } else {
        val params = Array[AnyRef](new jLong(fromId), new jLong(toId), new jLong(fromId), new jLong(toId))
        try {
          Full(jdbcTemplate.query(getRunsQuery, params, ReportsExecutionMapper).asScala)
        } catch {
          case e:DataAccessException =>
            val msg = s"Could not fetch agent executions in the database. Reason is : ${e.getMessage}"
            logger.error(msg)
            Failure(msg, Full(e), Empty)
        }
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
    for {
      toId    <- getMaxId(lastProcessedId, endDate)
      reports <- getRuns(lastProcessedId, toId)
    } yield {
      (distinctRuns(reports), toId)
    }
  }

  override def countChangeReports(startTime: DateTime, intervalInHour: Int): Box[Map[RuleId, Map[Interval, Int]]] = {
    //be careful, extract from 'epoch' gives seconds, not millis
    val mod = intervalInHour * 3600
    val start = startTime.getMillis / 1000
    val query = s"select ruleid, count(*) as number, ( extract('epoch' from executiontimestamp)::bigint - ${start})/${mod} as interval from ruddersysevents " +
                s"where eventtype = 'result_repaired' and executionTimeStamp > '${new Timestamp(startTime.getMillis)}'::timestamp group by ruleid, interval;"

    //that query will return interval number in the "interval" column. So interval=0 mean
    //interval from startTime to startTime + intervalInHour hours, etc.
    //=> little function to build an interval from its number
    def interval(t:DateTime, int: Int)(num: Int) = new Interval(t.plusHours(num*int), t.plusHours((num+1)*int))

    try {
      val res = jdbcTemplate.query(query, CountChangesMapper(interval(startTime, intervalInHour))).asScala
      //group by ruleId, and then interval
      val groups = res.groupBy(_._1).mapValues( _.groupBy(_._3).mapValues(_.map( _._2).head)) //head non empty due to groupBy, and seq == 1 by query
      Full(groups)
    } catch {
      case ex: Exception =>
        val error = Failure("Error when trying to retrieve change reports", Some(ex), Empty)
        logger.error(error)
        error
    }
  }


  override def getChangeReportsOnInterval(lowestId: Long, highestId: Long): Box[Seq[ResultRepairedReport]] = {
    val query = s"${baseQuery} and eventtype='${Reports.RESULT_REPAIRED}' and id >= ${lowestId} and id <= ${highestId} order by executionTimeStamp asc"
    transformJavaList(jdbcTemplate.query(query, ReportsMapper))
  }

  override def getChangeReportsByRuleOnInterval(ruleId: RuleId, interval: Interval, limit: Option[Int]): Box[Seq[ResultRepairedReport]] = {
    val l = limit match {
      case Some(i) if(i > 0) => s"limit ${i}"
      case _                 => ""
    }
    val query = s"${baseQuery} and eventtype='${Reports.RESULT_REPAIRED}' and ruleid='${ruleId.value}' " +
                s" and executionTimeStamp >  '${new Timestamp(interval.getStartMillis)}'::timestamp " +
                s" and executionTimeStamp <= '${new Timestamp(interval.getEndMillis)  }'::timestamp order by executionTimeStamp asc ${l}"

    transformJavaList(jdbcTemplate.query(query, ReportsMapper))
  }

  private[this] def transformJavaList(l: java.util.List[Reports]) = {
      try {
        Full(l.asScala.collect{case r:ResultRepairedReport => r})
      } catch {
        case ex: Exception =>
          val error = Failure("Error when trying to retrieve change reports", Some(ex), Empty)
          logger.error(error)
          error
      }
    }


  override def getReportsByKindBeetween(lower: Long, upper: Long, limit: Int, kinds: List[String]) : Box[Seq[(Long,Reports)]] = {
    if (lower>=upper)
      Full(Nil)
    else{
      val query = s"${idQuery} and id between '${lower}' and '${upper}' and (${kinds.map(k => s"eventtype='${k}'").mkString(" or ")}) order by id asc limit ${limit}"
      try {
        Full(jdbcTemplate.query(query, ReportsWithIdMapper).asScala)
      } catch {
        case e:DataAccessException =>
        logger.error("Could not fetch reports between ids %d and %d in the database. Reason is : %s".format(lower,upper,e.getMessage()))
        Failure("Could not fetch reports between ids %d and %d in the database. Reason is : %s".format(lower,upper,e.getMessage()))
      }
    }
  }
}

final case class CountChangesMapper(intMapper: Int => Interval) extends RowMapper[(RuleId, Int, Interval)] {
   def mapRow(rs : ResultSet, rowNum: Int) : (RuleId, Int, Interval) = {
        (
          RuleId(rs.getString("ruleId"))
        , rs.getInt("number")
        , intMapper(rs.getInt("interval"))
      )
    }
}

object ReportsMapper extends RowMapper[Reports] {
   def mapRow(rs : ResultSet, rowNum: Int) : Reports = {
        Reports(
            new DateTime(rs.getTimestamp("executionDate"))
          , RuleId(rs.getString("ruleId"))
          , DirectiveId(rs.getString("directiveId"))
          , NodeId(rs.getString("nodeId"))
          , rs.getInt("serial")
          , rs.getString("component")
          , rs.getString("keyValue")
          , new DateTime(rs.getTimestamp("executionTimeStamp"))
          , rs.getString("eventType")
          , rs.getString("msg")
          //what about policy ? => contains the technique name, not used directly by Rudder
        )
    }
}

object ExecutionTimeMapper extends RowMapper[DateTime] {
   def mapRow(rs : ResultSet, rowNum: Int) : DateTime = {
        new DateTime(rs.getTimestamp("date"))
    }
}

object DatabaseSizeMapper extends RowMapper[Long] {
   def mapRow(rs : ResultSet, rowNum: Int) : Long = {
        rs.getLong("size")
    }
}

object IdMapper extends RowMapper[Long] {
   def mapRow(rs : ResultSet, rowNum: Int) : Long = {
        rs.getLong("id")
    }
}

object ReportsWithIdMapper extends RowMapper[(Long, Reports)] {
  def mapRow(rs : ResultSet, rowNum: Int) : (Long, Reports) = {
    (IdMapper.mapRow(rs, rowNum), ReportsMapper.mapRow(rs, rowNum))
    }
}

object ReportsExecutionMapper extends RowMapper[AgentRun] {

  //we want to match: """End execution with config [75rz605art18a05]"""
  // the (?s) allows . to match any characters, even non displayable ones
  val nodeConfigVersionRegex = """(?s).+\[([^\]]+)\].*""".r

   def mapRow(rs : ResultSet, rowNum: Int) : AgentRun = {
     AgentRun(
         AgentRunId(NodeId(rs.getString("nodeid")), new DateTime(rs.getTimestamp("executiontimestamp")))
       , {
           val s = rs.getString("nodeconfigid")
           if(s == null) {
             None
           } else {
             s match {
                //check if we have the version and modify the report accordingly
                case nodeConfigVersionRegex(v) =>
                  Some(NodeConfigId(v))
                case _ =>
                  None
             }
           }
         }
       , rs.getBoolean("complete")
       , rs.getLong("insertionid")
     )
    }
}
