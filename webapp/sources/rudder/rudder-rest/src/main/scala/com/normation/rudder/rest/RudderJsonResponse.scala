/*
 *************************************************************************************
 * Copyright 2021 Normation SAS
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

import com.normation.errors.*
import com.normation.rudder.domain.logger.ApiLogger
import com.normation.rudder.rest.lift.DefaultParams
import com.normation.zio.*
import net.liftweb.http.InMemoryResponse
import net.liftweb.http.LiftResponse
import scala.collection.immutable
import zio.json.*

/*
 * This class deals with everything serialisation related for API.
 * Change things with care! Everything must be versioned!
 * Even changing a field name can lead to an API incompatible change and
 * so will need a new API version number (and be sure that old behavior is kept
 * for previous versions).
 */

/*
 * Rudder standard response.
 * We normalize response format to look like what is detailed here: https://docs.rudder.io/api/v/13/#section/Introduction/Response-format
 * Data are always name-spaced, so that theoretically an answer can mixe several type of data. For example, for node details:
 *     "data": { "nodes": [ ... list of nodes ... ] }
 * And for globalCompliance:
 *     "data": { "globalCompliance": { ... } }
 */
object RudderJsonResponse {
  //////////////////////////// general structure of a JSON response ////////////////////////////
  // and utilities for specific objects

  final case class JsonRudderApiResponse[A](
      action:       String,
      id:           Option[String],
      result:       String,
      data:         Option[A],
      errorDetails: Option[String]
  )
  object JsonRudderApiResponse {
    def error(id: Option[String], schema: ResponseSchema, errorDetails: Option[String]): JsonRudderApiResponse[Unit] =
      JsonRudderApiResponse(schema.action, id, "error", None, errorDetails)
    def error(id: Option[String], schema: ResponseSchema, message: String):              JsonRudderApiResponse[Unit] =
      JsonRudderApiResponse(schema.action, id, "error", None, Some(message))

    def genericError[A](
        id:           Option[String],
        schema:       ResponseSchema,
        data:         A,
        errorDetails: Option[String]
    ): JsonRudderApiResponse[A] =
      JsonRudderApiResponse(schema.action, id, "error", Some(data), errorDetails)

    def success[A](schema: ResponseSchema, id: Option[String], data: A): JsonRudderApiResponse[A] =
      JsonRudderApiResponse(schema.action, id, "success", Some(data), None)

    def success[A](schema: ResponseSchema, id: Option[String]): JsonRudderApiResponse[A] =
      JsonRudderApiResponse(schema.action, id, "success", None, None)
  }

  //////////////////////////// Lift JSON response ////////////////////////////

  final case class LiftJsonResponse[A](json: A, prettify: Boolean, code: Int)(using encoder: JsonEncoder[A])
      extends LiftResponse {
    def toResponse: InMemoryResponse = {
      // Indent is not the number of space per indentation, but the level of indentation for this elem starts...
      // if we set 2, it will think it is at level 2 already, hence produce 4 four spaces
      val indent = if (prettify) Some(0) else None
      val bytes  = encoder.encodeJson(json, indent).toString.getBytes("UTF-8")
      InMemoryResponse(
        bytes,
        ("Content-Length", bytes.length.toString) :: ("Content-Type", "application/json; charset=utf-8") :: Nil,
        Nil,
        code
      )
    }
  }

  /*
   * Information about schema needed to build response
   */
  final case class ResponseSchema(
      action:        String,
      dataContainer: Option[String]
  )

  object ResponseSchema {
    def fromSchema(schema: EndpointSchema): ResponseSchema = ResponseSchema(schema.name, schema.dataContainer)
  }

  sealed trait ResponseError {
    def errorMsg: Option[String]
    def toLiftErrorResponse(id: Option[String], schema: ResponseSchema)(using
        prettify: Boolean
    ): LiftResponse = this match {
      case UnauthorizedError(errorMsg) => unauthorizedError(id, schema, errorMsg)
      case ForbiddenError(errorMsg)    => forbiddenError(id, schema, errorMsg)
      case NotFoundError(errorMsg)     => notFoundError(id, schema, errorMsg)
    }
  }
  final case class UnauthorizedError(errorMsg: Option[String]) extends ResponseError
  final case class ForbiddenError(errorMsg: Option[String]) extends ResponseError
  final case class NotFoundError(errorMsg: Option[String])  extends ResponseError

