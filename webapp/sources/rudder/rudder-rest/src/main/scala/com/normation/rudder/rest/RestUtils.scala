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

import com.normation.rudder.api.ApiVersion
import com.normation.rudder.domain.logger.ApiLogger
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.http.*
import net.liftweb.http.provider.HTTPCookie
import net.liftweb.util.Helpers.tryo
import org.json4s.*
import org.json4s.JsonDSL.*
import org.json4s.other.JsonUtils.*

/*
 * A JsonResponse able to keep pretty format is asked to.
 * It's basically a copy/post of lift JsonResponse
 */
case class JsonResponsePrettify(
    json:     JValue,
    headers:  List[(String, String)],
    cookies:  List[HTTPCookie],
    code:     Int,
    prettify: Boolean
) extends LiftResponse {
  def toResponse: InMemoryResponse = {
    val bytes = (if (prettify) json.prettyRender else json.compactRender).getBytes("UTF-8")
    InMemoryResponse(
      bytes,
      ("Content-Length", bytes.length.toString) :: ("Content-Type", "application/json; charset=utf-8") :: headers,
      cookies,
      code
    )
  }
}

/**
 */
object RestUtils {
  def getCharset(req: Req): String = {
    // copied from `Req.forcedBodyAsJson`
    def r  = """; *charset=(.*)""".r
    def r2 = """[^=]*$""".r
    req.contentType.flatMap(ct => r.findFirstIn(ct).flatMap(r2.findFirstIn)).getOrElse("UTF-8")
  }

  def apiVersionFromRequest(req: Req)(implicit availableVersions: List[ApiVersion]): Box[ApiVersion] = {

    val latest = availableVersions.maxBy(_.value)
    def fromString(version: String): Box[ApiVersion] = {
      version match {
        case "latest" => Full(latest)
        case value    =>
          tryo(value.toInt) match {
            case Full(version_) =>
              availableVersions.find(_.value == version_) match {
                case Some(apiVersion) => Full(apiVersion)
                case None             => Failure(s" ${version_} is not a valid api version")
              }
            // Never empty due to tryo
            case eb: EmptyBox => eb
          }
      }
    }

    req.header("X-API-VERSION") match {
      case Full(value) => fromString(value)
      case eb: EmptyBox => eb ?~ ("Error when getting header X-API-VERSION")
    }
  }

  def getPrettify(req: Req): Box[Boolean] = {
    req.json match {
      case Full(json) =>
        json \ "prettify" match {
          case JBool(prettify) => Full(prettify)
          case JNothing        => Full(false)
          case x               => Failure(s"Not a valid value for 'prettify' parameter, current value is : ${x}")
        }
      case _          =>
        req.params.get("prettify") match {
          case None                 => Full(false)
          case Some("true" :: Nil)  => Full(true)
          case Some("false" :: Nil) => Full(false)
          case _                    => Failure("Prettify should only have one value, and should be set to true or false")
        }
    }
  }

  def effectiveResponse(
      id:       Option[String],
      message:  JValue,
      status:   HttpStatus,
      action:   String,
      prettify: Boolean
  ): LiftResponse = {
    status match {
      case _: RestError =>
        // Log any error
        ApiLogger.ResponseError.info(message.compactRender)
      case _ => // Do nothing
    }
    val json = ("action" -> action) ~
      ("id"             -> id) ~
      ("result"         -> status.status) ~
      (status.container -> message)

    JsonResponsePrettify(json, List(), List(), status.code, prettify)
  }

  def toJsonResponse(id: Option[String], message: JValue)(implicit action: String, prettify: Boolean): LiftResponse = {
    effectiveResponse(id, message, RestOk, action, prettify)
  }

  def toJsonError(id: Option[String], message: JValue, error: RestError = InternalError)(implicit
      action:   String = "rest",
      prettify: Boolean
  ): LiftResponse = {
    effectiveResponse(id, message, error, action, prettify)
  }

}

trait HttpStatus {
  def code:      Int
  def status:    String
  def container: String
}

object RestOk extends HttpStatus {
  val code      = 200
  val status    = "success"
  val container = "data"
}

trait RestError extends HttpStatus {
  val status    = "error"
  val container = "errorDetails"
}

object InternalError extends RestError {
  val code = 500
}

object NotFoundError  extends RestError {
  val code = 404
}
object ForbiddenError extends RestError {
  val code = 403
}
