/*
 *************************************************************************************
 * Copyright 2013 Normation SAS
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
package com.normation.rudder.api

import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.ldap.core.LDAPConstants
import com.normation.ldap.sdk.BuildFilter
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.LDAPRudderError
import com.normation.ldap.sdk.RoLDAPConnection
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants
import com.normation.rudder.domain.RudderLDAPConstants.A_API_UUID
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.facts.nodes.NodeSecurityContext
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.repository.ldap.LDAPDiffMapper
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.rudder.services.user.PersonIdentService
import com.normation.zio.*
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import zio.*
import zio.syntax.*

/**
 * A repository to retrieve API Accounts
 */
trait RoApiAccountRepository {

  /**
   * Retrieve all standard API Account (not linked to an user,
   * not system, i.e account.kind == PublicApi)
   * AND (Rudder 8.3) with hashed token
   */
  def getAllStandardAccounts: IOResult[Seq[ApiAccount]]

  def getByToken(hashedToken: ApiTokenHash): IOResult[Option[ApiAccount]]

  def getById(id: ApiAccountId): IOResult[Option[ApiAccount]]

  def getSystemAccount: ApiAccount
}

/**
 * A Repository to save principals
 */
trait WoApiAccountRepository {

  /**
   * Save an API account
   * If an account with a same name or same token exists,
   * action won't be performed.
   *
   */
  def save(principal: ApiAccount, modId: ModificationId, actor: EventActor): IOResult[ApiAccount]

  def delete(id: ApiAccountId, modId: ModificationId, actor: EventActor): IOResult[ApiAccountId]
}

final class RoLDAPApiAccountRepository(
    val rudderDit:     RudderDit,
    val ldapConnexion: LDAPConnectionProvider[RoLDAPConnection],
    val mapper:        LDAPEntityMapper,
    val systemAcl:     List[ApiAclElement],
    val systemToken:   ApiTokenHash
) extends RoApiAccountRepository {

  val systemAPIAccount: ApiAccount = {
    ApiAccount(
      ApiAccountId("rudder-system-api-account"),
      ApiAccountKind.System,
      ApiAccountName("Rudder system account"),
      Some(systemToken),
      "For internal use",
      isEnabled = true,
      creationDate = DateTime.now(DateTimeZone.UTC),
      tokenGenerationDate = DateTime.now(DateTimeZone.UTC),
      tenants = NodeSecurityContext.All
    )
  }

  override def getSystemAccount: ApiAccount = systemAPIAccount

  override def getAllStandardAccounts: IOResult[Seq[ApiAccount]] = {
    for {
      ldap    <- ldapConnexion
      entries <- ldap.searchOne(rudderDit.API_ACCOUNTS.dn, BuildFilter.IS(RudderLDAPConstants.OC_API_ACCOUNT))
    } yield {
      // map to ApiAccount in a "as much as possible" way
      val accounts = entries.flatMap(e => {
        mapper.entry2ApiAccount(e) match {
          case Left(err) =>
            ApplicationLogger.debug(s"Ignoring API Account with dn ${e.dn.toString()} due to mapping error: ${err.fullMsg}")
            None
          case Right(p)  =>
            p.kind match {
              case _: ApiAccountKind.PublicApi => Some(p)
              case _ => None
            }
        }
      })
      accounts
    }
  }

  /*
  Look for a given token hash in the LDAP.
   */
  override def getByToken(hashedToken: ApiTokenHash): IOResult[Option[ApiAccount]] = {
    val hash = hashedToken.exposeHash();
    for {
      ldap     <- ldapConnexion
      // here, be careful to the semantic of get with a filter!
      optEntry <- hash match {
                    case None    => None.succeed
                    case Some(h) => ldap.get(rudderDit.API_ACCOUNTS.dn, BuildFilter.EQ(RudderLDAPConstants.A_API_TOKEN, h))
                  }
      optRes   <- optEntry match {
                    case None    => None.succeed
                    case Some(e) => mapper.entry2ApiAccount(e).map(Some(_)).toIO
                  }
    } yield {
      optRes

    }
  }

  override def getById(id: ApiAccountId): IOResult[Option[ApiAccount]] = {
    if (id == systemAPIAccount.id) {
      Some(systemAPIAccount).succeed
    } else {
      for {
        ldap     <- ldapConnexion
        optEntry <- ldap.get(rudderDit.API_ACCOUNTS.API_ACCOUNT.dn(id))
        optRes   <- optEntry match {
                      case None    => None.succeed
                      case Some(e) => mapper.entry2ApiAccount(e).map(Some(_)).toIO
                    }
      } yield {
        optRes
      }
    }
  }
}