  //////////////////////////// utility methods to build responses ////////////////////////////

  object generic {
    // generic response, not in rudder normalized format - use it if you want an ad-hoc json response.
    def success[A](json: A)(using prettify: Boolean, encoder: JsonEncoder[A]):           LiftJsonResponse[A] =
      LiftJsonResponse(json, prettify, 200)
    def internalError[A](json: A)(using prettify: Boolean, encoder: JsonEncoder[A]):     LiftJsonResponse[A] =
      LiftJsonResponse(json, prettify, 500)
    def unauthorizedError[A](json: A)(using prettify: Boolean, encoder: JsonEncoder[A]): LiftJsonResponse[A] =
      LiftJsonResponse(json, prettify, 401)
    def notFoundError[A](json: A)(using prettify: Boolean, encoder: JsonEncoder[A]):     LiftJsonResponse[A] =
      LiftJsonResponse(json, prettify, 404)
    def forbiddenError[A](json: A)(using prettify: Boolean, encoder: JsonEncoder[A]):    LiftJsonResponse[A] =
      LiftJsonResponse(json, prettify, 404)
  }

  trait DataContainer[A] {
    def name: String
    def data: A
  }

  // rudder response. The "A" parameter is the business object (or list of it) in the response.
  // Success
  def successOne[A](schema: ResponseSchema, obj: A, id: Option[String])(using
      prettify: Boolean,
      encoder:  JsonEncoder[A]
  ): LiftJsonResponse[? <: JsonRudderApiResponse[?]] = {
    schema.dataContainer match {
      case Some(key) =>
        given enc: JsonEncoder[JsonRudderApiResponse[Map[String, List[A]]]] = DeriveJsonEncoder.gen
        generic.success(JsonRudderApiResponse.success(schema, id, Map((key, List(obj)))))
      case None      => // in that case, the object is not even in a list
        given enc: JsonEncoder[JsonRudderApiResponse[A]] = DeriveJsonEncoder.gen
        generic.success(JsonRudderApiResponse.success(schema, id, obj))
    }
  }
  def successList[A](schema: ResponseSchema, objs: List[A])(using
      prettify: Boolean,
      encoder:  JsonEncoder[A]
  ): LiftJsonResponse[
    ? <: JsonRudderApiResponse[? <: immutable.Iterable[Any] & PartialFunction[Int & String, Any] & Equals]
  ] = {
    schema.dataContainer match {
      case None      =>
        given enc: JsonEncoder[JsonRudderApiResponse[List[A]]] = DeriveJsonEncoder.gen
        generic.success(JsonRudderApiResponse.success(schema, None, objs))
      case Some(key) =>
        given enc: JsonEncoder[JsonRudderApiResponse[Map[String, List[A]]]] = DeriveJsonEncoder.gen
        generic.success(JsonRudderApiResponse.success(schema, None, Map(key -> objs)))
    }
  }
  def successZero(schema: ResponseSchema)(using
      prettify: Boolean
  ): LiftJsonResponse[JsonRudderApiResponse[String]] = {
    given enc: JsonEncoder[JsonRudderApiResponse[String]] = DeriveJsonEncoder.gen
    generic.success(JsonRudderApiResponse.success(schema, None))
  }
  def successZero(schema: ResponseSchema, msg: String)(using
      prettify: Boolean
  ): LiftJsonResponse[JsonRudderApiResponse[String]] = {
    given enc: JsonEncoder[JsonRudderApiResponse[String]] = DeriveJsonEncoder.gen
    generic.success(JsonRudderApiResponse.success(schema, None, msg))
  }
  // errors
  given nothing:      JsonEncoder[Option[Unit]]                = new JsonEncoder[Option[Unit]] {
    def unsafeEncode(n:       Option[Unit], indent: Option[Int], out: zio.json.internal.Write): Unit = out.write("null")
    override def isNothing(a: Option[Unit]): Boolean = true
  }
  given errorEncoder: JsonEncoder[JsonRudderApiResponse[Unit]] = DeriveJsonEncoder.gen

