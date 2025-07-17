/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
 *************************************************************************************
 *
 * This file is part of Rudder.
 *
 * Rudder is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * In accordance with the terms of section 7 (7. Additional Terms.) of
 * the GNU General Public License version 3, the copyright holders add
 * the following Additional permissions:
 * Notwithstanding to the terms of section 5 (5. Conveying Modified Source
 * Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
 * Public License version 3, when you create a Related Module, this
 * Related Module is not considered as a part of the work and may be
 * distributed under the license agreement of your choice.
 * A "Related Module" means a set of sources files including their
 * documentation that, without modification of the Source Code, enables
 * supplementary functions or services in addition to those offered by
 * the Software.
 *
 * Rudder is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

 *
 *************************************************************************************
 */

package com.normation.rudder.campaigns

import better.files.File
import com.normation.errors.*
import com.normation.rudder.hooks.*
import com.normation.zio.*
import scala.jdk.CollectionConverters.*
import zio.*
import zio.syntax.*

/*
 * This service is in charge of managing campaign hooks.
 */
trait CampaignHooksService {

  /*
   * Run hooks that are configured to run before the campaign time slot is reached.
   * Hooks results are collected to for trace.
   */
  def runPreHooks(c: Campaign, e: CampaignEvent): IOResult[HookResults]

  /*
   * Run hooks that are configured to run before the campaign time slot is reached.
   * Hooks results are collected to for trace.
   */
  def runPostHooks(c: Campaign, e: CampaignEvent): IOResult[HookResults]
}

object NoopCampaignHooksService extends CampaignHooksService {
  override def runPreHooks(c: Campaign, e: CampaignEvent): IOResult[HookResults] = {
    HookResults(List(HookResult("pre-hook", 0, "", "", ""))).succeed
  }

  override def runPostHooks(c: Campaign, e: CampaignEvent): IOResult[HookResults] = {
    HookResults(List(HookResult("post-hook", 0, "", "", ""))).succeed
  }
}

/*
 * Hooks are by-campaign, so we need some help to create the overall structure when
 * a new campaign is created, and delete the whole thing when the campaign is deleted.
 */
trait CampaignHooksRepository {
  /*
   * Create the pre-/post-hook sub-directories specific to that campaign
   */
  def initHooks(cid: CampaignId): IOResult[Unit]

  /*
   * Delete hook directory related to that campaign.
   */
  def deleteHooks(cid: CampaignId): IOResult[Unit]
}

object NoopCampaignHooksRepository extends CampaignHooksRepository {
  override def initHooks(cid:   CampaignId): IOResult[Unit] = ZIO.unit
  override def deleteHooks(cid: CampaignId): IOResult[Unit] = ZIO.unit
}

/*
 *  The standard layout for hooks is:
 *  - HOOKS_D/campaigns
 *     |- <campaign-uuid>
 *     |     |- pre-hooks
 *     |     |    ` scripts
 *     |     `- post-hooks
 *     |          ` scripts
 *     ...
 */
class FsCampaignHooksRepository(HOOKS_D: String) extends CampaignHooksRepository {
  val campaignRootHooksDir: File = File(HOOKS_D) / FsCampaignHooksRepository.CampaignDirName

  private def getDir(cid: CampaignId): File = {
    // perhaps we will want per-revision hook directory some day?
    campaignRootHooksDir / cid.serialize
  }

  override def initHooks(cid: CampaignId): IOResult[Unit] = {
    IOResult.attempt(s"Error when initializing hook directories for campaign '${cid.serialize}'") {
      val campaignHooksDir = getDir(cid)
      CampaignHookTypes.values.foreach(t => (campaignHooksDir / t.entryName).createDirectoryIfNotExists(createParents = true))
    }
  }

  override def deleteHooks(cid: CampaignId): IOResult[Unit] = {
    val campaignHooksDir = getDir(cid)
    IOResult.attempt(s"Error when deleting hook directory for campaign '${cid.serialize}'") {
      campaignHooksDir.delete()
    }
  }
}

object FsCampaignHooksRepository {

  val CampaignDirName: String = "campaigns"

}

class FsCampaignHooksService(
    HOOKS_D:               String,
    HOOKS_IGNORE_SUFFIXES: List[String]
) extends CampaignHooksService {

  def runPreHooks(c: Campaign, e: CampaignEvent): IOResult[HookResults] = {
    runHooks("pre-hooks", HOOKS_D, HOOKS_IGNORE_SUFFIXES, c, e, CampaignHookTypes.CampaignPreHooks)
  }

  def runPostHooks(c: Campaign, e: CampaignEvent): IOResult[HookResults] = {
    runHooks("post-hooks", HOOKS_D, HOOKS_IGNORE_SUFFIXES, c, e, CampaignHookTypes.CampaignPostHooks)
  }

  /*
   * Run hooks for the campaign. By defaults, hooks are located with other Rudder hooks in
   * /opt/rudder/etc/hooks.d.
   * Each campaign can have different hooks, so they
   * We only need campaign ID to find hooks, but campaign and event are for giving context.
   */
  private def runHooks(
      rootDir:        String,
      name:           String,
      ignoreSuffixes: List[String],
      c:              Campaign,
      e:              CampaignEvent,
      hookType:       CampaignHookTypes // pre or post
  ): IOResult[HookResults] = {

    val loggerName = name + "." + hookType.entryName
    val dir        = rootDir + "/" + name + "/" + hookType.entryName

    (
      for {
        systemEnv <- IOResult.attempt(java.lang.System.getenv.asScala.toSeq).map(seq => HookEnvPairs.build(seq*))
        hooks     <- RunHooks.getHooksPure(dir, HOOKS_IGNORE_SUFFIXES)
        res       <- for {
                       timeHooks0 <- currentTimeMillis
                       res        <- RunHooks.asyncRunHistory(
                                       loggerName,
                                       hooks,
                                       HookEnvPairs.build(
                                         ("CAMPAIGN_ID", c.info.id.serialize),
                                         ("CAMPAIGN_NAME", c.info.name),
                                         ("CAMPAIGN_TYPE", c.campaignType.value),
                                         ("CAMPAIGN_EVENT_ID", e.id.value),
                                         ("CAMPAIGN_EVENT_NAME", e.name)
                                       ),
                                       systemEnv,
                                       HookExecutionHistory.Keep,
                                       1.minutes // warn if a hook took more than a minute
                                     )
                       timeHooks1 <- currentTimeMillis
                       _          <-
                         PureHooksLogger.For(loggerName).trace(s"Inventory received hooks ran in ${timeHooks1 - timeHooks0} ms")
                     } yield res
      } yield HookResults((res._1 :: res._2).map(HookResult.fromCode))
    ).catchAll { err =>
      PureHooksLogger.For(loggerName).error(err.fullMsg) *>
      HookResults(List(HookResult(loggerName, 1, "", err.fullMsg, "System error when executing hook"))).succeed
    }
  }
}
