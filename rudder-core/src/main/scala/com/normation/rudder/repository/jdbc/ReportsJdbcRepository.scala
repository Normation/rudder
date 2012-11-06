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

class ReportsJdbcRepository(jdbcTemplate : JdbcTemplate) extends ReportsRepository with Loggable {

  val baseQuery = "select executiondate, nodeid, ruleid, directiveid, serial, component, keyValue, executionTimeStamp, eventtype, policy, msg from RudderSysEvents where 1=1 ";
  val baseArchivedQuery = "select executiondate, nodeid, ruleid, directiveid, serial, component, keyValue, executionTimeStamp, eventtype, policy, msg from archivedruddersysevents where 1=1 ";
  
  
  // find the last full run per node
  // we are not looking for older request that 15 minutes for the moment
  val lastQuery = "select nodeid as Node, max(executiontimestamp) as Time from ruddersysevents where ruleId = 'hasPolicyServer-root' and component = 'common' and keyValue = 'EndRun' and executionTimeStamp > (now() - interval '15 minutes') group by nodeid"
  val lastQueryByNode = "select nodeid as Node, max(executiontimestamp) as Time from ruddersysevents where ruleId = 'hasPolicyServer-root' and component = 'common' and keyValue = 'EndRun' and nodeid = ? and executionTimeStamp > (now() - interval '15 minutes') group by nodeid"
    
  val joinQuery = "select executiondate, nodeid, ruleId, directiveid, serial, component, keyValue, executionTimeStamp, eventtype, policy, msg from RudderSysEvents join (" + lastQuery +" ) as Ordering on Ordering.Node = nodeid and executionTimeStamp = Ordering.Time where 1=1";
  val joinQueryByNode = "select executiondate, nodeid, ruleId, directiveid, serial, component, keyValue, executionTimeStamp, eventtype, policy, msg from RudderSysEvents join (" + lastQueryByNode +" ) as Ordering on Ordering.Node = nodeid and executionTimeStamp = Ordering.Time where 1=1";
  
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
  
  
  
