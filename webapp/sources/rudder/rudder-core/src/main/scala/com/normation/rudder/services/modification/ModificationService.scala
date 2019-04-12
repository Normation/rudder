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

import com.normation.eventlog._

import com.normation.rudder.repository._
import com.normation.rudder.repository.EventLogRepository
import com.normation.utils.StringUuidGenerator

import org.eclipse.jgit.lib.PersonIdent
import com.normation.box._

import net.liftweb.common._

class ModificationService(
      eventLogRepository : EventLogRepository
    , gitModificationRepository : GitModificationRepository
    , itemArchiveManager : ItemArchiveManager
    , uuidGen : StringUuidGenerator ) {

  def getCommitsfromEventLog(eventLog:EventLog) : Box[Option[GitCommitId]] = {
    eventLog.modificationId match {
      case None        => Full(None)
      case Some(modId) => gitModificationRepository.getCommits(modId).toBox
    }
  }

  def restoreToEventLog(eventLog:EventLog, commiter:PersonIdent, rollbackedEvents:Seq[EventLog], target:EventLog) = {
    for {
      commit   <- getCommitsfromEventLog(eventLog)
      rollback <- commit match {
                    case None    => Failure(s"The event log ${eventLog.id.getOrElse("")} don't have a matching commit ID and can't be restored")
                    case Some(x) => itemArchiveManager.rollback(x, commiter, ModificationId(uuidGen.newUuid), eventLog.principal, None, rollbackedEvents, target, "after", false).toBox
                  }
    } yield {
      rollback
    }

  }

  def restoreBeforeEventLog(eventLog:EventLog, commiter:PersonIdent, rollbackedEvents:Seq[EventLog], target:EventLog) = {
    for {
      commit   <- getCommitsfromEventLog(eventLog)
      rollback <- commit match {
                    case None    => Failure(s"The event log ${eventLog.id.getOrElse("")} don't have a matching commit ID and can't be restored")
                    case Some(x) =>
                      val parentCommit = GitCommitId(x.value+"^")
                      itemArchiveManager.rollback(parentCommit, commiter, ModificationId(uuidGen.newUuid), eventLog.principal, None, rollbackedEvents, target, "before", false).toBox
                  }
    } yield {
      rollback
    }
  }
}
