/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
 *************************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *************************************************************************************
 */

package com.normation.rudder.services.nodes.history.impl

import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.inventory.domain.AcceptedInventory
import com.normation.inventory.domain.InventoryStatus
import com.normation.inventory.domain.NodeId
import com.normation.rudder.db.Doobie
import com.normation.rudder.db.Doobie.*
import com.normation.rudder.facts.nodes.NodeFact
import com.normation.rudder.services.nodes.history.HistoryLog
import com.normation.rudder.services.nodes.history.HistoryLogRepository
import com.normation.utils.DateFormaterService
import com.normation.utils.DateFormaterService.json.*
import doobie.Meta
import doobie.Read
import doobie.Write
import doobie.implicits.*
import org.joda.time.DateTime
import zio.interop.catz.*
import zio.json.*

/*
 * Store inventory information about a node for when it is accepted/refused.
 * This service is a bit convoluted b/c it was adapted from a time where we used LDAP LDIF.
 * But it proved to be generic enough to adapt to postgres. Still, the types are a be complicated.
 *
 * Storage of NodeFact into `NodeFacts` table which is:
 *
 *   nodeId            text PRIMARY KEY
 * , acceptRefuseEvent jsonb // { "date": "timestamp with timezone", "actor": "string", "status": "accepted/deleted" }
 * , acceptRefuseFact  jsonb
 * , deleteEvent       jsonb // { "date": "timestamp with timezone", "actor": "string" }
 *
 */

final case class FactLogData(fact: NodeFact, actor: EventActor, status: InventoryStatus)

final case class FactLog(id: NodeId, datetime: DateTime, data: FactLogData) extends HistoryLog[NodeId, DateTime, FactLogData] {
  override val historyType: String   = "nodefact"
  override def version:     DateTime = datetime
}

final case class NodeAcceptRefuseEvent(date: DateTime, actor: String, status: String)
object NodeAcceptRefuseEvent {
  implicit val codecFactLogAcceptRefuseEvent: JsonCodec[NodeAcceptRefuseEvent] = DeriveJsonCodec.gen
}
final case class NodeDeleteEvent(date: DateTime, actor: String)
object NodeDeleteEvent       {
  implicit val codecNodeDeleteEvent: JsonCodec[NodeDeleteEvent] = DeriveJsonCodec.gen
}

trait InventoryHistoryDelete {
  // if the node doesn't have an acceptation line, ignore
  def saveDeleteEvent(id: NodeId, date: DateTime, actor: EventActor): IOResult[Unit]

  def getDeleteEvent(id: NodeId): IOResult[Option[NodeDeleteEvent]]

  // delete a node facts
  def delete(id: NodeId): IOResult[Unit]

  def deleteFactIfDeleteEventBefore(date: DateTime): IOResult[Vector[NodeId]]

  def deleteFactCreatedBefore(date: DateTime): IOResult[Vector[NodeId]]
}

