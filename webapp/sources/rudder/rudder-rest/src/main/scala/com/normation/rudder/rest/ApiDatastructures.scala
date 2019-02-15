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

package com.normation.rudder.rest

import cats.implicits._
import cats.data._
import com.normation.rudder.api.HttpAction


/*
 * This file defined data structures describing what is an (abstract) API in Rudder,
 * how it is connected to endpoints (actual path in URLs), and what is the execution
 * logic which allows to find and execute an API business logic when a request comes.
 *
 * The first goal of that separation is to be able to:
 * - have the list of all abstract APIs in a single list, usable for enumeration
 *   (typically, self-documentation, list of all APIs for authz, etc)
 *
 * - have a common logic to automatically map an (abstract) API (i.e GET /nodes) to several
 *   endpoints (i.e /secure/api/v7/nodes, /api/latest/nodes, ...)
 *
 * - to be able to have different authorization rules based on the actual requested endpoint
 *
 * - to be able to have SEVERAL interpretors for the API logic (typically, one with the
 *   actual Rudder business logic, but also one that only prints things for debugging, and
 *   one does simple things for testing)
 *
 *
 * More preciselly:
 * - "EndpointSchema" is the abstract API with the HTTP request type, the canonical path, and
 *   the range of supported versions.
 *
 * - "ApiModule" is the pair of an EndpointSchema coupled with its handler function (what to
 *   do to process a request and give a response)
 *
 * - "Endpoint" is the materialisation of a connected EndpointSchema to a specific (path, version)
 *
 * - "BuildHandler" is the actual Rudder logic about how a request is processed (how to find
 *   the actual API from the path/version, when authz is done, etc).
 */


//Api supported range version
sealed trait ApiV
object ApiV {
  case object OnlyLatest                 extends ApiV
  case class  From(from: Int           ) extends ApiV
  case class  FromTo(from: Int, to: Int) extends ApiV //from must be <= to
}

/*
 * In Rudder, we have several kind of API:
 * - successly public API, which can't be accessed from /secure/api path (extremely rare)
 * - successly internal API, which can ONLY be accessed from /secure/api (typically UI related API)
 * - mixed API, which can be accessed by both path.
 *
 * The main difference is on authorization, where public API need an API Token, and internal
 * API need an authenticated user in session.
 */
sealed trait ApiKind { def name: String }
object ApiKind {
  final case object Internal extends ApiKind { val name = "internal" }
  final case object Public   extends ApiKind { val name = "public"   }
  final case object General  extends ApiKind { val name = "general"  }
}

/*
 * The API path, i.e the relative part specific to that endpoint
 * We differentiate on two kind of item : string part (normal path of the URI),
 * and variables (i.e part that are used to identify some resources)
 */
sealed trait ApiPathSegment { def value: String }
object ApiPathSegment {
  // a variable used to identify a resources
  final case class Resource(value: String) extends ApiPathSegment
  final case class Segment (value: String) extends ApiPathSegment
}

final case class ApiPath(parts: NonEmptyList[ApiPathSegment]) {
  //canonical representation: variable in {}
  override def toString() = parts.map { p => p match {
    case ApiPathSegment.Resource(v) => "{"+v+"}"
    case ApiPathSegment.Segment (v) => v
  } }.toList.mkString("/") //no string at the end

  def value = toString()

  // create a new path by adding a subpath at the end
  def /(p: ApiPath): ApiPath = ApiPath(parts.concatNel(p.parts))
  def /(s: String ): ApiPath = /(ApiPath.of(s))

  def drop(prefix: ApiPath): Either[String, ApiPath] = {
    def dropRec(p: List[ApiPathSegment], path: List[ApiPathSegment]): Either[String, ApiPath] = {
      (p, path) match {
        case (h1 :: Nil, h2 :: g :: t) if(h1 == h2) => Right(ApiPath(NonEmptyList(g, t)))
        case (h1 :: t1 , h2 :: t2) => dropRec(t1, t2)
        case _ => Left(s"Path '${prefix.value}' is not a prefix of path '${this.value}'")
      }
    }
    dropRec(prefix.parts.toList, this.parts.toList)
  }
}