final class WoLDAPApiAccountRepository(
    rudderDit:          RudderDit,
    ldapConnexion:      LDAPConnectionProvider[RwLDAPConnection],
    mapper:             LDAPEntityMapper,
    diffMapper:         LDAPDiffMapper,
    actionLogger:       EventLogRepository,
    personIdentService: PersonIdentService
) extends WoApiAccountRepository {
  repo =>
  /*
   * We want to make all API account modification purely exclusive.
   * The action is rare, so there is no contention/scaling problem here.
   */
  val semaphore: Semaphore = Semaphore.make(1).runNow

  override def save(
      principal: ApiAccount,
      modId:     ModificationId,
      actor:     EventActor
  ): IOResult[ApiAccount] = {
    semaphore.withPermit(
      for {
        ldap        <- ldapConnexion
        existing    <-
          ldap.get(rudderDit.API_ACCOUNTS.API_ACCOUNT.dn(principal.id)) map {
            case None    => None.succeed
            case Some(e) =>
              Some(e).succeed
          }
        name        <- ldap.get(rudderDit.API_ACCOUNTS.dn, BuildFilter.EQ(LDAPConstants.A_NAME, principal.name.value)) map {
                         case None    => None.succeed
                         case Some(e) =>
                           if (e(A_API_UUID) == Some(principal.id.value)) {
                             Some(e).succeed
                           } else {
                             LDAPRudderError.Consistency(s"An account with the same name ${principal.name.value} exists").fail
                           }
                       }
        optPrevious <- ldap.get(rudderDit.API_ACCOUNTS.API_ACCOUNT.dn(principal.id))

        entry         = mapper.apiAccount2Entry(principal)
        saved        <- ldap.save(entry, removeMissingAttributes = true)
        loggedAction <- optPrevious match {
                          // if there is a previous value, then it's an update
                          case Some(previous) =>
                            for {
                              optDiff <- diffMapper.modChangeRecords2ApiAccountDiff(previous, saved).toIO

                              action <- optDiff match {
                                          case Some(diff) =>
                                            actionLogger
                                              .saveModifyApiAccount(modId, principal = actor, modifyDiff = diff, None)
                                              .chainError("Error when logging modification of an API Account as an event")
                                          case None       =>
                                            ZIO.unit
                                        }
                            } yield {
                              action
                            }
                          // if there is no previous value, then it's a creation
                          case None           =>
                            for {
                              diff <- diffMapper.addChangeRecords2ApiAccountDiff(entry.dn, saved).toIO

                              action <- actionLogger
                                          .saveCreateApiAccount(modId, principal = actor, addDiff = diff, None)
                                          .chainError("Error when logging creation of API Account as an event")
                            } yield {
                              action
                            }
                        }
      } yield {
        principal
      }
    )
  }

  override def delete(
      id:    ApiAccountId,
      modId: ModificationId,
      actor: EventActor
  ): IOResult[ApiAccountId] = {
    for {
      ldap         <- ldapConnexion
      entry        <- ldap.get(rudderDit.API_ACCOUNTS.API_ACCOUNT.dn(id)).flatMap {
                        case None    => LDAPRudderError.Consistency(s"Api Account with ID '${id.value}' is not present").fail
                        case Some(x) => x.succeed
                      }
      oldAccount   <- mapper.entry2ApiAccount(entry).toIO
      deleted      <- ldap.delete(rudderDit.API_ACCOUNTS.API_ACCOUNT.dn(id))
      diff          = DeleteApiAccountDiff(oldAccount)
      loggedAction <- actionLogger.saveDeleteApiAccount(modId, principal = actor, deleteDiff = diff, None)
    } yield {
      id
    }
  }
}
