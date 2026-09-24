/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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

package com.normation.rudder.services.modification

import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.EventLog
import com.normation.eventlog.EventLogDetails
import com.normation.eventlog.EventLogFilter
import com.normation.eventlog.EventLogRequest
import com.normation.eventlog.ModificationId
import com.normation.eventlog.RollbackEventId
import com.normation.eventlog.RollbackPosition
import com.normation.eventlog.RollbackTarget
import com.normation.rudder.domain.eventlog.AddDirective
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.git.GitCommitId
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.repository.GitModificationRepository
import com.normation.rudder.repository.ItemRollbackRepository
import com.normation.rudder.services.eventlog.EventLogFactory
import com.normation.rudder.tenants.ChangeContext
import com.normation.rudder.tenants.QueryContext
import doobie.Fragment
import org.junit.runner.RunWith
import zio.syntax.*
import zio.test.*
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class ItemRollbackServiceTest extends ZIOSpecDefault {

  import ItemRollbackServiceTest.*
  import ItemRollbackServiceTest.given

  override def spec: Spec[Any, Any] = suite("restoreItem")(
    test("restoring the state before a change targets the parent commit") {
      check(eventIdGen, commitGen) { (eventId, commit) =>
        for {
          restored <- service(commit).restoreItem(eventId, RollbackPosition.Before)
        } yield {
          assertTrue(restored == GitCommitId(commit.value + "^"))
        }
      }
    },
    test("restoring the state after a change targets the same commit") {
      check(eventIdGen, commitGen) { (eventId, commit) =>
        for {
          restored <- service(commit).restoreItem(eventId, RollbackPosition.After)
        } yield {
          assertTrue(restored == commit)
        }
      }
    },
    test("an event log without a modification id can not be rolled back") {
      check(eventIdGen, commitGen) { (eventId, commit) =>
        for {
          restored <- service(commit, eventLogWithoutModificationId).restoreItem(eventId, RollbackPosition.Before).either
        } yield {
          assertTrue(restored.left.exists(_.fullMsg.contains("don't have a matching commit ID")))
        }
      }
    }
  )
}

private object ItemRollbackServiceTest {

  given cc: ChangeContext = ChangeContext.newForRudder(Some("rollback item service test"))

  val eventIdGen: Gen[Any, RollbackEventId] = Gen.long.map(RollbackEventId(_))

  val commitGen: Gen[Any, GitCommitId] = Gen.stringN(40)(Gen.hexCharLower).map(GitCommitId(_))

  val modificationId: ModificationId = ModificationId("modification-id")

  def eventLogWith(modificationId: Option[ModificationId]): EventLog = AddDirective(
    EventLogDetails(
      modificationId = modificationId,
      principal = EventActor("test"),
      reason = None,
      details = <entry/>
    )
  )

  val eventLogWithModificationId:    EventLog = eventLogWith(Some(modificationId))
  val eventLogWithoutModificationId: EventLog = eventLogWith(None)

  def stubEventLogRepository(eventLog: EventLog): EventLogRepository = new EventLogRepository {
    override def getEventLogById(id: Long)(implicit qc: QueryContext): IOResult[EventLog] = eventLog.succeed

    override def eventLogFactory: EventLogFactory = ???
    override def saveEventLog(modId:           ModificationId, eventLog:             EventLog):     IOResult[EventLog]      = ???
    override def getEventLogByCriteria(
        criteria:       Option[Fragment],
        limit:          Option[Int],
        orderBy:        List[Fragment],
        extendedFilter: Option[Fragment]
    ): IOResult[Seq[EventLog]] = ???
    override def getEventLogByCriteria(filter: Option[EventLogRequest])(implicit qc: QueryContext): IOResult[Seq[EventLog]] = ???
    override def getEventLogCount(filter:      Option[EventLogRequest])(implicit qc: QueryContext): IOResult[Long]          = ???
    override def getEventLogWithChangeRequest(id: Int)(implicit
        qc: QueryContext
    ): IOResult[Option[(EventLog, Option[ChangeRequestId])]] = ???
    override def getEventLogByChangeRequest(
        changeRequest:   ChangeRequestId,
        xpath:           String,
        optLimit:        Option[Int],
        orderBy:         Option[String],
        eventTypeFilter: List[EventLogFilter]
    ): IOResult[Vector[EventLog]] = ???
    override def getLastEventByChangeRequest(
        xpath:           String,
        eventTypeFilter: List[EventLogFilter]
    ): IOResult[Map[ChangeRequestId, EventLog]] = ???
  }

  val stubItemRollbackRepository: ItemRollbackRepository = new ItemRollbackRepository {
    override def rollbackItem(target: RollbackTarget, eventLog: EventLog)(implicit
        cc: ChangeContext
    ): IOResult[GitCommitId] = target.archiveCommit.succeed
  }

  def service(commit: GitCommitId, eventLog: EventLog = eventLogWithModificationId): ItemRollbackService = {
    val stubGitModificationRepository: GitModificationRepository = new GitModificationRepository {
      override def getCommits(modificationId: ModificationId): IOResult[Option[GitCommitId]] = Some(commit).succeed
      override def addCommit(commit:          GitCommitId, modId: ModificationId) = ???
    }
    new ItemRollbackServiceImpl(stubGitModificationRepository, stubEventLogRepository(eventLog), stubItemRollbackRepository)
  }
}