  def findExecutionTimeByNode(
      nodeId   : NodeId
    , beginDate: DateTime
    , endDate  : Option[DateTime]
  ) : Seq[DateTime] = {
    var query = "select distinct executiontimestamp from ruddersysevents where ruleId = 'hasPolicyServer-root' and component = 'common' and keyValue = 'EndRun' and nodeId = ? and executiontimestamp >= ?"
       
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
  
  def getOldestReports() : Box[Reports] = {
    jdbcTemplate.query(baseQuery + " order by executionTimeStamp asc limit 1",
          ReportsMapper).toSeq match {
      case seq if seq.size > 1 => Failure("Too many answer for the latest report in the database")
      case seq => seq.headOption ?~! "No report where found in database (and so, we can not get the oldest one)"
      
    } 
  }
  
  def getOldestArchivedReports() : Box[Reports] = {
    jdbcTemplate.query(baseArchivedQuery + " order by executionTimeStamp asc limit 1",
          ReportsMapper).toSeq match {
      case seq if seq.size > 1 => Failure("Too many answer for the latest report in the database")
      case seq => seq.headOption ?~! "No archived report where found in database (and so, we can not get the oldest one)"
      
    }
  }
  
    def getNewestReports() : Box[Reports] = {
    jdbcTemplate.query(baseQuery + " order by executionTimeStamp desc limit 1",
          ReportsMapper).toSeq match {
      case seq if seq.size > 1 => Failure("Too many answer for the latest report in the database")
      case seq => seq.headOption ?~! "No report where found in database (and so, we can not get the newest one)"
      
    } 
  }
  
  def getNewestArchivedReports() : Box[Reports] = {
    jdbcTemplate.query(baseArchivedQuery + " order by executionTimeStamp desc limit 1",
          ReportsMapper).toSeq match {
      case seq if seq.size > 1 => Failure("Too many answer for the latest report in the database")
      case seq => seq.headOption ?~! "No archived report where found in database (and so, we can not get the newest one)"
      
    } 
  }
  
  def getDatabaseSize() : Box[Long] = {
    try {
      jdbcTemplate.query(
        """SELECT  nspname ||  '.'  ||  relname AS  "relation",
            pg_relation_size(C.oid)  AS  "size"
          FROM  pg_class C
          LEFT  JOIN  pg_namespace N ON  (N.oid=  C.relnamespace)
          WHERE  nspname NOT  IN  ('pg_catalog',  'information_schema') and relname = 'ruddersysevents'
          """
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
  
  def getArchiveSize() : Box[Long] = {
    try {
      jdbcTemplate.query(
        """SELECT  nspname ||  '.'  ||  relname AS  "relation",
            pg_relation_size(C.oid)  AS  "size"
          FROM  pg_class C
          LEFT  JOIN  pg_namespace N ON  (N.oid=  C.relnamespace)
          WHERE  nspname NOT  IN  ('pg_catalog',  'information_schema') and relname = 'archivedruddersysevents'
          """
          , DatabaseSizeMapper).toSeq match {
        case seq if seq.size > 1 => Failure("Too many answer for the latest report in the database")
        case seq  => seq.headOption ?~! "The query used to find database size did not return any tuple"

      }
    } catch {
       case e: DataAccessException =>
         val msg ="Could not compute the size of the archive, cause is " + e.getMessage()
         logger.error(msg)
         Failure(msg,Full(e),Empty)
     }
  }

  def archiveEntries(date : DateTime) : Box[Int] = {
    try{
      val migrate = jdbcTemplate.execute("""
          insert into ArchivedRudderSysEvents
                (id, executionDate, nodeId, directiveId, ruleId, serial, component, keyValue, executionTimeStamp, eventType, policy, msg)
          (select id, executionDate, nodeId, directiveId, ruleId, serial, component, keyValue, executionTimeStamp, eventType, policy, msg from RudderSysEvents
        where executionTimeStamp < '%s')
        """.format(date.toString("yyyy-MM-dd HH:mm") )
    )
    
    logger.debug("""Archiving report with SQL query: [[
                   | insert into ArchivedRudderSysEvents (id, executionDate, nodeId, policyInstanceId, configurationRuleId, serial, component, keyValue, executionTimeStamp, eventType, policy, msg)    
                   | (select id, executionDate, nodeId, policyInstanceId, configurationRuleId, serial, component, keyValue, executionTimeStamp, eventType, policy, msg from RudderSysEvents 
                   | where executionTimeStamp < '%s')
                   |]]""".stripMargin.format(date.toString("yyyy-MM-dd HH:mm") ) )

      val delete = jdbcTemplate.update("""
        delete from RudderSysEvents  where executionTimeStamp < '%s'
        """.format(date.toString("yyyy-MM-dd HH:mm") )
    )

      jdbcTemplate.execute("vacuum RudderSysEvents")

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
                   | delete from RudderSysEvents  where executionTimeStamp < '%s'
                   |]] and: [[
                   | delete from ArchivedRudderSysEvents  where executionTimeStamp < '%s'
                   |]]""".stripMargin.format(date.toString("yyyy-MM-dd HH:mm"),date.toString("yyyy-MM-dd HH:mm")))

    try{

      val delete = jdbcTemplate.update("""
          delete from RudderSysEvents  where executionTimeStamp < '%s'
          """.format(date.toString("yyyy-MM-dd HH:mm") )
      ) + jdbcTemplate.update("""
          delete from ArchivedRudderSysEvents  where executionTimeStamp < '%s'
          """.format(date.toString("yyyy-MM-dd HH:mm") )
      )

      jdbcTemplate.execute("vacuum RudderSysEvents")
      jdbcTemplate.execute("vacuum full ArchivedRudderSysEvents")


      Full(delete)
    } catch {
       case e: DataAccessException =>
         val msg ="Could not delete entries in the database, cause is " + e.getMessage()
         logger.error(msg)
         Failure(msg,Full(e),Empty)
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