object ApiPath {
  def toPathElement(s: String): ApiPathSegment = {
    if(s.startsWith("{") && s.endsWith("}") ) {
      ApiPathSegment.Resource(s.substring(1, s.size-1))
    } else {
      ApiPathSegment.Segment(s)
    }
  }
  def of(head: String, tail: String*) = {
    ApiPath(NonEmptyList(toPathElement(head), tail.map(toPathElement).toList))
  }
  // parse a path to an api path.
  // we don't accept empty string and ignore empty subpart, but appart
  // from that everything works
  def parse(path: String): Either[String, ApiPath] = {
    path.trim.split("/").filter( _.size > 0).toList match {
      case Nil    =>
        Left("The given is empty, it can't be a Rudder API path")
      case h :: t =>
        Right(ApiPath.of(h, t:_*))
    }
  }

  // check if two path are compatible.
  // Path are compatible when all subpath elements are equals, and when they are of the same size
  // (resource elements act as joker
  def compatible(p1: ApiPath, p2: ApiPath): Boolean = {
    import ApiPathSegment._
    (p1.parts.size == p2.parts.size) && (p1.parts.toList, p2.parts.toList).zipped.forall {
      case (Segment(a), Segment(b)) => a == b
      case (Resource(_), _) => true
      case (_, Resource(_)) => true
    }
  }
}

/**
 * A bag of endpoints schema is provided by an ApiModuleProvider
 */
trait ApiModuleProvider[A <: EndpointSchema] {
  // the list of endpoints for that module
  def endpoints: List[A]

  // specific authorization required for accesing path in that module
  // a default is provided that tells "only admin can access it"
  def authorizationApiMapping: AuthorizationApiMapping = AuthorizationApiMapping.OnlyAdmin
}

/**
 * The actual definition of what is an endpoint schema.
 */
trait EndpointSchema {

  // canonical path of that api
  def path: ApiPath
  // the unique action for that end point. One
  // endpoint schema can have only one action.
  def action: HttpAction
  // a name for the API. Can be used in answer to notice
  // the user, or to classify endpoints.
  // IT IS NOT AN IDENTIFIER. The only identifier is the
  // ACTION+PATH couple.
  // typically, several version of the same API will have the
  // same name, but not the same path.
  def name: String = {
    val n = this.getClass.getSimpleName()
    n(0).toLower + n.substring(1, n.size-1) // also remove the last '$'
  }

  // the kind of API
  def kind: ApiKind

  // an human readable description of the API
  def description: String

  // first version where that endpoint was available

  // we should be able to tell "only latest", for ex. for modules.
  def versions: ApiV

  //the type of parameter parsed from Path: Unit, (String), (String, Int), etc
  type RESOURCES
  def getResources(path: ApiPath): Either[ApiError.BadParam, RESOURCES]
}


trait EndpointSchema0 extends EndpointSchema {
  type RESOURCES = Unit
}

object EndpointSchema {
  object syntax {
    // syntaxt to build endpoints
    implicit class BuildPath(action: HttpAction) {
      def /(s: String) = (action, ApiPath.of(s))
    }
    implicit class AddPath(pair: (HttpAction, ApiPath)) {
      def /(s: String) = (pair._1, pair._2 / s)
      def /(path: ApiPath) = (pair._1, ApiPath(pair._2.parts.concatNel(path.parts)))
    }
  }
}

// utility extension trait to define "version from N to latest"
trait StartsAtVersion2  extends EndpointSchema { val versions = ApiV.From(2 ) }
trait StartsAtVersion3  extends EndpointSchema { val versions = ApiV.From(3 ) }
trait StartsAtVersion4  extends EndpointSchema { val versions = ApiV.From(4 ) }
trait StartsAtVersion5  extends EndpointSchema { val versions = ApiV.From(5 ) }
trait StartsAtVersion6  extends EndpointSchema { val versions = ApiV.From(6 ) }
trait StartsAtVersion7  extends EndpointSchema { val versions = ApiV.From(7 ) }
trait StartsAtVersion8  extends EndpointSchema { val versions = ApiV.From(8 ) }
trait StartsAtVersion9  extends EndpointSchema { val versions = ApiV.From(9 ) }
trait StartsAtVersion10 extends EndpointSchema { val versions = ApiV.From(10) }
trait StartsAtVersion11 extends EndpointSchema { val versions = ApiV.From(11) }
trait StartsAtVersion12 extends EndpointSchema { val versions = ApiV.From(12) }
trait StartsAtVersion13 extends EndpointSchema { val versions = ApiV.From(13) }
trait StartsAtVersion14 extends EndpointSchema { val versions = ApiV.From(14) }
trait StartsAtVersion15 extends EndpointSchema { val versions = ApiV.From(15) }

