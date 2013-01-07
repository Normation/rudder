package com.normation.rudder.services.modification

import com.normation.rudder.repository._
import com.normation.eventlog._
import net.liftweb.common._
import com.normation.utils.StringUuidGenerator
import org.eclipse.jgit.lib.PersonIdent

class ModificationService(
      eventLogRepository : EventLogRepository
    , gitModificationRepository : GitModificationRepository
    , itemArchiveManager : ItemArchiveManager
    , uuidGen : StringUuidGenerator ) {

  def getCommitsfromEventLog(eventLog:EventLog) : Option[GitCommitId] = {
    eventLog.modificationId match {
      case None =>
      None
      case Some(modId) => gitModificationRepository.getCommits(modId) match {
        case Full(s) => s.headOption
        case eb:EmptyBox =>
          None
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