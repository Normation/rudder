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
import com.normation.rudder.domain.policies.PolicyInstanceId
import com.normation.rudder.domain.policies.ConfigurationRuleId
import com.normation.rudder.repository.ReportsRepository
import scala.collection._
import org.joda.time._
import org.slf4j.{Logger,LoggerFactory}
import com.normation.rudder.domain.reports.bean._
import com.normation.cfclerk.domain.{CFCPolicyInstanceId}

import org.springframework.jdbc.core._
import java.sql.ResultSet;
import java.sql.Timestamp

import scala.collection.JavaConversions._

class ReportsJdbcRepository(jdbcTemplate : JdbcTemplate) extends ReportsRepository {

  val baseQuery = "select executiondate, nodeid, configurationruleid, policyinstanceid, serial, component, keyValue, executionTimeStamp, eventtype, policy, msg from RudderSysEvents where 1=1 ";
  
  
  // find the last full run per node
  val lastQuery = "select nodeid as Node, max(executiontimestamp) as Time from ruddersysevents where configurationRuleId = 'hasPolicyServer-root' and component = 'common' and keyValue = 'EndRun' group by nodeid"
  val lastQueryByNode = "select nodeid as Node, max(executiontimestamp) as Time from ruddersysevents where configurationRuleId = 'hasPolicyServer-root' and component = 'common' and keyValue = 'EndRun' and nodeid = ? group by nodeid"
  // todo : add a time limit
    
  val joinQuery = "select executiondate, nodeid, configurationruleid, policyinstanceid, serial, component, keyValue, executionTimeStamp, eventtype, policy, msg from RudderSysEvents join (" + lastQuery +" ) as Ordering on Ordering.Node = nodeid and executionTimeStamp = Ordering.Time where 1=1";
  val joinQueryByNode = "select executiondate, nodeid, configurationruleid, policyinstanceid, serial, component, keyValue, executionTimeStamp, eventtype, policy, msg from RudderSysEvents join (" + lastQueryByNode +" ) as Ordering on Ordering.Node = nodeid and executionTimeStamp = Ordering.Time where 1=1";
  
  def findReportsByConfigurationRule(configurationRuleId: ConfigurationRuleId, serial : Option[Int], beginDate: Option[DateTime], endDate: Option[DateTime]): Seq[Reports] = {
    var query = baseQuery + " and configurationRuleId = ? "
    var array = mutable.Buffer[AnyRef](configurationRuleId.value)

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

  def findReportsByServer(nodeId : NodeId, configurationRuleId : Option[ConfigurationRuleId], serial : Option[Int], beginDate: Option[DateTime], endDate: Option[DateTime]): Seq[Reports] = {
  	var query = baseQuery + " and nodeId = ? "
    var array = mutable.Buffer[AnyRef](nodeId.value)
    
    configurationRuleId match {
      case None => 
      case Some(cr) => query = query + " and configurationRuleId = ?"; array += cr.value

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
  
  def findReportsByNode(nodeId : NodeId, configurationRuleId : ConfigurationRuleId, 
      serial : Int, beginDate: DateTime, endDate: Option[DateTime]): Seq[Reports] = {
  	var query = baseQuery + " and nodeId = ?  and configurationRuleId = ? and serial = ? and executionTimeStamp >= ?"
    var array = mutable.Buffer[AnyRef](nodeId.value, 
        configurationRuleId.value, 
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
   * Return the last (really the last, serial wise, with full execution) reports for a configuration rule
   */
  def findLastReportByConfigurationRule(configurationRuleId : ConfigurationRuleId, serial : Int, node : Option[NodeId]) : Seq[Reports] = {
    var query = ""
    var array = mutable.Buffer[AnyRef]()

    node match {
      case None => 
        	query += joinQuery +  " and configurationRuleId = ? and serial = ? and executionTimeStamp > (now() - interval '15 minutes')"
        	array ++= mutable.Buffer[AnyRef](configurationRuleId.value, new java.lang.Integer(serial))
      case Some(nodeId) => 
        query += joinQueryByNode +  " and configurationRuleId = ? and serial = ? and executionTimeStamp > (now() - interval '15 minutes') and nodeId = ?"
        array ++= mutable.Buffer[AnyRef](nodeId.value, configurationRuleId.value, new java.lang.Integer(serial), nodeId.value)
    }
    
    jdbcTemplate.query(query,
          array.toArray[AnyRef],
          ReportsMapper).toSeq;
  }
  
  
  
  def findExecutionTimeByNode(nodeId : NodeId, 
      beginDate: DateTime, 
      endDate: Option[DateTime] ) : Seq[DateTime] = {
    var query = "select distinct executiontimestamp as executionDate from ruddersysevents where configurationRuleId = 'hasPolicyServer-root' and component = 'common' and keyValue = 'EndRun' and nodeId = ? and executiontimestamp >= ?"
       
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
}


object ReportsMapper extends RowMapper[Reports] {
   def mapRow(rs : ResultSet, rowNum: Int) : Reports = {
        Reports.factory(new DateTime(rs.getTimestamp("executionDate")),
        					ConfigurationRuleId(rs.getString("configurationRuleId")), 
                  PolicyInstanceId(rs.getString("policyInstanceId")), 
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
        new DateTime(rs.getTimestamp("executionDate"))
    }
}