// utility extension trait to define the kind of API
trait PublicApi   extends EndpointSchema { val kind = ApiKind.Public   }
trait InternalApi extends EndpointSchema { val kind = ApiKind.Internal }
trait GeneralApi  extends EndpointSchema { val kind = ApiKind.General  }

// An utility that compare a schema path and a provided one, and returns
// exactly the number of asked elements.
// It returns nothing if schema path has more Resource element than
// asked for, nor if any sub path element from schema is not exactly
// the same in destination.
trait PathMatcher[T] {
  def compare(schema: List[ApiPathSegment], path: List[ApiPathSegment]): Option[T]
}

object PathMatcher {
  final object Zero extends PathMatcher[Unit] {
    def compare(schema: List[ApiPathSegment], path: List[ApiPathSegment]): Option[Unit] = {
      (schema, path) match {
        case (Nil, Nil) => Some(())
        case (ApiPathSegment.Segment(a) :: t1, ApiPathSegment.Segment(b) :: t2) if(a == b) => compare(t1, t2)
        case _ => None
      }
    }
  }

  final object One extends PathMatcher[String] {
    def compare(schema: List[ApiPathSegment], path: List[ApiPathSegment]): Option[String] = {
      (schema, path) match {
        case (Nil, Nil) => None // where would be my free variable value, hum?
        case (ApiPathSegment.Segment(a)  :: t1, ApiPathSegment.Segment(b) :: t2) if(a == b) => compare(t1, t2)
        case (ApiPathSegment.Resource(_) :: t1, ApiPathSegment.Segment(b) :: t2) => Zero.compare(t1, t2).map( _ => b)
        case _ => None
      }
    }
  }

  final object Two extends PathMatcher[(String, String)] {
    def compare(schema: List[ApiPathSegment], path: List[ApiPathSegment]): Option[(String, String)] = {
      (schema, path) match {
        case (Nil, Nil) => None // where would be my free variable value, hum?
        case (ApiPathSegment.Segment(a)  :: t1, ApiPathSegment.Segment(b) :: t2) if(a == b) => compare(t1, t2)
        case (ApiPathSegment.Resource(_) :: t1, ApiPathSegment.Segment(b) :: t2) => One.compare(t1, t2).map(c => (b, c))
        case _ => None
      }
    }
  }
}

trait ZeroParam extends EndpointSchema0 {
  override def getResources(p: ApiPath) : Either[ApiError.BadParam, Unit] = {
    PathMatcher.Zero.compare(path.parts.toList, p.parts.toList) match {
      case None => Left(ApiError.BadParam("Endpoint scheme define more resource parameters than expected", this.name))
      case Some(()) => Right(())
    }
  }
}
trait OneParam extends EndpointSchema {
  type RESOURCES = String
  override def getResources(p: ApiPath) : Either[ApiError.BadParam, String] =  {
    PathMatcher.One.compare(path.parts.toList, p.parts.toList) match {
      case None => Left(ApiError.BadParam("Endpoint scheme define more resource parameters than expected", this.name))
      case Some(x) => Right(x)
    }
  }
}
trait TwoParam extends EndpointSchema {
  type RESOURCES = (String, String)
  override def getResources(p: ApiPath) : Either[ApiError.BadParam, (String, String)] =  {
    PathMatcher.Two.compare(path.parts.toList, p.parts.toList) match {
      case None => Left(ApiError.BadParam("Endpoint scheme define more resource parameters than expected", this.name))
      case Some(x) => Right(x)
    }
  }
}

//////////
/// Now for the Rudder API logic (with abstract REQ / RESPONSE)
//////////



/**
 * This algbra describe how an endpoint maybe serve a request.
 * It maybe serve because we need to check if the endpoint does handle a request before serving it.
 */

//////////
////////// Data structures
//////////

// Generic errors that may happen when processing requests
sealed trait ApiError extends Exception {
  def msg    : String
  def apiName: String
}
object ApiError {
  final case class Authz     (msg: String, apiName: String) extends ApiError
  final case class BadRequest(msg: String, apiName: String) extends ApiError
  final case class BadParam  (msg: String, apiName: String) extends ApiError
}

