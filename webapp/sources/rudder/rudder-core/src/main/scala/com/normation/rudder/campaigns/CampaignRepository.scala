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
import com.normation.errors.IOResult
import com.normation.errors.Unexpected
import zio._
import zio.syntax._

trait CampaignRepository {
  def getAll(): IOResult[List[Campaign]]
  def get(id:    CampaignId): IOResult[Campaign]
  def delete(id: CampaignId): IOResult[CampaignId]
  def save(c:    Campaign):   IOResult[Campaign]
}

object CampaignRepositoryImpl {
  def make(
      campaignSerializer:      CampaignSerializer,
      path:                    File,
      campaignEventRepository: CampaignEventRepository
  ): IOResult[CampaignRepositoryImpl] = {
    IOResult.effectM {
      if (path.exists) {
        if (!path.isDirectory || !path.isWritable) {
          Unexpected(s"Campaign configuration repository is not a writable directory: " + path.pathAsString).fail
        } else UIO.unit
      } else {
        path.createDirectoryIfNotExists(createParents = true).succeed
      }
    } *>
    new CampaignRepositoryImpl(campaignSerializer, path, campaignEventRepository).succeed
  }
}

class CampaignRepositoryImpl(campaignSerializer: CampaignSerializer, path: File, campaignEventRepository: CampaignEventRepository)
    extends CampaignRepository {

  def getAll():            IOResult[List[Campaign]] = {
    for {
      jsonFiles <- IOResult.effect(path.collectChildren(_.extension.exists(_ == ".json")))
      campaigns <- (ZIO.foreach(jsonFiles.toList) { json =>
                     (for {
                       c <-
                         campaignSerializer.parse(json.contentAsString)
                     } yield {
                       c
                     }).either.chainError("Error when getting all campaigns from filesystem")
                   })
      _         <- ZIO.foreach(campaigns.partitionMap(identity)._1)(err => CampaignLogger.error(err.msg))
    } yield {
      campaigns.partitionMap(identity)._2
    }
  }
  def get(id: CampaignId): IOResult[Campaign]       = {
    for {
      content  <- IOResult.effect(s"error when getting campaign file for campaign with id '${id.value}'") {
                    val file = path / (s"${id.value}.json")
                    file.createFileIfNotExists(createParents = true)
                    file
                  }
      campaign <- campaignSerializer.parse(content.contentAsString)
    } yield {
      campaign
    }
  }
  def save(c: Campaign):   IOResult[Campaign]       = {
    for {
      file    <- IOResult.effect(s"error when creating campaign file for campaign with id '${c.info.id.value}'") {
                   val file = path / (s"${c.info.id.value}.json")
                   file.createFileIfNotExists(true)
                   file
                 }
      content <- campaignSerializer.serialize(c)
      _       <- IOResult.effect(file.write(content))
    } yield {
      c
    }
  }

  def delete(id: CampaignId): IOResult[CampaignId] = {
    for {
      campaign_deleted <- IOResult.effect(s"error when delete campaign file for campaign with id '${id.value}'") {
                            val file = path / (s"${id.value}.json")
                            file.delete()
                          }
      events_deleted   <- campaignEventRepository.deleteEvent(campaignId = Some(id))
    } yield {
      id
    }
  }

}
