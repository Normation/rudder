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
import com.normation.rudder.rest.*
import com.normation.rudder.rest.ApiAccounts as API
import com.normation.rudder.rest.data.*
import com.normation.rudder.rest.implicits.*
import com.normation.rudder.users.UserService
import com.normation.utils.StringUuidGenerator
import io.scalaland.chimney.syntax.*
import net.liftweb.http.*
import org.joda.time.DateTime
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
      (for {
        account <- ZioJsonExtractor.parseJson[NewRestApiAccount](req).toIO
        res     <- service.saveAccount(account)
      } yield res).toLiftResponseOne(params, schema, x => Some(x.id.value))
    }
  }

  object UpdateAccount extends LiftApiModule {
    val schema: API.UpdateAccount.type = API.UpdateAccount

    def process(v: ApiVersion, path: ApiPath, s: String, req: Req, params: DefaultParams, t: AuthzToken): LiftResponse = {
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
      (for {
        id <- toId(s)
        r  <- service.regenerateToken(id)
      } yield r).toLiftResponseOne(params, schema, Some(s))
    }
  }

  object DeleteAccount extends LiftApiModule {
    val schema: API.DeleteAccount.type = API.DeleteAccount

    def process(v: ApiVersion, path: ApiPath, s: String, req: Req, params: DefaultParams, t: AuthzToken): LiftResponse = {
      (for {
        id <- toId(s)
        r  <- service.deleteAccount(id)
      } yield r).toLiftResponseOne(params, schema, Some(s))
    }
  }
}

trait ApiAccountApiService {
  def getAccounts(): IOResult[List[ApiAccountDetails.Public]]
  def getAccount(id:       ApiAccountId): IOResult[Option[ApiAccountDetails.Public]]
  def saveAccount(account: NewRestApiAccount): IOResult[ApiAccountDetails]
  def updateAccount(id:    ApiAccountId, data: UpdateApiAccount): IOResult[ApiAccountDetails.Public]
  def regenerateToken(id:  ApiAccountId): IOResult[ApiAccountDetails]
  def deleteAccount(id:    ApiAccountId): IOResult[Option[ApiAccountDetails.Public]]
}

class ApiAccountApiServiceV1(
    readApi:        RoApiAccountRepository,
    writeApi:       WoApiAccountRepository,
    tokenGenerator: TokenGenerator,
    uuidGen:        StringUuidGenerator,
    userService:    UserService
) extends ApiAccountApiService {
  // mapping from/to rest data
  private val mapper = {
    val getNow         = DateTime.now().succeed
    val generateId     = ApiAccountId(uuidGen.newUuid).succeed
    val generateSecret = ApiTokenSecret.generate(tokenGenerator).transformInto[ClearTextSecret].succeed
    def generateToken(secret: ClearTextSecret): IOResult[ApiTokenHash] =
      ApiTokenHash.fromSecret(secret.transformInto[ApiTokenSecret]).succeed

    new ApiAccountMapping(getNow, generateId, generateSecret, generateToken)
  }

  override def getAccounts(): IOResult[List[ApiAccountDetails.Public]] = {
    readApi.getAllStandardAccounts.map(_.toList.map(_.transformInto[ApiAccountDetails.Public]))
  }

  override def getAccount(id: ApiAccountId): IOResult[Option[ApiAccountDetails.Public]] = {
    readApi.getById(id).flatMap {
      case None                                                                  =>
        None.succeed
      case Some(account) if (account.kind.kind.name == ApiAccountType.PublicApi) =>
        Some(account.transformInto[ApiAccountDetails.Public]).succeed
      case Some(x)                                                               =>
        ApiLoggerPure.warn(s"Access to API account with ID '${id.value}' is not authorized via API") *>
        None.succeed
    }
  }

  override def saveAccount(data: NewRestApiAccount): IOResult[ApiAccountDetails] = {
    for {
      pair <- mapper.fromNewApiAccount(data)
      _    <- writeApi.save(pair._1, ModificationId(uuidGen.newUuid), userService.getCurrentUser.actor)
    } yield {
      pair._2 match {
        case Some(s) => mapper.toDetailsWithSecret(pair._1, s)
        case None    => pair._1.transformInto[ApiAccountDetails.Public]
      }
    }
  }

  override def updateAccount(id: ApiAccountId, data: UpdateApiAccount): IOResult[ApiAccountDetails.Public] = {
    for {
      a <- readApi.getById(id).notOptional(s"API account with ID '${id.value}' was not found")
      up = mapper.update(a, data)
      _ <- writeApi.save(up, ModificationId(uuidGen.newUuid), userService.getCurrentUser.actor)
    } yield up.transformInto[ApiAccountDetails.Public]
  }

  override def regenerateToken(id: ApiAccountId): IOResult[ApiAccountDetails] = {
    for {
      a    <- readApi.getById(id).notOptional(s"API account with ID '${id.value}' was not found")
      pair <- mapper.updateToken(a)
      _    <- writeApi.save(pair._1, ModificationId(uuidGen.newUuid), userService.getCurrentUser.actor)
    } yield mapper.toDetailsWithSecret.tupled(pair)
  }

  override def deleteAccount(id: ApiAccountId): IOResult[Option[ApiAccountDetails.Public]] = {
    for {
      opt <- readApi.getById(id)
      _   <- opt match {
               case None    => None.succeed
               case Some(_) => writeApi.delete(id, ModificationId(uuidGen.newUuid), userService.getCurrentUser.actor)
             }
    } yield opt.map(_.transformInto[ApiAccountDetails.Public])
  }
}