// information needed from a request to be able to process it
final case class RequestInfo(
    action          : HttpAction
  , path            : ApiPath
    // this the version from a specified parameter, not
    // one deducted from path
  , versionFromParam: Option[ApiVersion]
)

/**
 * This is the description of an endpoint connected to a path for a given version.
 * - prefix: the prefix path into which the endpoint is connected
 * - version: the actual version of the api requested
 */
final case class Endpoint(schema: EndpointSchema, prefix: ApiPath, version: ApiVersion)

trait ApiModule[REQ, RESP, T, P] {
  val schema: EndpointSchema
  def getParam(req: REQ): Either[ApiError.BadParam, P]
  def handler(version: ApiVersion, path: ApiPath, resources: schema.RESOURCES, req: REQ, params: P, authzToken: T): RESP
}

trait ApiModule0[REQ, RESP, T, P] extends ApiModule[REQ, RESP, T, P] {
  val schema: EndpointSchema0
  def handler0(version: ApiVersion, path: ApiPath, req: REQ, params: P, authzToken: T): RESP
  override def handler(version: ApiVersion, path: ApiPath, resources: Unit, req: REQ, params: P, authzToken: T): RESP = {
    handler0(version, path, req, params, authzToken)
  }
}

//////////
//////////  Third party services needed for API processing logic
//////////

// ApiAuthorization is defined in its own file

/*
 * This trait allows to bind an endpoint (i.e: some logic) to several path.
 */
trait ConnectEndpoint {
  //bind path to endpoint (an endpoint logic can be served by several urls)
  def withVersion(endpoint: EndpointSchema, supportedVersions: List[ApiVersion]): List[Endpoint]
}

/*
 * A logger
 */
trait Log {
  def trace(msg: => String): Unit
  def debug(msg: => String): Unit
  def info (msg: => String): Unit
  def warn (msg: => String): Unit
  def error(msg: => String): Unit
}

class RudderEndpointDispatcher(logger: Log) extends ConnectEndpoint {
  /*
   * In Rudder, by default we distribute on:
   * == public API ==
   * - /api/version/endpoint (for each supported version of the API)
   * - /api/latest/endpoint (if most recent supported version is a provided version)
   * - /api/endpoint (version in request header)
   * == internal ==
   *   On secure, we only make available the latest version on a shorter path:
   * - /secure/api/endpoint
   */
  val publicBase   = ApiPath.of("api")
  val internalBase = ApiPath.of("secure", "api")


  // from a base path and versions, builed "base/v1", "base/v2", "...", "base/latest"
  // return the couple (version, path), because we need the actual version in Endpoint
  def prefix(base: ApiPath, versions: List[ApiVersion], latest: ApiVersion): List[(ApiVersion, ApiPath)] = {
    versions.flatMap { v =>
      (v, base / v.value.toString) :: (if(v == latest) (v, base / "latest") :: Nil else Nil)
    }
  }

  /*
   * For a list of supported versions and an API version interval, build the list of
   * version for that API
   * return (include latest, set of defined version)
   */
  def definedVersion(endointVersions: ApiV, currentApiVersions: NonEmptyList[ApiVersion]): (List[ApiVersion], ApiVersion) = {
    //at least one version in supported API, so we know which is "latest"
    val versions = currentApiVersions.sortBy( _.value )
    val latest = versions.last
    // build the list of version for that API.
    val apiVersions = endointVersions match {
        case ApiV.OnlyLatest   => List(latest)
        case ApiV.From(i)      => versions.toList.dropWhile( v => v.value < i) //latest is in that set if first <= latest
        case ApiV.FromTo(i, j) => versions.toList.dropWhile( v => v.value < i).takeWhile(v => v.value <= j)
    }
    (apiVersions, latest)
  }

  def withVersion(endpointSchema: EndpointSchema, supportedVersions: List[ApiVersion]): List[Endpoint] = {
    supportedVersions match {
      case Nil    => Nil
      case h :: t =>

        val (versions, latest) = definedVersion(endpointSchema.versions, NonEmptyList(h, t))
        val publicEndpoints = endpointSchema.kind match {
          case ApiKind.Internal => Nil //don't add these endpoints for internal only API
          case _                =>

            // path with version in it
            val versionnedPath =  prefix(publicBase, versions, latest).map { case (version, path) =>
              //for each supported path, bind the endpoint schema
              Endpoint(endpointSchema, path, version)
            }
            // the "include all" path - bind it one time for each version
            val headerPath = versions.map { version =>
              Endpoint(endpointSchema, publicBase, version)
            }
            versionnedPath ::: headerPath
          }

          val internalEndpoint = endpointSchema.kind match {
            case ApiKind.Public => Nil // don't add these endpoints for public only API
            case _              =>
              List(Endpoint(endpointSchema, internalBase, latest))
          }

          val endpoints = internalEndpoint ::: publicEndpoints

          logger.trace(s"Connecting '${endpointSchema.name}', request type '${endpointSchema.action.name.toUpperCase()}' "+
                       s"to URLs: ${endpoints.map(e => s"[v${e.version.value}] ${(e.prefix / endpointSchema.path).value}").mkString("; ")}")
          endpoints
    }
  }
}


