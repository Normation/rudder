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

import com.normation.rudder.domain.policies.PolicyInstanceId
import com.normation.inventory.domain.NodeId
import net.liftweb.common._
import com.normation.rudder.domain.policies._
import com.normation.rudder.repository.ConfigurationExpectedReportsRepository
import com.normation.rudder.domain.reports.ConfigurationExpectedReports
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

class ConfigurationExpectedReportsJdbcRepository(jdbcTemplate : JdbcTemplate)
    extends ConfigurationExpectedReportsRepository {

  val logger = LoggerFactory.getLogger(classOf[ConfigurationExpectedReportsJdbcRepository])
  
  val TABLE_NAME = "expectedreports"
  val NODE_TABLE_NAME = "expectedreportsnodes"
    
  val baseQuery = "select pkid, nodejoinkey, configurationruleid, serial, policyinstanceid, component, cardinality, componentsvalues, begindate, enddate  from " + TABLE_NAME + "  where 1=1 ";

  def findAllCurrentExpectedReports(): scala.collection.Set[ConfigurationRuleId] = {
    jdbcTemplate.query("select distinct configurationruleid from "+ TABLE_NAME +
        "      where enddate is null",
          ConfigurationRuleIdMapper).toSet;
  }

  
  def findAllCurrentExpectedReportsAndSerial(): scala.collection.Map[ConfigurationRuleId, Int] = {
    jdbcTemplate.query("select distinct configurationruleid, serial from "+ TABLE_NAME +
        "      where enddate is null",
          ConfigurationRuleIdAndSerialMapper).toMap;
  }
    
  /**
   * Return current expectedreports (the one still pending) for this ConfigurationRule
   * @param configurationRule
   * @return
   */
  def findCurrentExpectedReports(configurationRuleId : ConfigurationRuleId) : Option[ConfigurationExpectedReports] = {
  	val list = toConfigurationExpectedReports(jdbcTemplate.query(baseQuery +
        "      and enddate is null and configurationruleid = ?",
          Array[AnyRef](configurationRuleId.value), 
          ConfigurationExpectedReportsMapper).toSeq)

    
    list.size match {
       case 0 => None
       case 1 => Some(setNodes(list).head)
       case _ => throw new Exception("Inconsistency in the database")
       
    }
  }
  
  /**
   * Simply set the endDate for the expected report for this conf rule
   * @param configurationRuleId 
   */
  def closeExpectedReport(configurationRuleId : ConfigurationRuleId) : Unit = {
  	logger.info("Closing report {}", configurationRuleId)
    findCurrentExpectedReports(configurationRuleId) match {
      case None =>
      			logger.warn("Cannot close a non existing entry %s".format(configurationRuleId.value))
      case Some(entry) =>
        jdbcTemplate.update("update "+ TABLE_NAME +"  set enddate = ? where nodejoinkey = ? and configurationRuleId = ?",
          new Timestamp(new DateTime().getMillis), new java.lang.Integer(entry.nodeJoinKey), entry.configurationRuleId.value
        )
    }
  }

  /**
   * TODO: change this API !
   * Save an expected reports.
   * 
   */
  def saveExpectedReports(configurationRuleId : ConfigurationRuleId, serial: Int,
      				policyExpectedReports : Seq[PolicyExpectedReports],
      				nodes : Seq[NodeId]) : Box[ConfigurationExpectedReports] = {
  	 logger.info("Saving expected report for configuration rule {}", configurationRuleId.value)
  	 findCurrentExpectedReports(configurationRuleId) match {
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
      		  new ExpectedConfRuleMapping(0, nodeJoinKey, configurationRuleId, serial,
      		        policy.policyInstanceId, component.componentName, component.cardinality, component.componentsValues, new DateTime(), None)
      		}
      		"select pkid, nodejoinkey, configurationruleid, serial, policyinstanceid, component, componentsvalues, cardinality, begindate, enddate  from "+ TABLE_NAME + "  where 1=1 ";

      		list.foreach(entry => 
			      		jdbcTemplate.update("insert into "+ TABLE_NAME +" ( nodejoinkey, configurationruleid, serial, policyinstanceid, component, cardinality, componentsValues, begindate) " + 
			      		    " values (?,?,?,?,?,?,?,?)",
				          new java.lang.Integer(entry.nodeJoinKey), configurationRuleId.value, new java.lang.Integer(entry.serial), entry.policyExpectedReport.value,
				          entry.component,  new java.lang.Integer(entry.cardinality), ComponentsValuesSerialiser.serializeComponents(entry.componentsValues), new Timestamp(entry.beginDate.getMillis)
				        )
				  )
	        saveServer(nodes, nodeJoinKey )
	        findCurrentExpectedReports(configurationRuleId)
  	 }
  }
  
  /**
   * Return all the expected reports for this policyinstance between the two date
   * @param policyInstanceId
   * @return
   */
  def findExpectedReports(configurationRuleId : ConfigurationRuleId, beginDate : Option[DateTime], endDate : Option[DateTime]) : Seq[ConfigurationExpectedReports] = {
    var query = baseQuery + " and configurationRuleId = ? "
    var array = mutable.Buffer[AnyRef](configurationRuleId.value)
    
    beginDate match {
      case None => 
      case Some(date) => query = query + " and coalesce(endDate, ?) >= ?"; array += new Timestamp(date.getMillis);array += new Timestamp(date.getMillis)
    }
    
    endDate match {
      case None => 
      case Some(date) => query = query + " and beginDate < ?"; array += new Timestamp(date.getMillis)
    }
    
    val list = toConfigurationExpectedReports(jdbcTemplate.query(query,
          array.toArray[AnyRef],
          ConfigurationExpectedReportsMapper).toSeq)
    setNodes(list)
  
  }
  
  /**
   * Return all the expected reports between the two dates
   * @return
   */
  def findExpectedReports(beginDate : DateTime, endDate : DateTime) : Seq[ConfigurationExpectedReports] = {
    var query = baseQuery + " and beginDate < ? and coalesce(endDate, ?) >= ? "
    var array = mutable.Buffer[AnyRef](new Timestamp(endDate.getMillis), new Timestamp(beginDate.getMillis), new Timestamp(beginDate.getMillis))

    val list = toConfigurationExpectedReports(jdbcTemplate.query(query,
          array.toArray[AnyRef],
          ConfigurationExpectedReportsMapper).toSeq)
    setNodes(list)  
  }
  
  /**
   * Return all the expected reports for this server between the two date
   * @param policyInstanceId
   * @return
   */
  def findExpectedReportsByServer(serverId : NodeId, beginDate : Option[DateTime], endDate : Option[DateTime]) : Seq[ConfigurationExpectedReports] = {
    var joinQuery = "select pkid, "+ TABLE_NAME +".nodejoinkey, configurationruleid, policyinstanceid, serial, component, componentsvalues, cardinality, begindate, enddate  from "+ TABLE_NAME +
        " join "+ NODE_TABLE_NAME +" on "+ NODE_TABLE_NAME +".nodejoinkey = "+ TABLE_NAME +".nodejoinkey " +
        " where nodeid = ? " 
  
    var array = mutable.Buffer[AnyRef](serverId.value)
    
    beginDate match {
      case None => 
      case Some(date) => joinQuery = joinQuery + " and coalesce(endDate, ?) >= ?"; array += new Timestamp(date.getMillis);array += new Timestamp(date.getMillis)
    }
    
    endDate match {
      case None => 
      case Some(date) => joinQuery = joinQuery + " and beginDate < ?"; array += new Timestamp(date.getMillis)
    }
    
    val list = toConfigurationExpectedReports(jdbcTemplate.query(joinQuery,
          array.toArray[AnyRef],
          ConfigurationExpectedReportsMapper).toSeq)
    setNodes(list)
  }

  
  
  /**
   * Return currents expectedreports (the one still pending) for this server
   * @param policyInstanceId
   * @return
   */
  def findCurrentExpectedReportsByServer(serverId : NodeId) : Seq[ConfigurationExpectedReports] = {
    val joinQuery = "select pkid, "+ TABLE_NAME +".nodejoinkey, configurationruleid,policyinstanceid, serial, component, componentsvalues, cardinality, begindate, enddate  from "+ TABLE_NAME +
        " join "+ NODE_TABLE_NAME +" on "+ NODE_TABLE_NAME +".nodejoinkey = "+ TABLE_NAME +".nodejoinkey " +
        "      where enddate is null and  "+ NODE_TABLE_NAME +".nodeId = ?";

    val list = toConfigurationExpectedReports(jdbcTemplate.query(joinQuery,
          Array[AnyRef](serverId.value), 
          ConfigurationExpectedReportsMapper).toSeq)
    setNodes(list)
    
  }

  /**
   * read from the database the server list, and set them for each entry of the seq
   * @param result
   * @return
   */
  private def setNodes(list : Seq[ConfigurationExpectedReports]) : Seq[ConfigurationExpectedReports] = {
    val result = mutable.Buffer[ConfigurationExpectedReports]()
    for (entry <- list) {
      val nodesList = jdbcTemplate.queryForList("select nodeId from "+ NODE_TABLE_NAME +" where nodeJoinKey = ?",
        Array[AnyRef](new java.lang.Integer(entry.nodeJoinKey)), 
        classOf[ String ]
      )
      result += entry.copy(nodesList = nodesList.map(x => NodeId(x)))
    }
    result
  }

  /**
   * Save the server list in the database
   */
  private def saveServer(servers : Seq[NodeId], nodeJoinKey : Int) = {
    for (server <- servers) {
      jdbcTemplate.update("insert into "+ NODE_TABLE_NAME +" ( nodejoinkey, nodeid) values (?,?)",
        new java.lang.Integer(nodeJoinKey), server.value
      )
    }
  }
  
  private def getNextVersionId() : Int = {
    jdbcTemplate.queryForInt("SELECT nextval('confVersionId')")
  }
  
  
  /**
   * Agregate the differents lines mapping a ConfigurationExpectedReports
   * Caution, can only agregate 
   */
  def toConfigurationExpectedReports(mapping : Seq[ExpectedConfRuleMapping]) : Seq[ConfigurationExpectedReports] = {
    val result = mutable.Map[SerialedConfigurationRuleId, ConfigurationExpectedReports]()
    
    for (mapped <- mapping) {
      result.get(SerialedConfigurationRuleId(mapped.configurationRuleId, mapped.serial)) match {
        case None => result += (SerialedConfigurationRuleId(mapped.configurationRuleId, mapped.serial) -> new  ConfigurationExpectedReports(
            mapped.configurationRuleId,
            Seq(new PolicyExpectedReports(
                		mapped.policyExpectedReport,
                		Seq(new ComponentCard(mapped.component, mapped.cardinality, mapped.componentsValues)))
            		),
            mapped.serial,
            mapped.nodeJoinKey,
            Seq(),
            mapped.beginDate,
            mapped.endDate
            ))
        case Some(entry) =>
          	// two case, either add a policyExpectedReport, either add a componentCard
            entry.policyExpectedReports.find(x => x.policyInstanceId == mapped.policyExpectedReport) match {
              case None => // No policy expected reports, we create one
                result += (SerialedConfigurationRuleId(entry.configurationRuleId,
                    				entry.serial) -> entry.copy(policyExpectedReports = entry.policyExpectedReports :+ new PolicyExpectedReports(
                		mapped.policyExpectedReport,
                		Seq(new ComponentCard(mapped.component, mapped.cardinality, mapped.componentsValues))
            			))
            		)
              case Some(expectedReport) =>
                val newPolicies =  entry.policyExpectedReports.filter(x => x.policyInstanceId != mapped.policyExpectedReport) :+
                									expectedReport.copy(components = (expectedReport.components :+ new ComponentCard(mapped.component, mapped.cardinality, mapped.componentsValues)) )
                result += (SerialedConfigurationRuleId(entry.configurationRuleId,
                    				entry.serial) -> entry.copy(policyExpectedReports = newPolicies))
            }
      }
    }
    result.values.toSeq
  }
  
}