  def internalError(id: Option[String], schema: ResponseSchema, errorMsg: String)(using
      prettify: Boolean
  ): LiftJsonResponse[JsonRudderApiResponse[Unit]] = {
    generic.internalError(JsonRudderApiResponse.error(id, schema, errorMsg))
  }
  // Internal error with a specific schema
  def internalError[A](id: Option[String], schema: ResponseSchema, obj: A, errorMsg: Option[String])(using
      prettify: Boolean,
      encoder:  JsonEncoder[A]
  ): LiftJsonResponse[? <: JsonRudderApiResponse[?]] = {
    schema.dataContainer match {
      case Some(key) =>
        given enc: JsonEncoder[JsonRudderApiResponse[Map[String, List[A]]]] = DeriveJsonEncoder.gen
        generic.internalError(JsonRudderApiResponse.genericError(id, schema, Map((key, List(obj))), errorMsg))
      case None      => // in that case, the object is not even in a list
        given enc: JsonEncoder[JsonRudderApiResponse[A]] = DeriveJsonEncoder.gen
        generic.internalError(JsonRudderApiResponse.genericError(id, schema, obj, errorMsg))
    }
  }
  def unauthorizedError(id: Option[String], schema: ResponseSchema, errorMsg: Option[String])(using
      prettify: Boolean
  ): LiftJsonResponse[JsonRudderApiResponse[Unit]] = {
    generic.unauthorizedError(JsonRudderApiResponse.error(id, schema, errorMsg))
  }
  def notFoundError(id: Option[String], schema: ResponseSchema, errorMsg: Option[String])(using
      prettify: Boolean
  ): LiftJsonResponse[JsonRudderApiResponse[Unit]] = {
    generic.notFoundError(JsonRudderApiResponse.error(id, schema, errorMsg))
  }
  def forbiddenError(id: Option[String], schema: ResponseSchema, errorMsg: Option[String])(using
      prettify: Boolean
  ): LiftJsonResponse[JsonRudderApiResponse[Unit]] = {
    generic.forbiddenError(JsonRudderApiResponse.error(id, schema, errorMsg))
  }

  // import that to transform a class from JsonResponse into a lift response. An encoder for the JsonResponse class
  // must be available.
  object syntax {
    extension [A](result: IOResult[Seq[A]]) {
      def toLiftResponseList(params: DefaultParams, schema: ResponseSchema)(using encoder: JsonEncoder[A]): LiftResponse = {
        given prettify: Boolean = params.prettify
        result
          .fold(
            err => {
              ApiLogger.ResponseError.info(err.fullMsg)

              // here, we don't want to return stack trace, since it can be security vulnerability
              internalError(None, schema, err.msg)
            },
            seq => successList(schema, seq.toList)
          )
          .catchAllDefect(err => zio.ZIO.succeed(internalError(None, schema, err.getMessage)))
          .runNow
      }
      def toLiftResponseList(params: DefaultParams, schema: EndpointSchema)(using encoder: JsonEncoder[A]): LiftResponse = {
        toLiftResponseList(params, ResponseSchema.fromSchema(schema))
      }
    }

    // ADT that matches error or success to determine the id value to use/compute
    sealed trait IdTrace[A] {
      import IdTrace.*
      // if no computed id is given, we use the constant one
      def success(a: A): Option[String] = this match {
        case Success(f) => f(a)
        case Const(id)  => id
      }

      def error: Option[String] = this match {
        case Const(id)  => id
        case Success(_) => None
      }
    }

    object IdTrace {
      case class Const[A](id: Option[String])               extends IdTrace[A] // can apply to both error and success cases
      case class Success[A](computeId: A => Option[String]) extends IdTrace[A]
    }

