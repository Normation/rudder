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

import scala.collection.JavaConverters.asScalaBufferConverter
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.repository.ReportsRepository
import org.joda.time._
import org.slf4j.{Logger,LoggerFactory}
import com.normation.rudder.domain.reports._
import com.normation.cfclerk.domain.{Cf3PolicyDraftId}
import org.springframework.jdbc.core._
import java.sql.ResultSet
import java.sql.Timestamp
import net.liftweb.common._
import net.liftweb.common.Box._
import java.sql.Types
import org.springframework.dao.DataAccessException
import com.normation.rudder.reports.execution.AgentRun
import com.normation.rudder.domain.reports.Reports
import com.normation.rudder.reports.execution.AgentRunId
import com.normation.rudder.reports.execution.AgentRun
import com.normation.rudder.reports.execution.AgentRun
import com.normation.rudder.reports.execution.AgentRun

class ReportsJdbcRepository(jdbcTemplate : JdbcTemplate) extends ReportsRepository with Loggable {

  val reportsTable = "ruddersysevents"
  val archiveTable = "archivedruddersysevents"

  private[this] val baseQuery = "select executiondate, nodeid, ruleid, directiveid, serial, component, keyValue, executionTimeStamp, eventtype, policy, msg from RudderSysEvents where 1=1 ";
  private[this] val baseArchivedQuery = "select executiondate, nodeid, ruleid, directiveid, serial, component, keyValue, executionTimeStamp, eventtype, policy, msg from archivedruddersysevents where 1=1 ";

  private[this] val reportsExecutionTable = "reportsexecution"

  private[this] val idQuery = "select id, executiondate, nodeid, ruleid, directiveid, serial, component, keyValue, executionTimeStamp, eventtype, policy, msg from ruddersysevents where 1=1 ";

