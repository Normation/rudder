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

package com.normation.rudder.repository.jdbc

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.repository.ReportsRepository
import scala.collection._
import org.joda.time._
import org.slf4j.{Logger,LoggerFactory}
import com.normation.rudder.domain.reports.bean._
import com.normation.cfclerk.domain.{Cf3PolicyDraftId}
import org.springframework.jdbc.core._
import java.sql.ResultSet
import java.sql.Timestamp
import scala.collection.JavaConversions._
import net.liftweb.common._
import net.liftweb.common.Box._
import java.sql.Types
import org.springframework.dao.DataAccessException
import com.normation.rudder.reports.execution.ReportExecution

class ReportsJdbcRepository(jdbcTemplate : JdbcTemplate) extends ReportsRepository with Loggable {

  val baseQuery = "select executiondate, nodeid, ruleid, directiveid, serial, component, keyValue, executionTimeStamp, eventtype, policy, msg from RudderSysEvents where 1=1 ";
  val baseArchivedQuery = "select executiondate, nodeid, ruleid, directiveid, serial, component, keyValue, executionTimeStamp, eventtype, policy, msg from archivedruddersysevents where 1=1 ";

  val reportsTable = "ruddersysevents"
  val archiveTable = "archivedruddersysevents"

  val idQuery = "select id, executiondate, nodeid, ruleid, directiveid, serial, component, keyValue, executionTimeStamp, eventtype, policy, msg from ruddersysevents where 1=1 ";

  // find the last full run per node
  // we are not looking for older request that 15 minutes for the moment
  val lastQuery = "select nodeid as Node, max(date) as Time from reportsexecution where date > (now() - interval '15 minutes') and complete = true group by nodeid"
  val lastQueryByNode = "select nodeid as Node, max(date) as Time from reportsexecution where date > (now() - interval '15 minutes') and nodeid = ? and complete = true group by nodeid"

  val joinQuery = "select executiondate, nodeid, ruleId, directiveid, serial, component, keyValue, executionTimeStamp, eventtype, policy, msg from RudderSysEvents join (" + lastQuery +" ) as Ordering on Ordering.Node = nodeid and executionTimeStamp = Ordering.Time where 1=1";
  val joinQueryByNode = "select executiondate, nodeid, ruleId, directiveid, serial, component, keyValue, executionTimeStamp, eventtype, policy, msg from RudderSysEvents join (" + lastQueryByNode +" ) as Ordering on Ordering.Node = nodeid and executionTimeStamp = Ordering.Time where 1=1";

  val fetchExecutions = """select T.nodeid, T.executiontimestamp, coalesce(C.iscomplete, false) as complete from
                          (select distinct nodeid, executiontimestamp from ruddersysevents where id > ? and id <= ?) as T left join
                          (select true as isComplete, nodeid, executiontimestamp from
                            ruddersysevents where id > ? and id <= ? and ruleId like 'hasPolicyServer%' and component = 'common' and keyValue = 'EndRun') as C on T.nodeid = C.nodeid and T.executiontimestamp = C.executiontimestamp"""

  def findReportsByRule(
      ruleId   : RuleId
    , serial   : Option[Int]
    , beginDate: Option[DateTime]
    , endDate  : Option[DateTime]
  ): Seq[Reports] = {
    var query = baseQuery + " and ruleId = ? "
    var array = mutable.Buffer[AnyRef](ruleId.value)

    serial match {
      case None => ;
      case Some(int) => query = query + " and serial = ?"; array += new java.lang.Integer(int)
    }

    beginDate match {
      case None =>
      case Some(date) => query = query + " and executionTimeStamp > ?"; array += new Timestamp(date.getMillis)
    }

    endDate match {
      case None =>
      case Some(date) => query = query + " and executionTimeStamp < ?"; array += new Timestamp(date.getMillis)
    }
println(query)
println("array is " + array)

    jdbcTemplate.query(query,
          array.toArray[AnyRef],
          ReportsMapper).toSeq;

  }

