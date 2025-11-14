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

package com.normation.rudder.rest.lift

import com.normation.errors.*
import com.normation.eventlog.ModificationId
import com.normation.rudder.api.*
import com.normation.rudder.apidata.ZioJsonExtractor
import com.normation.rudder.domain.logger.ApiLoggerPure
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.rest.{ApiAccounts as API, *}
import com.normation.rudder.rest.data.*
import com.normation.rudder.rest.syntax.*
import com.normation.rudder.users.UserService
import com.normation.utils.StringUuidGenerator
import com.softwaremill.quicklens.*
import io.scalaland.chimney.syntax.*
import net.liftweb.http.*
import zio.*
import zio.syntax.*

/*
 * API account management with API.
 */
class ApiAccountApi(
    service: ApiAccountApiService
) extends LiftApiModuleProvider[API] {

  def schemas: ApiModuleProvider[API] = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map {
      case API.GetAllAccounts  => GetAllAccounts
      case API.GetAccount      => GetAccount
      case API.CreateAccount   => CreateAccount
      case API.UpdateAccount   => UpdateAccount
      case API.RegenerateToken => RegenerateToken
      case API.DeleteToken     => DeleteToken
      case API.DeleteAccount   => DeleteAccount
    }
  }

  // utility method to check that the string for ID is a valid account ID.
  def toId(s: String): IOResult[ApiAccountId] = ApiAccountId.parse(s).toIO

  /*
   * Return only public API accounts
   */
  object GetAllAccounts extends LiftApiModule0 {
    val schema: API.GetAllAccounts.type = API.GetAllAccounts

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      service.getAccounts().toLiftResponseList(params, schema)
    }
  }

  object GetAccount extends LiftApiModule {
    val schema: API.GetAccount.type = API.GetAccount

    def process(v: ApiVersion, path: ApiPath, s: String, req: Req, params: DefaultParams, t: AuthzToken): LiftResponse = {
      (for {
        id <- toId(s)
        r  <- service.getAccount(id)
      } yield r).toLiftResponseOne(params, schema, Some(s))
    }
  }

  object CreateAccount extends LiftApiModule0 {
    val schema: API.CreateAccount.type = API.CreateAccount

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      (for {
        account <- ZioJsonExtractor.parseJson[NewRestApiAccount](req).toIO
        res     <- service.createAccount(account)
      } yield res).toLiftResponseOne(params, schema, x => Some(x.id.value))
    }
  }

  object UpdateAccount extends LiftApiModule {
    val schema: API.UpdateAccount.type = API.UpdateAccount

    def process(v: ApiVersion, path: ApiPath, s: String, req: Req, params: DefaultParams, t: AuthzToken): LiftResponse = {
      implicit val qc: QueryContext = t.qc
      (for {
        id      <- toId(s)
        account <- ZioJsonExtractor.parseJson[UpdateApiAccount](req).toIO
        res     <- service.updateAccount(id, account)
      } yield res).toLiftResponseOne(params, schema, x => Some(x.id.value))
    }
  }

  object RegenerateToken extends LiftApiModule {
    val schema: API.RegenerateToken.type = API.RegenerateToken

    def process(v: ApiVersion, path: ApiPath, s: String, req: Req, params: DefaultParams, t: AuthzToken): LiftResponse = {
      implicit val qc: QueryContext = t.qc
      (for {
        id <- toId(s)
        r  <- service.regenerateToken(id)
      } yield r).toLiftResponseOne(params, schema, Some(s))
    }
  }

  object DeleteToken extends LiftApiModule {
    val schema: API.DeleteToken.type = API.DeleteToken

    def process(v: ApiVersion, path: ApiPath, s: String, req: Req, params: DefaultParams, t: AuthzToken): LiftResponse = {
      implicit val qc: QueryContext = t.qc
      (for {
        id <- toId(s)
        r  <- service.deleteToken(id)
      } yield r).toLiftResponseOne(params, schema, Some(s))
    }
  }

  object DeleteAccount extends LiftApiModule {
    val schema: API.DeleteAccount.type = API.DeleteAccount

    def process(v: ApiVersion, path: ApiPath, s: String, req: Req, params: DefaultParams, t: AuthzToken): LiftResponse = {
      implicit val qc: QueryContext = t.qc
      (for {
        id <- toId(s)
        r  <- service.deleteAccount(id).map(_.toList)
      } yield r).toLiftResponseList(params, schema)
    }
  }
}

/*
 * Corresponding service in charge of ensuring API specific logic:
 * - only deal with account with kind PublicApi
 * - mapping to JSON object with restriction on token
 */
