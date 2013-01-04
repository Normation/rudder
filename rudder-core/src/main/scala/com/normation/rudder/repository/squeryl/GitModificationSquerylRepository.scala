package com.normation.rudder.repository.squeryl

import net.liftweb.common._
import com.normation.rudder.repository.GitModificationRepository
import com.normation.eventlog.ModificationId
import com.normation.rudder.repository.GitCommitId
import org.squeryl._
import org.squeryl.annotations._
import org.squeryl.PrimitiveTypeMode._
import com.normation.rudder.repository.jdbc.SquerylConnectionProvider
import com.normation.rudder.repository.GitCommitId


case class GitCommitJoin (
    @Column("gitcommit") id: String,
    @Column("modificationid") ModificationId: String
) extends KeyedEntity[String]

object CommitJoin extends Schema {
  val commitTable = table[GitCommitJoin]("gitcommit")
  
}
class GitModificationSquerylRepository(
    sessionProvider : SquerylConnectionProvider
  ) extends GitModificationRepository {

  import CommitJoin._
  
  
  def addCommit(commit:GitCommitId, modId:ModificationId) : Box[GitCommitJoin] = {
    try {
      val entry = sessionProvider.ourTransaction {
          commitTable.insert(GitCommitJoin(commit.value,modId.value))
      }
      Full(entry)
    } catch {
      case e : Exception => 
              Failure("could not add commit id %s with modification id %s from the database, cause is %s".format(commit.value,modId.value,e.getMessage()))
    }
  }

  def getCommits(modificationId: ModificationId): Box[Option[GitCommitId]] = {
    try {
       sessionProvider.ourTransaction {
       val q = from(commitTable)(entry => 
      
             where(entry.ModificationId === modificationId.value)
          select(entry.id)
              )
       if (q.size <= 1)
         Full(q.headOption.map(GitCommitId))
       else {
         Failure("Multiple commits for modification %s, commits are : %s ".format(modificationId.value,q.mkString(", ")))
       }
     }
  } catch {
    case e : Exception => 
      Failure("could not get any commit id from the database, cause is %s".format(e.getMessage()))
  }
  }

}