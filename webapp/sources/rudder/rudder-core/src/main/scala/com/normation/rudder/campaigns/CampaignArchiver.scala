package com.normation.rudder.campaigns

import com.normation.errors.IOResult
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.git.GitCommitId
import com.normation.rudder.git.GitItemRepository
import com.normation.rudder.git.GitRepositoryProvider
import com.normation.rudder.repository.xml.BuildFilePaths
import com.normation.rudder.services.user.PersonIdentService
import scala.jdk.CollectionConverters.*
import zio.ZIO

trait CampaignArchiver {
  def saveCampaign(campaignId: CampaignId)(implicit cc: ChangeContext): IOResult[Unit]

  def deleteCampaign(campaignId: CampaignId)(implicit cc: ChangeContext): IOResult[Unit]

  def init(implicit changeContext: ChangeContext): IOResult[Unit]
}

class CampaignArchiverImpl(
    override val gitRepo: GitRepositoryProvider,
    campaignDir:          String,
    personIdentService:   PersonIdentService
) extends CampaignArchiver with GitItemRepository with BuildFilePaths[CampaignId] {

  override val relativePath = campaignDir

  override def saveCampaign(campaignId: CampaignId)(implicit cc: ChangeContext): IOResult[Unit] = {
    for {
      f     <- newFile(campaignId).toIO
      ident <- personIdentService.getPersonIdentOrDefault(cc.actor.name)
      _     <- ZIO.whenZIO(IOResult.attempt(f.exists()))(commitAddFile(ident, f.getPath, s"Archive campaign '${campaignId.value}'"))
    } yield {}
  }

  override def deleteCampaign(campaignId: CampaignId)(implicit cc: ChangeContext): IOResult[Unit] = {
    for {
      f     <- newFile(campaignId).toIO
      ident <- personIdentService.getPersonIdentOrDefault(cc.actor.name)
      _     <- ZIO.whenZIO(IOResult.attempt(f.exists()))(commitRmFile(ident, f.getPath, s"Delete campaign '${campaignId.value}'"))
    } yield {}
  }

  override def getFileName(itemId: CampaignId): String = s"${itemId.value}.json"

  override def init(implicit changeContext: ChangeContext): IOResult[Unit] = {
    gitRepo.semaphore.withPermit(
      for {
        status <- IOResult.attempt(gitRepo.git.status.call)
        _      <- ZIO.when(status.getUntracked.asScala.exists(_.startsWith(relativePath))) {
                    for {
                      add    <- IOResult.attempt(gitRepo.git.add.addFilepattern(relativePath).call)
                      ident  <- personIdentService.getPersonIdentOrDefault(changeContext.actor.name)
                      rev    <- IOResult.attempt(gitRepo.git.commit.setCommitter(ident).setMessage("Initial commit for campaigns").call)
                      commit <- IOResult.attempt(GitCommitId(rev.getName))
                    } yield {}
                  }
      } yield {}
    )
  }
}
