/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.rudder.rest

import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.UserService
import com.normation.utils.StringUuidGenerator
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.http.Req
import net.liftweb.http._
import net.liftweb.http.js.JsExp
import net.liftweb.json.JsonAST.RenderSettings
import net.liftweb.json.JsonDSL._
import net.liftweb.json._
import net.liftweb.util.Helpers.tryo

/**
 */
object RestUtils extends Loggable {

  /**
   * Get the rest user name, as follow:
   * - if the user is authenticated, use the provided UserName
   * - else, use the HTTP Header: X-REST-USERNAME
   * - else, return none
   */
  def getUsername(req:Req)(implicit userService : UserService ) : Option[String] = {

    userService.getCurrentUser.actor.name match {
      case "unknown" => req.header(s"X-REST-USERNAME") match {
        case eb:EmptyBox => None
        case Full(name) => Some(name)
      }
      case userName => Some(userName)
    }
  }

  def getActor(req:Req)(implicit userService : UserService ) : EventActor = EventActor(getUsername(req).getOrElse("UnknownRestUser"))

  def getPrettify(req:Req) : Box[Boolean] =
    req.json match {
      case Full(json) => json \ "prettify" match {
        case JBool(prettify) => Full(prettify)
        case JNothing        => Full(false)
        case x               => Failure(s"Not a valid value for 'prettify' parameter, current value is : ${x}")
      }
      case _ =>
        req.params.get("prettify") match {
          case None                 => Full(false)
          case Some("true" :: Nil)  => Full(true)
          case Some("false" :: Nil) => Full(false)
          case _                    => Failure("Prettify should only have one value, and should be set to true or false")
    }
  }

  /**
   * Our own JSON render function to extends net.liftweb.json.JsonAst.render function
   * All code is taken from JsonAst object from lift-json_2.10-2.5.1.jar (dépendency used in rudder 2.10 at least)
   * and available at: https://github.com/lift/framework/blob/2.5.1/core/json/src/main/scala/net/liftweb/json/JsonAST.scala#L392
   * What we added:
   *   - add a new line after each element in array
   *   - Add a new line at the end and beginning of an array and indent one more level array data
   *   - space after colon
   *
   *   TODO: see if/how it is possible to get the same behaviour
   */
  def render(value: JValue): String = {
    JsonAST.render(value, RenderSettings.pretty.copy(spaceAfterFieldName= true))
  }

  def effectiveResponse (id:Option[String], message:JValue, status:HttpStatus, action : String , prettify : Boolean) : LiftResponse = {
    status match {
      case _:RestError =>
        // Log any error
        logger.error(compactRender(message))
      case _ => // Do nothing
    }
    val json = ( "action" -> action ) ~
                  ( "id"     -> id ) ~
                  ( "result" -> status.status ) ~
                  ( status.container   ->  message )
    val content : JsExp = new JsExp {
      lazy val toJsCmd = if(prettify) render(json) else compactRender(json)
    }

    JsonResponse(content,List(),List(), status.code)

  }

  def toJsonResponse(id:Option[String], message:JValue) ( implicit action : String, prettify : Boolean) : LiftResponse = {
    effectiveResponse (id, message, RestOk, action, prettify)
  }

  def toJsonError(id:Option[String], message:JValue)( implicit action : String = "rest", prettify : Boolean) : LiftResponse = {
    effectiveResponse (id, message, InternalError, action, prettify)
  }

  def notValidVersionResponse(action:String)(implicit availableVersions : List[ApiVersion]) = {
    val versions = "latest" :: availableVersions.map(_.value.toString)
    toJsonError(None, JString(s"Version used does not exist, please use one of the following: ${versions.mkString("[ ", ", ", " ]")} "))(action,false)
   }

  def missingResponse(version:Int,action:String) = {
    toJsonError(None, JString(s"Version ${version} exists for this API function, but it's implementation is missing"))(action,false)
   }

  def unauthorized = effectiveResponse(None, JString("You are not authorized to access that API"),ForbiddenError, "",false)

