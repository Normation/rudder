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

import com.normation.rudder.domain.policies.DirectiveId
import com.normation.inventory.domain.NodeId
import net.liftweb.common._
import com.normation.rudder.domain.policies._
import com.normation.rudder.repository.RuleExpectedReportsRepository
import com.normation.rudder.domain.reports.RuleExpectedReports
import com.normation.rudder.domain.reports._
import scala.collection._
import org.joda.time._
import org.slf4j.{Logger,LoggerFactory}
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core._
import java.sql.ResultSet
import java.sql.Timestamp
import scala.collection.JavaConversions._
import net.liftweb.json._
import com.normation.utils.HashcodeCaching

class RuleExpectedReportsJdbcRepository(jdbcTemplate : JdbcTemplate)
    extends RuleExpectedReportsRepository {

  val logger = LoggerFactory.getLogger(classOf[RuleExpectedReportsJdbcRepository])
  
  val TABLE_NAME = "expectedreports"
  val NODE_TABLE_NAME = "expectedreportsnodes"
    
  val baseQuery = "select pkid, nodejoinkey, ruleid, serial, directiveid, component, cardinality, componentsvalues, begindate, enddate  from " + TABLE_NAME + "  where 1=1 ";

  def findAllCurrentExpectedReports(): scala.collection.Set[RuleId] = {
    jdbcTemplate.query("select distinct ruleid from "+ TABLE_NAME +
        "      where enddate is null",
          RuleIdMapper).toSet;
  }

  
  def findAllCurrentExpectedReportsAndSerial(): scala.collection.Map[RuleId, Int] = {
    jdbcTemplate.query("select distinct ruleid, serial from "+ TABLE_NAME +
        "      where enddate is null",
          RuleIdAndSerialMapper).toMap;
  }
    
  /**
   * Return current expectedreports (the one still pending) for this Rule
   * @param rule
   * @return
   */
  def findCurrentExpectedReports(ruleId : RuleId) : Option[RuleExpectedReports] = {
    val list = toRuleExpectedReports(jdbcTemplate.query(baseQuery +
        "      and enddate is null and ruleid = ?",
          Array[AnyRef](ruleId.value), 
          RuleExpectedReportsMapper).toSeq)

    
    list.size match {
       case 0 => None
       case 1 => Some(setNodes(list).head)
       case _ => throw new Exception("Inconsistency in the database")
       
    }
  }
  
  /**
   * Simply set the endDate for the expected report for this conf rule
   * @param ruleId 
   */
  def closeExpectedReport(ruleId : RuleId) : Unit = {
    logger.info("Closing report {}", ruleId)
    findCurrentExpectedReports(ruleId) match {
      case None =>
            logger.warn("Cannot close a non existing entry %s".format(ruleId.value))
      case Some(entry) =>
        jdbcTemplate.update("update "+ TABLE_NAME +"  set enddate = ? where nodejoinkey = ? and ruleId = ?",
          new Timestamp(DateTime.now().getMillis), new java.lang.Integer(entry.nodeJoinKey), entry.ruleId.value
        )
        () // unit is expected
    }
  }

  /**
   * TODO: change this API !
   * Save an expected reports.
   * 
   */
  def saveExpectedReports(ruleId : RuleId, serial: Int,
              policyExpectedReports : Seq[DirectiveExpectedReports],
              nodes : Seq[NodeId]) : Box[RuleExpectedReports] = {
     logger.info("Saving expected report for rule {}", ruleId.value)
     findCurrentExpectedReports(ruleId) match {
      case Some(x) =>
          logger.error("Inconsistency in the database : cannot save an already existing expected report")
          Failure("cannot save an already existing expected report")
          
      case None =>
          // Compute first the version id
          val nodeJoinKey = getNextVersionId
          
          // Create the lines for the mapping
          val list = for {
            policy <- policyExpectedReports
            component <- policy.components
            
          } yield {
            new ExpectedConfRuleMapping(0, nodeJoinKey, ruleId, serial,
                  policy.directiveId, component.componentName, component.cardinality, component.componentsValues, DateTime.now(), None)
          }
          "select pkid, nodejoinkey, ruleid, serial, directiveid, component, componentsvalues, cardinality, begindate, enddate  from "+ TABLE_NAME + "  where 1=1 ";

          list.foreach(entry => 
                jdbcTemplate.update("insert into "+ TABLE_NAME +" ( nodejoinkey, ruleid, serial, directiveid, component, cardinality, componentsValues, begindate) " + 
                    " values (?,?,?,?,?,?,?,?)",
                  new java.lang.Integer(entry.nodeJoinKey), ruleId.value, new java.lang.Integer(entry.serial), entry.policyExpectedReport.value,
                  entry.component,  new java.lang.Integer(entry.cardinality), ComponentsValuesSerialiser.serializeComponents(entry.componentsValues), new Timestamp(entry.beginDate.getMillis)
                )
          )
          saveNode(nodes, nodeJoinKey )
          findCurrentExpectedReports(ruleId)
     }
  }
  
  /**
   * Return all the expected reports for this policyinstance between the two date
   * @param directiveId
   * @return
   */
  def findExpectedReports(ruleId : RuleId, beginDate : Option[DateTime], endDate : Option[DateTime]) : Seq[RuleExpectedReports] = {
    var query = baseQuery + " and ruleId = ? "
    var array = mutable.Buffer[AnyRef](ruleId.value)
    
    beginDate match {
      case None => 
      case Some(date) => query = query + " and coalesce(endDate, ?) >= ?"; array += new Timestamp(date.getMillis);array += new Timestamp(date.getMillis)
    }
    
    endDate match {
      case None => 
      case Some(date) => query = query + " and beginDate < ?"; array += new Timestamp(date.getMillis)
    }
    
    val list = toRuleExpectedReports(jdbcTemplate.query(query,
          array.toArray[AnyRef],
          RuleExpectedReportsMapper).toSeq)
    setNodes(list)
  
  }
  
  /**
   * Return all the expected reports between the two dates
   * @return
   */
  def findExpectedReports(beginDate : DateTime, endDate : DateTime) : Seq[RuleExpectedReports] = {
    var query = baseQuery + " and beginDate < ? and coalesce(endDate, ?) >= ? "
    var array = mutable.Buffer[AnyRef](new Timestamp(endDate.getMillis), new Timestamp(beginDate.getMillis), new Timestamp(beginDate.getMillis))

    val list = toRuleExpectedReports(jdbcTemplate.query(query,
          array.toArray[AnyRef],
          RuleExpectedReportsMapper).toSeq)
    setNodes(list)  
  }
  
  /**
   * Return all the expected reports for this server between the two date
   * @param directiveId
   * @return
   */
  def findExpectedReportsByNode(nodeId : NodeId, beginDate : Option[DateTime], endDate : Option[DateTime]) : Seq[RuleExpectedReports] = {
    var joinQuery = "select pkid, "+ TABLE_NAME +".nodejoinkey, ruleid, directiveid, serial, component, componentsvalues, cardinality, begindate, enddate  from "+ TABLE_NAME +
        " join "+ NODE_TABLE_NAME +" on "+ NODE_TABLE_NAME +".nodejoinkey = "+ TABLE_NAME +".nodejoinkey " +
        " where nodeid = ? " 
  
    var array = mutable.Buffer[AnyRef](nodeId.value)
    
    beginDate match {
      case None => 
      case Some(date) => joinQuery = joinQuery + " and coalesce(endDate, ?) >= ?"; array += new Timestamp(date.getMillis);array += new Timestamp(date.getMillis)
    }
    
    endDate match {
      case None => 
      case Some(date) => joinQuery = joinQuery + " and beginDate < ?"; array += new Timestamp(date.getMillis)
    }
    
    val list = toRuleExpectedReports(jdbcTemplate.query(joinQuery,
          array.toArray[AnyRef],
          RuleExpectedReportsMapper).toSeq)
    setNodes(list)
  }

  
  
  /**
   * Return currents expectedreports (the one still pending) for this server
   * @param directiveId
   * @return
   */
  def findCurrentExpectedReportsByNode(nodeId : NodeId) : Seq[RuleExpectedReports] = {
    val joinQuery = "select pkid, "+ TABLE_NAME +".nodejoinkey, ruleid,directiveid, serial, component, componentsvalues, cardinality, begindate, enddate  from "+ TABLE_NAME +
        " join "+ NODE_TABLE_NAME +" on "+ NODE_TABLE_NAME +".nodejoinkey = "+ TABLE_NAME +".nodejoinkey " +
        "      where enddate is null and  "+ NODE_TABLE_NAME +".nodeId = ?";

    val list = toRuleExpectedReports(jdbcTemplate.query(joinQuery,
          Array[AnyRef](nodeId.value), 
          RuleExpectedReportsMapper).toSeq)
    setNodes(list)
    
  }

  /**
   * read from the database the server list, and set them for each entry of the seq
   * @param result
   * @return
   */
  private def setNodes(list : Seq[RuleExpectedReports]) : Seq[RuleExpectedReports] = {
    val result = mutable.Buffer[RuleExpectedReports]()
    for (entry <- list) {
      val nodesList = jdbcTemplate.queryForList("select nodeId from "+ NODE_TABLE_NAME +" where nodeJoinKey = ?",
        Array[AnyRef](new java.lang.Integer(entry.nodeJoinKey)), 
        classOf[ String ]
      )
      result += entry.copy(nodeIds = nodesList.map(x => NodeId(x)))
    }
    result
  }

  /**
   * Save the server list in the database
   */
  private def saveNode(servers : Seq[NodeId], nodeJoinKey : Int) = {
    for (server <- servers) {
      jdbcTemplate.update("insert into "+ NODE_TABLE_NAME +" ( nodejoinkey, nodeid) values (?,?)",
        new java.lang.Integer(nodeJoinKey), server.value
      )
    }
  }
  
  private def getNextVersionId() : Int = {
    jdbcTemplate.queryForInt("SELECT nextval('ruleVersionId')")
  }
  
  
  /**
   * Agregate the differents lines mapping a RuleExpectedReports
   * Caution, can only agregate 
   */
  def toRuleExpectedReports(mapping : Seq[ExpectedConfRuleMapping]) : Seq[RuleExpectedReports] = {
    val result = mutable.Map[SerialedRuleId, RuleExpectedReports]()
    
    for (mapped <- mapping) {
      result.get(SerialedRuleId(mapped.ruleId, mapped.serial)) match {
        case None => result += (SerialedRuleId(mapped.ruleId, mapped.serial) -> new  RuleExpectedReports(
            mapped.ruleId,
            Seq(new DirectiveExpectedReports(
                    mapped.policyExpectedReport,
                    Seq(new ReportComponent(mapped.component, mapped.cardinality, mapped.componentsValues)))
                ),
            mapped.serial,
            mapped.nodeJoinKey,
            Seq(),
            mapped.beginDate,
            mapped.endDate
            ))
        case Some(entry) =>
            // two case, either add a policyExpectedReport, either add a componentCard
            entry.directiveExpectedReports.find(x => x.directiveId == mapped.policyExpectedReport) match {
              case None => // No policy expected reports, we create one
                result += (SerialedRuleId(entry.ruleId,
                            entry.serial) -> entry.copy(directiveExpectedReports = entry.directiveExpectedReports :+ new DirectiveExpectedReports(
                    mapped.policyExpectedReport,
                    Seq(new ReportComponent(mapped.component, mapped.cardinality, mapped.componentsValues))
                  ))
                )
              case Some(expectedReport) =>
                val newPolicies =  entry.directiveExpectedReports.filter(x => x.directiveId != mapped.policyExpectedReport) :+
                                  expectedReport.copy(components = (expectedReport.components :+ new ReportComponent(mapped.component, mapped.cardinality, mapped.componentsValues)) )
                result += (SerialedRuleId(entry.ruleId,
                            entry.serial) -> entry.copy(directiveExpectedReports = newPolicies))
            }
      }
    }
    result.values.toSeq
  }
  
}