/*
 * We want to defined the application that allows, for a given ApiModule and a given
 * request, to optionnally give a response (if the module can handle the request).
 * When handled, we can
 * REQ: request
 * RESP: response
 * T: authentication token
 * P: extracted parameters from request
 */
trait BuildHandler[REQ, RESP, T, P] {
  // external service nneeded
  def authz            : ApiAuthorization[T]
  def connectEndpoint  : ConnectEndpoint
  def supportedVersions: List[ApiVersion]
  def logger           : Log

  def apis(): List[ApiModule[REQ, RESP, T, P]]

  // logic depending from REQ et RESP types

  // from a request, we need to be able to extract a path and action.
  // we also try to collect the version from header. If we don't have it,
  // we will need to get it from path.
  def getRequestInfo(req: REQ, supportedVersions: List[ApiVersion]): Either[ApiError, RequestInfo]
  def toResponse(error: ApiError): RESP

  // an utility method used to log request information
  def logReq(req: REQ): String


  // discriminate endpoint based on path / action / version
  // Here, we must not considere the missing endpoint as an error, as perhaps some other endpoint will
  // handle it. It must be different from a real error (version missing or mismatch, etc)
  def findEndpoint(endpoints: List[Endpoint], info: RequestInfo): Either[ApiError, Option[Endpoint]] = {
    //find endpoints for that request type and path (can have 0 => not a managed endpoint by that api,
    //1 => most likely ok (but double check version), N > 1 => discriminate on version
    val collected = endpoints.flatMap { case x@Endpoint(e, p, v) =>
      def msg = s"Check handle: ${e.action} == ${info.action} && version = ${v.value} && compatible(${info.path} , ${p / e.path}) =>"

      if(e.action == info.action && ApiPath.compatible(info.path, (p / e.path))) {
        logger.trace( msg + " YES")
        Some(x)
      } else {
        logger.trace( msg + " NO ")
        None
      }

    }

    logger.trace(s"Potential candidate: [${collected.mkString("; ")}]")
    collected match {
      case Nil => // that api doesn't manage it
        Right(None)
      case e :: Nil => // one candidate, double check version
        info.versionFromParam match {
          case None => //ok
            logger.trace(s"Found endpoint for handling: ${e}")
            Right(Some(e))
          case Some(v) => // check that the requested version matches the provided one
            if(v == e.version) {
              logger.trace(s"Found endpoint for handling: ${e}")
              Right(Some(e))
            } else {
              logger.debug(s"Candidate endpoint ${e} has non compatible version, required ${v.value}")
              Left(ApiError.BadRequest(s"Request is requiring version '${v.value}' but the available endpoints only provide version '${e.version.value}'", e.schema.name))
            }
        }
      case es =>
        // in that case, we beed to have the version provided in request parameters
        info.versionFromParam match {
          case None =>
            logger.debug(s"Several candidate endpoints, but version required in request parameter for them and it is missing")
            Left(ApiError.BadRequest(s"Request is missing a version but the version is required for '${info.action.name.toUpperCase()} ${info.path.value}'. "+
                s"Available versions: ${es.map( _.version.value).mkString("'","'","'")}", "unknow"))
          case Some(v) =>
            es.find( _.version == v) match {
              case None =>
                logger.debug(s"Several candidate endpoints, but none with the one provided in request parameter")
                Left(ApiError.BadRequest(s"Request is requiring version '${v.value}' but that version is not available for '${info.action.name.toUpperCase()} ${info.path.value}'. "+
                    s"Available versions: ${es.map( _.version.value).mkString("'","'","'")}", "unknow"))
              case Some(e) => // finally!
                logger.trace(s"Found endpoint for handling: ${e}")
                Right(Some(e))
            }
        }
    }
  }


  /*
   * This is the actual application with the handling logic for and api:
   * - from an abstract schema,
   * - connect the logic to all endpoint path,
   * - parse request for needed information,
   * - check if the api manage that request,
   * - check rights,
   * - get resources ID from path
   * - actually do the logic to create the response.
   */

  // build the handler: given a request and an endpoint, create the response if the request is
  // handled by the response (else return none to let someone else process it)
  // at that point, no path/version check have to be done, we know we
  // are on the correct ones. It should appear in type.

  // Also, notice the evaluation pattern: anything not after the "() =>" can and WILL
  // be evaluated multiple time for each request, because they will be part of the left hand side
  // of pattern matching of partial function, and Scala does what it want with them.

  def buildApi(): List[REQ => Option[() => RESP]] = {
    apis.map { api =>
      val endpoints = connectEndpoint.withVersion(api.schema, supportedVersions)
      // build the handlers for a given request
      // BUT be careful, the first part UNTIL the `yield { optEndpoint match...`
      // is evaluated FOR ALL REQUEST coming to Rudder. So be efficient here.
      // When all Rudder API will be normalized, we will be able to simplify the test
      // to know if we should handle the request to simply ("does uri starts with 'api' or 'secure/api')
      // and if so handle the request (even if it's to fail with an error "no endpoint can handle that api request")
      (req: REQ) =>
        (for {
          //try to get interesting info
          info        <- getRequestInfo(req, supportedVersions)
          _           =  logger.trace(s"Check if API '${api.schema.name}' has endpoints for '${info.action.name.toUpperCase()} ${info.path.value}'")
          // find the matching endpoint
          optEndpoint <- findEndpoint(endpoints, info)

        } yield {

          ///// END OF EVAL FOR ALL WEBAPP REQUESTS /////

          //handle the optionnality and so the fact that API may not handle that path
          optEndpoint match {
            case None           => Option.empty[() => RESP]
            case Some(endpoint) => Some(() => {
              //here we are allowed to do time-consuming side effects
              // we do handle that request ! Now for actual handling;
              // in all case an response, even if an error (so Some(() => Full(...)))
              logger.debug(s"Processing request: ${logReq(req)}")
              logger.debug(s"Found a valid endpoint handler: '${endpoint.schema.name}' on [${endpoint.schema.action.name.toUpperCase} ${endpoint.schema.path}] with version '${endpoint.version.value}'")
              val response: RESP = (for {
                token         <- authz.checkAuthz(endpoint, info.path).leftMap { error =>
                                   logger.error(s"Authorization error for '${info.action.name.toUpperCase()} ${info.path.value}': ${error.msg}")
                                   error
                                 }
                // we know from the find that we can drop prefix from path, but well...
                canonicalPath <- info.path.drop(endpoint.prefix).leftMap(msg => ApiError.BadRequest(msg, endpoint.schema.name))
                //extracts params from path
                resources     <- api.schema.getResources(canonicalPath).leftMap { error =>
                                   logger.error(s"Error when extracting request path resources from '${info.action.name.toUpperCase()} ${info.path.value}': ${error.msg}")
                                   error
                                 }
                // extract modul parameters from request
                params        <- api.getParam(req).leftMap { error =>
                                   logger.error(s"Error when extracting request parameters from '${info.action.name.toUpperCase()} ${info.path.value}': ${error.msg}")
                                   error
                                 }
                // here, we would like to have a an abstraction of the parameter to give to handler, so that
                // we don't actually have to give REQ to handler. It would require to parameter ApiModule
                // notice that it may be hard, because it could depend of the version to get the correct REQ parameter
                // Next iteration !
              } yield {
                // congrats!
                // we always return information to the user, even if something failed
                // when the response is created.

                val start = System.currentTimeMillis()
                logger.trace(s"Executing handler for '${info.action.name.toUpperCase()} ${info.path.value}'")
                val exec = api.handler(endpoint.version, info.path, resources, req, params, token)
                logger.debug(s"Handler for '${info.action.name.toUpperCase()} ${info.path.value}' executed in ${System.currentTimeMillis() - start} ms")
                exec
              }).fold(error => toResponse(error), identity) // align left (i.e error) and righ type

              response
            })
          }
        }).fold(error => Some(() => toResponse(error)), identity) // align left (i.e error) and righ type
    }
  }
}


