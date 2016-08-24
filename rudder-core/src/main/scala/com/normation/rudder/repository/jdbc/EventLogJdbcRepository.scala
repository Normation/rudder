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

import com.normation.rudder.repository.EventLogRepository
import org.joda.time.DateTime
import org.slf4j.{Logger,LoggerFactory}
import com.normation.eventlog._
import com.normation.rudder.domain.eventlog._
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
import scala.collection.JavaConversions._
import org.joda.time.format.ISODateTimeFormat
import com.normation.rudder.domain.policies.DeleteRuleDiff
import com.normation.rudder.domain.policies.RuleId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.services.eventlog.EventLogFactory
import com.normation.rudder.domain.eventlog._
import scala.collection.mutable.Buffer
import com.normation.rudder.domain.workflows.ChangeRequestId
import scala.util.Try
import scala.util.Success
import scala.util.{Failure => Catch}
import com.normation.rudder.domain.workflows.ChangeRequestId

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

   //reason: column 7
   //causeId: column 8
   val INSERT_SQL = "insert into EventLog (creationDate, modificationId, principal, eventType, severity, data %s %s) values (?, ?, ?, ?, ?, ? %s %s)"

   val SELECT_SQL = "SELECT id, creationDate, modificationId, principal, eventType, severity, data, reason, causeId FROM EventLog where 1=1 "

  /**
   * Save an eventLog
   * Optionnal : the user. At least one of the eventLog user or user must be defined
   * Return the event log with its serialization number
   */
  def saveEventLog(modId: ModificationId, eventLog : EventLog) : Box[EventLog] = {
     val keyHolder = new GeneratedKeyHolder()

     try {
         jdbcTemplate.update(
         new PreparedStatementCreator() {
           def createPreparedStatement(connection : Connection) : PreparedStatement = {
             val sqlXml = connection.createSQLXML()
             sqlXml.setString(eventLog.details.toString)
             var i = 6
             val (reasonCol, reasonVal) = eventLog.eventDetails.reason match {
               case None => ("", "")
               case Some(r) =>
                 i = i + 1
                 (", reason", ", ?") //#7
             }

             val (causeCol, causeVal) = eventLog.cause match {
               case None => ("","")
               case Some(id) =>
                 i = i + 1
                 (", causeId", ", ?") //#8
             }

             val ps = connection.prepareStatement(INSERT_SQL.format(reasonCol, causeCol, reasonVal, causeVal), Seq[String]("id").toArray[String]);

             ps.setTimestamp(1, new Timestamp(eventLog.creationDate.getMillis))
             ps.setString(2, modId.value)
             ps.setString(3, eventLog.principal.name)
             ps.setString(4, eventLog.eventType.serialize)
             ps.setInt(5, eventLog.severity)
             ps.setSQLXML(6, sqlXml) // have a look at the SQLXML

             eventLog.eventDetails.reason.foreach( x => ps.setString(7, x) )
             eventLog.cause foreach( x => ps.setInt(i, x)  )

             ps
           }
         },
         keyHolder)

         getEventLog(keyHolder.getKey().intValue)
     } catch {
       case e:Exception => logger.error(e.getMessage)
       Failure("Exception caught while trying to save an eventlog : %s".format(e.getMessage),Full(e), Empty)
     }
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


  def getEventLogByChangeRequest(
      changeRequest   : ChangeRequestId
    , xpath           : String
    , optLimit        : Option[Int] = None
    , orderBy         : Option[String] = None
    , eventTypeFilter : Option[Seq[EventLogFilter]] = None) : Box[Seq[EventLog]]= {
    Try {
      jdbcTemplate.query(
        new PreparedStatementCreator() {
           def createPreparedStatement(connection : Connection) : PreparedStatement = {
             val order = orderBy.map(o => " order by " + o).getOrElse("")
             val limit = optLimit.map( l => " limit " + l).getOrElse("")
             val eventFilter = eventTypeFilter.map( seq => " and eventType in (" + seq.map(x => "?").mkString(",") + ")").getOrElse("")

             val query= s"${SELECT_SQL} and cast (xpath('${xpath}', data) as text[]) = ? ${eventFilter} ${order} ${limit}"
             val ps = connection.prepareStatement(
                 query, Array[String]());
             ps.setArray(1, connection.createArrayOf("text", Seq(changeRequest.value.toString).toArray[AnyRef]) )

             // if with have eventtype filter, apply them
             eventTypeFilter.map { seq => seq.zipWithIndex.map { case (eventType, number) =>
               // zipwithIndex starts at 0, and we have already 1 used for the array, so we +2 the index
               ps.setString((number+2), eventType.eventType.serialize )
               }
             }

             ps
           }
         }, EventLogReportsMapper)
    } match {
      case Success(x) => Full(x)
      case Catch(error) => Failure(error.toString())
    }
  }

  def getLastEventByChangeRequest(
      xpath: String
    , eventTypeFilter : Option[Seq[EventLogFilter]] = None
  ) : Box[Map[ChangeRequestId,EventLog]] = {

    val eventFilter = eventTypeFilter.map( seq => " where eventType in (" + seq.map(x => "?").mkString(",") + ")").getOrElse("")

    // Query to get Last event by change request, we need to do something like a groupby and get the one with a higher creationDate
    // We partition our result by id, and order them by creation desc, and get the number of that row
    // then we only keep the first row (rownumber <=1)
    val query = s"""
      select * from (
        select
            *
          , cast (xpath(?, data) as text[]) as crid
          , row_number() over (
              partition by cast (xpath(?, data) as text[])
              order by creationDate desc
            ) as rownumber
        from eventlog
        ${eventFilter}
      ) lastEvents where rownumber <= 1;
      """
    Try {
      jdbcTemplate.query(
          new PreparedStatementCreator() {
            def createPreparedStatement(connection : Connection) : PreparedStatement = {
              val ps = connection.prepareStatement(query, Array[String]());
              ps.setString(1, xpath )
              ps.setString(2, xpath )

              // if with have eventtype filter, apply them
              eventTypeFilter.map { seq => seq.zipWithIndex.map { case (eventType, index) =>
                // zipwithIndex starts at 0, and we have already 2 used for the array and start at 1, so we +3 the index
                ps.setString((index+3), eventType.eventType.serialize )
              } }

              ps
            }
          }
        , EventLogWithCRIdMapper
      )
    } match {
      case Success(x) => Full(x.toMap)
      case Catch(error) => Failure(error.toString())
    }
  }

  def getEventLogByCriteria(criteria : Option[String], optLimit:Option[Int] = None, orderBy:Option[String]) : Box[Seq[EventLog]] = {
    val where = criteria.map(c => s"and ${c}").getOrElse("")
    val order = orderBy.map(o => s" order by ${o}").getOrElse("")
    val limit = optLimit.map(l => s" limit ${l}").getOrElse("")
    val select = s"${SELECT_SQL} ${where} ${order} ${limit}"
    Try {
      Full(jdbcTemplate.query(select, EventLogReportsMapper).toSeq)
    } match {
      case Success(events) => events
      case Catch(e) => val msg = s"could not find event log with request ${select} cause: ${e}"
        logger.error(msg)
        Failure(msg)
    }
  }

  def getEventLogWithChangeRequest(id:Int) : Box[Option[(EventLog,Option[ChangeRequestId])]] = {

    val select = "SELECT E.id, creationDate, E.modificationId, principal, eventType, severity, data, reason, causeId, CR.id as changeRequestId FROM EventLog E  LEFT JOIN changeRequest CR on E.modificationId = CR.modificationId"
    Try {
      jdbcTemplate.query(select + " where E.id = ?" ,
        Array[AnyRef](id.asInstanceOf[AnyRef]),
        EventLogWithChangeRequestMapper)
    } match {
      case Success(list) =>
        list.size match {
          case 0 => Full(None)
          case 1 => Full(Some(list.get(0)))
          case _ => Failure("Too many event log for this id")
        }
      case Catch(e) => val msg = s"could not find event log with request ${select} cause: ${e}"
        logger.error(msg)
        Failure(msg)
    }

  }

}

