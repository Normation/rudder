/*
 *************************************************************************************
 * Copyright 2022 Normation SAS
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
import com.normation.errors.Inconsistency
import com.normation.errors.IOResult
import com.normation.errors.Unexpected
import com.normation.rudder.tenants.ChangeContext
import zio.*
import zio.syntax.*

trait CampaignRepository {
  def getAll(typeFilter: List[CampaignType], statusFilter: List[CampaignStatusValue]): IOResult[List[Campaign]]
  def get(id:            CampaignId): IOResult[Option[Campaign]]
  def delete(id:         CampaignId): IOResult[Unit]
  def save(c:            Campaign): IOResult[Campaign]
}

object CampaignRepositoryImpl {
  def make(
      path:               File,
      campaignArchiver:   CampaignArchiver,
      campaignSerializer: CampaignSerializer,
      hooksRepository:    CampaignHooksRepository
  ): IOResult[CampaignRepositoryImpl] = {
    IOResult.attemptZIO {
      if (path.exists) {
        if (!path.isDirectory || !path.isWritable) {
          Unexpected(s"Campaign configuration repository is not a writable directory: " + path.pathAsString).fail
        } else ZIO.unit
      } else {
        path.createDirectoryIfNotExists(createParents = true).succeed
      }
    } *>
    // init archiver
    campaignArchiver.init(using ChangeContext.newForRudder()) *>
    // return campaign repo
    new CampaignRepositoryImpl(path, campaignArchiver, campaignSerializer, hooksRepository).succeed
  }
}

/*
 * A default implementation for the campaign repository. Campaigns are stored in a json file
 * (by default for Rudder in /var/rudder/configuration-repository/campaigns)
 */
class CampaignRepositoryImpl(
    path:               File,
    campaignArchiver:   CampaignArchiver,
    campaignSerializer: CampaignSerializer,
    hooksRepository:    CampaignHooksRepository
) extends CampaignRepository {

  override def getAll(typeFilter: List[CampaignType], statusFilter: List[CampaignStatusValue]): IOResult[List[Campaign]] = {
    if (path.exists) {
      for {
        jsonFiles          <- IOResult.attempt(path.collectChildren(_.extension.exists(_ == ".json")))
        campaigns          <- ZIO.foreach(jsonFiles.toList) { json =>
                                (for {
                                  c <-
                                    campaignSerializer
                                      .parse(json.contentAsString)
                                      .chainError(s"Error when parsing campaign file at '${json.pathAsString}'")
                                } yield {
                                  c
                                }).either
                              }
        (errs, campaignRes) = campaigns.partitionMap(identity)
        _                  <- ZIO.foreach(errs)(err => CampaignLogger.error(err.msg))
      } yield {
        Campaign.filter(campaignRes, typeFilter, statusFilter)
      }
    } else {
      Nil.succeed
    }
  }

  override def get(id: CampaignId): IOResult[Option[Campaign]] = {
    val file = path / (s"${id.value}.json")
    for {
      campaign <- ZIO.when(file.exists) {
                    campaignSerializer.parse(file.contentAsString)
                  }
    } yield {
      campaign
    }
  }

  /*
   * When we save a campaign, we also init hook directories for that campaign.
   */
  override def save(c: Campaign): IOResult[Campaign] = {
    for {
      _       <- ZIO.when(c.info.id.value.isBlank)(Inconsistency("A campaign id must be defined and non empty").fail)
      _       <- ZIO.when(c.info.name.isBlank)(Inconsistency("A campaign name must be defined and non empty").fail)
      _       <- hooksRepository
                   .initHooks(c.info.id)
                   .chainError(
                     s"Error with hook directory initialization for campaign '${c.info.id}'"
                   )
      file    <- IOResult.attempt(s"error when creating campaign file for campaign with id '${c.info.id.value}'") {
                   val file = path / (s"${c.info.id.value}.json")
                   file.createFileIfNotExists(true)
                   file
                 }
      content <- campaignSerializer.serialize(c)
      _       <- IOResult.attempt(file.write(content))
      _       <- campaignArchiver.saveCampaign(c.info.id)(using ChangeContext.newForRudder())
    } yield {
      c
    }
  }

  override def delete(id: CampaignId): IOResult[Unit] = {
    for {
      campaign_deleted <- IOResult.attempt(s"error when delete campaign file for campaign with id '${id.value}'") {
                            val file = path / (s"${id.value}.json")
                            file.delete()
                          }
      _                <- campaignArchiver.deleteCampaign(id)(using ChangeContext.newForRudder())
    } yield ()
  }

}
