package com.normation.rudder.repository

import com.normation.eventlog.ModificationId
import com.normation.rudder.db.DB

import com.normation.errors._

/**
 * A repository to store git commit and link them to
 * an event log with an ID
 */
trait GitModificationRepository {

  /**
   * Get commits linked to a modification Id
   */
  def getCommits(modificationId: ModificationId) : IOResult[Option[GitCommitId]]

  def addCommit(commit:GitCommitId, modId:ModificationId) : IOResult[DB.GitCommitJoin]
}
