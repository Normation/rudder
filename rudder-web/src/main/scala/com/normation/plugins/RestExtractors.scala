/*
*************************************************************************************
* Copyright 2018 Normation SAS
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

package com.normation.plugins

import net.liftweb.common.{Box, Failure, Full}
import net.liftweb.http.Req
import net.liftweb.json._

/*
 * A file to keep rest extractors that could disapear/evolve in
 * Rudder but are needed in plugins in their original form.
 */

object PluginRestExtractor {


  def extractInt[T](key : String) (req : Req)(fun : BigInt => Box[T]) : Box[Option[T]]  = {
    req.json match {
      case Full(json) => json \ key match {
        case JInt(value) => fun(value).map(Some(_))
        case JNothing => Full(None)
        case x => Failure(s"Not a valid value for '${key}' parameter, current value is : ${x}")
      }
      case _ =>
        req.params.get(key) match {
          case None => Full(None)
          case Some(head :: Nil) => try {
            fun(head.toLong).map(Some(_))
          } catch {
            case e : Throwable =>
              Failure(s"Parsing request parameter '${key}' as an integer failed, current value is '${head}'. Error message is: '${e.getMessage}'.")
          }
          case Some(list) => Failure(s"${list.size} values defined for 'id' parameter, only one needs to be defined")
        }
    }
  }

  def extractBoolean[T](key : String) (req : Req)(fun : Boolean => T) : Box[Option[T]]  = {
    req.json match {
      case Full(json) => json \ key match {
        case JBool(value) => Full(Some(fun(value)))
        case JNothing => Full(None)
        case x => Failure(s"Not a valid value for '${key}' parameter, current value is : ${x}")
      }
      case _ =>
        req.params.get(key) match {
          case None => Full(None)
          case Some(head :: Nil) => try {
            Full(Some(fun(head.toBoolean)))
          } catch {
            case e : Throwable =>
              Failure(s"Parsing request parameter '${key}' as a boolean failed, current value is '${head}'. Error message is: '${e.getMessage}'.")
          }
          case Some(list) => Failure(s"${list.size} values defined for 'id' parameter, only one needs to be defined")
        }
    }
  }
}
