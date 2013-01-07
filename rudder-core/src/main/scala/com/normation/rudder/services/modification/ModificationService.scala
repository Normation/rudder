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
  
  def restoreToEventLog(eventLog:EventLog, commiter:PersonIdent) = {
    for {
      commit   <-  getCommitsfromEventLog(eventLog) 
    } yield {
      itemArchiveManager.importAll(commit, commiter, ModificationId(uuidGen.newUuid), eventLog.principal, None, false)
    }
  
  }
  
  def restoreBeforeEventLog(eventLog:EventLog, commiter:PersonIdent) = {
    for {
      commit   <-  getCommitsfromEventLog(eventLog) 
    } yield { val parentCommit = GitCommitId(commit.value+"^")
        itemArchiveManager.importAll(parentCommit, commiter, ModificationId(uuidGen.newUuid), eventLog.principal, None, false)
    }
  }
}