package com.normation.rudder.repository

import com.normation.eventlog.ModificationId
import net.liftweb.common.Box
import scala.concurrent.Future
import com.normation.rudder.db.DB


/**
 * A repository to store git commit and link them to
 * an event log with an ID
 */
trait GitModificationRepository {

  /**
   * Get commits linked to a modification Id
   */
  def getCommits(modificationId: ModificationId) : Future[Box[Option[GitCommitId]]]

  def addCommit(commit:GitCommitId, modId:ModificationId) : Future[Box[DB.GitCommitJoin]]
}
