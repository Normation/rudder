package com.normation.rudder.repository.jdbc

import net.liftweb.common._
import com.normation.eventlog.ModificationId
import com.normation.rudder.repository.GitCommitId
import com.normation.rudder.db.SlickSchema
import com.normation.rudder.db.DB.GitCommitJoin
import com.normation.rudder.repository.GitModificationRepository
import scala.util.{Success => TSuccess, Failure => TFailure }

import scala.concurrent.ExecutionContext.Implicits.global
import com.normation.rudder.db.DB
import scala.concurrent.Future

class SlickGitModificationRepositoryee(
    schema : SlickSchema
) extends GitModificationRepository {
  import schema.api._

  def addCommit(commit: GitCommitId, modId: ModificationId) : Future[Box[DB.GitCommitJoin]] = {
    val action = (schema.gitCommitJoinTable += GitCommitJoin(commit, modId))
    schema.db.run(action.asTry).map { res => res match {
      case TSuccess(x) => Full(DB.GitCommitJoin(commit, modId))
      case TFailure(ex) => Failure(s"Error when trying to add a Git Commit in DB: ${ex.getMessage}", Full(ex), Empty)
    } }
  }

  def getCommits(modificationId: ModificationId): Future[Box[Option[GitCommitId]]] = {

    val action = (
      for {
        commit <- schema.gitCommitJoinTable
        if(commit.modificationId === modificationId.value) //.value on right seems strange, I may be something about the mapping
      } yield {
        commit
      }
    )

    schema.db.run(action.asTry).map { res => res match {
      case TSuccess(seq) =>
        if(seq.size <=) Full(seq.headOption)
        else Failure(s"Multiple commits for modification with ID '${modificationId.value}', commits are : ${q.mkString(", ")}")

      case TFailure(ex) => Failure(s"Error when trying to get Git Commit for modification ID '${modificationId.value}': ${ex.getMessage}", Full(ex), Empty)
    } }
  }

}