object EventLogWithChangeRequestMapper extends RowMapper[(EventLog,Option[ChangeRequestId])] with Loggable {

  def mapRow(rs : ResultSet, rowNum: Int) : (EventLog,Option[ChangeRequestId]) = {
    val eventLog = EventLogReportsMapper.mapRow(rs, rowNum)
    val changeRequestId = {
          val crId = rs.getInt("changeRequestId")
          if (crId > 0)
            Some(ChangeRequestId(crId))
          else
            None
        }
    (eventLog,changeRequestId)
  }
}

object EventLogReportsMapper extends RowMapper[EventLog] with Loggable {
  def mapRow(rs : ResultSet, rowNum: Int) : EventLog = {
    val eventLogDetails = EventLogDetails(
        id             = Some(rs.getInt("id"))
      , modificationId = {
          val modId = rs.getString("modificationId")
          if (modId != null && modId.size > 0)
            Some(ModificationId(modId))
          else
            None
        }
      , principal      = EventActor(rs.getString("principal"))
      , creationDate   = new DateTime(rs.getTimestamp("creationDate"))
      , cause          = {
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
        WorkflowStepChanged ::
        NodeEventLogsFilter.eventList :::
        AssetsEventLogsFilter.eventList :::
        RuleEventLogsFilter.eventList :::
        GenericEventLogsFilter.eventList :::
        ImportExportEventLogsFilter.eventList :::
        NodeGroupEventLogsFilter.eventList :::
        DirectiveEventLogsFilter.eventList :::
        PolicyServerEventLogsFilter.eventList :::
        PromisesEventLogsFilter.eventList :::
        UserEventLogsFilter.eventList :::
        APIAccountEventLogsFilter.eventList :::
        ChangeRequestLogsFilter.eventList :::
        TechniqueEventLogsFilter.eventList :::
        ParameterEventsLogsFilter.eventList :::
        ModifyGlobalPropertyEventLogsFilter.eventList


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

object EventLogWithCRIdMapper extends RowMapper[(ChangeRequestId,EventLog)] with Loggable {
  def mapRow(rs : ResultSet, rowNum: Int) : (ChangeRequestId,EventLog) = {
    val eventLog = EventLogReportsMapper.mapRow(rs, rowNum)
    // Since we extracted the value from xml,  it is surrounded by "{" and "}" we need to remove first and last character
    val id = rs.getString("crId")
    val crId = ChangeRequestId(rs.getString("crId").substring(1, id.length()-1).toInt)
    (crId,eventLog)
  }
}
