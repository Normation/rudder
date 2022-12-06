/*
 *************************************************************************************
 * Copyright 2017 Normation SAS
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

import cats.data._
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.api.HttpAction
import com.normation.rudder.rest._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.http.rest.RestHelper
import net.liftweb.json.JsonAST.JString
import org.slf4j.LoggerFactory
import scala.util.control.NonFatal

final case class DefaultParams(
    prettify:                 Boolean,
    reason:                   Option[String],
    changeRequestName:        Option[String],
    changeRequestDescription: Option[String]
)

trait LiftApiModule extends ApiModule[Req, Full[LiftResponse], AuthzToken, DefaultParams] {
  override def handler(
      version:    ApiVersion,
      path:       ApiPath,
      resources:  schema.RESOURCES,
      req:        Req,
      params:     DefaultParams,
      authzToken: AuthzToken
  ): Full[LiftResponse] = {
    Full(process(version, path, resources, req, params, authzToken))
  }
  override def getParam(req: Req): Either[ApiError.BadParam, DefaultParams] = {
    // this can fail because params try to decode things as string always, which fails with binary data
    try {
      // prettify is always given in the param list
      val defaultPrettify = false
      val prettify        = req.params.get("pretiffy") match {
        case None               => defaultPrettify
        case Some(value :: Nil) =>
          value.toLowerCase match {
            case "true"  => true
            case "false" => false
            case _       => defaultPrettify
          }
        case _                  => defaultPrettify
      }
      def get(name: String): Option[String] = req.params.get(name).flatMap(_.headOption)

      Right(DefaultParams(prettify, get("reason"), get("changeRequestName"), get("changeRequestDescription")))
    } catch {
      case NonFatal(ex) => Left(ApiError.BadParam(s"Error when trying to read request parameters: ${ex.getMessage}", schema.name))
    }
  }

  // As in our case, we always return Full, we are adding that method to simplify plombing
  def process(
      version:    ApiVersion,
      path:       ApiPath,
      resources:  schema.RESOURCES,
      req:        Req,
      params:     DefaultParams,
      authzToken: AuthzToken
  ): LiftResponse
}

trait LiftApiModule0 extends ApiModule0[Req, Full[LiftResponse], AuthzToken, DefaultParams] with LiftApiModule {
  def process(
      version:    ApiVersion,
      path:       ApiPath,
      resources:  Unit,
      req:        Req,
      params:     DefaultParams,
      authzToken: AuthzToken
  ): LiftResponse = {
    process0(version, path, req, params, authzToken)
  }
  def handler0(
      version:    ApiVersion,
      path:       ApiPath,
      req:        Req,
      params:     DefaultParams,
      authzToken: AuthzToken
  ): Full[LiftResponse] = {
    Full(process0(version, path, req, params, authzToken))
  }
  def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse
}

/*
 * A LiftApiModuleProvider is just a class providing LiftModules
 */
trait LiftApiModuleProvider[A <: EndpointSchema] {
  def schemas:            ApiModuleProvider[A]
  def getLiftEndpoints(): List[LiftApiModule]

  /*
   * Helps debugging bad mapping between a shema and its implementation.
   * Will throw an exception at boot time in a schema / implem mismatch.
   * In case you don't get it, look for the corresponding implementation how the
   * API / implem is done, or if the "def schema = ... " in implem is correct
   */
  {
    val ss    = getLiftEndpoints().map(_.schema).toSet
    val names = ss.map(_.name)
    assert(
      schemas.endpoints.forall(s => ss.exists(_ == s)),
      s"Programming error: an API schema misses definition: [${schemas.endpoints.collect {
          case s if (!names.contains(s.name)) => s.name
        }.mkString(",")}]"
    )
  }
}

object LiftApiProcessingLogger extends Log {
  protected def _logger = LoggerFactory.getLogger("api-processing")
  def trace(msg: => String): Unit = _logger.trace(msg)
  def debug(msg: => String): Unit = _logger.debug(msg)
  def info(msg: => String):  Unit = _logger.info(msg)
  def warn(msg: => String):  Unit = _logger.warn(msg)
  def error(msg: => String): Unit = _logger.error(msg)
}

