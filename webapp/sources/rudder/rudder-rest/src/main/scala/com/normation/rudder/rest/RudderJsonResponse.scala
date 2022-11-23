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

import com.github.ghik.silencer.silent
import com.normation.errors._
import com.normation.rudder.rest.lift.DefaultParams
import com.normation.rudder.rest.lift.LiftApiProcessingLogger
import com.normation.zio._
import net.liftweb.http.InMemoryResponse
import net.liftweb.http.LiftResponse
import zio.json._
import zio.json.DeriveJsonEncoder

/*
 * This class deals with everything serialisation related for API.
 * Change things with care! Everything must be versionned!
 * Even changing a field name can lead to an API incompatible change and
 * so will need a new API version number (and be sure that old behavior is kept
 * for previous versions).
 */

/*
 * Rudder standard response.
 * We normalize response format to look like what is detailed here: https://docs.rudder.io/api/v/13/#section/Introduction/Response-format
 * Data are always name-spaced, so that theorically an answer can mixe several type of data. For example, for node details:
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
    def error(schema: ResponseSchema, message: String): JsonRudderApiResponse[Unit] =
      JsonRudderApiResponse(schema.action, None, "error", None, Some(message))

    def success[A](schema: ResponseSchema, id: Option[String], data: A): JsonRudderApiResponse[A] =
      JsonRudderApiResponse(schema.action, id, "success", Some(data), None)
  }

  //////////////////////////// Lift JSON response ////////////////////////////

  final case class RudderJsonResponse[A](json: A, prettify: Boolean, code: Int)(implicit encoder: JsonEncoder[A])
      extends LiftResponse {
    def toResponse = {
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
    def fromSchema(schema: EndpointSchema) = ResponseSchema(schema.name, schema.dataContainer)
  }

  //////////////////////////// utility methods to build responses ////////////////////////////

  object generic {
    // generic response, not in rudder normalized format - use it if you want an ad-hoc json response.
    def success[A](json: A)(implicit prettify: Boolean, encoder: JsonEncoder[A])        = RudderJsonResponse(json, prettify, 200)
    def internalError[A](json: A)(implicit prettify: Boolean, encoder: JsonEncoder[A])  = RudderJsonResponse(json, prettify, 500)
    def notFoundError[A](json: A)(implicit prettify: Boolean, encoder: JsonEncoder[A])  = RudderJsonResponse(json, prettify, 404)
    def forbiddenError[A](json: A)(implicit prettify: Boolean, encoder: JsonEncoder[A]) = RudderJsonResponse(json, prettify, 404)
  }

  trait DataContainer[A] {
    def name: String
    def data: A
  }

  // rudder response. The "A" parameter is the business object (or list of it) in the response.
  // Success
  @silent("parameter value encoder .* is never used") // used by magnolia macro
  def successOne[A](schema: ResponseSchema, obj: A, id: Option[String])(implicit prettify: Boolean, encoder: JsonEncoder[A]) = {
    schema.dataContainer match {
      case Some(key) =>
        implicit val enc: JsonEncoder[JsonRudderApiResponse[Map[String, List[A]]]] = DeriveJsonEncoder.gen
        generic.success(JsonRudderApiResponse.success(schema, id, Map((key, List(obj)))))
      case None      => // in that case, the object is not even in a list
        implicit val enc: JsonEncoder[JsonRudderApiResponse[A]] = DeriveJsonEncoder.gen
        generic.success(JsonRudderApiResponse.success(schema, id, obj))
    }
  }
  @silent("parameter value encoder .* is never used") // used by magnolia macro
  def successList[A](schema: ResponseSchema, objs: List[A])(implicit prettify: Boolean, encoder: JsonEncoder[A])             = {
    schema.dataContainer match {
      case None      =>
        implicit val enc: JsonEncoder[JsonRudderApiResponse[List[A]]] = DeriveJsonEncoder.gen
        generic.success(JsonRudderApiResponse.success(schema, None, objs))
      case Some(key) =>
        implicit val enc: JsonEncoder[JsonRudderApiResponse[Map[String, List[A]]]] = DeriveJsonEncoder.gen
        generic.success(JsonRudderApiResponse.success(schema, None, Map(key -> objs)))
    }
  }
  // errors
  implicit val nothing: JsonEncoder[Option[Unit]] = new JsonEncoder[Option[Unit]] {
    def unsafeEncode(n: Option[Unit], indent: Option[Int], out: zio.json.internal.Write): Unit    = out.write("null")
    override def isNothing(a: Option[Unit]):                                              Boolean = true
  }
  implicit val errorEncoder: JsonEncoder[JsonRudderApiResponse[Unit]] = DeriveJsonEncoder.gen

  def internalError(schema: ResponseSchema, errorMsg: String)(implicit prettify: Boolean)  = {
    generic.internalError(JsonRudderApiResponse.error(schema, errorMsg))
  }
  def notFoundError(schema: ResponseSchema, errorMsg: String)(implicit prettify: Boolean)  = {
    generic.notFoundError(JsonRudderApiResponse.error(schema, errorMsg))
  }
  def forbiddenError(schema: ResponseSchema, errorMsg: String)(implicit prettify: Boolean) = {
    generic.forbiddenError(JsonRudderApiResponse.error(schema, errorMsg))
  }

  // import that to transform a class from JsonResponse into a lift response. An encoder for the JsonResponse classe
  // must be available.
  trait implicits {
    implicit class ToLiftResponseList[A](result: IOResult[Seq[A]]) {
      def toLiftResponseList(params: DefaultParams, schema: ResponseSchema)(implicit encoder: JsonEncoder[A]): LiftResponse = {
        implicit val prettify = params.prettify
        result
          .fold(
            err => {
              LiftApiProcessingLogger.error(err.fullMsg)
              internalError(schema, err.fullMsg)
            },
            seq => successList(schema, seq.toList)
          )
          .runNow
      }
      def toLiftResponseList(params: DefaultParams, schema: EndpointSchema)(implicit encoder: JsonEncoder[A]): LiftResponse = {
        toLiftResponseList(params, ResponseSchema.fromSchema(schema))
      }
    }
    implicit class ToLiftResponseOne[A](result: IOResult[A])       {
      def toLiftResponseOne(params: DefaultParams, schema: ResponseSchema, id: A => Option[String])(implicit
          encoder:                  JsonEncoder[A]
      ): LiftResponse = {
        implicit val prettify = params.prettify
        result
          .fold(
            err => {
              LiftApiProcessingLogger.error(err.fullMsg)
              internalError(schema, err.fullMsg)
            },
            one => successOne(schema, one, id(one))
          )
          .runNow
      }
      def toLiftResponseOne(params: DefaultParams, schema: EndpointSchema, id: A => Option[String])(implicit
          encoder:                  JsonEncoder[A]
      ): LiftResponse = {
        toLiftResponseOne(params, ResponseSchema.fromSchema(schema), id)
      }
      // when the computation give the response schema
      def toLiftResponseOneMap[B](
          params:         DefaultParams,
          errorSchema:    ResponseSchema,
          map:            A => (ResponseSchema, B, Option[String])
      )(implicit encoder: JsonEncoder[B]): LiftResponse = {
        implicit val prettify = params.prettify
        result
          .fold(
            err => {
              LiftApiProcessingLogger.error(err.fullMsg)
              internalError(errorSchema, err.fullMsg)
            },
            one => {
              val (schema, x, id) = map(one)
              successOne(schema, x, id)
            }
          )
          .runNow
      }
    }
  }
}

object implicits extends RudderJsonResponse.implicits
