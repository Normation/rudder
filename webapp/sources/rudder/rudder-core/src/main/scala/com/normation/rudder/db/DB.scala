/*
 *************************************************************************************
 * Copyright 2016 Normation SAS
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

package com.normation.rudder.db

import cats.implicits.*
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.db.Doobie.*
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.git.GitCommitId
import com.normation.rudder.reports.execution.AgentRun as RudderAgentRun
import com.normation.rudder.reports.execution.AgentRunId
import com.normation.rudder.reports.execution.AgentRunWithoutCompliance
import doobie.*
import org.joda.time.DateTime

/*
 * Here, we are declaring case classes that are mapped to SQL tables.
 * We don't have to mappe ALL table, just the one we are using, and for
 * which it is more convenient to have a case class than a simple tuple
 * or ah HList.
 *
 * Convention: all case classes declared here are under the DB object
 * and should always be used with that prefix, for ex:
 *
 * DB.ExpectedReports
 *
 * (i.e, we should never do "import _"). So that we are able to differenciate
 * from ExpectedReports (Rudder object) to DB.ExpectedReports (a line from
 * the table).
 */

object DB {

  //////////

  final case class GitCommitJoin(
      gitCommit:      GitCommitId,
      modificationId: ModificationId
  )

  //////////

  final case class AgentRun(
      nodeId:       String,
      date:         DateTime,
      nodeConfigId: Option[String],
      insertionId:  Long
  ) {
    def toAgentRun: RudderAgentRun =
      RudderAgentRun(AgentRunId(NodeId(nodeId), date), nodeConfigId.map(NodeConfigId.apply), insertionId)
  }

  final case class UncomputedAgentRun(
      nodeId:        String,
      date:          DateTime,
      nodeConfigId:  Option[String],
      insertionId:   Long,
      insertionDate: DateTime
  ) {
    def toAgentRunWithoutCompliance: AgentRunWithoutCompliance = {
      AgentRunWithoutCompliance(
        AgentRunId(NodeId(nodeId), date),
        nodeConfigId.map(NodeConfigId.apply),
        insertionId,
        insertionDate
      )
    }
  }

  def insertUncomputedAgentRun(runs: List[UncomputedAgentRun]): ConnectionIO[Int] = {
    implicit val ReportWrite: Write[DB.UncomputedAgentRun] = {
      type R = (String, DateTime, Option[String], Long, DateTime)
      Write[R].contramap((r: DB.UncomputedAgentRun) => (r.nodeId, r.date, r.nodeConfigId, r.insertionId, r.insertionDate))
    }

    Update[DB.UncomputedAgentRun]("""
      insert into reportsexecution
        (nodeid, date, nodeconfigid, insertionid, insertiondate)
      values (?,?,?, ?,?)
    """).updateMany(runs)
  }
  //////////

  final case class StatusUpdate(
      key:    String,
      lastId: Long,
      date:   DateTime
  )

}