  def findReportsByNode(
      nodeId   : NodeId
    , ruleId   : Option[RuleId]
    , serial   : Option[Int]
    , beginDate: Option[DateTime]
    , endDate  : Option[DateTime]
  ) : Seq[Reports] = {
    var query = baseQuery + " and nodeId = ? "
    var array = mutable.Buffer[AnyRef](nodeId.value)

    ruleId match {
      case None =>
      case Some(cr) => query = query + " and ruleId = ?"; array += cr.value

        // A serial makes sense only if the CR is set
        serial match {
         case None => ;
         case Some(int) => query = query + " and serial = ?"; array += new java.lang.Integer(int)
      }
    }

    beginDate match {
      case None =>
      case Some(date) => query = query + " and executionDate > ?"; array += new Timestamp(date.getMillis)
    }

    endDate match {
      case None =>
      case Some(date) => query = query + " and executionDate < ?"; array += new Timestamp(date.getMillis)
    }

    query = query + " ORDER BY id desc LIMIT 1000"
    jdbcTemplate.query(query,
          array.toArray[AnyRef],
          ReportsMapper).toSeq;


  }

  def findReportsByNode(
      nodeId   : NodeId
    , ruleId   : RuleId
    , serial   : Int
    , beginDate: DateTime
    , endDate  : Option[DateTime]
  ): Seq[Reports] = {
    var query = baseQuery + " and nodeId = ?  and ruleId = ? and serial = ? and executionTimeStamp >= ?"
    var array = mutable.Buffer[AnyRef](nodeId.value,
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
          ReportsMapper).toSeq;


  }


  /**
   * Return the last (really the last, serial wise, with full execution) reports for a rule
   */
  def findLastReportByRule(
      ruleId: RuleId
    , serial: Int
    , node  : Option[NodeId]
  ) : Seq[Reports] = {
    var query = ""
    var array = mutable.Buffer[AnyRef]()

    node match {
      case None =>
          query += joinQuery +  " and ruleId = ? and serial = ? and executionTimeStamp > (now() - interval '15 minutes')"
          array ++= mutable.Buffer[AnyRef](ruleId.value, new java.lang.Integer(serial))
      case Some(nodeId) =>
        query += joinQueryByNode +  " and ruleId = ? and serial = ? and executionTimeStamp > (now() - interval '15 minutes') and nodeId = ?"
        array ++= mutable.Buffer[AnyRef](nodeId.value, ruleId.value, new java.lang.Integer(serial), nodeId.value)
    }

    jdbcTemplate.query(query,
          array.toArray[AnyRef],
          ReportsMapper).toSeq;
  }

  /**
   * Return the last (really the last, serial wise, with full execution) reports for a rule
   */
  def findLastReportsByRules(
      rulesAndSerials: Seq[(RuleId, Int)]
  ) : Seq[Reports] = {
    var query = joinQuery + " and ( 1 != 1 "
    var array = mutable.Buffer[AnyRef]()

    rulesAndSerials.foreach { case (ruleId, serial) =>
          query +=   " or (ruleId = ? and serial = ?)"
          array ++= mutable.Buffer[AnyRef](ruleId.value, new java.lang.Integer(serial))
    }
    query += " ) and executionTimeStamp > (now() - interval '15 minutes')"
    jdbcTemplate.query(query,
          array.toArray[AnyRef],
          ReportsMapper).toSeq;
  }

  def findExecutionTimeByNode(
      nodeId   : NodeId
    , beginDate: DateTime
    , endDate  : Option[DateTime]
  ) : Seq[DateTime] = {
    var query = "select distinct date from reportsexecution where and nodeId = ? and date >= ?"

    var array = mutable.Buffer[AnyRef](nodeId.value, new Timestamp(beginDate.getMillis))

    endDate match {
      case None => ;
      case Some(date) => query = query + " and executiontimestamp < ?"; array += new Timestamp(date.getMillis)
    }

    query = query + " order by executiontimestamp "

    jdbcTemplate.query(query,
          array.toArray[AnyRef],
          ExecutionTimeMapper).toSeq;
  }

