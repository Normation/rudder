/*
 *************************************************************************************
 * Copyright 2019 Normation SAS
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
package com.normation.rudder.rest.data

import cats.data.NonEmptyList
import com.normation.eventlog.EventActor
import com.normation.eventlog.EventLog
import com.normation.eventlog.EventLogRequest
import com.normation.eventlog.EventLogRequest.Column
import com.normation.eventlog.EventLogRequest.Direction
import com.normation.eventlog.EventLogRequest.Order
import com.normation.eventlog.EventLogRequest.PrincipalFilter
import com.normation.eventlog.EventLogRequest.Search
import com.normation.eventlog.EventLogType
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.web.services.EventLogDetailsGenerator
import com.normation.utils.DateFormaterService
import enumeratum.Enum
import enumeratum.EnumEntry.Lowercase
import io.scalaland.chimney.Transformer
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.joda.time.DateTime
import scala.xml.NodeSeq
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/**
  * Representation of an event log in the Rest API.
  * This is data in the table of event logs
  */
final case class RestEventLog(
    id:                              Option[Int],
    @jsonField("date") creationDate: DateTime,
    actor:                           EventActor,
    @jsonField("type") eventType:    String,
    description:                     NodeSeq,
    hasDetails:                      Boolean
)

object RestEventLog {

  // We still have Joda DateTime because we map an event log field using chimney
  implicit val datetimeEncoder:    JsonEncoder[DateTime]     = JsonEncoder[String].contramap(DateFormaterService.getDisplayDate)
  implicit val eventActorEncoder:  JsonEncoder[EventActor]   = JsonEncoder[String].contramap(_.name)
  implicit val descriptionEncoder: JsonEncoder[NodeSeq]      = JsonEncoder[String].contramap(_.toString())
  implicit val encoder:            JsonEncoder[RestEventLog] = DeriveJsonEncoder.gen[RestEventLog]

  // For localization we need to transform eventType using translation, passing liftweb.S is not so elegant
  // so we need a conversion (poke at Scala 3's Conversion)
  implicit def transformer(implicit
      translateEventType: EventLogType => String,
      eventLogDetail:     EventLogDetailsGenerator
  ): Transformer[EventLog, RestEventLog] = {
    Transformer
      .define[EventLog, RestEventLog]
      .enableMethodAccessors // because source is a trait
      .withFieldComputed(_.actor, _.principal)
      .withFieldComputed(_.eventType, e => translateEventType(e.eventType))
      .withFieldComputed(_.description, eventLogDetail.displayDescription)
      .withFieldComputed(_.hasDetails, _.details != <entry></entry>)
      .withFieldComputed(_.creationDate, e => DateFormaterService.toDateTime(e.creationDate))
      .buildTransformer
  }
}

/**
  * Response data from the event log API :
  * - success has non-empty "data"
  * - error has "error" message
  */
sealed trait RestEventLogResponse {
  def draw:            Int
  def recordsTotal:    Long
  def recordsFiltered: Long
}
final case class RestEventLogSuccess(
    draw:            Int,
    recordsTotal:    Long,
    recordsFiltered: Long,
    data:            Seq[RestEventLog]
) extends RestEventLogResponse
object RestEventLogSuccess        {
  implicit val encoder: JsonEncoder[RestEventLogSuccess] = DeriveJsonEncoder.gen[RestEventLogSuccess]
}

final case class RestEventLogError private[data] (
    draw:            Int = 0,
    recordsTotal:    Long = 0,
    recordsFiltered: Long = 0,
    data:            String = "",
    error:           String
) extends RestEventLogResponse

object RestEventLogError {
  implicit val encoder: JsonEncoder[RestEventLogError] = DeriveJsonEncoder.gen[RestEventLogError]

  def apply(errorMessage: String): RestEventLogError = RestEventLogError(error = errorMessage)
}

final case class RestEventLogFilter(
    draw:      Int,
    start:     Int,
    length:    Int,
    search:    Option[EventLogRequest.Search],
    startDate: Option[LocalDateTime],
    endDate:   Option[LocalDateTime],
    principal: Option[EventLogRequest.PrincipalFilter],
    order:     Chunk[EventLogRequest.Order]
) {
  def toEventLogRequest: EventLogRequest = {
    EventLogRequest(
      start,
      length,
      search,
      startDate.map(_.toInstant(ZoneOffset.UTC)),
      endDate.map(_.toInstant(ZoneOffset.UTC)),
      principal,
      order.toList.headOption
    )
  }
}

object RestEventLogFilter  {
  implicit val searchDecoder:             JsonDecoder[Search]                   = DeriveJsonDecoder.gen[Search]
  implicit val columnDecoder:             JsonDecoder[Column]                   = JsonDecoder[Int].mapOrFail(Column.fromId)
  implicit val directionDecoder:          JsonDecoder[Direction]                = JsonDecoder[String].mapOrFail(Direction.parse)
  implicit val eventActorDecoder:         JsonDecoder[NonEmptyList[EventActor]] = JsonDecoder[String].mapOrFail(actors =>
    NonEmptyList.fromList(actors.split(",").toList.map(EventActor(_))).toRight("Could not decode actors.")
  )
  implicit val principalFilterDecoder:    JsonDecoder[PrincipalFilter]          = DeriveJsonDecoder.gen[PrincipalFilter]
  implicit val orderDecoder:              JsonDecoder[Order]                    = DeriveJsonDecoder.gen[Order]
  implicit val localDateTimeDecoder:      JsonDecoder[LocalDateTime]            = {
    val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    JsonDecoder[String].map(LocalDateTime.parse(_, format))
  }
  implicit val restEventLogFilterDecoder: JsonDecoder[RestEventLogFilter]       = DeriveJsonDecoder.gen[RestEventLogFilter]
}

final case class RestEventLogDetails(
    id:                 String,
    content:            NodeSeq,
    canRollback:        Boolean,
    nodePropertiesDiff: Option[SimpleDiffJson[List[NodeProperty]]]
)
object RestEventLogDetails {
  implicit val contentEncoder:        JsonEncoder[NodeSeq]             = JsonEncoder[String].contramap(_.toString())
  implicit val nodePropertiesEncoder: JsonEncoder[List[NodeProperty]]  =
    JsonEncoder[Json].contramap(props => Json.Obj(Chunk.from(props).sortBy(_.name).map(p => (p.name, p.jsonZio))))
  implicit val encoder:               JsonEncoder[RestEventLogDetails] = DeriveJsonEncoder.gen[RestEventLogDetails]
}

final case class RestEventLogRollback(
    action: RestEventLogRollback.Action,
    id:     String
)

object RestEventLogRollback {
  sealed trait Action extends Lowercase
  object Action       extends Enum[Action] {
    case object After  extends Action
    case object Before extends Action

    override def values: IndexedSeq[Action] = findValues

    implicit val encoder: JsonEncoder[Action] = JsonEncoder[String].contramap(_.entryName)
  }

  implicit val encoder: JsonEncoder[RestEventLogRollback] = DeriveJsonEncoder.gen[RestEventLogRollback]
}
