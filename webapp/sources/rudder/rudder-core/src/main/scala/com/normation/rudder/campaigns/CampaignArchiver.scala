package com.normation.rudder.campaigns

import better.files.File
import com.normation.errors.IOResult
import com.normation.rudder.domain.logger.GitArchiveLoggerPure
import com.normation.rudder.git.GitCommitId
import com.normation.rudder.git.GitItemRepository
import com.normation.rudder.git.GitRepositoryProvider
import com.normation.rudder.repository.xml.BuildFilePaths
import com.normation.rudder.services.user.PersonIdentService
import com.normation.rudder.tenants.ChangeContext
import scala.jdk.CollectionConverters.*
import zio.ZIO

trait CampaignArchiver {
  def saveCampaign(campaignId: CampaignId)(implicit cc: ChangeContext): IOResult[Unit]

  def deleteCampaign(campaignId: CampaignId)(implicit cc: ChangeContext): IOResult[Unit]

  // init campaign directory/git if not already done
  def init(implicit changeContext: ChangeContext): IOResult[Unit]

  // expose campaign full path so that repository knows where to look for
  def campaignPath: File
}

class CampaignArchiverImpl(
    override val gitRepo: GitRepositoryProvider,
    campaignDir:          String,
    personIdentService:   PersonIdentService
) extends CampaignArchiver with GitItemRepository with BuildFilePaths[CampaignId] {

  override val relativePath: String = campaignDir

  override val campaignPath: File = gitRepo.rootDirectory / campaignDir

  override def saveCampaign(campaignId: CampaignId)(implicit cc: ChangeContext): IOResult[Unit] = {
    for {
      f     <- newFile(campaignId).toIO
      path   = toGitPath(f)
      ident <- personIdentService.getPersonIdentOrDefault(cc.actor.name)
      _     <- ZIO.whenZIO(IOResult.attempt(f.exists()))(commitAddFile(ident, path, s"Archive campaign '${campaignId.value}'"))
    } yield {}
  }

  override def deleteCampaign(campaignId: CampaignId)(implicit cc: ChangeContext): IOResult[Unit] = {
    for {
      f     <- newFile(campaignId).toIO
      path   = toGitPath(f)
      ident <- personIdentService.getPersonIdentOrDefault(cc.actor.name)
      _     <- ZIO.whenZIO(IOResult.attempt(f.exists()))(commitRmFile(ident, path, s"Delete campaign '${campaignId.value}'"))
    } yield {}
  }

  override def getFileName(itemId: CampaignId): String = s"${itemId.value}.json"

  override def init(implicit changeContext: ChangeContext): IOResult[Unit] = {
    gitRepo.semaphore.withPermit(
      for {
        status <- IOResult.attempt(gitRepo.git.status.call)
        _      <- ZIO.when(status.getUntracked.asScala.exists(_.startsWith(relativePath))) {
                    for {
                      _     <- GitArchiveLoggerPure.info(s"Init archives for campaigns at '${campaignPath}'")
                      _     <- IOResult.attempt(gitRepo.git.add.addFilepattern(relativePath).call)
                      ident <- personIdentService.getPersonIdentOrDefault(changeContext.actor.name)
                      rev   <- IOResult.attempt(gitRepo.git.commit.setCommitter(ident).setMessage("Initial commit for campaigns").call)
                      _     <- IOResult.attempt(GitCommitId(rev.getName))
                    } yield {}
                  }
      } yield {}
    )
  }
}
