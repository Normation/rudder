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

import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.reports.execution.{AgentRunId, AgentRunWithoutCompliance, AgentRun => RudderAgentRun}

import org.joda.time.DateTime
import doobie._
import com.normation.rudder.db.Doobie._

import cats.implicits._
import com.normation.rudder.git.GitCommitId

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

final object DB {

  //////////

  final case class MigrationEventLog[T](
      id                 : T
    , detectionTime      : DateTime
    , detectedFileFormat : Long
    , migrationStartTime : Option[DateTime]
    , migrationEndTime   : Option[DateTime]
    , migrationFileFormat: Option[Long]
    , description        : Option[String]
  )



  final case class Reports[T](
      id                 : T
    , executionDate      : DateTime
    , nodeId             : String
    , directiveId        : String
    , ruleId             : String
    , reportId           : String
    , component          : String
    , keyValue           : String
    , executionTimestamp : DateTime
    , eventType          : String
    , policy             : String
    , msg                : String
  )

  def insertReports(reports: List[com.normation.rudder.domain.reports.Reports]): ConnectionIO[Int] = {
    val dbreports = reports.map { r =>
      DB.Reports[Unit]((), r.executionDate, r.nodeId.value, r.directiveId.serialize, r.ruleId.serialize, r.reportId
                      , r.component, r.keyValue, r.executionTimestamp, r.severity, "policy", r.message)
    }

    Update[DB.Reports[Unit]]("""
      insert into ruddersysevents
        (executiondate, nodeid, directiveid, ruleid, reportid, component, keyvalue, executiontimestamp, eventtype, policy, msg)
      values (?,?,?, ?,?,?, ?,?,?, ?,?)
    """).updateMany(dbreports)
  }
  //////////

  final case class GitCommitJoin (
      gitCommit     : GitCommitId
    , modificationId: ModificationId
  )

  //////////

  final case class RunProperties (
      name : String
    , value: String
  )

  //////////

  final case class AgentRun(
      nodeId      : String
    , date        : DateTime
    , nodeConfigId: Option[String]
    , insertionId : Long
  ) {
    def toAgentRun = RudderAgentRun(AgentRunId(NodeId(nodeId), date), nodeConfigId.map(NodeConfigId), insertionId)
  }

  final case class UncomputedAgentRun(
      nodeId       : String
    , date         : DateTime
    , nodeConfigId : Option[String]
    , insertionId  : Long
    , insertionDate: DateTime
  ) {
   def toAgentRunWithoutCompliance = AgentRunWithoutCompliance(AgentRunId(NodeId(nodeId), date), nodeConfigId.map(NodeConfigId), insertionId, insertionDate)
  }

  def insertUncomputedAgentRun(runs: List[UncomputedAgentRun]): ConnectionIO[Int] = {
    Update[DB.UncomputedAgentRun]("""
      insert into reportsexecution
        (nodeid, date, nodeconfigid, insertionid, insertiondate)
      values (?,?,?, ?,?)
    """).updateMany(runs)
  }
  //////////

  final case class StatusUpdate(
      key    : String
    , lastId : Long
    , date   : DateTime
  )

}


