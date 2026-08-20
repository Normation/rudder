/*
 *************************************************************************************
 * Copyright 2011-2013 Normation SAS
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
import com.normation.eventlog.*
import com.normation.rudder.git.GitCommitId
import com.normation.rudder.repository.*
import com.normation.rudder.tenants.*
import org.eclipse.jgit.lib.PersonIdent
import zio.syntax.*

class ModificationService(
    gitModificationRepository: GitModificationRepository,
    itemArchiveManager:        ItemArchiveManager
) {

  def getCommitsfromEventLog(eventLog: EventLog): IOResult[Option[GitCommitId]] = {
    eventLog.modificationId match {
      case None        => None.succeed
      case Some(modId) => gitModificationRepository.getCommits(modId)
    }
  }

  /*
   * An event log can only be rolled back if we know the commit it led to, ie if it has a modification id.
   */
  private def commitOf(eventLog: EventLog): IOResult[GitCommitId] = {
    getCommitsfromEventLog(eventLog).notOptional(
      s"The event log ${eventLog.id.getOrElse("")} don't have a matching commit ID and can't be restored"
    )
  }

  // the state just *before* a change is the parent of the commit that change led to
  private def parentOf(commit: GitCommitId): GitCommitId = GitCommitId(commit.value + "^")

  def restoreToEventLog(
      eventLog:         EventLog,
      commiter:         PersonIdent,
      rollbackedEvents: Seq[EventLog],
      target:           EventLog
  ): IOResult[GitCommitId] = {
    for {
      commit   <- commitOf(eventLog)
      rollback <- itemArchiveManager.rollback(commit, commiter, rollbackedEvents, target, "after")(using
                    QueryContext.systemQC.newCC(None).copy(actor = eventLog.principal)
                  )
    } yield {
      rollback
    }
  }

  def restoreBeforeEventLog(
      eventLog:         EventLog,
      commiter:         PersonIdent,
      rollbackedEvents: Seq[EventLog],
      target:           EventLog
  ): IOResult[GitCommitId] = {
    for {
      commit   <- commitOf(eventLog)
      rollback <- itemArchiveManager.rollback(parentOf(commit), commiter, rollbackedEvents, target, "before")(using
                    QueryContext.systemQC.newCC(None).copy(actor = eventLog.principal)
                  )
    } yield {
      rollback
    }
  }

  def restoreItemEventLog(
      eventLog:         EventLog,
      commiter:         PersonIdent,
      rollbackedEvents: Seq[EventLog],
      target:           EventLog
  )(using cc: ChangeContext): IOResult[GitCommitId] = {
    for {
      commit   <- commitOf(eventLog)
      rollback <- itemArchiveManager.rollbackItem(parentOf(commit), commiter, rollbackedEvents, target)
    } yield {
      rollback
    }
  }
}
