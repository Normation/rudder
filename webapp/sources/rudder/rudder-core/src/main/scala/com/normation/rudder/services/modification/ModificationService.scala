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

import com.normation.box.*
import com.normation.eventlog.*
import com.normation.rudder.git.GitCommitId
import com.normation.rudder.repository.*
import com.normation.rudder.tenants.*
import net.liftweb.common.*
import org.eclipse.jgit.lib.PersonIdent

class ModificationService(
    gitModificationRepository: GitModificationRepository,
    itemArchiveManager:        ItemArchiveManager
) {

  def getCommitsfromEventLog(eventLog: EventLog): Box[Option[GitCommitId]] = {
    eventLog.modificationId match {
      case None        => Full(None)
      case Some(modId) => gitModificationRepository.getCommits(modId).toBox
    }
  }

  def restoreToEventLog(
      eventLog:         EventLog,
      commiter:         PersonIdent,
      rollbackedEvents: Seq[EventLog],
      target:           EventLog
  ): Box[GitCommitId] = {
    for {
      commit   <- getCommitsfromEventLog(eventLog)
      rollback <- commit match {
                    case None    =>
                      Failure(s"The event log ${eventLog.id.getOrElse("")} don't have a matching commit ID and can't be restored")
                    case Some(x) =>
                      itemArchiveManager
                        .rollback(
                          x,
                          commiter,
                          rollbackedEvents,
                          target,
                          "after"
                        )(using QueryContext.systemQC.newCC(None).copy(actor = eventLog.principal))
                        .toBox
                  }
    } yield {
      rollback
    }

  }

  def restoreBeforeEventLog(
      eventLog:         EventLog,
      commiter:         PersonIdent,
      rollbackedEvents: Seq[EventLog],
      target:           EventLog
  ): Box[GitCommitId] = {
    for {
      commit   <- getCommitsfromEventLog(eventLog)
      rollback <- commit match {
                    case None    =>
                      Failure(s"The event log ${eventLog.id.getOrElse("")} don't have a matching commit ID and can't be restored")
                    case Some(x) =>
                      val parentCommit = GitCommitId(x.value + "^")
                      itemArchiveManager
                        .rollback(
                          parentCommit,
                          commiter,
                          rollbackedEvents,
                          target,
                          "before"
                        )(using QueryContext.systemQC.newCC(None).copy(actor = eventLog.principal))
                        .toBox
                  }
    } yield {
      rollback
    }
  }
}
