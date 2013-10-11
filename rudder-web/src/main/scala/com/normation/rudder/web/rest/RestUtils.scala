/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.rudder.web.rest

import com.normation.rudder.web.model.CurrentUser
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import net.liftweb.http.Req
import com.normation.eventlog.EventActor
import org.apache.commons.codec.binary.Base64
import net.liftweb.json._
import net.liftweb.http._
import net.liftweb.json.JsonDSL._
import net.liftweb.http.js.JsExp
import scala.text.Document
import net.liftweb.common.Loggable
import net.liftweb.common.Box
import net.liftweb.common.Failure
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
  def getUsername(req:Req) : Option[String] = {

    CurrentUser.is match {
      case None => req.header(s"X-REST-USERNAME") match {
        case eb:EmptyBox => None
        case Full(name) => Some(name)
      }
      case Some(u) => Some(u.getUsername)
    }
  }

  def getActor(req:Req) : EventActor = EventActor(getUsername(req).getOrElse("UnknownRestUser"))

  def getPrettify(req:Req) : Box[Boolean] = req.params.get("prettify") match {
    case None => Full(false)
    case Some("true" :: Nil) => Full(true)
    case Some("false" :: Nil) => Full(false)
    case _ => Failure("Prettify should only have one value, and should be set to true or false")
  }

  private[this] def effectiveResponse (id:Option[String], message:JValue, status:HttpStatus, action : String , prettify : Boolean) : LiftResponse = {
    val printer: Document => String = if (prettify) Printer.pretty else Printer.compact
    val json = ( "action" -> action ) ~
                  ( "id"     -> id ) ~
                  ( "result" -> status.status ) ~
                  ( status.container   ->  message )
    val content : JsExp = new JsExp {
      lazy val toJsCmd = printer(JsonAST.render((json)))
    }

    JsonResponse(content,List(),List(), status.code)

  }


  def toJsonResponse(id:Option[String], message:JValue) ( implicit action : String, prettify : Boolean) : LiftResponse = {
    effectiveResponse (id, message, RestOk, action, prettify)
  }

  def toJsonError(id:Option[String], message:JValue)( implicit action : String = "rest", prettify : Boolean) : LiftResponse = {
    effectiveResponse (id, message, RestError, action, prettify)
  }

  def notValidVersionResponse(action:String)(implicit availableVersions : List[Int]) = {
    val versions = "latest" :: availableVersions.map(_.toString)
    toJsonError(None, JString(s"Version used does not exists, please use one of the following: ${versions.mkString("[ ", ", ", " ]")} "))(action,false)
   }

  def missingResponse(version:Int,action:String) = {
    toJsonError(None, JString(s"Version ${version} exists for this API function, but it's implementation is missing"))(action,false)
   }

}

sealed case class ApiVersion (
  value : Int
)

object ApiVersion {

  def fromRequest(req:Req)( implicit availableVersions : List[Int]) : Box[ApiVersion] = {

    val latest = availableVersions.max
    def fromString (version : String) : Box[ApiVersion] = {
      version match {
        case "latest"  => Full(ApiVersion(latest))
        case value =>
           tryo { value.toInt } match {
             case Full(version) =>
               if (availableVersions.contains(version)) {
                 Full(ApiVersion(version))
               } else {
                 Failure(s" ${version} is not a valid api version")
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

sealed trait HttpStatus {
  def code : Int
  def status : String
  def container : String
}

object RestOk extends HttpStatus{
  val code = 200
  val status = "success"
  val container = "data"
}

object RestError extends HttpStatus{
  val code = 500
  val status = "error"
  val container = "errorDetails"
}