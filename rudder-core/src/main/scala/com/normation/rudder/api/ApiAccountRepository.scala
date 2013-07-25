/*
*************************************************************************************
* Copyright 2013 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/
package com.normation.rudder.api

import net.liftweb.common.Box
import net.liftweb.common.Full
import org.joda.time.DateTime
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.rudder.domain.RudderDit
import com.normation.ldap.sdk.RoLDAPConnection
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.rudder.services.queries.LDAPFilter
import com.normation.ldap.sdk.BuildFilter
import com.normation.rudder.domain.RudderLDAPConstants
import net.liftweb.common.Loggable
import net.liftweb.common.EmptyBox
import net.liftweb.common.Empty
import net.liftweb.common.Failure
import com.normation.inventory.ldap.core.LDAPConstants
import com.normation.rudder.domain.RudderLDAPConstants.A_API_UUID
import com.normation.rudder.repository.ldap.LDAPDiffMapper
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.services.user.PersonIdentService
import com.normation.eventlog.ModificationId
import com.normation.eventlog.EventActor

/**
 * A repository to retrieve API Accounts
 */
trait RoApiAccountRepository {

  /**
   * Retrieve all API Account
   */
  def getAll(): Box[Seq[ApiAccount]]

  def getByToken(token: ApiToken): Box[Option[ApiAccount]]

  def getById(id : ApiAccountId) : Box[Option[ApiAccount]]

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
  def save(
      principal : ApiAccount
    , modId     : ModificationId
    , actor     : EventActor): Box[ApiAccount]

  def delete(
      id        : ApiAccountId
    , modId     : ModificationId
    , actor     : EventActor) : Box[ApiAccountId]
}



final class RoLDAPApiAccountRepository(
    val rudderDit    : RudderDit
  , val ldapConnexion: LDAPConnectionProvider[RoLDAPConnection]
  , val mapper       : LDAPEntityMapper
) extends RoApiAccountRepository with Loggable {

  override def getAll(): Box[Seq[ApiAccount]] = {

    for {
      ldap <- ldapConnexion
    } yield {
      val entries = ldap.searchOne(rudderDit.API_ACCOUNTS.dn, BuildFilter.IS(RudderLDAPConstants.OC_API_ACCOUNT))
      //map to ApiAccount in a "as much as possible" way
      entries.flatMap ( e => mapper.entry2ApiAccount(e) match {
          case eb:EmptyBox =>
            val error = eb ?~! s"Ignoring API Account with dn ${e.dn} due to mapping error"
            logger.debug(error.messageChain)
            None
          case Full(p) => Some(p)
      } )
    }
  }

  override def getByToken(token: ApiToken): Box[Option[ApiAccount]] = {
    for {
      ldap     <- ldapConnexion
      //here, be careful to the semantic of get with a filter!
      optEntry <- ldap.get(rudderDit.API_ACCOUNTS.dn, BuildFilter.EQ(RudderLDAPConstants.A_API_TOKEN, token.value)) match {
                  case f:Failure => f
                  case Empty => Full(None)
                  case Full(e) => Full(Some(e))
                }
      optRes   <- optEntry match {
                    case None => Full(None)
                    case Some(e) => mapper.entry2ApiAccount(e).map( Some(_) )
                  }
    } yield {
      optRes
    }
  }

  override def getById(id:ApiAccountId) : Box[Option[ApiAccount]] = {
    for {
      ldap     <- ldapConnexion
      optEntry <- ldap.get(rudderDit.API_ACCOUNTS.API_ACCOUNT.dn(id)) match {
                    case f:Failure => f
                    case Empty => Full(None)
                    case Full(e) => Full(Some(e))
                  }
      optRes   <- optEntry match {
                    case None => Full(None)
                    case Some(e) => mapper.entry2ApiAccount(e).map( Some(_) )
                  }
    } yield {
      optRes
    }
  }
}


final class WoLDAPApiAccountRepository(
    rudderDit          : RudderDit
  , ldapConnexion      : LDAPConnectionProvider[RwLDAPConnection]
  , mapper             : LDAPEntityMapper
  , diffMapper         : LDAPDiffMapper
  , actionLogger       : EventLogRepository
  , personIdentService : PersonIdentService
) extends WoApiAccountRepository with Loggable {
  repo =>

  override def save(
      principal : ApiAccount
    , modId     : ModificationId
    , actor     : EventActor) : Box[ApiAccount] = {
    repo.synchronized {
      for {
        ldap     <- ldapConnexion
        existing <- ldap.get(rudderDit.API_ACCOUNTS.dn, BuildFilter.EQ(RudderLDAPConstants.A_API_TOKEN, principal.token.value)) match {
                      case f:Failure => f
                      case Empty => Full(None)
                      case Full(e) => if(e(A_API_UUID) == Some(principal.id.value)) {
                                        Full(e)
                                      } else {
                                        Failure("An account with given token but different id already exists")
                                      }
                    }
        name     <- ldap.get(rudderDit.API_ACCOUNTS.dn, BuildFilter.EQ(LDAPConstants.A_NAME, principal.name.value)) match {
                      case f:Failure => f
                      case Empty => Full(None)
                      case Full(e) => if(e(A_API_UUID) == Some(principal.id.value)) {
                                        Full(e)
                                      } else {
                                        Failure(s"An account with the same name ${principal.name.value} exists")
                                      }
                    }
        optPrevious <- ldap.get(rudderDit.API_ACCOUNTS.API_ACCOUNT.dn(principal.id)) match {
                        case f:Failure => f
                        case Empty => Full(None)
                        case Full(e) => Full(Some(e))
                      }

        entry =  mapper.apiAccount2Entry(principal)
        saved <- ldap.save(entry, removeMissingAttributes=true)
        loggedAction <- optPrevious match {
                            // if there is a previous value, then it's an update
                            case Some(previous) =>
                              for {
                                optDiff <- diffMapper.modChangeRecords2ApiAccountDiff(
                                            previous
                                          , saved)

                                action  <- optDiff match {
                                            case Some(diff) => actionLogger.saveModifyApiAccount(modId, principal = actor, modifyDiff = diff, None) ?~! "Error when logging modification of an API Account as an event"
                                            case None => Full("Ok")
                                          }
                              } yield {
                                action
                              }
                            // if there is no previous value, then it's a creation
                            case None =>
                              for {
                                diff   <- diffMapper.addChangeRecords2ApiAccountDiff(
                                            entry.dn
                                          , saved)

                                action <-  actionLogger.saveCreateApiAccount(modId, principal = actor, addDiff = diff, None) ?~! "Error when logging creation of API Account as an event"
                              } yield {
                                action
                              }
                       }
      } yield {
        principal
      }
    }
  }

  override def delete(
      id        : ApiAccountId
    , modId     : ModificationId
    , actor     : EventActor) : Box[ApiAccountId] = {
    for {
      ldap         <- ldapConnexion
      entry        <- ldap.get(rudderDit.API_ACCOUNTS.API_ACCOUNT.dn(id)) ?~! "Api Account with ID '%s' is not present".format(id.value)
      oldAccount   <- mapper.entry2ApiAccount(entry)
      deleted      <- ldap.delete(rudderDit.API_ACCOUNTS.API_ACCOUNT.dn(id))
      diff         =  DeleteApiAccountDiff(oldAccount)
      loggedAction <- actionLogger.saveDeleteApiAccount(modId, principal = actor, deleteDiff = diff, None)
    } yield {
      id
    }
  }
}