  def getOldestReports() : Box[Option[Reports]] = {
    jdbcTemplate.query(baseQuery + " order by executionTimeStamp asc limit 1",
          ReportsMapper).toSeq match {
      case seq if seq.size > 1 => Failure("Too many answer for the latest report in the database")
      case seq => Full(seq.headOption)

    }
  }

  def getOldestArchivedReports() : Box[Option[Reports]] = {
    jdbcTemplate.query(baseArchivedQuery + " order by executionTimeStamp asc limit 1",
          ReportsMapper).toSeq match {
      case seq if seq.size > 1 => Failure("Too many answer for the latest report in the database")
      case seq => Full(seq.headOption)

    }
  }

  def getNewestReportOnNode(nodeId:NodeId) : Box[Option[Reports]] = {

    val array = Seq(nodeId.value)
    val query = baseQuery + s" and nodeid = ? order by executionTimeStamp desc limit 1"
    jdbcTemplate.query(query,array.toArray[AnyRef],ReportsMapper).toSeq match {
      case seq if seq.size > 1 => Failure("Too many answer for the latest report in the database")
      case seq => Full(seq.headOption)

    }
  }



  def getNewestReports() : Box[Option[Reports]] = {
    jdbcTemplate.query(baseQuery + " order by executionTimeStamp desc limit 1",
          ReportsMapper).toSeq match {
      case seq if seq.size > 1 => Failure("Too many answer for the latest report in the database")
      case seq => Full(seq.headOption)

    }
  }

  def getNewestArchivedReports() : Box[Option[Reports]] = {
    jdbcTemplate.query(baseArchivedQuery + " order by executionTimeStamp desc limit 1",
          ReportsMapper).toSeq match {
      case seq if seq.size > 1 => Failure("Too many answer for the latest report in the database")
      case seq => Full(seq.headOption)


    }
  }

  def getDatabaseSize(databaseName:String) : Box[Long] = {
    try {
      jdbcTemplate.query(
        """SELECT  nspname ||  '.'  ||  relname AS  "relation",
            pg_relation_size(C.oid)  AS  "size"
          FROM  pg_class C
          LEFT  JOIN  pg_namespace N ON  (N.oid=  C.relnamespace)
          WHERE  nspname NOT  IN  ('pg_catalog',  'information_schema') and relname = '%s'
          """.format(databaseName)
          , DatabaseSizeMapper).toSeq match {
        case seq if seq.size > 1 => Failure("Too many answer for the latest report in the database")
        case seq  => seq.headOption ?~! "The query used to find database size did not return any tuple"

       }
     } catch {
       case e: DataAccessException =>
         val msg ="Could not compute the size of the database, cause is " + e.getMessage()
         logger.error(msg)
         Failure(msg,Full(e),Empty)
     }
  }

  def archiveEntries(date : DateTime) : Box[Int] = {
    try{
      val migrate = jdbcTemplate.execute("""
          insert into %s
                (id, executionDate, nodeId, directiveId, ruleId, serial, component, keyValue, executionTimeStamp, eventType, policy, msg)
          (select id, executionDate, nodeId, directiveId, ruleId, serial, component, keyValue, executionTimeStamp, eventType, policy, msg from %s
        where executionTimeStamp < '%s')
        """.format(archiveTable,reportsTable,date.toString("yyyy-MM-dd") )
       )

      logger.debug("""Archiving report with SQL query: [[
                   | insert into %s (id, executionDate, nodeId, directiveId, ruleId, serial, component, keyValue, executionTimeStamp, eventType, policy, msg)
                   | (select id, executionDate, nodeId, directiveId, ruleId, serial, component, keyValue, executionTimeStamp, eventType, policy, msg from %s
                   | where executionTimeStamp < '%s')
                   |]]""".stripMargin.format(archiveTable,reportsTable,date.toString("yyyy-MM-dd")))

      val delete = jdbcTemplate.update("""
        delete from %s  where executionTimeStamp < '%s'
        """.format(reportsTable,date.toString("yyyy-MM-dd") )
      )

      jdbcTemplate.execute("vacuum %s".format(reportsTable))

      Full(delete)
    } catch {
       case e: DataAccessException =>
         val msg ="Could not archive entries in the database, cause is " + e.getMessage()
         logger.error(msg)
         Failure(msg,Full(e),Empty)
     }

  }

