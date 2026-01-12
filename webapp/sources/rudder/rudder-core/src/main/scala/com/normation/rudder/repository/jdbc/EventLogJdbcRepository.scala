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

import cats.implicits.*
import com.normation.NamedZioLogger
import com.normation.errors.*
import com.normation.eventlog.*
import com.normation.eventlog.EventLogRequest.Direction.Asc
import com.normation.eventlog.EventLogRequest.Direction.Desc
import com.normation.rudder.db.Doobie
import com.normation.rudder.db.Doobie.*
import com.normation.rudder.domain.eventlog.*
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.ncf.eventlogs.EditorTechniqueEventLogsFilter
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.services.eventlog.EventLogFactory
import doobie.*
import doobie.free.connection
import doobie.free.preparedstatement
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.fragments
import doobie.util.log.LoggingInfo
import doobie.util.log.Parameters.NonBatch
import scala.annotation.nowarn
import scala.xml.*
import zio.interop.catz.*

/**
 * The EventLog repository
 * Save in an SQL table the EventLog, and retrieve unspecialized version of the eventlog
 * Usually, the EventLog won't be created with an id nor a cause id (nor a principal), that why they can be passed
 * in parameters
 *
 */
class EventLogJdbcRepository(
    doobie:                       Doobie,
    override val eventLogFactory: EventLogFactory
) extends EventLogRepository with NamedZioLogger {

  import com.normation.rudder.repository.jdbc.EventLogJdbcRepository.*
  import doobie.*

  override def loggerName: String = this.getClass.getName

  /**
   * Save an eventLog
   * Optional : the user. At least one of the eventLog user or user must be defined
   * Return the event log with its serialization number
   */
  def saveEventLog(modId: ModificationId, eventLog: EventLog): IOResult[EventLog] = {
    // we only know how to store Elem, not NodeSeq
    eventLog.details match {
      case elt: Elem =>
        val boxId: IOResult[Int] = {
          transactIOResult(s"Error when persisting event log ${eventLog.eventType.serialize}")(xa =>
            saveEventLogSQL(modId, eventLog, elt).withUniqueGeneratedKeys[Int]("id").transact(xa)
          )
        }

        for {
          id     <- boxId
          details = eventLog.eventDetails.copy(id = Some(id), modificationId = Some(modId))
          saved  <- EventLogReportsMapper.mapEventLog(eventLog.eventType, details).toIO
        } yield {
          saved
        }
    }
  }

  /*
   * How we transform data from DB into an eventlog.
   * We never fail, because we want to let the user be able to
   * see unreconized event log (even if it means looking to XML,
   * it's better than missing an important info)
   */
  private def toEventLog(pair: (String, EventLogDetails)): EventLog = {
    val (eventType, eventLogDetails) = pair
    EventLogReportsMapper.mapEventLog(
      EventTypeFactory(eventType),
      eventLogDetails
    ) match {
      case Right(log)  => log
      case Left(error) =>
        logEffect.warn(s"Error when trying to get the event type, recorded type was: '${eventType}'")
        UnspecializedEventLog(eventLogDetails)
    }
  }

  def getEventLogByChangeRequest(
      changeRequest:   ChangeRequestId,
      xpath:           String,
      optLimit:        Option[Int] = None,
      orderBy:         Option[String] = None,
      eventTypeFilter: List[EventLogFilter] = Nil
  ): IOResult[Vector[EventLog]] = {

    val order       = orderBy.map(o => " order by " + o).getOrElse("")
    val limit       = optLimit.map(l => " limit " + l).getOrElse("")
    val eventFilter = eventTypeFilter match {
      case Nil => ""
      case seq => " and eventType in (" + seq.map(_ => "?").mkString(",") + ")"
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
    val param          = eventTypeParam.foldLeft(HPS.set(1, List(changeRequest.value.toString))) {
      case (current, (event, index)) =>
        // zipwithIndex starts at 0, and we have already 1 used for the array, so we +2 the index
        current *> HPS.set(index + 2, event.eventType.serialize)
    }

    transactIOResult(s"Error when retrieving event logs for change request '${changeRequest.value}'")(xa => {
      import com.normation.rudder.db.Doobie.*
      (for {

        // def stream[A: Read](sql: String, prep: PreparedStatementIO[Unit], chunkSize: Int): Stream[ConnectionIO, A] =
        // liftStream(chunkSize, IFC.prepareStatement(sql), prep, IFPS.executeQuery)
        // IFC.prepareStatement(sql), prep, IFPS.executeQuery
        entries <- HC.stream[(String, EventLogDetails)](
                     connection.prepareStatement(q),
                     param,
                     preparedstatement.executeQuery,
                     512,
                     LoggingInfo(q, NonBatch(Nil), "get events")
                   ).compile
                     .toVector
      } yield {
        entries.map(toEventLog)
      }).transact(xa)
    })
  }

  def getLastEventByChangeRequest(
      xpath:           String,
      eventTypeFilter: List[EventLogFilter] = Nil
  ): IOResult[Map[ChangeRequestId, EventLog]] = {

    transactIOResult(s"Error when retrieving event logs for change request '${xpath}'")(xa => {
      (for {
        entries <- EventLogJdbcRepository.getLastEventByChangeRequestSQL(xpath, eventTypeFilter).to[Vector]
      } yield {
        entries.flatMap {
          case (crIds, tpe, details) => {
            crIds.headOption
              .flatMap(_.toIntOption)
              .map(id => (ChangeRequestId(id), toEventLog((tpe, details))))
          }
        }.toMap
      }).transact(xa)
    })
  }

  def getEventLogCount(filter: Option[EventLogRequest]): IOResult[Long] = {
    val q = getEventLogCountSQL(filter)

    transactIOResult(s"Error when retrieving event logs count with request: ${q}")(xa => {
      (for {
        entries <- q.unique
      } yield {
        entries
      }).transact(xa)
    })
  }

  def getEventLogByCriteria(filter: Option[EventLogRequest]): IOResult[Seq[EventLog]] = {
    val q = getEventLogByCriteriaSQL(filter)

    transactIOResult(s"Error when retrieving event logs for change request with request: ${q}")(xa => {
      (for {
        entries <- q.to[Vector]
      } yield {
        entries.map(toEventLog)
      }).transact(xa)
    })
  }

  @nowarn("msg=deprecated")
  def getEventLogByCriteria(
      criteria:       Option[Fragment],
      optLimit:       Option[Int] = None,
      orderBy:        List[Fragment],
      extendedFilter: Option[Fragment]
  ): IOResult[Seq[EventLog]] = {

    val where = criteria match {
      case Some(value) => fr" where " ++ value
      case None        => Fragment.empty
    }
    val order = orderBy match {
      case Nil  => Fragment.empty
      case list => fr" order by " ++ list.intercalate(fr",")
    }
    val limit = optLimit match {
      case Some(l) => fr" limit ${l} "
      case None    => Fragment.empty
    }
    val from  = extendedFilter.getOrElse(fr"from eventlog")

    val q = sql"""select eventtype, id, modificationid, principal, creationdate, causeid, severity, reason, data """ ++
      from ++ where ++ order ++ limit

    transactIOResult(s"Error when retrieving event logs for change request with request: ${q}")(xa => {
      (for {
        entries <- q.query[(String, EventLogDetails)].to[Vector]
      } yield {
        entries.map(toEventLog)
      }).transact(xa)
    })
  }

  def getEventLogWithChangeRequest(id: Int): IOResult[Option[(EventLog, Option[ChangeRequestId])]] = {

    val select = sql"""
      SELECT E.eventtype, E.id, E.modificationid, E.principal, E.creationdate, E.causeid, E.severity, E.reason, E.data, CR.id as changeRequestId
      FROM EventLog E LEFT JOIN changeRequest CR on E.modificationId = CR.modificationId
      where E.id = ${id}
    """

    transactIOResult(s"Error when retrieving event logs for change request with request: ${select}")(xa => {
      (for {
        optEntry <- select.query[(String, EventLogDetails, Option[Int])].option
      } yield {
        optEntry.map {
          case (tpe, details, crid) =>
            (toEventLog((tpe, details)), crid.flatMap(i => if (i > 0) Some(ChangeRequestId(i)) else None))
        }
      }).transact(xa)
    })
  }

  def getEventLogById(id: Long): IOResult[EventLog] = {
    val q = Query[Long, (String, EventLogDetails)](s"""
      select eventtype, id, modificationid, principal, creationdate, causeid, severity, reason, data
      from eventlog where id = ?
    """).toQuery0(id)
    transactIOResult(s"Error when getting event log with id ${id}")(xa => q.unique.map(toEventLog).transact(xa))
  }
}

object EventLogJdbcRepository {

  def saveEventLogSQL(modId: ModificationId, eventLog: EventLog, elt: Elem): Update0 = {
    sql"""
            insert into eventlog (creationdate, modificationid, principal, eventtype, severity, data, reason, causeid)
            values(${eventLog.creationDate}, ${modId.value}, ${eventLog.principal.name}, ${eventLog.eventType.serialize},
                   ${eventLog.severity}, $elt, ${eventLog.eventDetails.reason}, ${eventLog.cause}
                  )
          """.update
  }

  private def filterToFromAndWhere(filter: Option[EventLogRequest]) = {
    val interval = filter.flatMap(f => {
      (f.startDate, f.endDate) match {
        case (None, None)             => None
        case (Some(start), None)      => Some(fr"creationdate > $start")
        case (None, Some(end))        => Some(fr"creationdate < $end")
        case (Some(start), Some(end)) =>
          val orderedDate = if (start.isAfter(end)) (end, start) else (start, end)
          Some(fr"creationdate > ${orderedDate._1} and creationdate < ${orderedDate._2}")
      }
    })

    val includePrincipals = filter.flatMap(f => f.principal).flatMap(p => p.include.map(nel => fragments.in(fr"principal", nel)))
    val excludePrincipals =
      filter.flatMap(f => f.principal).flatMap(p => p.exclude.map(nel => fragments.notIn(fr"principal", nel)))

    val search = filter.flatMap(f => f.search).flatMap(toFragment)

    val where = Fragments.whereAndOpt(interval, includePrincipals, excludePrincipals, search)

    val fromWithSearchFragment = {
      fr"""
          |from (
          |  select eventtype,
          |         id,
          |         modificationid,
          |         principal,
          |         creationdate,
          |         causeid,
          |         severity,
          |         reason,
          |         data,
          |         UNNEST(xpath('string(//entry)',data))::text as filter
          |  from eventlog
          |) as temp1
                                 """.stripMargin
    }
    val regularFrom            = fr"from eventlog"
    val fromWithSearch         = (search, interval) match {
      case (None, intervalCriteria)               => None
      case (Some(search), None)                   => Some(fromWithSearchFragment)
      case (Some(search), Some(intervalCriteria)) => Some(fromWithSearchFragment)
    }
    (fromWithSearch.getOrElse(regularFrom), where)
  }

  def getEventLogCountSQL(filter: Option[EventLogRequest]): Query0[Long] = {
    val (from, where) = filterToFromAndWhere(filter)
    sql"""
         |  SELECT count(*)
         |  ${from}
         |  ${where}
         |""".stripMargin.query[Long]
  }

  def getEventLogByCriteriaSQL(filter: Option[EventLogRequest]): Query0[(String, EventLogDetails)] = {
    val order   = filter.flatMap(_.order.map(toFragment))
    val orderBy = order
      .map(fr"ORDER BY" ++ _)
      .getOrElse(Fragment.empty)

    val limit = filter.map(f => fr"LIMIT" ++ Fragment.const(f.length.toString)).getOrElse(Fragment.empty)

    val offset = filter.map(f => fr"OFFSET" ++ Fragment.const(f.start.toString)).getOrElse(Fragment.empty)

    val (from, where) = filterToFromAndWhere(filter)

    sql"""
         |  SELECT eventtype, id, modificationid, principal, creationdate, causeid, severity, reason, data
         |  ${from}
         |  ${where}
         |  ${orderBy}
         |  ${limit}
         |  ${offset}
         |""".stripMargin.query[(String, EventLogDetails)]
  }

  // Query to get Last event by change request, we need to do something like a groupby and get the one with a higher creationDate
  // We partition our result by id, and order them by creation desc, and get the number of that row
  // then we only keep the first row (rownumber <=1)
  def getLastEventByChangeRequestSQL(
      xpath:           String,
      eventTypeFilter: List[EventLogFilter]
  ): Query0[(List[String], String, EventLogDetails)] = {
    val subqueryFilter: Fragment = {
      eventTypeFilter.toNel
        .map(events => fr"WHERE" ++ fragments.in(fr"eventtype", events.map(_.eventType.serialize)))
        .getOrElse(fr"")
    }
    val xpathFr = Fragment.const(xpath)
    val subquery: Fragment = {
      fr"""
        SELECT eventtype, id, modificationid, principal, creationdate, causeid, severity, reason, data
          , CAST (xpath('$xpathFr', data) AS varchar[]) AS crids
          , row_number() OVER (
            PARTITION BY CAST (xpath('$xpathFr', data) AS varchar[])
            ORDER BY creationDate DESC
          ) AS rownumber
        FROM eventlog
      """ ++ subqueryFilter
    }

    val result = {
      fr"""
        SELECT crids, eventtype, id, modificationid, principal, creationdate, causeid, severity, reason, data
        FROM ( $subquery ) lastEvents WHERE rownumber <= 1
      """.query[(List[String], String, EventLogDetails)]
    }

    result
  }

  def toFragment(order: EventLogRequest.Order): Fragment = {
    val directionFragment = order.dir match {
      case Desc => fr"DESC"
      case Asc  => fr"ASC"
    }
    Fragment.const(order.column.entryName) ++ directionFragment
  }

  def toFragment(search: EventLogRequest.Search): Option[Fragment] = {
    if (search.value.isEmpty) {
      None
    } else {
      val v = "%" + search.value + "%"
      Some(fr"(temp1.filter ilike ${v} OR temp1.eventtype ilike ${v})")
    }
  }
}

private object EventLogReportsMapper extends NamedZioLogger {

  override def loggerName: String = this.getClass.getName

  private val logFilters = {
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
    ModifyGlobalPropertyEventLogsFilter.eventList :::
    SecretEventsLogsFilter.eventList :::
    EditorTechniqueEventLogsFilter.eventList
  }

  def mapEventLog(
      eventType:       EventLogType,
      eventLogDetails: EventLogDetails
  ): PureResult[EventLog] = {

    logFilters.find(pf => pf.isDefinedAt((eventType, eventLogDetails))).map(x => x.apply((eventType, eventLogDetails))) match {
      case Some(value) =>
        Right(value)
      case None        =>
        Left(Inconsistency(s"Unknown Event type: '${eventType.serialize}'"))
    }
  }
}
