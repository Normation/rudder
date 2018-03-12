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

import net.liftweb.json._
import net.liftweb.common._
import com.normation.utils.Control._
import cats._, cats.implicits._
import com.normation.rudder.domain.policies.JsonTagExtractor
import scala.language.higherKinds

trait JsonExctractorUtils[A[_]] {

  implicit def monad : Monad[A]

  def getOrElse[T](value : A[T], default : T) : T
  def boxedIdentity[T] : T => Box[T] = Full(_)
  def emptyValue[T] : Box[A[T]]
  protected[this] def extractJson[T, U ] (json:JValue, key:String, convertTo : U => Box[T], validJson : PartialFunction[JValue, U]) : Box[A[T]] = {
    json \ key match {
      case value if validJson.isDefinedAt(value) =>
        convertTo(validJson(value)).map(monad.pure(_))
      case JNothing => emptyValue ?~! s"parameter ${key} cannot be empty"
      case invalidJson => Failure(s"Not a good value for parameter ${key}: ${compactRender(invalidJson)}")
    }
  }

  def extractJsonString[T](json:JValue, key:String, convertTo : String => Box[T] = boxedIdentity[String]) = {
    extractJson(json, key, convertTo ,{ case JString(value) => value } )
  }

  def extractJsonBoolean[T](json:JValue, key:String, convertTo : Boolean => Box[T] = boxedIdentity[Boolean] ) = {
    extractJson(json, key, convertTo ,{ case JBool(value) => value } )
  }

  def extractJsonInt(json:JValue, key:String ) = {
    extractJsonBigInt(json,key, i => Full(i.toInt) )
  }

  def extractJsonBigInt[T](json:JValue, key:String , convertTo : BigInt => Box[T] = boxedIdentity[BigInt] )= {
    extractJson(json, key, convertTo ,{ case JInt(value) => value } )
  }

  def extractJsonObj[T](json : JValue, key : String, jsonValueFun : JObject => Box[T])  = {
    extractJson(json, key, jsonValueFun, { case obj : JObject => obj } )
  }

  def extractJsonListString[T] (json: JValue, key: String, convertTo: List[String] => Box[T] = boxedIdentity[List[String]]): Box[A[T]] = {
    json \ key match {
      case JArray(values) =>
        (for {
          strings <- sequence(values) { _ match {
                        case JString(s) => Full(s)
                        case x => Failure(s"Error extracting a string from json: '${x}'")
                      } }
          converted <- convertTo(strings.toList)
        } yield {
          converted
        }).map(monad.pure(_))
      case JNothing   => convertTo(Nil).map(monad.pure(_))
      case _              => Failure(s"Not a good value for parameter ${key}")
    }
  }

  def extractJsonArray[T] (json: JValue)( convertTo: JValue => Box[A[T]] ): Box[A[List[T]]] = {
    json  match {
      case JArray(values) =>
        for {
          converted <- sequence(values) { convertTo(_) }
        } yield {
          Traverse[List].sequence(converted.toList)
        }
      case JNothing   => emptyValue ?~! s"Array is empty when extracting array"
      case _          => Failure(s"Invalid json to extract a json array, current value is: ${compactRender(json)}")
    }
  }
}

trait DataExtractor[T[_]] extends JsonTagExtractor[T]
object DataExtractor {

  object OptionnalJson extends DataExtractor[Option] {
    def monad = implicitly
    def emptyValue[T] = Full(None)
    def getOrElse[T](value : Option[T], default : T) = value.getOrElse(default)
  }

  type Id[X] = X

  object CompleteJson extends DataExtractor[Id] {
    def monad = implicitly
    def emptyValue[T] = Failure(s"parameter cannot be empty")
    def getOrElse[T](value : T, default : T) = value
  }
}
