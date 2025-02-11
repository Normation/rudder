/*
 *************************************************************************************
 * Copyright 2019 Normation SAS
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
package com.normation.rudder.rest.internal

import com.normation.eventlog.ModificationId
import com.normation.rudder.api.*
import com.normation.rudder.api.ApiAuthorization as ApiAuthz
import com.normation.rudder.api.RoApiAccountRepository
import com.normation.rudder.api.WoApiAccountRepository
import com.normation.rudder.apidata.ApiAccountSerialisation.*
import com.normation.rudder.facts.nodes.NodeSecurityContext
import com.normation.rudder.rest.ApiAuthorizationLevelService
import com.normation.rudder.rest.OldInternalApiAuthz
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.RestUtils.*
import com.normation.rudder.tenants.TenantService
import com.normation.rudder.users.UserService
import com.normation.utils.StringUuidGenerator
import com.normation.zio.*
import net.liftweb.common.*
import net.liftweb.common.Loggable
import net.liftweb.http.LiftResponse
import net.liftweb.http.rest.RestHelper
import net.liftweb.json.JArray
import net.liftweb.json.JsonDSL.*
import org.joda.time.DateTime

class RestApiAccounts(
    readApi:        RoApiAccountRepository,
    writeApi:       WoApiAccountRepository,
    restExtractor:  RestExtractorService,
    tokenGenerator: TokenGenerator,
    uuidGen:        StringUuidGenerator,
    userService:    UserService,
    apiAuthService: ApiAuthorizationLevelService,
    tenantsService: TenantService
) extends RestHelper with Loggable {

  // used in ApiAccounts snippet to get the context path
  // of that service
  val relativePath: List[String] = "oldapiaccounttoremove" :: Nil

  serve {
    case Get("oldapiaccounttoremove" :: Nil, req) =>
      implicit val prettify: Boolean = restExtractor
        .extractBoolean("prettify")(req)(identity)
        .getOrElse(Some(false))
        .getOrElse(
          false
        )
      implicit val action:   String  = "getAllAccounts"

      // here, 'write' and not 'read' because some tokens may have admin write access,
      // and we want to avoid escalation
      OldInternalApiAuthz.withWriteAdmin(readApi.getAllStandardAccounts.either.runNow match {
        case Right(accountSeq) =>
          val filtered = accountSeq.toList
            .map((a) => {
              // Don't send hashes

              a.copy(token = a.token match {
                case Some(t) =>
                  if (t.isHashed) {
                    None
                  } else {
                    Some(t)
                  }
                case None    => None
              })
            })
          val accounts = {
            (
              ("aclPluginEnabled"     -> apiAuthService.aclEnabled) ~
              ("tenantsPluginEnabled" -> tenantsService.tenantsEnabled) ~
              ("accounts"             -> JArray(
                filtered.map(_.toJson)
              ))
            )
          }
          toJsonResponse(None, accounts)
        case Left(err)         =>
          val msg = s"Could not get accounts cause : ${err.fullMsg}"
          logger.error(msg)
          toJsonError(None, msg)

      })

    case "oldapiaccounttoremove" :: Nil JsonPut body -> req =>
      implicit val prettify: Boolean = restExtractor
        .extractBoolean("prettify")(req)(identity)
        .getOrElse(Some(false))
        .getOrElse(
          false
        )
      implicit val action:   String  = "updateAccount"

      OldInternalApiAuthz.withWriteAdmin(req.json match {
        case Full(json) =>
          restExtractor.extractApiAccountFromJSON(json) match {
            case Full(restApiAccount) =>
              if (restApiAccount.name.isDefined) {
                // generate the id for creation
                val (id, secret, hash) = {
                  // here, we have two cases: either an ID is provided, and we use it. In that case,
                  // the token is likely for integration with a third party authentication protocol, and we
                  // don't generate a hash. Else, we generate both a hash and an id.
                  restApiAccount.id match {
                    case Some(id) if (id.value.trim.nonEmpty) =>
                      (id, None, None)
                    case _                                    =>
                      val secret = ApiToken(ApiToken.generate_secret(tokenGenerator))
                      val hash   = ApiToken(ApiToken.hash(secret.value))
                      (ApiAccountId(uuidGen.newUuid), Some(secret), Some(hash))
                  }
                }
                val now                = DateTime.now
                // by default, token expires after one month
                val expiration         = restApiAccount.expiration.getOrElse(Some(now.plusMonths(1)))
                val acl                = restApiAccount.authz.getOrElse(ApiAuthz.None)

                val account = ApiAccount(
                  id,
                  ApiAccountKind.PublicApi(acl, expiration),
                  restApiAccount.name.get,
                  hash,
                  restApiAccount.description.getOrElse(""),
                  restApiAccount.enabled.getOrElse(true),
                  now,
                  now,
                  restApiAccount.tenants.getOrElse(NodeSecurityContext.All)
                )
                writeApi.save(account, ModificationId(uuidGen.newUuid), userService.getCurrentUser.actor).either.runNow match {
                  case Right(_) =>
                    val accounts = ("accounts" -> JArray(
                      List(
                        account
                          .copy(
                            // Send clear text secret
                            token = secret
                          )
                          .toJson
                      )
                    ))
                    toJsonResponse(None, accounts)

                  case Left(err) =>
                    val msg = s"Could not create account cause: ${err.fullMsg}"
                    logger.error(msg)
                    toJsonError(None, msg)
                }
              } else {
                val msg = s"Could not create account cause: could not get account"
                logger.error(msg)
                toJsonError(None, msg)
              }

            case eb: EmptyBox =>
              val msg = s"Could not create account cause: ${(eb ?~ "could not extract data from JSON").msg}"
              logger.error(msg)
              toJsonError(None, msg)
          }
        case eb: EmptyBox =>
          logger.error("No Json data sent")
          toJsonError(None, "No Json data sent")
      })

    case "oldapiaccounttoremove" :: tokenId :: Nil JsonPost body -> req =>
      val apiTokenId = ApiAccountId(tokenId)
      implicit val prettify: Boolean = restExtractor
        .extractBoolean("prettify")(req)(identity)
        .getOrElse(Some(false))
        .getOrElse(
          false
        )
      implicit val action:   String  = "updateAccount"

      OldInternalApiAuthz.withWriteAdmin(req.json match {
        case Full(json) =>
          restExtractor.extractApiAccountFromJSON(json) match {
            case Full(restApiAccount) =>
              readApi.getById(apiTokenId).either.runNow match {
                case Right(Some(account)) =>
                  val updateAccount = restApiAccount.update(account)
                  save(updateAccount)

                case Right(None) =>
                  val msg = s"Could not update account ${tokenId} cause: could not get account"
                  logger.error(msg)
                  toJsonError(None, msg)
                case Left(err)   =>
                  val msg = s"Could not update account ${tokenId} cause: ${err.fullMsg}"
                  logger.error(msg)
                  toJsonError(None, msg)
              }
            case eb: EmptyBox =>
              val msg = s"Could not update account ${tokenId} cause: ${(eb ?~ "could not extract data from JSON").msg}"
              logger.error(msg)
              toJsonError(None, msg)
          }
        case eb: EmptyBox =>
          toJsonError(None, "No Json data sent")
      })

    case Delete("oldapiaccounttoremove" :: tokenId :: Nil, req) =>
      val apiTokenId = ApiAccountId(tokenId)
      implicit val prettify: Boolean = restExtractor
        .extractBoolean("prettify")(req)(identity)
        .getOrElse(Some(false))
        .getOrElse(
          false
        )
      implicit val action:   String  = "deleteAccount"

      OldInternalApiAuthz.withWriteAdmin(readApi.getById(apiTokenId).either.runNow match {
        case Right(Some(account)) =>
          writeApi.delete(account.id, ModificationId(uuidGen.newUuid), userService.getCurrentUser.actor).either.runNow match {
            case Right(_) =>
              val filtered = account.copy(token = if (account.token.map(_.isHashed).getOrElse(true)) {
                None
              } else {
                account.token
              })
              val accounts = ("accounts" -> JArray(List(filtered.toJson)))
              toJsonResponse(None, accounts)

            case Left(err) =>
              toJsonError(None, s"Could not delete account ${tokenId} cause: ${err.fullMsg}")
          }

        case Right(None) =>
          toJsonError(None, s"Could not delete account ${tokenId} cause: could not get account")
        case Left(err)   =>
          toJsonError(None, s"Could not delete account ${tokenId} cause: ${err.fullMsg}")
      })

    case Post("oldapiaccounttoremove" :: tokenId :: "regenerate" :: Nil, req) =>
      val apiTokenId = ApiAccountId(tokenId)
      implicit val prettify: Boolean = restExtractor
        .extractBoolean("prettify")(req)(identity)
        .getOrElse(Some(false))
        .getOrElse(
          false
        )
      implicit val action:   String  = "regenerateAccount"

      OldInternalApiAuthz.withWriteAdmin(readApi.getById(apiTokenId).either.runNow match {
        case Right(Some(account)) =>
          val newSecret = ApiToken.generate_secret(tokenGenerator)
          val newHash   = ApiToken.hash(newSecret)

          val generationDate = DateTime.now
          writeApi
            .save(
              account.copy(token = Some(ApiToken(newHash)), tokenGenerationDate = generationDate),
              ModificationId(uuidGen.newUuid),
              userService.getCurrentUser.actor
            )
            .either
            .runNow match {
            case Right(account) =>
              val accounts = ("accounts" -> JArray(
                List(
                  account
                    .copy(
                      // Send clear text secret
                      token = Some(ApiToken(newSecret))
                    )
                    .toJson
                )
              ))
              toJsonResponse(None, accounts)

            case Left(err) =>
              val msg = s"Could not regenerate account ${tokenId} cause: ${err.fullMsg}"
              logger.error(msg)
              toJsonError(None, s"Could not regenerate account ${tokenId} cause: ${err.fullMsg}")(
                "regenerateAccount",
                prettify = true
              )
          }

        case Right(None) =>
          val msg = s"Could not regenerate account ${tokenId} cause could not get account"
          logger.error(msg)
          toJsonError(None, msg)
        case Left(err)   =>
          val msg = s"Could not regenerate account ${tokenId} cause: ${err.fullMsg}"
          logger.error(msg)
          toJsonError(None, msg)
      })

  }

  def save(account: ApiAccount)(implicit action: String, prettify: Boolean): LiftResponse = {
    writeApi.save(account, ModificationId(uuidGen.newUuid), userService.getCurrentUser.actor).either.runNow match {
      case Right(res) =>
        val filtered = res.copy(token = if (res.token.map(_.isHashed).getOrElse(true)) {
          None
        } else {
          res.token
        })
        val accounts = ("accounts" -> JArray(List(filtered.toJson)))
        toJsonResponse(None, accounts)

      case Left(err) =>
        toJsonError(None, s"Could not update account '${account.name.value}' cause : ${err.fullMsg}")
    }
  }

}

final case class RestApiAccount(
    id:          Option[ApiAccountId],
    name:        Option[ApiAccountName],
    description: Option[String],
    enabled:     Option[Boolean],
    oldId:       Option[ApiAccountId],
    expiration:  Option[Option[DateTime]],
    authz:       Option[ApiAuthz],
    tenants:     Option[NodeSecurityContext]
) {

  // Id cannot change if already defined
  def update(account: ApiAccount): ApiAccount = {
    val nameUpdate    = name.getOrElse(account.name)
    val enableUpdate  = enabled.getOrElse(account.isEnabled)
    val descUpdate    = description.getOrElse(account.description)
    val tenantsUpdate = tenants.getOrElse(account.tenants)
    val kind          = account.kind match {
      case ApiAccountKind.PublicApi(a, e) =>
        ApiAccountKind.PublicApi(authz.getOrElse(a), expiration.getOrElse(e))
      case x                              => x
    }

    account.copy(name = nameUpdate, isEnabled = enableUpdate, description = descUpdate, kind = kind, tenants = tenantsUpdate)
  }
}