  def response (restExtractor : RestExtractorService, dataName: String, id : Option[String]) (function : Box[JValue], req : Req, errorMessage : String) (implicit action : String) : LiftResponse = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
    function match {
      case Full(category : JValue) =>
        toJsonResponse(id, ( dataName -> category))
      case eb: EmptyBox =>
        val message = (eb ?~! errorMessage).messageChain
        toJsonError(id, message)
    }
  }

  type ActionType = (EventActor, ModificationId, Option[String]) => Box[JValue]
  def actionResponse (restExtractor : RestExtractorService, dataName: String, uuidGen: StringUuidGenerator, id : Option[String]) (function : Box[ActionType], req : Req, errorMessage : String)(implicit action : String, userService : UserService ) : LiftResponse = {
    actionResponse2(restExtractor, dataName, uuidGen, id)(function, req, errorMessage)(action, RestUtils.getActor(req))
  }
  def actionResponse2 (restExtractor : RestExtractorService, dataName: String, uuidGen: StringUuidGenerator, id : Option[String]) (function : Box[ActionType], req : Req, errorMessage : String)(implicit action : String, actor: EventActor ) : LiftResponse = {
    implicit val prettify = restExtractor.extractPrettify(req.params)

    ( for {
      reason <- restExtractor.extractReason(req)
      modId = ModificationId(uuidGen.newUuid)
      result <- function.flatMap { _(actor,modId,reason) }
    } yield {
      result
    } ) match {
      case Full(result : JValue) =>
        toJsonResponse(id, ( dataName -> result))
      case eb: EmptyBox =>
        val message = (eb ?~! errorMessage).messageChain
        toJsonError(id, message)
    }
  }

  type WorkflowType = (EventActor, Option[String], String, String) => Box[JValue]
  def workflowResponse (restExtractor : RestExtractorService, dataName: String, uuidGen: StringUuidGenerator, id : Option[String]) (function : Box[WorkflowType] , req : Req, errorMessage : String, defaultName : String)(implicit action : String, userService : UserService ) : LiftResponse = {
    workflowResponse2(restExtractor, dataName, uuidGen, id)(function, req, errorMessage, defaultName)(action, RestUtils.getActor(req))
  }
  def workflowResponse2 (restExtractor : RestExtractorService, dataName: String, uuidGen: StringUuidGenerator, id : Option[String]) (function : Box[WorkflowType] , req : Req, errorMessage : String, defaultName : String)(implicit action : String, actor: EventActor) : LiftResponse = {
    implicit val prettify = restExtractor.extractPrettify(req.params)

    ( for {
      reason <- restExtractor.extractReason(req)
      crName <- restExtractor.extractChangeRequestName(req).map(_.getOrElse(defaultName))
      crDesc = restExtractor.extractChangeRequestDescription(req)
      result <- function.flatMap { _(actor,reason, crName, crDesc) }
    } yield {
      result
    } ) match {
      case Full(result : JValue) =>
        toJsonResponse(id, ( dataName -> result))
      case eb: EmptyBox =>
        val message = (eb ?~! errorMessage).messageChain
        toJsonError(id, message)
    }
  }

  def notFoundResponse(id:Option[String], message:JValue) ( implicit action : String, prettify : Boolean) = {
    effectiveResponse (id, message, NotFoundError, action, prettify)
 }

}

sealed case class ApiVersion (
    value : Int
  , deprecated : Boolean
)

object ApiVersion {

  def fromRequest(req:Req)( implicit availableVersions : List[ApiVersion]) : Box[ApiVersion] = {

    val latest = availableVersions.maxBy(_.value)
    def fromString (version : String) : Box[ApiVersion] = {
      version match {
        case "latest"  => Full(latest)
        case value =>
           tryo { value.toInt } match {
             case Full(version) =>
               availableVersions.find(_.value == version) match {
                 case Some(apiVersion) => Full(apiVersion)
                 case None => Failure(s" ${version} is not a valid api version")
               }
             // Never empty due to tryo
             case eb:EmptyBox => eb
          }
      }
    }

    req.header("X-API-VERSION") match {
      case Full(value) => fromString(value)
      case eb: EmptyBox => eb ?~ ("Error when getting header X-API-VERSION")
    }
  }

}

trait HttpStatus {
  def code : Int
  def status : String
  def container : String
}

object RestOk extends HttpStatus{
  val code = 200
  val status = "success"
  val container = "data"
}

trait RestError extends HttpStatus{
  val status = "error"
  val container = "errorDetails"
}

object InternalError extends RestError {
  val code = 500
}

object NotFoundError extends RestError {
  val code = 404
}
object ForbiddenError extends RestError {
  val code = 403
}
