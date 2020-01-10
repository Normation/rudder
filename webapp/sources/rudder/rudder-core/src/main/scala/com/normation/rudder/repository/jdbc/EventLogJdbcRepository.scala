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
import com.normation.eventlog._
import com.normation.rudder.domain.eventlog._
import java.sql.ResultSet
import net.liftweb.common._
import scala.xml._
import com.normation.rudder.services.eventlog.EventLogFactory
import com.normation.rudder.domain.eventlog._
import com.normation.rudder.domain.workflows.ChangeRequestId

import doobie._, doobie.implicits._
import cats.implicits._
import doobie.postgres.implicits._
import com.normation.rudder.db.Doobie._
import com.normation.rudder.db.Doobie

/**
 * The EventLog repository
 * Save in an SQL table the EventLog, and retrieve unspecialized version of the eventlog
 * Usually, the EventLog won't be created with an id nor a cause id (nor a principal), that why they can be passed
 * in parameters
 *
 */
class EventLogJdbcRepository(
    doobie                      : Doobie
  , override val eventLogFactory: EventLogFactory
) extends EventLogRepository with Loggable {

  import doobie._


  /**
   * Save an eventLog
   * Optionnal : the user. At least one of the eventLog user or user must be defined
   * Return the event log with its serialization number
   */
  def saveEventLog(modId: ModificationId, eventLog : EventLog) : Box[EventLog] = {
    //we only know how to store Elem, not NodeSeq
    eventLog.details match {
      case elt: Elem =>

        val boxId: Box[Int] = sql"""
          insert into eventlog (creationdate, modificationid, principal, eventtype, severity, data, reason, causeid)
          values(${eventLog.creationDate}, ${modId.value}, ${eventLog.principal.name}, ${eventLog.eventType.serialize},
                 ${eventLog.severity}, ${elt}, ${eventLog.eventDetails.reason}, ${eventLog.cause}
                )
        """.update.withUniqueGeneratedKeys[Int]("id").transact(xa).attempt.unsafeRunSync

        for {
          id      <- boxId
          details =  eventLog.eventDetails.copy(id = Some(id), modificationId = Some(modId))
          saved   <- EventLogReportsMapper.mapEventLog(eventLog.eventType, details)
        } yield {
          saved
        }
      case _ => Failure(s"Eventlog with type '${eventLog.eventType} has invalid XML for details (it must be a well formed document with only one root): ${eventLog.details}'")
    }
  }

  /*
   * How we transform data from DB into an eventlog.
   * We never fail, because we want to let the user be able to
   * see unreconized event log (even if it means looking to XML,
   * it's better than missing an important info)
   */
  private[this] def toEventLog(pair: (String, EventLogDetails)): EventLog = {
    val (eventType, eventLogDetails) = pair
    EventLogReportsMapper.mapEventLog(
        EventTypeFactory(eventType)
      , eventLogDetails
    ) match {
      case Full(log)   => log
      case e: EmptyBox =>
        logger.warn(s"Error when trying to get the event type, recorded type was: '${eventType}'", e)
        UnspecializedEventLog(eventLogDetails)
      }
  }

  def getEventLogByChangeRequest(
      changeRequest   : ChangeRequestId
    , xpath           : String
    , optLimit        : Option[Int] = None
    , orderBy         : Option[String] = None
    , eventTypeFilter : List[EventLogFilter] = Nil
  ) : Box[Vector[EventLog]]= {

    val order = orderBy.map(o => " order by " + o).getOrElse("")
    val limit = optLimit.map( l => " limit " + l).getOrElse("")
    val eventFilter = eventTypeFilter match {
      case Nil => ""
      case seq => " and eventType in (" + seq.map(x => "?").mkString(",") + ")"
    }

    val q = s"""
      select eventtype, id, modificationid, principal, creationdate, causeid, severity, reason, data
      from eventlog
      where cast (xpath('${xpath}', data) as varchar[]) = ?
      ${eventFilter} ${order} ${limit}
    """

    // here, we have to build the parameters by hand
    // the first is the array needed by xpath, the following are eventType - if any
    val eventTypeParam = eventTypeFilter.zipWithIndex
    val param = eventTypeParam.foldLeft(HPS.set(1, List(changeRequest.value.toString))) { case (current, (event,index)) =>
      // zipwithIndex starts at 0, and we have already 1 used for the array, so we +2 the index
      current *> HPS.set(index+2, event.eventType.serialize)
    }

    (for {
      entries <- HC.stream[(String, EventLogDetails)](q, param, 512).compile.toVector
    } yield {
      entries.map(toEventLog)
    }).transact(xa).attempt.unsafeRunSync
  }

  def getLastEventByChangeRequest(
      xpath          : String
    , eventTypeFilter: List[EventLogFilter] = Nil
  ) : Box[Map[ChangeRequestId,EventLog]] = {

    val eventFilter = eventTypeFilter match {
      case Nil => ""
      case seq => "where eventType in (" + seq.map(x => "?").mkString(",") + ")"
    }

    // Query to get Last event by change request, we need to do something like a groupby and get the one with a higher creationDate
    // We partition our result by id, and order them by creation desc, and get the number of that row
    // then we only keep the first row (rownumber <=1)
    val q = s"""
      select crid, eventtype, id, modificationid, principal, creationdate, causeid, severity, reason, data
      from (
        select
            eventtype, id, modificationid, principal, creationdate, causeid, severity, reason, data
          , cast (xpath('${xpath}', data) as varchar[]) as crid
          , row_number() over (
              partition by cast (xpath('${xpath}', data) as varchar[])
              order by creationDate desc
            ) as rownumber
        from eventlog
        ${eventFilter}
      ) lastEvents where rownumber <= 1;
    """

    // here, we have to build the parameters by hand
    // the first is the array needed by xpath, the following are eventType - if any
    val eventTypeParam = eventTypeFilter.zipWithIndex
    val param = eventTypeParam.traverse { case (event,index) =>
      // zipwithIndex starts at 0, but sql index at 1, so we +1 the index
      HPS.set(index+1, event.eventType.serialize)
    }.void

    (for {
      entries <- HC.stream[(String, String, EventLogDetails)](q, param, 512).compile.toVector
    } yield {
      entries.map { case (crid, tpe, details) =>
        (ChangeRequestId(crid.substring(1, crid.length()-1).toInt), toEventLog((tpe, details)) )
      }.toMap
    }).transact(xa).attempt.unsafeRunSync
  }

  def getEventLogByCriteria(criteria : Option[String], optLimit:Option[Int] = None, orderBy:Option[String]) : Box[Vector[EventLog]] = {

    val where = criteria.map(c => s"where ${c}").getOrElse("")
    val order = orderBy.map(o => s" order by ${o}").getOrElse("")
    val limit = optLimit.map(l => s" limit ${l}").getOrElse("")

    val q = s"""
      select eventtype, id, modificationid, principal, creationdate, causeid, severity, reason, data
      from eventlog
      ${where} ${order} ${limit}
    """

    (for {
      entries <- query[(String, EventLogDetails)](q).to[Vector]
    } yield {
      entries.map(toEventLog)
    }).transact(xa).attempt.unsafeRunSync ?~! s"could not find event log with request ${q}"
  }

  def getEventLogWithChangeRequest(id:Int) : Box[Option[(EventLog,Option[ChangeRequestId])]] = {

    val select = sql"""
      SELECT E.eventtype, E.id, E.modificationid, E.principal, E.creationdate, E.causeid, E.severity, E.reason, E.data, CR.id as changeRequestId
      FROM EventLog E LEFT JOIN changeRequest CR on E.modificationId = CR.modificationId
      where E.id = ${id}
    """

    (for {
      optEntry <- select.query[(String, EventLogDetails, Option[Int])].option
    } yield {
      optEntry.map { case (tpe, details, crid) =>
        (toEventLog((tpe, details)), crid.flatMap(i => if(i > 0) Some(ChangeRequestId(i)) else None))
      }
    }).transact(xa).attempt.unsafeRunSync ?~! s"could not find event log with request ${select}"
  }

}

private object EventLogReportsMapper extends Loggable {
  def mapRow(rs : ResultSet, rowNum: Int) : EventLog = {
    val eventLogDetails = EventLogDetails(
        id             = Some(rs.getInt("id"))
      , modificationId = {
          val modId = rs.getString("modificationId")
          if (modId != null && modId.nonEmpty)
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
                        if(desc != null && desc.nonEmpty) {
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


  def mapEventLog(
      eventType      : EventLogType
    , eventLogDetails: EventLogDetails
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
}