  // find the last full run per node
  // we are not looking for older request than interval minutes
  private[this] def lastQuery(interval: Int) = s"select nodeid as Node, max(date) as Time from reportsexecution where date > (now() - interval '${interval} minutes') and complete = true group by nodeid"
  private[this] def lastQueryByNode(interval: Int) = s"select nodeid as Node, max(date) as Time from reportsexecution where date > (now() - interval '${interval} minutes') and nodeid = ? and complete = true group by nodeid"
  private[this] def joinQuery(interval: Int) = "select executiondate, nodeid, ruleId, directiveid, serial, component, keyValue, executionTimeStamp, eventtype, policy, msg from RudderSysEvents join (" + lastQuery(interval) +" ) as Ordering on Ordering.Node = nodeid and executionTimeStamp = Ordering.Time where 1=1"
  private[this] def joinQueryByNode(interval: Int) = "select executiondate, nodeid, ruleId, directiveid, serial, component, keyValue, executionTimeStamp, eventtype, policy, msg from RudderSysEvents join (" + lastQueryByNode(interval) +" ) as Ordering on Ordering.Node = nodeid and executionTimeStamp = Ordering.Time where 1=1";

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
      s"""select
           executiondate, nodeid, ruleId, serial, directiveid, component, keyValue, executionTimeStamp, eventtype, policy, msg
         from
           RudderSysEvents
         where
          (nodeid, executiontimestamp) in (VALUES ${nodeParam})
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


  /**
   * Return the last (really the last, serial wise, with full execution) reports for a rule
   */
  override def findLastReportByRule(
      ruleId     : RuleId
    , serial     : Int
    , node       : Option[NodeId]
    , runInterval: Int
  ) : Seq[Reports] = {
    import scala.collection.mutable.Buffer
    var query = ""
    var array = Buffer[AnyRef]()
    val interval = 3*runInterval

    node match {
      case None =>
          query += joinQuery(interval) +  s" and ruleId = ? and serial = ? and executionTimeStamp > (now() - interval '${interval} minutes')"
          array ++= Buffer[AnyRef](ruleId.value, new java.lang.Integer(serial))
      case Some(nodeId) =>
        query += joinQueryByNode(interval) +  s" and ruleId = ? and serial = ? and executionTimeStamp > (now() - interval '${interval} minutes') and nodeId = ?"
        array ++= Buffer[AnyRef](nodeId.value, ruleId.value, new java.lang.Integer(serial), nodeId.value)
    }

    jdbcTemplate.query(query,
          array.toArray[AnyRef],
          ReportsMapper).asScala
  }

  /**
   * Return the last (really the last, serial wise, with full execution) reports for a rule
   */
  override def findLastReportsByRules(
      rulesAndSerials: Set[(RuleId, Int)]
    , runInterval    : Int
  ) : Seq[Reports] = {
    import scala.collection.mutable.Buffer
    val interval = 3*runInterval

    var query = joinQuery(interval) + " and ( 1 != 1 "
    var array = Buffer[AnyRef]()

    rulesAndSerials.foreach { case (ruleId, serial) =>
          query +=   " or (ruleId = ? and serial = ?)"
          array ++= Buffer[AnyRef](ruleId.value, new java.lang.Integer(serial))
    }
    query += s" ) and executionTimeStamp > (now() - interval '${interval} minutes')"
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

  override def deleteEntries(date : DateTime) : Box[Int] = {

    logger.debug("""Deleting report with SQL query: [[
                   | delete from %s  where executionTimeStamp < '%s'
                   |]] and: [[
                   | delete from %s  where executionTimeStamp < '%s'
                   |]] and: [[
                   | delete from %s  where date < '%s'
                   |]]""".stripMargin.format(reportsTable, date.toString("yyyy-MM-dd")
                                           , archiveTable, date.toString("yyyy-MM-dd")
                                           , reportsExecutionTable, date.toString("yyyy-MM-dd")))
    try{

      val delete = jdbcTemplate.update("""
          delete from %s where executionTimeStamp < '%s'
          """.format(reportsTable,date.toString("yyyy-MM-dd") )
      ) + jdbcTemplate.update("""
          delete from %s  where executionTimeStamp < '%s'
          """.format(archiveTable,date.toString("yyyy-MM-dd") )
      )+ jdbcTemplate.update("""
          delete from %s  where date < '%s'
          """.format(reportsExecutionTable,date.toString("yyyy-MM-dd") )
      )

      jdbcTemplate.execute("vacuum %s".format(reportsTable))
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

  override def getHighestId : Box[Long] = {
    val query = "select id from RudderSysEvents order by id desc limit 1"
    try {
      jdbcTemplate.query(query,IdMapper).asScala match {
        case seq if seq.size > 1 => Failure("Too many answer for the highest id in the database")
        case seq => seq.headOption ?~! "No report where found in database (and so, we can not get highest id)"
      }
    } catch {
      case e:DataAccessException =>
        logger.error("Could not fetch highest id in the database. Reason is : %s".format(e.getMessage()))
        Failure(e.getMessage())
    }
  }

  override def getLastHundredErrorReports(kinds:List[String]) : Box[Seq[(Reports,Long)]] = {
    val query = "%s and (%s) order by executiondate desc limit 100".format(idQuery,kinds.map("eventtype='%s'".format(_)).mkString(" or "))
      try {
        Full(jdbcTemplate.query(query,ReportsWithIdMapper).asScala)
      } catch {
        case e:DataAccessException =>
        logger.error("Could not fetch last hundred reports in the database. Reason is : %s".format(e.getMessage()))
        Failure("Could not fetch last hundred reports in the database. Reason is : %s".format(e.getMessage()))
      }
  }

  override def getReportsWithLowestId : Box[Option[(Reports,Long)]] = {
    jdbcTemplate.query(s"${idQuery} order by id asc limit 1",
          ReportsWithIdMapper).asScala match {
      case seq if seq.size > 1 => Failure("Too many answer for the latest report in the database")
      case seq => Full(seq.headOption)

    }
  }

  /**
   * From an id and an end date, return a list of AgentRun, and the max ID that has been considered
   */
  override def getReportsfromId(lastProcessedId: Long, endDate: DateTime) : Box[(Seq[AgentRun], Long)] = {

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
      import java.lang.{Long => jLong}
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

    //actual logic for getReportsfromId
    for {
      toId    <- getMaxId(lastProcessedId, endDate)
      reports <- getRuns(lastProcessedId, toId)
    } yield {
      (reports, toId)
    }
  }




  override def getChangeReports(notBefore: DateTime): Box[Seq[ResultRepairedReport]] = {
    val query = s"${baseQuery} and eventtype='${Reports.RESULT_REPAIRED}' and executionTimeStamp > '${new Timestamp(notBefore.getMillis)}'::timestamp order by executionTimeStamp asc"
    try {
      Full(jdbcTemplate.query(query,ReportsMapper).asScala.collect{case r:ResultRepairedReport => r})
    } catch {
      case ex: Exception =>
        val error = Failure("Error when trying to retrieve change reports", Some(ex), Empty)
        logger.error(error)
        error
    }
  }


  override def getErrorReportsBeetween(lower : Long, upper:Long,kinds:List[String]) : Box[Seq[Reports]] = {
    if (lower>=upper)
      Empty
    else{
      val query = "%s and id between '%d' and '%d' and (%s) order by executiondate asc".format(baseQuery,lower,upper,kinds.map("eventtype='%s'".format(_)).mkString(" or "))
      try {
        Full(jdbcTemplate.query(query,ReportsMapper).asScala)
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

object ReportsWithIdMapper extends RowMapper[(Reports,Long)] {
  def mapRow(rs : ResultSet, rowNum: Int) : (Reports,Long) = {
    (ReportsMapper.mapRow(rs, rowNum),IdMapper.mapRow(rs, rowNum))
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
