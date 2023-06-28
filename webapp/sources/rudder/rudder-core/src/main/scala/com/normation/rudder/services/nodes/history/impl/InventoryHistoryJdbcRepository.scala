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

import com.normation.errors._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.db.Doobie
import com.normation.rudder.facts.nodes.NodeFact
import com.normation.rudder.services.nodes.history.HistoryLog
import com.normation.rudder.services.nodes.history.HistoryLogRepository
import doobie.Meta
import doobie.Read
import doobie.Write
import doobie.implicits._
import doobie.implicits.javasql._
import doobie.implicits.toSqlInterpolator
import org.joda.time.DateTime
import zio.interop.catz._

/*
 * Storage of NodeFact into `NodeFacts` table which is:
 *
 *   nodeId           text PRIMARY KEY
 * , acceptRefuseDate timestamp with time zone
 * , acceptRefuseFact jsonb
 *
 *
 */

final case class FactLog(id: NodeId, datetime: DateTime, data: NodeFact) extends HistoryLog[NodeId, DateTime, NodeFact] {
  override val historyType: String   = "nodefact"
  override def version:     DateTime = datetime
}

class InventoryHistoryJdbcRepository(
    doobie: Doobie
) extends HistoryLogRepository[NodeId, DateTime, NodeFact, FactLog] {

  import com.normation.rudder.db.Doobie.DateTimeMeta
  import com.normation.rudder.db.json.implicits._
  import com.normation.rudder.facts.nodes.NodeFactSerialisation._
  import doobie._

  implicit val nodeFactMeta: Meta[NodeFact] = new Meta(pgDecoderGet, pgEncoderPut)

  implicit val lotWrite: Write[FactLog] = {
    Write[(String, DateTime, NodeFact)].contramap {
      case log =>
        (log.id.value, log.version, log.data)
    }
  }

  implicit val logRead: Read[FactLog] = {
    Read[(String, DateTime, NodeFact)].map { d: (String, DateTime, NodeFact) =>
      FactLog(
        NodeId(d._1),
        d._2,
        d._3
      )
    }
  }

  /**
   * Save an inventory and return the ID of the saved inventory, and
   * its version
   */
  override def save(id: NodeId, data: NodeFact, datetime: DateTime = DateTime.now): IOResult[FactLog] = {
    val q = sql"""insert into nodefacts (nodeId, acceptRefuseDate, acceptRefuseFact) values (${id}, ${datetime}, ${data})
                  on conflict (nodeId) do update set (acceptRefuseDate, acceptRefuseFact) = (EXCLUDED.acceptRefuseDate, EXCLUDED.acceptRefuseFact)"""

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
  override def get(id: NodeId, version: DateTime): IOResult[FactLog] = {
    val q =
      sql"select nodeId, acceptRefuseDate, acceptRefuseFact from nodefacts where nodeId = ${id.value} and acceptRefuseDate = ${new java.sql.Timestamp(version.getMillis)}"

    transactIOResult(s"error when getting node '${id.value}' accept/refuse fact")(xa => q.query[FactLog].unique.transact(xa))
  }

  /**
   * Return the list of version for ID.
   * IO(Empty list) if no version for the given id
   * List is sorted with last version (most recent) first
   * ( versions.head > versions.head.head )
   */
  override def versions(id: NodeId): IOResult[Seq[DateTime]] = {
    val q =
      sql"select acceptRefuseDate from nodefacts where nodeId = ${id.value}"

    transactIOResult(s"error when getting node '${id.value}' accept/refuse fact date")(xa =>
      q.query[DateTime].option.transact(xa)
    ).map(_.toSeq)
  }

}
