package com.normation.rudder.web.rest

import net.liftweb.http.rest.RestHelper
import net.liftweb.common.Loggable
import com.normation.rudder.api.WoApiAccountRepository
import com.normation.rudder.api.RoApiAccountRepository
import com.normation.rudder.web.rest.RestUtils._
import net.liftweb.json.JsonDSL._
import net.liftweb.common._
import net.liftweb.json.JArray
import org.joda.time.DateTime
import com.normation.rudder.web.components.DateFormaterService
import com.normation.rudder.api._
import net.liftweb.http.LiftResponse

class RestApiAccounts (
    readApi        : RoApiAccountRepository
  , writeApi       : WoApiAccountRepository
  , restExtractor  : RestExtractorService
  , tokenGenerator : TokenGenerator
) extends RestHelper with Loggable {

  val tokenSize = 32

  //used in ApiAccounts snippet to get the context path
  //of that service
  val relativePath = "secure" :: "apiaccounts" :: Nil

  serve {
    case Get("secure" :: "apiaccounts" :: Nil, req) =>
      readApi.getAll() match {
        case Full(accountSeq) =>
          val accounts = ("accounts" -> JArray(accountSeq.toList.map(toJson(_))))
          toJsonResponse(None,accounts)("getAllAccounts",true)
        case eb : EmptyBox =>
              toJsonError(None,s"Could not get accounts cause : ${(eb ?~ "could not get account").msg}")("getAllAccounts",true)

      }


    case "secure" :: "apiaccounts" :: Nil JsonPut body -> req =>
      req.json match {
        case Full(json) =>
        restExtractor.extractApiAccountFromJSON(json) match {
          case Full(restApiAccount) =>
            if (restApiAccount.id.isDefined) {
              val account = ApiAccount(restApiAccount.id.get,ApiToken(tokenGenerator.newToken(tokenSize)), restApiAccount.description.getOrElse(""), restApiAccount.enabled.getOrElse(true), DateTime.now, DateTime.now)
              writeApi.save(account) match {
                case Full(_) =>
                  val accounts = ("accounts" -> JArray(List(toJson(account))))
                  toJsonResponse(None,accounts)("updateAccount",true)

                case eb : EmptyBox =>
                  toJsonError(None,s"Could not create account cause : ${(eb ?~ "could not save account").msg}")("updateAccount",true)
              }
            } else {
              toJsonError(None,s"Could not create account cause : could not get account")("updateAccount",true)
            }

          case eb : EmptyBox =>
                toJsonError(None,s"Could not create account cause : ${(eb ?~ "could not extract data from JSON").msg}")("updateAccount",true)
        }
        case eb:EmptyBox=>
          toJsonError(None, "No Json data sent")("updateAccount",true)
      }


    case "secure" :: "apiaccounts" :: token :: Nil JsonPost body -> req =>
      val apiToken = ApiToken(token)
      req.json match {
        case Full(json) =>
        restExtractor.extractApiAccountFromJSON(json) match {
          case Full(restApiAccount) =>
            readApi.getByToken(apiToken) match {
              case Full(Some(account)) =>
                val updateAccount = restApiAccount.update(account)

                save(updateAccount, restApiAccount.oldId)

              case Full(None) =>
                toJsonError(None,s"Could not update account with token $token cause : could not get account")("updateAccount",true)
              case eb : EmptyBox =>
                toJsonError(None,s"Could not update account with token $token cause : ${(eb ?~ "could not get account").msg}")("updateAccount",true)
            }
          case eb : EmptyBox =>
                toJsonError(None,s"Could not update account with token $token cause : ${(eb ?~ "could not extract data from JSON").msg}")("updateAccount",true)
        }
        case eb:EmptyBox=>
          toJsonError(None, "No Json data sent")("updateAccount",true)
      }


    case Delete("secure" :: "apiaccounts" :: token :: Nil, req) =>
      val apiToken = ApiToken(token)
      readApi.getByToken(apiToken) match {
        case Full(Some(account)) =>
          writeApi.delete(account.id) match {
            case Full(_) =>
              val accounts = ("accounts" -> JArray(List(toJson(account))))
              toJsonResponse(None,accounts)("deleteAccount",true)

            case eb : EmptyBox =>
              toJsonError(None,s"Could not delete account with token $token cause : ${(eb ?~ "could not delete account").msg}")("deleteAccount",true)
          }

        case Full(None) =>
          toJsonError(None,s"Could not delete account with token $token cause : could not get account")("deleteAccount",true)
        case eb : EmptyBox =>
          toJsonError(None,s"Could not delete account with token $token cause : ${(eb ?~ "could not get account").msg}")("deleteAccount",true)
      }

    case Post("secure" :: "apiaccounts" :: token :: "regenerate" :: Nil, req) =>
      val apiToken = ApiToken(token)
      readApi.getByToken(apiToken) match {
        case Full(Some(account)) =>
          val newToken = ApiToken(tokenGenerator.newToken(tokenSize))
          val generationDate = DateTime.now
          writeApi.save(account.copy(token = newToken,tokenGenerationDate = generationDate)) match {
            case Full(account) =>
              val accounts = ("accounts" -> JArray(List(toJson(account))))
              toJsonResponse(None,accounts)("regenerateAccount",true)

            case eb : EmptyBox =>
              toJsonError(None,s"Could not regenerate account with token $token cause : ${(eb ?~ "could not save account").msg}")("regenerateAccount",true)
          }

        case Full(None) =>
          toJsonError(None,s"Could not regenerate account with token $token cause : could not get account")("regenerateAccount",true)
        case eb : EmptyBox =>
          toJsonError(None,s"Could not regenerate account with token $token cause : ${(eb ?~ "could not get account").msg}")("regenerateAccount",true)
      }

  }

  def save(account:ApiAccount, oldId: Option[ApiAccountId]) : LiftResponse = {
    val res = oldId match {
      case Some(id) if(id != account.id) =>
        for {
          moved <- writeApi.rename(id, account.id)
          saved <- writeApi.save(account)
        } yield {
          saved
        }
      case _ => writeApi.save(account)
    }

    res match {
      case Full(res) =>
        val accounts = ("accounts" -> JArray(List(toJson(res))))
        toJsonResponse(None,accounts)("updateAccount",true)

      case eb : EmptyBox =>
        toJsonError(None, s"Could not update account with token ${account.token} cause : ${(eb ?~ "could not save account").msg}")("updateAccount",true)
    }
  }

  def toJson(account : ApiAccount) = {
    ("id" -> account.id.value) ~
    ("token" -> account.token.value) ~
    ("tokenGenerationDate" -> DateFormaterService.getFormatedDate(account.tokenGenerationDate)) ~
    ("description" -> account.description) ~
    ("creationDate" -> DateFormaterService.getFormatedDate(account.creationDate)) ~
    ("enabled" -> account.isEnabled)
  }





}

case class RestApiAccount(
    id          : Option[ApiAccountId]
  , description : Option[String]
  , enabled     : Option[Boolean]
  , oldId       : Option[ApiAccountId]
) {

 def update(account : ApiAccount) = {
    val idUpdate    = id.getOrElse(account.id)
    val enableUpdate = enabled.getOrElse(account.isEnabled)
    val descUpdate  = description.getOrElse(account.description)

    account.copy(id = idUpdate, isEnabled = enableUpdate, description = descUpdate )
  }
}


