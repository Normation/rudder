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

import com.normation.rudder.repository._
import com.normation.eventlog._
import net.liftweb.common._
import com.normation.utils.StringUuidGenerator
import org.eclipse.jgit.lib.PersonIdent
import com.normation.rudder.repository.EventLogRepository
import scala.concurrent.Await
import scala.util.{Try, Success => TSuccess, Failure => TFailure}
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit

class ModificationService(
      eventLogRepository : EventLogRepository
    , gitModificationRepository : GitModificationRepository
    , itemArchiveManager : ItemArchiveManager
    , uuidGen : StringUuidGenerator ) {

  def getCommitsfromEventLog(eventLog:EventLog) : Option[GitCommitId] = {
    eventLog.modificationId match {
      case None        => None
      case Some(modId) =>
        Try(Await.result(gitModificationRepository.getCommits(modId), Duration(200, TimeUnit.MILLISECONDS))) match {
        case TSuccess(Full(s))     => s.headOption
        case TSuccess(eb:EmptyBox) => None
        case TFailure(ex) => Failure(s"Error when finding back commit ID for modification ID '${modId.value}': ${ex.getMessage}", Full(ex), Empty)
      }
    }
  }

  def restoreToEventLog(eventLog:EventLog, commiter:PersonIdent, rollbackedEvents:Seq[EventLog], target:EventLog) = {
    for {
      commit   <-  getCommitsfromEventLog(eventLog)
      rollback <- itemArchiveManager.rollback(commit, commiter, ModificationId(uuidGen.newUuid), eventLog.principal, None, rollbackedEvents, target, "after", false)
    } yield {
      rollback
    }

  }

  def restoreBeforeEventLog(eventLog:EventLog, commiter:PersonIdent, rollbackedEvents:Seq[EventLog], target:EventLog) = {
    for {
      commit   <-  getCommitsfromEventLog(eventLog)
      parentCommit = GitCommitId(commit.value+"^")
      rollback <- itemArchiveManager.rollback(parentCommit, commiter, ModificationId(uuidGen.newUuid), eventLog.principal, None, rollbackedEvents, target, "before", false)
    } yield { val parentCommit = GitCommitId(commit.value+"^")
        rollback
    }
  }
}
