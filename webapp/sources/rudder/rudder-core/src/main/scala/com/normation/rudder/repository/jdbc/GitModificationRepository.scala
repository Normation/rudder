package com.normation.rudder.repository.jdbc


import com.normation.errors._
import com.normation.eventlog.ModificationId
import com.normation.rudder.db.DB
import com.normation.rudder.db.Doobie
import com.normation.rudder.repository.GitCommitId
import com.normation.rudder.repository.GitModificationRepository
import doobie.implicits._

class GitModificationRepositoryImpl(
    db : Doobie
) extends GitModificationRepository {
  import db._

  def addCommit(commit: GitCommitId, modId: ModificationId): IOResult[DB.GitCommitJoin] = {
    val sql = sql"""
      insert into gitcommit (gitcommit, modificationid)
      values (${commit.value}, ${modId.value})
    """.update

    transactIOResult(s"Error when trying to add a Git Commit in DB")(xa => sql.run.transact(xa)).map(x =>
      DB.GitCommitJoin(commit, modId)
    )
  }

  def getCommits(modificationId: ModificationId): IOResult[Option[GitCommitId]] = {

    val sql = sql"""
      select gitcommit from gitcommit where modificationid=${modificationId.value}
    """.query[String].option

    transactIOResult(s"Error when trying to get Git Commit for modification ID '${modificationId.value}'")(xa => sql.transact(xa)).map(x =>
      x.map(id => GitCommitId(id))
    )
  }
}
