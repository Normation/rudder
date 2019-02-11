package com.normation.rudder.rest

import com.normation.eventlog.ModificationId
import com.normation.rudder.UserService
import com.normation.rudder.api.RoApiAccountRepository
import com.normation.rudder.api.WoApiAccountRepository
import com.normation.rudder.api._
import com.normation.rudder.api.{ApiAuthorization => ApiAuthz}
import com.normation.rudder.rest.ApiAccountSerialisation._
import com.normation.rudder.rest.RestUtils._
import com.normation.utils.StringUuidGenerator
import net.liftweb.common.Loggable
import net.liftweb.common._
import net.liftweb.http.LiftResponse
import net.liftweb.http.rest.RestHelper
import net.liftweb.json.JArray
import net.liftweb.json.JsonDSL._
import org.joda.time.DateTime

import com.normation.zio._

class RestApiAccounts (
    readApi        : RoApiAccountRepository
  , writeApi       : WoApiAccountRepository
  , restExtractor  : RestExtractorService
  , tokenGenerator : TokenGenerator
  , uuidGen        : StringUuidGenerator
  , userService    : UserService
  , apiAuthService : ApiAuthorizationLevelService
) extends RestHelper with Loggable {

  val tokenSize = 32

  //used in ApiAccounts snippet to get the context path
  //of that service
  val relativePath = "secure" :: "apiaccounts" :: Nil

  serve {
    case Get("secure" :: "apiaccounts" :: Nil, req) =>
      readApi.getAllStandardAccounts.either.runNow match {
        case Right(accountSeq) =>
          val accounts =
            (
              ("aclPluginEnabled" -> apiAuthService.aclEnabled) ~
              ("accounts"  -> JArray(accountSeq.toList.map(_.toJson)))
            )
          toJsonResponse(None,accounts)("getAllAccounts",true)
        case Left(err) =>
          val msg = s"Could not get accounts cause : ${err.fullMsg}"
          logger.error(msg)
          toJsonError(None,msg)("getAllAccounts",true)

      }

    case "secure" :: "apiaccounts" :: Nil JsonPut body -> req =>
      req.json match {
        case Full(json) =>
        restExtractor.extractApiAccountFromJSON(json) match {
          case Full(restApiAccount) =>
            if (restApiAccount.name.isDefined) {
              // generate the id for creation
              val id = ApiAccountId(uuidGen.newUuid)
              val now = DateTime.now
              //by default, token expires after one month
              val expiration = restApiAccount.expiration.getOrElse(Some(now.plusMonths(1)))
              val acl = restApiAccount.authz.getOrElse(ApiAuthz.None)

              val account = ApiAccount(
                  id
                , ApiAccountKind.PublicApi(acl, expiration)
                , restApiAccount.name.get ,ApiToken(tokenGenerator.newToken(tokenSize))
                , restApiAccount.description.getOrElse("")
                , restApiAccount.enabled.getOrElse(true)
                , now
                , now
              )
              writeApi.save(account, ModificationId(uuidGen.newUuid), userService.getCurrentUser.actor).either.runNow match {
                case Right(_) =>
                  val accounts = ("accounts" -> JArray(List(account.toJson)))
                  toJsonResponse(None,accounts)("updateAccount",true)

                case Left(err) =>
                  val msg = s"Could not create account cause : ${err.fullMsg}"
                  logger.error(msg)
                  toJsonError(None,msg)("updateAccount",true)
              }
            } else {
              val msg = s"Could not create account cause : could not get account"
              logger.error(msg)
              toJsonError(None,msg)("updateAccount",true)
            }

          case eb : EmptyBox =>
            val msg = s"Could not create account cause : ${(eb ?~ "could not extract data from JSON").msg}"
            logger.error(msg)
            toJsonError(None, msg)("updateAccount",true)
        }
        case eb:EmptyBox=>
          logger.error("No Json data sent")
          toJsonError(None, "No Json data sent")("updateAccount",true)
      }

    case "secure" :: "apiaccounts" :: token :: Nil JsonPost body -> req =>
      val apiToken = ApiToken(token)
      req.json match {
        case Full(json) =>
        restExtractor.extractApiAccountFromJSON(json) match {
          case Full(restApiAccount) =>
            readApi.getByToken(apiToken).either.runNow match {
              case Right(Some(account)) =>
                val updateAccount = restApiAccount.update(account)
                save(updateAccount)

              case Right(None) =>
                val msg = s"Could not update account with token $token cause : could not get account"
                logger.error(msg)
                toJsonError(None,msg)("updateAccount",true)
              case Left(err) =>
                val msg = s"Could not update account with token $token cause : ${err.fullMsg}"
                logger.error(msg)
                toJsonError(None,msg)("updateAccount",true)
            }
          case eb : EmptyBox =>
            val msg = s"Could not update account with token $token cause : ${(eb ?~ "could not extract data from JSON").msg}"
            logger.error(msg)
            toJsonError(None,msg)("updateAccount",true)
        }
        case eb:EmptyBox=>
          toJsonError(None, "No Json data sent")("updateAccount",true)
      }

    case Delete("secure" :: "apiaccounts" :: token :: Nil, req) =>
      val apiToken = ApiToken(token)
      readApi.getByToken(apiToken).either.runNow match {
        case Right(Some(account)) =>
          writeApi.delete(account.id, ModificationId(uuidGen.newUuid), userService.getCurrentUser.actor).either.runNow match {
            case Right(_) =>
              val accounts = ("accounts" -> JArray(List(account.toJson)))
              toJsonResponse(None,accounts)("deleteAccount",true)

            case Left(err) =>
              toJsonError(None,s"Could not delete account with token $token cause : ${err.fullMsg}")("deleteAccount",true)
          }

        case Right(None) =>
          toJsonError(None,s"Could not delete account with token $token cause : could not get account")("deleteAccount",true)
        case Left(err) =>
          toJsonError(None,s"Could not delete account with token $token cause : ${err.fullMsg}")("deleteAccount",true)
      }

    case Post("secure" :: "apiaccounts" :: token :: "regenerate" :: Nil, req) =>
      val apiToken = ApiToken(token)
      readApi.getByToken(apiToken).either.runNow match {
        case Right(Some(account)) =>
          val newToken = ApiToken(tokenGenerator.newToken(tokenSize))
          val generationDate = DateTime.now
          writeApi.save(
              account.copy(token = newToken, tokenGenerationDate = generationDate)
            , ModificationId(uuidGen.newUuid)
            , userService.getCurrentUser.actor).either.runNow match {
            case Right(account) =>
              val accounts = ("accounts" -> JArray(List(account.toJson)))
              toJsonResponse(None,accounts)("regenerateAccount",true)

            case Left(err) =>
              val msg = s"Could not regenerate account with token $token cause : ${err.fullMsg}"
              logger.error(msg)
              toJsonError(None,s"Could not regenerate account with token $token cause : ${err.fullMsg}")("regenerateAccount",true)
          }

        case Right(None) =>
          val msg = s"Could not regenerate account with token $token cause could not get account"
          logger.error(msg)
          toJsonError(None,msg)("regenerateAccount",true)
        case Left(err) =>
          val msg = s"Could not regenerate account with token $token cause : ${err.fullMsg}"
          logger.error(msg)
          toJsonError(None,msg)("regenerateAccount",true)
      }

  }

  def save(account:ApiAccount) : LiftResponse = {
    writeApi.save(account, ModificationId(uuidGen.newUuid), userService.getCurrentUser.actor).either.runNow match {
      case Right(res) =>
        val accounts = ("accounts" -> JArray(List(res.toJson)))
        toJsonResponse(None,accounts)("updateAccount",true)

      case Left(err) =>
        toJsonError(None, s"Could not update account '${account.name.value}' cause : ${err.fullMsg}")("updateAccount",true)
    }
  }

}

case class RestApiAccount(
    id          : Option[ApiAccountId]
  , name        : Option[ApiAccountName]
  , description : Option[String]
  , enabled     : Option[Boolean]
  , oldId       : Option[ApiAccountId]
  , expiration  : Option[Option[DateTime]]
  , authz       : Option[ApiAuthz]
) {

  // Id cannot change if already defined
  def update(account : ApiAccount) = {
    val nameUpdate   = name.getOrElse(account.name)
    val enableUpdate = enabled.getOrElse(account.isEnabled)
    val descUpdate   = description.getOrElse(account.description)
    val kind = account.kind match {
      case ApiAccountKind.PublicApi(a, e) =>
        ApiAccountKind.PublicApi(authz.getOrElse(a), expiration.getOrElse(e))
      case x => x
    }

    account.copy(name = nameUpdate, isEnabled = enableUpdate, description = descUpdate, kind = kind)
  }
}