  def deleteEntries(date : DateTime) : Box[Int] = {

    logger.debug("""Deleting report with SQL query: [[
                   | delete from %s  where executionTimeStamp < '%s'
                   |]] and: [[
                   | delete from %s  where executionTimeStamp < '%s'
                   |]]""".stripMargin.format(reportsTable,date.toString("yyyy-MM-dd"),archiveTable,date.toString("yyyy-MM-dd")))
    try{

      val delete = jdbcTemplate.update("""
          delete from %s where executionTimeStamp < '%s'
          """.format(reportsTable,date.toString("yyyy-MM-dd") )
      ) + jdbcTemplate.update("""
          delete from %s  where executionTimeStamp < '%s'
          """.format(archiveTable,date.toString("yyyy-MM-dd") )
      )

      jdbcTemplate.execute("vacuum %s".format(reportsTable))
      jdbcTemplate.execute("vacuum full %s".format(archiveTable))

      Full(delete)
    } catch {
       case e: DataAccessException =>
         val msg ="Could not delete entries in the database, cause is " + e.getMessage()
         logger.error(msg)
         Failure(msg,Full(e),Empty)
     }
  }

  def getHighestId : Box[Int] = {
    val query = "select id from RudderSysEvents order by id desc limit 1"
    try {
      jdbcTemplate.query(query,IdMapper).toSeq match {
        case seq if seq.size > 1 => Failure("Too many answer for the highest id in the database")
        case seq => seq.headOption ?~! "No report where found in database (and so, we can not get highest id)"
      }
    } catch {
      case e:DataAccessException =>
        logger.error("Could not fetch highest id in the database. Reason is : %s".format(e.getMessage()))
        Failure(e.getMessage())
    }
  }

  def getLastHundredErrorReports(kinds:List[String]) : Box[Seq[(Reports,Int)]] = {
    val query = "%s and (%s) order by executiondate desc limit 100".format(idQuery,kinds.map("eventtype='%s'".format(_)).mkString(" or "))
      try {
        Full(jdbcTemplate.query(query,ReportsWithIdMapper).toSeq)
      } catch {
        case e:DataAccessException =>
        logger.error("Could not fetch last hundred reports in the database. Reason is : %s".format(e.getMessage()))
        Failure("Could not fetch last hundred reports in the database. Reason is : %s".format(e.getMessage()))
      }
  }

  def getReportsWithLowestId : Box[Option[(Reports,Int)]] = {
    jdbcTemplate.query(s"${idQuery} order by id asc limit 1",
          ReportsWithIdMapper).toSeq match {
      case seq if seq.size > 1 => Failure("Too many answer for the latest report in the database")
      case seq => Full(seq.headOption)

    }
  }

