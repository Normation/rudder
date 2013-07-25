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
  def save(principal: ApiAccount): Box[ApiAccount]

  def delete(id: ApiAccountId): Box[ApiAccountId]
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
    val rudderDit    : RudderDit
  , val ldapConnexion: LDAPConnectionProvider[RwLDAPConnection]
  , val mapper       : LDAPEntityMapper
) extends WoApiAccountRepository with Loggable {

  override def save(principal: ApiAccount) : Box[ApiAccount] = {
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
                                      Failure("An account with the same name exists")
                                    }
                  }
      entry =  mapper.apiAccount2Entry(principal)
      saved <- ldap.save(entry, removeMissingAttributes=true)
    } yield {
      principal
    }
  }

  override def delete(id:ApiAccountId) : Box[ApiAccountId] = {
    for {
      ldap     <- ldapConnexion
      deleted  <- ldap.delete(rudderDit.API_ACCOUNTS.API_ACCOUNT.dn(id))
    } yield {
      id
    }
  }
}
