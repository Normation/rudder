package com.normation.rudder.repository.jdbc

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{ Failure => TFailure }
import scala.util.{ Success => TSuccess }

import com.normation.eventlog.ModificationId
import com.normation.rudder.db.DB
import com.normation.rudder.db.DB.GitCommitJoin
import com.normation.rudder.db.SlickSchema
import com.normation.rudder.repository.GitCommitId
import com.normation.rudder.repository.GitModificationRepository

import net.liftweb.common._

class SlickGitModificationRepository(
    schema : SlickSchema
) extends GitModificationRepository {
  import schema.api._

  def addCommit(commit: GitCommitId, modId: ModificationId) : Future[Box[DB.GitCommitJoin]] = {
    val action = (schema.gitCommitJoin += GitCommitJoin(commit, modId))
    schema.db.run(action.asTry).map { res => res match {
      case TSuccess(x) => Full(DB.GitCommitJoin(commit, modId))
      case TFailure(ex) => Failure(s"Error when trying to add a Git Commit in DB: ${ex.getMessage}", Full(ex), Empty)
    } }
  }

  def getCommits(modificationId: ModificationId): Future[Box[Option[GitCommitId]]] = {

    val q = (
      for {
        commit <- schema.gitCommitJoin
        if(commit.modificationId === modificationId.value) //.value on right seems strange, I may be something about the mapping
      } yield {
        commit
      }
    )

    schema.db.run(q.result.asTry).map { res => res match {
      case TSuccess(seq) =>
        if(seq.size <= 1) {
          Full(seq.headOption.map(_.gitCommit))
        } else {
          Failure(s"Multiple commits for modification with ID '${modificationId.value}', commits are : ${seq.mkString(", ")}")
        }

      case TFailure(ex) => Failure(s"Error when trying to get Git Commit for modification ID '${modificationId.value}': ${ex.getMessage}", Full(ex), Empty)
    } }
  }

}