object RuleExpectedReportsMapper extends RowMapper[ExpectedConfRuleMapping] {
  def mapRow(rs : ResultSet, rowNum: Int) : ExpectedConfRuleMapping = {
    new ExpectedConfRuleMapping(
      rs.getInt("pkid"),
      rs.getInt("nodejoinkey"),
      new RuleId(rs.getString("ruleid")),
      rs.getInt("serial"),
      DirectiveId(rs.getString("directiveid")), 
      rs.getString("component"),
      rs.getInt("cardinality"),
      ComponentsValuesSerialiser.unserializeComponents(rs.getString("componentsvalues")),
      new DateTime(rs.getTimestamp("begindate")),
      if(rs.getTimestamp("enddate")!=null) {
        Some(new DateTime(rs.getTimestamp("endDate")))
      } else None
    )
  }
}

object RuleIdMapper extends RowMapper[RuleId] {
  def mapRow(rs : ResultSet, rowNum: Int) : RuleId = {
    new RuleId(rs.getString("ruleid"))
  }
}
object RuleIdAndSerialMapper extends RowMapper[(RuleId, Int)] {
  def mapRow(rs : ResultSet, rowNum: Int) : (RuleId, Int) = {
    (new RuleId(rs.getString("ruleid")) , rs.getInt("serial"))
  }
}

/**
 * Just a plain mapping of the database
 */
case class ExpectedConfRuleMapping(
    val pkId : Int,
    val nodeJoinKey : Int, 
    val ruleId : RuleId,
    val serial : Int, 
    val policyExpectedReport : DirectiveId,
    val component : String,
    val cardinality : Int,
    val componentsValues : Seq[String],
    // the period where the configuration is applied to the servers
    val beginDate : DateTime = DateTime.now(),
    val endDate : Option[DateTime] = None
) extends HashcodeCaching 
    
    
 object ComponentsValuesSerialiser {
  
  def serializeComponents(ids:Seq[String]) : String = {
    implicit val formats = Serialization.formats(NoTypeHints)
    Serialization.write(ids)
  }
  /*
   * from a JSON array: [ "id1", "id2", ...], get the list of
   * components values Ids. 
   * Never fails, but returned an empty list. 
   */
  def unserializeComponents(ids:String) : Seq[String] = {
    implicit val formats = DefaultFormats 
    parse(ids).extract[List[String]]
 }

}