object ConfigurationExpectedReportsMapper extends RowMapper[ExpectedConfRuleMapping] {
  def mapRow(rs : ResultSet, rowNum: Int) : ExpectedConfRuleMapping = {
    new ExpectedConfRuleMapping(
      rs.getInt("pkid"),
      rs.getInt("nodejoinkey"),
      new ConfigurationRuleId(rs.getString("configurationruleid")),
      rs.getInt("serial"),
      PolicyInstanceId(rs.getString("policyinstanceid")), 
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

object ConfigurationRuleIdMapper extends RowMapper[ConfigurationRuleId] {
  def mapRow(rs : ResultSet, rowNum: Int) : ConfigurationRuleId = {
    new ConfigurationRuleId(rs.getString("configurationruleid"))
  }
}
object ConfigurationRuleIdAndSerialMapper extends RowMapper[(ConfigurationRuleId, Int)] {
  def mapRow(rs : ResultSet, rowNum: Int) : (ConfigurationRuleId, Int) = {
    (new ConfigurationRuleId(rs.getString("configurationruleid")) , rs.getInt("serial"))
  }
}

/**
 * Just a plain mapping of the database
 */
case class ExpectedConfRuleMapping(
    val pkId : Int,
    val nodeJoinKey : Int, 
    val configurationRuleId : ConfigurationRuleId,
    val serial : Int, 
    val policyExpectedReport : PolicyInstanceId,
    val component : String,
    val cardinality : Int,
    val componentsValues : Seq[String],
    // the period where the configuration is applied to the servers
    val beginDate : DateTime = new DateTime(),
    val endDate : Option[DateTime] = None
    )
    
    
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