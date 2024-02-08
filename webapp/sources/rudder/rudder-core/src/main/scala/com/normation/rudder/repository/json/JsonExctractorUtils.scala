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

package com.normation.rudder.repository.json

import cats._
import cats.implicits._
import com.normation.rudder.domain.policies.JsonTagExtractor
import com.normation.utils.Control._
import net.liftweb.common._
import net.liftweb.json._

trait JsonExtractorUtils[A[_]] {

  implicit def monad: Monad[A]

  def getOrElse[T](value: A[T], default: T): T
  def boxedIdentity[T]: T => Box[T] = Full(_)
  def emptyValue[T](id: String): Box[A[T]]
  protected[this] def extractJson[T, U](
      json:      JValue,
      key:       String,
      convertTo: U => Box[T],
      validJson: PartialFunction[JValue, U]
  ): Box[A[T]] = {
    json \ key match {
      case value if validJson.isDefinedAt(value) =>
        convertTo(validJson(value)).map(monad.pure(_))
      case JNothing                              => emptyValue(key) ?~! s"parameter ${key} cannot be empty"
      case invalidJson                           => Failure(s"Not a good value for parameter ${key}: ${compactRender(invalidJson)}")
    }
  }
  protected[this] def extractJson[T, U](
      json:      JValue,
      keys:      List[String],
      convertTo: U => Box[T],
      validJson: PartialFunction[JValue, U]
  ): Box[A[T]] = {
    keys match {
      case key :: rest =>
        extractJson(json, key, convertTo, validJson) match {
          case eb: EmptyBox =>
            val fail = eb ?~! s"Error when looking for key '${key}'"
            extractJson(json, rest, convertTo, validJson) match {
              case eb2: EmptyBox =>
                eb2 ?~! fail.messageChain
              case Full(value) => Full(value)

            }
          case Full(value) => Full(value)
        }

      case Nil => Failure("No key match") // message will be clarified by parent
    }
  }

  def extractJsonString[T](json: JValue, key: String, convertTo: String => Box[T] = boxedIdentity[String]): Box[A[T]] = {
    extractJson(json, key, convertTo, { case JString(value) => value })
  }
  def extractJsonStringMultipleKeys[T](
      json:      JValue,
      keys:      List[String],
      convertTo: String => Box[T] = boxedIdentity[String]
  ): Box[A[T]] = {
    extractJson(json, keys, convertTo, { case JString(value) => value })
  }

  def extractJsonBoolean[T](json: JValue, key: String, convertTo: Boolean => Box[T] = boxedIdentity[Boolean]): Box[A[T]] = {
    extractJson(json, key, convertTo, { case JBool(value) => value })
  }

  def extractJsonInt(json: JValue, key: String): Box[A[Int]] = {
    extractJsonBigInt(json, key, i => Full(i.toInt))
  }

  def extractJsonBigInt[T](json: JValue, key: String, convertTo: BigInt => Box[T] = boxedIdentity[BigInt]): Box[A[T]] = {
    extractJson(json, key, convertTo, { case JInt(value) => value })
  }

  def extractJsonObj[T](json: JValue, key: String, jsonValueFun: JObject => Box[T]):                    Box[A[T]] = {
    extractJson(json, key, jsonValueFun, { case obj: JObject => obj })
  }
  def extractJsonObjMultipleKeys[T](json: JValue, keys: List[String], jsonValueFun: JObject => Box[T]): Box[A[T]] = {
    extractJson(json, keys, jsonValueFun, { case obj: JObject => obj })
  }

  def extractJsonListString[T](
      json:      JValue,
      key:       String,
      convertTo: List[String] => Box[T] = boxedIdentity[List[String]]
  ): Box[A[T]] = {
    json \ key match {
      case JArray(values) =>
        (for {
          strings   <- traverse(values) {
                         _ match {
                           case JString(s) => Full(s)
                           case x          => Failure(s"Error extracting a string from json: '${x}'")
                         }
                       }
          converted <- convertTo(strings.toList)
        } yield {
          converted
        }).map(monad.pure(_))
      case JNothing       => emptyValue(key) ?~! s"Array of string is empty"
      case _              => Failure(s"Not a good value for parameter ${key}")
    }
  }

  def extractJsonArray[T](json: JValue, key: String)(convertTo: JValue => Box[T]):        Box[A[List[T]]] = {
    val trueJson =
      if (key.isEmpty) json else json \ key
    trueJson match {
      case JArray(values) =>
        for {
          converted <- traverse(values)(convertTo(_))
        } yield {
          monad.point(converted.toList)
        }
      case JNothing       => emptyValue(key) ?~! s"Array is empty when extracting array"
      case _              => Failure(s"Invalid json to extract a json array, current value is: ${compactRender(json)}")
    }
  }
  def extractJsonArray[T](json: JValue, keys: List[String])(convertTo: JValue => Box[T]): Box[A[List[T]]] = {
    keys.map(k => extractJsonArray(json, k)(convertTo)).reduce[Box[A[List[T]]]] {
      case (Full(res), _)                 => Full(res)
      case (_, Full(res))                 => Full(res)
      case (eb1: EmptyBox, eb2: EmptyBox) =>
        eb1 ?~! ((eb2 ?~! "error while extracting data from json array").messageChain)
    }

  }
}

trait DataExtractor[T[_]] extends JsonTagExtractor[T]
object DataExtractor {

  object OptionnalJson extends DataExtractor[Option] {
    def monad = implicitly
    def emptyValue[T](id: String):                  Box[Option[T]] = Full(None)
    def getOrElse[T](value: Option[T], default: T): T              = value.getOrElse(default)
  }

  type Id[X] = X

  object CompleteJson extends DataExtractor[Id] {
    def monad = implicitly
    def emptyValue[T](id: String): Box[Id[T]] = Failure(s"parameter '${id}' cannot be empty")
    def getOrElse[T](value: T, default: T) = value
  }
}