class LiftHandler(
    val connectEndpoint:   ConnectEndpoint,
    val supportedVersions: List[ApiVersion],
    val authz:             ApiAuthorization[AuthzToken],
    val forceVersion:      Option[ApiVersion] // always behave as if that version is passed in header parameter
) extends BuildHandler[Req, Full[LiftResponse], AuthzToken, DefaultParams] {

  val logger = LiftApiProcessingLogger

  private[this] var _apis = List.empty[LiftApiModule]
  def apis()              = _apis
  def addModule(module: LiftApiModule):         Unit = {
    _apis = _apis :+ module
  }
  def addModules(modules: List[LiftApiModule]): Unit = {
    _apis = _apis ::: modules
  }

  def logReq(req: Req): String = {
    val printJson = {
      if (req.json_?) {
        req.json match {
          case eb: EmptyBox => "JSON request with NOT VALID JSON body"
          case _ => "JSON request with valid JSON body"
        }
      } else if (req.xml_?) {
        "XML request type"
      } else {
        "simple (non-JSON) request"
      }
    }

    s"${req.requestType.method} ${req.contextPath}${req.uri} [${printJson}]"
  }

  def logBody(req: Req): String = {
    // this can fail if the body is binary
    try {
      req.body match {
        case Empty => "[request body is empty]"
        case f: Failure => (f ?~! "Error geting request body:").messageChain
        case Full(body) => new String(body.take(1024), "UTF-8") + (if (body.size > 1024) "..." else "")
      }
    } catch {
      case NonFatal(ex) =>
        logger.warn(s"Error when trying to read request body: ${ex.getMessage}")
        s"{request body can't be displayed (${ex.getMessage})}"
    }
  }

  def getRequestInfo(req: Req, supportedVersions: List[ApiVersion]): Either[ApiError, RequestInfo] = {
    def getAction() = {
      import HttpAction._
      req.requestType match {
        case GetRequest    => Right(GET)
        case PostRequest   => Right(POST)
        case HeadRequest   => Right(HEAD)
        case PutRequest    => Right(PUT)
        case DeleteRequest => Right(DELETE)
        case x             => Left(ApiError.BadRequest(s"API does not support HTTP request type '${x.method}'", "unknown"))
      }
    }

    def getApiPath() = {
      // here, we only use "sub path" kind of PathElement, because
      // we don't know what should be what. There is a bug in Lift decoding of path
      // that changes "+" into " " will it should not, see
      // https://issues.rudder.io/issues/20943
      // https://stackoverflow.com/questions/1634271/url-encoding-the-space-character-or-20/29948396#29948396
      (req.path.partPath.map { e =>
        // change back spaces into "+"
        ApiPathSegment.Segment(e.replaceAll(" ", "+"))
      }) match {
        case h :: t => Right(ApiPath(NonEmptyList(h, t)))
        case _      => Left(ApiError.BadRequest(s"API does not support request on root URL", "unknown"))
      }
    }

    /*
     * This should not provided on all url. It seems very strange to
     */
    def getVersionFromHeader() = {
      forceVersion orElse RestUtils.apiVersionFromRequest(req)(supportedVersions).toOption
    }

    for {
      action <- getAction()
      path   <- getApiPath()
    } yield {
      RequestInfo(action, path, getVersionFromHeader())
    }

  }

  def toResponse(error: ApiError): Full[LiftResponse] = {
    val msg = error match {
      case ApiError.BadRequest(x, _) => "Bad request: " + x
      case ApiError.BadParam(x, _)   => "Bad parameters for request: " + x
      case ApiError.Authz(x, _)      => "Authorization error: " + x
    }
    Full(RestUtils.toJsonError(None, JString(msg))(error.apiName, true))
  }

  // Get the lift object that can be added in lift rooting logic
  def getLiftRestApi(): RestHelper = {
    val handlers = buildApi().map(h => Function.unlift(h))
    val liftApi  = new RestHelper {
      handlers.foreach(h => serve(h))
    }
    liftApi
  }
}

/*
 * some type machinery to be able to have an implementation for old RuleApi up to V13
 * and an other for API with zio_json in V14 (and choose processing based on that)
 */
final case class ChooseApi0[A <: LiftApiModule0](old: A, current: A) extends LiftApiModule0 {
  override val schema = current.schema
  override def process0(
      version:    ApiVersion,
      path:       ApiPath,
      req:        Req,
      params:     DefaultParams,
      authzToken: AuthzToken
  ): LiftResponse = {
    if (version.value < 14) old.process0(version, path, req, params, authzToken)
    else current.process0(version, path, req, params, authzToken)
  }
}
trait LiftApiModuleN[R]                                              extends LiftApiModule  {
  type Aux[a] = EndpointSchema { type RESOURCES = a }
  override val schema: Aux[R]
}

final case class ChooseApiN[R](old: LiftApiModuleN[R], current: LiftApiModuleN[R]) extends LiftApiModule {
  override val schema = old.schema
  override def process(
      version:    ApiVersion,
      path:       ApiPath,
      _resources:  schema.RESOURCES,
      req:        Req,
      params:     DefaultParams,
      authzToken: AuthzToken
  ): LiftResponse = {
    val resources = _resources.asInstanceOf[R] 
    if (version.value < 14) old.process(version, path, resources, req, params, authzToken)
    else current.process(version, path, resources, req, params, authzToken)
  }
}

/*
 * An interface that can be extended by module which are in `ChooseApiN` for String
 */
trait LiftApiModuleString  extends LiftApiModuleN[String]
trait LiftApiModuleString2 extends LiftApiModuleN[(String, String)]

///// end type machinery /////
