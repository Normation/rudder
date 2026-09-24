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
import com.normation.rudder.tenants.Container
import com.normation.rudder.tenants.IfAbsent
import com.normation.rudder.tenants.QueryContext
import com.normation.rudder.tenants.TenantCheckLogic
import com.normation.utils.FileUtils
import zio.*
import zio.stm.STM
import zio.stm.TMap
import zio.stm.TReentrantLock
import zio.syntax.*

trait CampaignRepository {
  def getAll(typeFilter: List[CampaignType], statusFilter: List[CampaignStatusValue])(using
      qc: QueryContext
  ): IOResult[List[Campaign]]
  def get(id:    CampaignId)(using qc: QueryContext):  IOResult[Option[Campaign]]
  def delete(id: CampaignId)(using cc: ChangeContext): IOResult[Unit]
  def save(c:    Campaign)(using cc:   ChangeContext): IOResult[Campaign]
}

object CampaignRepositoryImpl {
  def make(
      path:               File,
      campaignArchiver:   CampaignArchiver,
      campaignSerializer: CampaignSerializer,
      hooksRepository:    CampaignHooksRepository,
      checkTenant:        TenantCheckLogic
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
    TMap
      .empty[CampaignId, TReentrantLock]
      .commit
      .map(locks => {
        new CampaignRepositoryImpl(path, campaignArchiver, campaignSerializer, hooksRepository, checkTenant, locks)
      })
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
    hooksRepository:    CampaignHooksRepository,
    checkTenant:        TenantCheckLogic,
    // per campaign (i.e. per file) read/write locks: campaign files are updated concurrently
    // (API, generation-time horizon renewal, campaign handler, on-demand runs) and a file
    // write plus its git archiving are not atomic
    locks:              TMap[CampaignId, TReentrantLock]
) extends CampaignRepository {

  // the lock map must not grow indefinitely
  private[campaigns] def lockCount: UIO[Int] = locks.size.commit

  private def lockFor(id: CampaignId): UIO[TReentrantLock] = {
    locks
      .get(id)
      .flatMap {
        case Some(l) => STM.succeed(l)
        case None    => TReentrantLock.make.flatMap(l => locks.put(id, l).as(l))
      }
      .commit
  }

  private def withReadLock[A](id: CampaignId)(effect: IOResult[A]): IOResult[A] = {
    lockFor(id).flatMap(l => ZIO.scoped(l.readLock *> effect))
  }

  private def withWriteLock[A](id: CampaignId)(effect: IOResult[A]): IOResult[A] = {
    lockFor(id).flatMap(l => ZIO.scoped(l.writeLock *> effect))
  }

  // We use "rawXXX" for tenant-agnostic version of the XXX method

  private def rawGetAll(typeFilter: List[CampaignType], statusFilter: List[CampaignStatusValue]): IOResult[List[Campaign]] = {
    if (path.exists) {
      for {
        jsonFiles          <- IOResult.attempt(path.collectChildren(_.extension.exists(_ == ".json")))
        campaigns          <- ZIO.foreach(jsonFiles.toList) { json =>
                                withReadLock(CampaignId(json.nameWithoutExtension)) {
                                  campaignSerializer
                                    .parse(json.contentAsString)
                                    .chainError(s"Error when parsing campaign file at '${json.pathAsString}'")
                                }.either
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

  private def campaignFile(id: CampaignId): IOResult[File] = {
    FileUtils.sanitizePath(path, s"${id.value}.json")
  }

  private def rawGet(id: CampaignId): IOResult[Option[Campaign]] = {
    campaignFile(id).flatMap { file =>
      // no lock for a campaign that does not exist: it has nothing to read, and locking would add an
      // entry to the lock map for every id ever asked for
      ZIO.ifZIO(IOResult.attempt(file.exists))(
        withReadLock(id)(campaignSerializer.parse(file.contentAsString).asSome),
        None.succeed
      )
    }
  }

  /*
   * When we save a campaign, we also init hook directories for that campaign.
   */
  private def rawSave(c: Campaign)(implicit cc: ChangeContext): IOResult[Campaign] = withWriteLock(c.info.id) {
    for {
      _       <- ZIO.when(c.info.id.value.isBlank)(Inconsistency("A campaign id must be defined and non empty").fail)
      _       <- ZIO.when(c.info.name.isBlank)(Inconsistency("A campaign name must be defined and non empty").fail)
      // a schedule that can not be computed makes every later policy generation fail, so it is refused here
      _       <- CampaignSchedule
                   .validate(c.info.schedule)
                   .toIO
                   .chainError(s"Campaign '${c.info.id.value}' does not have a valid schedule")
      _       <- hooksRepository
                   .initHooks(c.info.id)
                   .chainError(
                     s"Error with hook directory initialization for campaign '${c.info.id}'"
                   )
      path    <- campaignFile(c.info.id)
      file    <- IOResult.attempt(s"error when creating campaign file for campaign with id '${c.info.id.value}'") {
                   path.createFileIfNotExists(true)
                   path
                 }
      content <- campaignSerializer.serialize(c)
      _       <- IOResult.attempt(file.write(content))
      _       <- campaignArchiver.saveCampaign(c.info.id)
    } yield {
      c
    }
  }

  private def rawDelete(id: CampaignId)(implicit cc: ChangeContext): IOResult[Unit] = {
    withWriteLock(id) {
      for {
        file <- campaignFile(id)
        _    <- IOResult.attempt(s"error when delete campaign file for campaign with id '${id.value}'")(file.delete())
        _    <- campaignArchiver.deleteCampaign(id)
      } yield ()
    } *> locks.delete(id).commit
  }

  // Interface implementation, with the tenant limitation, call `rawXXX`

  override def getAll(typeFilter: List[CampaignType], statusFilter: List[CampaignStatusValue])(using
      qc: QueryContext
  ): IOResult[List[Campaign]] = {
    rawGetAll(typeFilter, statusFilter).map(checkTenant.filter(_))
  }

  override def get(id: CampaignId)(using qc: QueryContext): IOResult[Option[Campaign]] = {
    rawGet(id).map(checkTenant.flatMap(_))
  }

  // tenant logic: a campaign has no container (no category), and saving one is an upsert (only one method for update and
  // create), so `manageSave` check for existence before applying the correct tag
  override def save(c: Campaign)(using cc: ChangeContext): IOResult[Campaign] = {
    checkTenant.manageSave(c, rawGet(c.info.id), Container.none)(campaign => rawSave(campaign))
  }

  override def delete(id: CampaignId)(using cc: ChangeContext): IOResult[Unit] = {
    checkTenant.manageDelete(rawGet(id), IfAbsent(()))(_ => rawDelete(id))
  }
}
