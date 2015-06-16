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
import com.normation.eventlog.ModificationId
import com.normation.utils.StringUuidGenerator


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


  /**
   * Our own JSON render function to extends net.liftweb.json.JsonAst.render function
   * All code is taken from JsonAst object from lift-json_2.10-2.5.1.jar (dépendency used in rudder 2.10 at least)
   * and available at: https://github.com/lift/framework/blob/2.5.1/core/json/src/main/scala/net/liftweb/json/JsonAST.scala#L392
   * What we added:
   *   - add a new line after each element in array
   *   - Add a new line at the end and beginning of an array and indent one more level array data
   *   - space after colon
   */
  def render(value: JValue): Document = {

    import scala.text.{Document, DocText}
    import scala.text.Document._




    // Helper functions, needed but private in JSONAst,
    // That one modified, add a bref after the punctuate
    def series(docs: List[Document]) = punctuate(text(",") :: break, docs)

    // no modification here
    def trimArr(xs: List[JValue]) = xs.filter(_ != JNothing)
    def trimObj(xs: List[JField]) = xs.filter(_.value != JNothing)
    def fields(docs: List[Document]) = punctuate(text(",") :: break, docs)

    // Indentation changed
    def punctuate(p: Document, docs: List[Document]): Document = {
      if (docs.length == 0) {
        empty
      } else {
        docs.reduceLeft((d1, d2) => d1 :: p :: d2)
      }
    }

    def quote(s: String): String = {
      val buf = new StringBuilder
      appendEscapedString(buf, s)
      buf.toString
    }

    def appendEscapedString(buf: StringBuilder, s: String) {
      for (i <- 0 until s.length) {
        val c = s.charAt(i)
        buf.append(c match {
          case '"'  => "\\\""
          case '\\' => "\\\\"
          case '\b' => "\\b"
          case '\f' => "\\f"
          case '\n' => "\\n"
          case '\r' => "\\r"
          case '\t' => "\\t"
          case c if ((c >= '\u0000' && c < '\u0020')) => "\\u%04x".format(c: Int)
          case c => c
        } )
      }
    }

    // The actual render function
    // Fallback to JsonAst.render
    value match {
      case JArray(arr)   =>
        // origin: text("[") :: series(trimArr(arr).map(render)) :: text("]")
        // We want to break after [ and indent one more level
        val nested = break :: series(trimArr(arr).map(render))
        text("[") :: nest(2, nested) :: break :: text("]")
      case JField(n, v)  =>
        // origin : text("\"" + quote(n) + "\":") :: render(v)
        // Just add a space after the colon
        text("\"" + quote(n) + "\": ") :: render(v)
      case JObject(obj)  =>
        // origin:  val nested = break :: fields(trimObj(obj).map(f => text("\"" + quote(f.name) + "\":") :: render(f.value)))
        // Just add a space after the colon
        val nested = break :: fields(trimObj(obj).map(f => text("\"" + quote(f.name) + "\": ") :: render(f.value)))
        text("{") :: nest(2, nested) :: break :: text("}")
      case _ => JsonAST.render(value)
    }
  }

  private[this] def effectiveResponse (id:Option[String], message:JValue, status:HttpStatus, action : String , prettify : Boolean) : LiftResponse = {
    val printer: Document => String = if (prettify) Printer.pretty else Printer.compact
    val json = ( "action" -> action ) ~
                  ( "id"     -> id ) ~
                  ( "result" -> status.status ) ~
                  ( status.container   ->  message )
    val content : JsExp = new JsExp {
      lazy val toJsCmd = printer(render((json)))
    }

    JsonResponse(content,List(),List(), status.code)

  }


  def toJsonResponse(id:Option[String], message:JValue) ( implicit action : String, prettify : Boolean) : LiftResponse = {
    effectiveResponse (id, message, RestOk, action, prettify)
  }

  def toJsonError(id:Option[String], message:JValue)( implicit action : String = "rest", prettify : Boolean) : LiftResponse = {
    effectiveResponse (id, message, RestError, action, prettify)
  }

  def notValidVersionResponse(action:String)(implicit availableVersions : List[ApiVersion]) = {
    val versions = "latest" :: availableVersions.map(_.value.toString)
    toJsonError(None, JString(s"Version used does not exists, please use one of the following: ${versions.mkString("[ ", ", ", " ]")} "))(action,false)
   }

  def missingResponse(version:Int,action:String) = {
    toJsonError(None, JString(s"Version ${version} exists for this API function, but it's implementation is missing"))(action,false)
   }

  def response (restExtractor : RestExtractorService, dataName: String) (function : Box[JValue], req : Req, errorMessage : String) (implicit action : String) : LiftResponse = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
    function match {
      case Full(category : JValue) =>
        toJsonResponse(None, ( dataName -> category))
      case eb: EmptyBox =>
        val message = (eb ?~! errorMessage).messageChain
        toJsonError(None, message)
    }
  }

  def actionResponse (restExtractor : RestExtractorService, dataName: String, uuidGen: StringUuidGenerator) (function : (EventActor, ModificationId, Option[String]) => Box[JValue], req : Req, errorMessage : String)(implicit action : String) : LiftResponse = {
    implicit val prettify = restExtractor.extractPrettify(req.params)

    ( for {
      reason <- restExtractor.extractReason(req.params)
      modId = ModificationId(uuidGen.newUuid)
      actor = RestUtils.getActor(req)
      categories <- function(actor,modId,reason)
    } yield {
      categories
    } ) match {
      case Full(categories : JValue) =>
        toJsonResponse(None, ( "groupCategories" -> categories))
      case eb: EmptyBox =>
        val message = (eb ?~! errorMessage).messageChain
        toJsonError(None, message)
    }
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