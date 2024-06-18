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
import com.normation.rudder.rest.lift.DefaultParams
import com.normation.rudder.rest.lift.LiftApiProcessingLogger
import com.normation.zio.*
import net.liftweb.http.InMemoryResponse
import net.liftweb.http.LiftResponse
import scala.collection.immutable
import zio.json.*
import zio.json.DeriveJsonEncoder

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
    def error(id: Option[String], schema: ResponseSchema, message: String): JsonRudderApiResponse[Unit] =
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
  }

  //////////////////////////// Lift JSON response ////////////////////////////

  final case class LiftJsonResponse[A](json: A, prettify: Boolean, code: Int)(implicit encoder: JsonEncoder[A])
      extends LiftResponse {
    def toResponse: InMemoryResponse = {
      val indent = if (prettify) Some(2) else None
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

  //////////////////////////// utility methods to build responses ////////////////////////////

  object generic {
    // generic response, not in rudder normalized format - use it if you want an ad-hoc json response.
    def success[A](json: A)(implicit prettify: Boolean, encoder: JsonEncoder[A]):        LiftJsonResponse[A] =
      LiftJsonResponse(json, prettify, 200)
    def internalError[A](json: A)(implicit prettify: Boolean, encoder: JsonEncoder[A]):  LiftJsonResponse[A] =
      LiftJsonResponse(json, prettify, 500)
    def notFoundError[A](json: A)(implicit prettify: Boolean, encoder: JsonEncoder[A]):  LiftJsonResponse[A] =
      LiftJsonResponse(json, prettify, 404)
    def forbiddenError[A](json: A)(implicit prettify: Boolean, encoder: JsonEncoder[A]): LiftJsonResponse[A] =
      LiftJsonResponse(json, prettify, 404)
  }

  trait DataContainer[A] {
    def name: String
    def data: A
  }

  // rudder response. The "A" parameter is the business object (or list of it) in the response.
  // Success
  def successOne[A](schema: ResponseSchema, obj: A, id: Option[String])(implicit
      prettify: Boolean,
      encoder:  JsonEncoder[A]
  ): LiftJsonResponse[? <: JsonRudderApiResponse[?]] = {
    schema.dataContainer match {
      case Some(key) =>
        implicit val enc: JsonEncoder[JsonRudderApiResponse[Map[String, List[A]]]] = DeriveJsonEncoder.gen
        generic.success(JsonRudderApiResponse.success(schema, id, Map((key, List(obj)))))
      case None      => // in that case, the object is not even in a list
        implicit val enc: JsonEncoder[JsonRudderApiResponse[A]] = DeriveJsonEncoder.gen
        generic.success(JsonRudderApiResponse.success(schema, id, obj))
    }
  }
  def successList[A](schema: ResponseSchema, objs: List[A])(implicit
      prettify: Boolean,
      encoder:  JsonEncoder[A]
  ): LiftJsonResponse[
    ? <: JsonRudderApiResponse[? <: immutable.Iterable[Any] with PartialFunction[Int with String, Any] with Equals]
  ] = {
    schema.dataContainer match {
      case None      =>
        implicit val enc: JsonEncoder[JsonRudderApiResponse[List[A]]] = DeriveJsonEncoder.gen
        generic.success(JsonRudderApiResponse.success(schema, None, objs))
      case Some(key) =>
        implicit val enc: JsonEncoder[JsonRudderApiResponse[Map[String, List[A]]]] = DeriveJsonEncoder.gen
        generic.success(JsonRudderApiResponse.success(schema, None, Map(key -> objs)))
    }
  }
  def successZero(schema: ResponseSchema, msg: String)(implicit
      prettify: Boolean
  ): LiftJsonResponse[JsonRudderApiResponse[String]] = {
    implicit val enc: JsonEncoder[JsonRudderApiResponse[String]] = DeriveJsonEncoder.gen
    generic.success(JsonRudderApiResponse.success(schema, None, msg))
  }
  // errors
  implicit val nothing:      JsonEncoder[Option[Unit]]                = new JsonEncoder[Option[Unit]] {
    def unsafeEncode(n:       Option[Unit], indent: Option[Int], out: zio.json.internal.Write): Unit = out.write("null")
    override def isNothing(a: Option[Unit]): Boolean = true
  }
  implicit val errorEncoder: JsonEncoder[JsonRudderApiResponse[Unit]] = DeriveJsonEncoder.gen

  def internalError(id: Option[String], schema: ResponseSchema, errorMsg: String)(implicit
      prettify: Boolean
  ): LiftJsonResponse[JsonRudderApiResponse[Unit]] = {
    generic.internalError(JsonRudderApiResponse.error(id, schema, errorMsg))
  }
  // Internal error with a specific schema
  def internalError[A](id: Option[String], schema: ResponseSchema, obj: A, errorMsg: Option[String])(implicit
      prettify: Boolean,
      encoder:  JsonEncoder[A]
  ): LiftJsonResponse[? <: JsonRudderApiResponse[?]] = {
    schema.dataContainer match {
      case Some(key) =>
        implicit val enc: JsonEncoder[JsonRudderApiResponse[Map[String, List[A]]]] = DeriveJsonEncoder.gen
        generic.internalError(JsonRudderApiResponse.genericError(id, schema, Map((key, List(obj))), errorMsg))
      case None      => // in that case, the object is not even in a list
        implicit val enc: JsonEncoder[JsonRudderApiResponse[A]] = DeriveJsonEncoder.gen
        generic.internalError(JsonRudderApiResponse.genericError(id, schema, obj, errorMsg))
    }
  }
  def notFoundError(id: Option[String], schema: ResponseSchema, errorMsg: String)(implicit
      prettify: Boolean
  ): LiftJsonResponse[JsonRudderApiResponse[Unit]] = {
    generic.notFoundError(JsonRudderApiResponse.error(id, schema, errorMsg))
  }
  def forbiddenError(id: Option[String], schema: ResponseSchema, errorMsg: String)(implicit
      prettify: Boolean
  ): LiftJsonResponse[JsonRudderApiResponse[Unit]] = {
    generic.forbiddenError(JsonRudderApiResponse.error(id, schema, errorMsg))
  }

  // import that to transform a class from JsonResponse into a lift response. An encoder for the JsonResponse class
  // must be available.
  trait implicits {
    implicit class ToLiftResponseList[A](result: IOResult[Seq[A]]) {
      def toLiftResponseList(params: DefaultParams, schema: ResponseSchema)(implicit encoder: JsonEncoder[A]): LiftResponse = {
        implicit val prettify = params.prettify
        result
          .fold(
            err => {
              LiftApiProcessingLogger.error(err.fullMsg)
              internalError(None, schema, err.fullMsg)
            },
            seq => successList(schema, seq.toList)
          )
          .runNow
      }
      def toLiftResponseList(params: DefaultParams, schema: EndpointSchema)(implicit encoder: JsonEncoder[A]): LiftResponse = {
        toLiftResponseList(params, ResponseSchema.fromSchema(schema))
      }
    }

    implicit class ToLiftResponseOne[A](result: IOResult[A])    {
      // ADT that matches error or success to determine the id value to use/compute
      sealed trait IdTrace {
        // if no computed id is given, we use the constant one
        def success(a: A): Option[String] = this match {
          case SuccessIdTrace(f) => f(a)
          case ConstIdTrace(id)  => id
        }
        def error:         Option[String] = this match {
          case ConstIdTrace(id)  => id
          case SuccessIdTrace(_) => None
        }
      }
      case class ConstIdTrace(id: Option[String]) extends IdTrace // can apply to both error and success cases
      case class SuccessIdTrace(computeId: A => Option[String]) extends IdTrace

      private def toLiftResponseOne(params: DefaultParams, schema: ResponseSchema, id: IdTrace)(implicit
          encoder: JsonEncoder[A]
      ): LiftResponse = {
        implicit val prettify = params.prettify
        result
          .fold(
            err => {
              LiftApiProcessingLogger.error(err.fullMsg)
              internalError(id.error, schema, err.fullMsg)
            },
            one => successOne(schema, one, id.success(one))
          )
          .runNow
      }
      def toLiftResponseOne(params: DefaultParams, schema: EndpointSchema, id: Option[String])(implicit
          encoder: JsonEncoder[A]
      ): LiftResponse = {
        toLiftResponseOne(params, ResponseSchema.fromSchema(schema), ConstIdTrace(id))
      }
      def toLiftResponseOne(params: DefaultParams, schema: EndpointSchema, id: A => Option[String])(implicit
          encoder: JsonEncoder[A]
      ): LiftResponse = {
        toLiftResponseOne(params, ResponseSchema.fromSchema(schema), SuccessIdTrace(id))
      }
      // when the computation give the response schema
      def toLiftResponseOneMap[B](
          params:      DefaultParams,
          errorSchema: ResponseSchema,
          map:         A => (ResponseSchema, B, Option[String])
      )(implicit encoder: JsonEncoder[B]): LiftResponse = {
        implicit val prettify = params.prettify
        result
          .fold(
            err => {
              LiftApiProcessingLogger.error(err.fullMsg)
              internalError(None, errorSchema, err.fullMsg)
            },
            one => {
              val (schema, x, id) = map(one)
              successOne(schema, x, id)
            }
          )
          .runNow
      }
    }
    // when you don't have any parameter, just a response
    implicit class ToLiftResponseZero(result: IOResult[String]) {
      def toLiftResponseZero(params: DefaultParams, schema: ResponseSchema): LiftResponse = {
        implicit val prettify = params.prettify
        result
          .fold(
            err => {
              LiftApiProcessingLogger.error(err.fullMsg)
              internalError(None, schema, err.fullMsg)
            },
            msg => successZero(schema, msg)
          )
          .runNow
      }
      def toLiftResponseZero(params: DefaultParams, schema: EndpointSchema): LiftResponse = {
        toLiftResponseZero(params, ResponseSchema.fromSchema(schema))
      }
    }
  }
}

object implicits extends RudderJsonResponse.implicits