    extension [A](result: IOResult[A]) {
      private def toLiftResponseOne(params: DefaultParams, schema: ResponseSchema, id: IdTrace[A])(using
          encoder: JsonEncoder[A]
      ): LiftResponse = {
        given prettify: Boolean = params.prettify
        result
          .fold(
            err => {
              ApiLogger.ResponseError.info(err.fullMsg)

              // here, we don't want to return stack trace, since it can be security vulnerability
              internalError(id.error, schema, err.msg)
            },
            one => successOne(schema, one, id.success(one))
          )
          .catchAllDefect(err => zio.ZIO.succeed(internalError(id.error, schema, err.getMessage)))
          .runNow
      }
      def toLiftResponseOne(params: DefaultParams, schema: EndpointSchema, id: Option[String])(using
          encoder: JsonEncoder[A]
      ): LiftResponse = {
        toLiftResponseOne(params, ResponseSchema.fromSchema(schema), IdTrace.Const[A](id))
      }
      def toLiftResponseOne(params: DefaultParams, schema: EndpointSchema, id: A => Option[String])(using
          encoder: JsonEncoder[A]
      ): LiftResponse = {
        toLiftResponseOne(params, ResponseSchema.fromSchema(schema), IdTrace.Success(id))
      }
      // when the computation give the response schema
      def toLiftResponseOneMap[B](
          params:      DefaultParams,
          errorSchema: ResponseSchema,
          map:         A => (ResponseSchema, B, Option[String])
      )(using encoder: JsonEncoder[B]): LiftResponse = {
        given prettify: Boolean = params.prettify
        result
          .fold(
            err => {
              ApiLogger.ResponseError.info(err.fullMsg)

              // here, we don't want to return stack trace, since it can be security vulnerability
              internalError(None, errorSchema, err.msg)
            },
            one => {
              val (schema, x, id) = map(one)
              successOne(schema, x, id)
            }
          )
          .catchAllDefect(err => zio.ZIO.succeed(internalError(None, errorSchema, err.getMessage)))
          .runNow
      }

      def toLiftResponseZeroEither(
          params: DefaultParams,
          schema: ResponseSchema,
          id:     IdTrace[A]
      )(using ev: A <:< Either[ResponseError, Any]): LiftResponse = {
        given prettify: Boolean = params.prettify
        result
          .fold(
            err => {
              ApiLogger.ResponseError.info(err.fullMsg)
              internalError(None, schema, err.fullMsg)
            },
            either => {
              ev.apply(either) match {
                case Left(e)  => e.toLiftErrorResponse(id.error, schema)
                case Right(_) => successZero(schema)
              }
            }
          )
          .runNow
      }
      def toLiftResponseZeroEither(params: DefaultParams, schema: EndpointSchema, id: Option[String])(using
          ev: A <:< Either[ResponseError, Any]
      ): LiftResponse = {
        toLiftResponseZeroEither(params, ResponseSchema.fromSchema(schema), IdTrace.Const[A](id))(using ev)
      }

      def toLiftResponseZeroEither(params: DefaultParams, schema: EndpointSchema, id: A => Option[String])(using
          ev: A <:< Either[ResponseError, Any]
      ): LiftResponse = {
        toLiftResponseZeroEither(params, ResponseSchema.fromSchema(schema), IdTrace.Success[A](id))(using ev)
      }

    }
    // when you don't have any response, just a success
    extension (result: IOResult[Unit]) {
      def toLiftResponseZeroUnit(params: DefaultParams, schema: ResponseSchema): LiftResponse = {
        given prettify: Boolean = params.prettify
        result
          .fold(
            err => {
              ApiLogger.ResponseError.info(err.fullMsg)
              internalError(None, schema, err.fullMsg)
            },
            _ => successZero(schema)
          )
          .runNow
      }
      def toLiftResponseZeroUnit(params: DefaultParams, schema: EndpointSchema): LiftResponse = {
        toLiftResponseZeroUnit(params, ResponseSchema.fromSchema(schema))
      }
    }

    // when you don't have any parameter, just a message as response
    extension (result: IOResult[String]) {
      def toLiftResponseZero(params: DefaultParams, schema: ResponseSchema): LiftResponse = {
        given prettify: Boolean = params.prettify
        result
          .fold(
            err => {
              ApiLogger.ResponseError.info(err.fullMsg)

              // here, we don't want to return stack trace, since it can be security vulnerability
              internalError(None, schema, err.msg)
            },
            msg => successZero(schema, msg)
          )
          .catchAllDefect(err => zio.ZIO.succeed(internalError(None, schema, err.getMessage)))
          .runNow
      }
      def toLiftResponseZero(params: DefaultParams, schema: EndpointSchema): LiftResponse = {
        toLiftResponseZero(params, ResponseSchema.fromSchema(schema))
      }
    }
  }
}

export RudderJsonResponse.syntax
