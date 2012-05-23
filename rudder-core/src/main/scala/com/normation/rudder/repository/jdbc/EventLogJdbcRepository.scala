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

import com.normation.rudder.repository.EventLogRepository
import org.joda.time.DateTime
import org.slf4j.{Logger,LoggerFactory}
import com.normation.eventlog._
import com.normation.rudder.domain.log._
import com.normation.cfclerk.domain.Cf3PolicyDraftId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core._
import java.sql.ResultSet
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import org.springframework.jdbc.support.GeneratedKeyHolder
import com.normation.utils.User
import net.liftweb.common._
import scala.xml._
import java.security.Principal
import scala.collection.JavaConversions._
import org.joda.time.format.ISODateTimeFormat
import com.normation.rudder.domain.policies.DeleteRuleDiff
import com.normation.rudder.domain.policies.RuleId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.services.log.EventLogFactory
import com.normation.rudder.domain.log._

/**
 * The EventLog repository
 * Save in an SQL table the EventLog, and retrieve unspecialized version of the eventlog
 * Usually, the EventLog won't be created with an id nor a cause id (nor a principal), that why they can be passed 
 * in parameters
 * @author Nicolas CHARLES
 *
 */
class EventLogJdbcRepository(
    jdbcTemplate                : JdbcTemplate
  , override val eventLogFactory: EventLogFactory
) extends EventLogRepository {

   val logger = LoggerFactory.getLogger(classOf[EventLogRepository])
  
   //reason: column 6
   //causeId: column 7
   val INSERT_SQL = "insert into EventLog (creationDate, principal, eventType, severity, data %s %s) values (?, ?, ?, ?, ? %s %s)"
 
   val SELECT_SQL = "SELECT id, creationDate, principal, eventType, severity, data, reason, causeId FROM EventLog where 1=1 "
   
  /**
   * Save an eventLog
   * Optionnal : the user. At least one of the eventLog user or user must be defined
   * Return the event log with its serialization number
   */
  def saveEventLog(eventLog : EventLog) : Box[EventLog] = {
     val keyHolder = new GeneratedKeyHolder()

         jdbcTemplate.update(
         new PreparedStatementCreator() {
           def createPreparedStatement(connection : Connection) : PreparedStatement = {
             val sqlXml = connection.createSQLXML()
             sqlXml.setString(eventLog.details.toString)
             
             var i = 5
             val (reasonCol, reasonVal) = eventLog.eventDetails.reason match {
               case None => ("", "")
               case Some(r) => 
                 i = i + 1
                 (", reason", ", ?") //#6
             }
             
             val (causeCol, causeVal) = eventLog.cause match {
               case None => ("","")
               case Some(id) => 
                 i = i + 1
                 (", causeId", ", ?") //#7
             }
             
             val ps = connection.prepareStatement(INSERT_SQL.format(reasonCol, causeCol, reasonVal, causeVal), Seq[String]("id").toArray[String]);
             
             ps.setTimestamp(1, new Timestamp(eventLog.creationDate.getMillis))
             ps.setString(2, eventLog.principal.name)
             ps.setString(3, eventLog.eventType.serialize)
             ps.setInt(4, eventLog.severity)
             ps.setSQLXML(5, sqlXml) // have a look at the SQLXML
             
             eventLog.eventDetails.reason.foreach( x => ps.setString(6, x) )
             eventLog.cause foreach( x => ps.setInt(i, x)  )

             ps
           }
         },
         keyHolder)
     
     getEventLog(keyHolder.getKey().intValue)
     
  }
  
  
  def getEventLog(id : Int) : Box[EventLog] = {
    val list = jdbcTemplate.query(SELECT_SQL + " and id = ?" ,
        Array[AnyRef](id.asInstanceOf[AnyRef]),
        EventLogReportsMapper)
    list.size match {
      case 0 => Empty
      case 1 => Full(list.get(0))
      case _ => Failure("Too many event log for this id")
    }
  }
  
  def getEventLogByCriteria(criteria : Option[String], limit:Option[Int] = None, orderBy:Option[String]) : Box[Seq[EventLog]] = {
    val select = SELECT_SQL + 
        criteria.map( c => " and " + c).getOrElse("") + 
        orderBy.map(o => " order by " + o).getOrElse("") +
        limit.map( l => " limit " + l).getOrElse("")
    
    val list = jdbcTemplate.query(select, EventLogReportsMapper)
    
    list.size match {
      case 0 => Empty
      case _ => Full(Seq[EventLog]() ++ list)
    }
  }
}

object EventLogReportsMapper extends RowMapper[EventLog] with Loggable {
    
  def mapRow(rs : ResultSet, rowNum: Int) : EventLog = {
    val eventLogDetails = EventLogDetails(
        id          = Some(rs.getInt("id"))
      , principal   = EventActor(rs.getString("principal"))
      , creationDate= new DateTime(rs.getTimestamp("creationDate"))
      , cause       = {
                        if(rs.getInt("causeId")>0) {
                          Some(rs.getInt("causeId"))
                        } else None
                      }
      , severity    = rs.getInt("severity")
      , reason      = {
                        val desc = rs.getString("reason")
                        if(desc != null && desc.size > 0) {
                          Some(desc)
                        } else None
                      }
      , details     = XML.load(rs.getSQLXML("data").getBinaryStream() )
    )
    
    mapEventLog(
        eventType = EventTypeFactory(rs.getString("eventType"))
      , eventLogDetails 
    ) match {
      case Full(e) => e
      case e:EmptyBox => 
        logger.warn("Error when trying to get the event type, recorded type was: " + rs.getString("eventType"), e)
        UnspecializedEventLog(
            eventLogDetails
        )
      }
  }
  
  private[this] val logFilters = 
        AssetsEventLogsFilter.eventList ::: 
        RuleEventLogsFilter.eventList :::
        GenericEventLogsFilter.eventList :::
        ImportExportEventLogsFilter.eventList :::
        NodeGroupEventLogsFilter.eventList :::
        DirectiveEventLogsFilter.eventList :::
        PolicyServerEventLogsFilter.eventList :::
        PromisesEventLogsFilter.eventList :::
        UserEventLogsFilter.eventList :::
        TechniqueEventLogsFilter.eventList
        
    
  
  private[this] def mapEventLog(
      eventType     : EventLogType
    , eventLogDetails  : EventLogDetails
  ) : Box[EventLog] = {
  
  logFilters.find {
    pf => pf.isDefinedAt((eventType, eventLogDetails))
  }.map(
    x => x.apply((eventType, eventLogDetails))
  ) match {
    case Some(value) => Full(value)
    case None =>
      logger.error("Could not match event type %s".format(eventType.serialize))
      Failure("Unknow Event type")
  }

  }

  private def valuesParsing(elt: NodeSeq): Seq[String] = {
    val returnedValue = collection.mutable.Buffer[String]()
    for (value <- elt \ "value") {
      returnedValue += value.text
    }
    returnedValue
  }
  
   private def customDetailsParsing(elt : NodeSeq) : Map[String, List[String]] = {
    val returnedMap = scala.collection.mutable.Map[String, List[String]]()
    for (entry <- elt\"entry") {
      returnedMap += ((entry\"name").text -> ((entry\"values")\"value").map(x => x.text).toList)
      
    }
    returnedMap.toMap
   }
   
   
  
}