trait ApiAccountApiService {
  def getAccounts(): IOResult[List[ApiAccountDetails.Public]]
  def getAccount(id:         ApiAccountId): IOResult[Option[ApiAccountDetails.Public]]
  def createAccount(account: NewRestApiAccount)(using QueryContext): IOResult[ApiAccountDetails]
  def updateAccount(id:      ApiAccountId, data: UpdateApiAccount)(using QueryContext): IOResult[ApiAccountDetails.Public]
  def regenerateToken(id:    ApiAccountId)(using QueryContext): IOResult[ApiAccountDetails]
  def deleteToken(id:        ApiAccountId)(using QueryContext): IOResult[ApiAccountDetails]
  def deleteAccount(id:      ApiAccountId)(using QueryContext): IOResult[Option[ApiAccountDetails.Public]]
}

class ApiAccountApiServiceV1(
    readApi:     RoApiAccountRepository,
    writeApi:    WoApiAccountRepository,
    mapper:      ApiAccountMapping,
    uuidGen:     StringUuidGenerator,
    userService: UserService
) extends ApiAccountApiService {

  // we need to take care only for PublicApi account, and not of the system account in that API.
  // We don't want to do anything with it: don't get it, don't update it, don't delete it.
  // This will be better done once we have a clear topology of ApiAccount with subtypes
  private[lift] def getOptPublicApiAccount(id: ApiAccountId): IOResult[Option[ApiAccount]] = {
    readApi.getById(id).flatMap {
      case None                                                             =>
        None.succeed
      case Some(account) if (account.kind.kind == ApiAccountType.PublicApi) =>
        Some(account).succeed
      case Some(x)                                                          =>
        // we log to let ops know that perhaps someone is poking the API accounts, but
        // we don't give more info.
        ApiLoggerPure.warn(s"Access to API account with ID '${id.value}' is not authorized via API") *>
        None.succeed
    }
  }

  private[lift] def getPublicApiAccount(id: ApiAccountId): IOResult[ApiAccount] = {
    getOptPublicApiAccount(id).notOptional(s"API account with ID '${id.value}' was not found")
  }

  override def getAccounts(): IOResult[List[ApiAccountDetails.Public]] = {
    // ok, only standard API
    readApi.getAllStandardAccounts.map(_.toList.map(_.transformInto[ApiAccountDetails.Public]))
  }

  override def getAccount(id: ApiAccountId): IOResult[Option[ApiAccountDetails.Public]] = {
    getOptPublicApiAccount(id).map(_.map(_.transformInto[ApiAccountDetails.Public]))
  }

  override def createAccount(data: NewRestApiAccount)(using qc: QueryContext): IOResult[ApiAccountDetails] = {
    for {
      pair <- mapper.fromNewApiAccount(data)
      // check that that account doesn't already exist
      _    <- readApi.getById(pair._1.id).flatMap {
                case None    =>
                  ZIO.unit
                case Some(a) => Inconsistency(s"Error: an account with id ${a.id.value} already exists").fail
              }
      _    <- writeApi.save(pair._1, ModificationId(uuidGen.newUuid), qc.actor)
    } yield {
      pair._2 match {
        case Some(s) => mapper.toDetailsWithSecret(pair._1, s)
        case None    => pair._1.transformInto[ApiAccountDetails.Public]
      }
    }
  }

  override def updateAccount(id: ApiAccountId, data: UpdateApiAccount)(using
      qc: QueryContext
  ): IOResult[ApiAccountDetails.Public] = {
    for {
      a  <- getPublicApiAccount(id)
      up <- mapper.update(a, data).toIO
      _  <- writeApi.save(up, ModificationId(uuidGen.newUuid), qc.actor)
    } yield up.transformInto[ApiAccountDetails.Public]
  }

  override def regenerateToken(id: ApiAccountId)(using qc: QueryContext): IOResult[ApiAccountDetails] = {
    for {
      a    <- getPublicApiAccount(id)
      pair <- mapper.updateToken(a)
      _    <- writeApi.save(pair._1, ModificationId(uuidGen.newUuid), qc.actor)
    } yield mapper.toDetailsWithSecret.tupled(pair)
  }

  override def deleteToken(id: ApiAccountId)(using qc: QueryContext): IOResult[ApiAccountDetails] = {
    for {
      a <- getPublicApiAccount(id)
      u  = a.modify(_.token).setTo(None)
      _ <- writeApi.save(u, ModificationId(uuidGen.newUuid), qc.actor)
    } yield u.transformInto[ApiAccountDetails.Public]
  }

  override def deleteAccount(id: ApiAccountId)(using qc: QueryContext): IOResult[Option[ApiAccountDetails.Public]] = {
    for {
      opt <- getOptPublicApiAccount(id)
      _   <- opt match {
               case None    => None.succeed
               case Some(_) => writeApi.delete(id, ModificationId(uuidGen.newUuid), qc.actor)
             }
    } yield opt.map(_.transformInto[ApiAccountDetails.Public])
  }
}