class InventoryHistoryJdbcRepository(
    doobie: Doobie
) extends HistoryLogRepository[NodeId, DateTime, FactLogData, FactLog] with InventoryHistoryDelete {

  import com.normation.rudder.db.Doobie.DateTimeMeta
  import com.normation.rudder.db.json.implicits.*
  import com.normation.rudder.facts.nodes.NodeFactSerialisation.*
  import doobie.*

  implicit val nodeAcceptRefuseEventMeta: Meta[NodeAcceptRefuseEvent] = new Meta(pgDecoderGet, pgEncoderPut)
  implicit val nodeDeleteEventMeta:       Meta[NodeDeleteEvent]       = new Meta(pgDecoderGet, pgEncoderPut)
  implicit val nodeFactMeta:              Meta[NodeFact]              = new Meta(pgDecoderGet, pgEncoderPut)

  implicit val lotWrite: Write[FactLog] = {
    Write[(String, NodeAcceptRefuseEvent, NodeFact)].contramap {
      case log =>
        (log.id.value, NodeAcceptRefuseEvent(log.version, log.data.actor.name, log.data.status.name), log.data.fact)
    }
  }

  implicit val logRead: Read[FactLog] = {
    Read[(String, NodeAcceptRefuseEvent, NodeFact)].map { (d: (String, NodeAcceptRefuseEvent, NodeFact)) =>
      FactLog(
        NodeId(d._1),
        d._2.date,
        FactLogData(d._3, EventActor(d._2.actor), InventoryStatus.apply(d._2.status).getOrElse(AcceptedInventory))
      )
    }
  }

  /**
   * Save an inventory and return the ID of the saved inventory, and
   * its version
   */
  override def save(id: NodeId, data: FactLogData, datetime: DateTime): IOResult[FactLog] = {
    val event = NodeAcceptRefuseEvent(datetime, data.actor.name, data.status.name)
    val q     = sql"""insert into nodefacts (nodeId, acceptRefuseEvent, acceptRefuseFact) values (${id}, ${event}, ${data.fact})
                  on conflict (nodeId) do update set (acceptRefuseEvent, acceptRefuseFact) = (EXCLUDED.acceptRefuseEvent, EXCLUDED.acceptRefuseFact)"""

    transactIOResult(s"error when update node '${id.value}' accept/refuse fact")(xa => q.update.run.transact(xa)).map(_ =>
      FactLog(id, datetime, data)
    )
  }

  /**
   * Retrieve all ids known by the repository
   */
  override def getIds: IOResult[Seq[NodeId]] = {
    val q =
      sql"select nodeId from nodefacts"

    transactIOResult(s"error when getting node IDs accept/refuse facts")(xa => q.query[NodeId].to[Seq].transact(xa))
  }

  /**
   * Get the record for the given UUID and version.
   */
  override def get(id: NodeId, version: DateTime): IOResult[Option[FactLog]] = {
    val q = {
      sql"select nodeId, acceptRefuseEvent, acceptRefuseFact from nodefacts where nodeId = ${id.value} and acceptRefuseEvent ->>'date' = ${DateFormaterService
          .serialize(version)}"
    }

    transactIOResult(s"error when getting node '${id.value}' accept/refuse fact")(xa => q.query[FactLog].option.transact(xa))
  }

  /**
   * Return the list of version for ID.
   * IO(Empty list) if no version for the given id
   * List is sorted with last version (most recent) first
   * ( versions.head > versions.head.head )
   */
  override def versions(id: NodeId): IOResult[Seq[DateTime]] = {
    val q =
      sql"select acceptRefuseEvent ->> 'date' from nodefacts where nodeId = ${id.value}"

    transactIOResult(s"error when getting node '${id.value}' accept/refuse fact date")(xa =>
      q.query[DateTime].option.transact(xa)
    ).map(_.toSeq)
  }

  // save and get delete event

  // if the node doesn't have an acceptation line, ignore
  def saveDeleteEvent(id: NodeId, date: DateTime, actor: EventActor): IOResult[Unit] = {
    val event = NodeDeleteEvent(date, actor.name)
    val q     = sql"""update nodefacts set deleteEvent = ${event} where nodeid=${id}"""
    transactIOResult(s"error when setting delete event for node '${id.value}'")(xa => q.update.run.transact(xa)).unit
  }

  def getDeleteEvent(id: NodeId): IOResult[Option[NodeDeleteEvent]] = {
    val q = sql"""select deleteEvent from nodefacts where id=${id}"""
    transactIOResult(s"error when getting node '${id.value}' delete event")(xa => q.query[NodeDeleteEvent].option.transact(xa))
  }

  // delete a node facts
  def delete(id: NodeId): IOResult[Unit] = {
    val q = sql"""delete from nodefacts where id=${id}"""
    transactIOResult(s"error when deleting acceptation information for node '${id.value}'")(xa => q.update.run.transact(xa)).unit
  }

  // delete all facts which have a delete event older than given data
  def deleteFactIfDeleteEventBefore(date: DateTime): IOResult[Vector[NodeId]] = {
    val q = sql"""delete from nodefacts
           where to_timestamp(deleteEvent->>'date', 'YYYY-MM-DDTHH:MI:SS"Z"') < ${date}
           returning nodeid"""

    transactIOResult(
      s"error when deleting acceptation information for deleted nodes older than '${DateFormaterService.getDisplayDate(date)}'"
    )(xa => {
      q.query[NodeId].to[Vector].transact(xa)
    })
  }

  // delete all facts which were created before given data (whatever node current status)
  // Postgresql has a "returning id" clause, but it does not seems to be supported by JDBC. It
  // would have been good for logs
  def deleteFactCreatedBefore(date: DateTime): IOResult[Vector[NodeId]] = {
    val q = sql"""delete from nodefacts
         where to_timestamp(acceptRefuseEvent->>'date', 'YYYY-MM-DDTHH:MI:SS"Z"') < ${date}
         returning nodeid"""

    transactIOResult(
      s"error when deleting acceptation information for node facts older than '${DateFormaterService.getDisplayDate(date)}'"
    )(xa => {
      q.query[NodeId].to[Vector].transact(xa)
    })
  }
}