  /**
   * From an id and an end date, return a list of ReportExecution, and the max ID that has been considered
   */
  def getReportsfromId(id : Int, endDate : DateTime) : Box[(Seq[ReportExecution], Int)] = {
    // we first have to fetch the max id
    val queryForMaxId = "select max(id) as id from RudderSysEvents where id > ? and executionTimeStamp < ?"
    val array = mutable.Buffer[AnyRef](new java.lang.Integer(id), new Timestamp(endDate.getMillis))
    for {
      maxId <-
                try {
                  jdbcTemplate.query(
                        queryForMaxId
                      , array.toArray[AnyRef]
                      , IdMapper).toSeq match {
                    case seq if seq.size > 1 => Failure("Too many answer for the highest id in the database")
                    case seq => seq.headOption ?~! "No report where found in database (and so, we can not get highest id)"
                  }
                } catch {
                  case e:DataAccessException =>
                    logger.error("Could not fetch max id for execution in the database. Reason is : %s".format(e.getMessage()))
                    Failure(e.getMessage())
                }
      reports <- {
                  val arrayReports = mutable.Buffer[AnyRef](new java.lang.Integer(id), new java.lang.Integer(maxId), new java.lang.Integer(id), new java.lang.Integer(maxId))
                  try {
                    Full(jdbcTemplate.query(fetchExecutions ,arrayReports.toArray[AnyRef], ReportsExecutionMapper).toSeq)
                  } catch {
                    case e:DataAccessException =>
                      logger.error("Could not fetch agent executions in the database. Reason is : %s".format(e.getMessage()))
                      Failure(e.getMessage())
                  }
                }
    } yield {
      // there may be several executions at the same time
      (reports.distinct, maxId)
    }
  }

  def getErrorReportsBeetween(lower : Int, upper:Int,kinds:List[String]) : Box[Seq[Reports]] = {
    if (lower>=upper)
      Empty
    else{
      val query = "%s and id between '%d' and '%d' and (%s) order by executiondate asc".format(baseQuery,lower,upper,kinds.map("eventtype='%s'".format(_)).mkString(" or "))
      try {
        Full(jdbcTemplate.query(query,ReportsMapper).toSeq)
      } catch {
        case e:DataAccessException =>
        logger.error("Could not fetch reports between ids %d and %d in the database. Reason is : %s".format(lower,upper,e.getMessage()))
        Failure("Could not fetch reports between ids %d and %d in the database. Reason is : %s".format(lower,upper,e.getMessage()))
      }
    }
  }
}

object ReportsMapper extends RowMapper[Reports] {
   def mapRow(rs : ResultSet, rowNum: Int) : Reports = {
        Reports.factory(new DateTime(rs.getTimestamp("executionDate")),
                  RuleId(rs.getString("ruleId")),
                  DirectiveId(rs.getString("directiveId")),
                  NodeId(rs.getString("nodeId")),
                  rs.getInt("serial"),
                  rs.getString("component"),
                  rs.getString("keyValue"),
                  new DateTime(rs.getTimestamp("executionTimeStamp")),
                  rs.getString("eventType"),
                  rs.getString("msg"))
    }
}

object ExecutionTimeMapper extends RowMapper[DateTime] {
   def mapRow(rs : ResultSet, rowNum: Int) : DateTime = {
        new DateTime(rs.getTimestamp("executiontimestamp"))
    }
}

object DatabaseSizeMapper extends RowMapper[Long] {
   def mapRow(rs : ResultSet, rowNum: Int) : Long = {
        rs.getLong("size")
    }
}
object IdMapper extends RowMapper[Int] {
   def mapRow(rs : ResultSet, rowNum: Int) : Int = {
        rs.getInt("id")
    }
}

object ReportsWithIdMapper extends RowMapper[(Reports,Int)] {
  def mapRow(rs : ResultSet, rowNum: Int) : (Reports,Int) = {
    (ReportsMapper.mapRow(rs, rowNum),IdMapper.mapRow(rs, rowNum))
    }
}

object ReportsExecutionMapper extends RowMapper[ReportExecution] {
   def mapRow(rs : ResultSet, rowNum: Int) : ReportExecution = {
     ReportExecution(
         NodeId(rs.getString("nodeId"))
       , new DateTime(rs.getTimestamp("executionTimeStamp"))
       , rs.getBoolean("complete")
     )
    }
}