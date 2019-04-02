/*
*************************************************************************************
* Copyright 2019 Normation SAS
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

package com.normation.cfclerk.domain

import cats.data.NonEmptyList
import com.normation.errors._

sealed trait LoadTechniqueError extends RudderError

object LoadTechniqueError {

  final case class Parsing(msg: String)     extends LoadTechniqueError
  final case class Consistancy(msg: String) extends LoadTechniqueError
  final case class Constraint(msg: String) extends LoadTechniqueError
  final case class Variable(msg: String)    extends LoadTechniqueError
  final case class Chained(hint: String, cause: LoadTechniqueError) extends LoadTechniqueError with BaseChainError[LoadTechniqueError]
  final case class Accumulated(causes: NonEmptyList[LoadTechniqueError]) extends LoadTechniqueError {
    val msg = causes.map( _.fullMsg ).toList.mkString("; ")
  }
}

object implicits {

  implicit class ChainEither[E <: LoadTechniqueError, T](res: Either[E, T]) {
    def chain(msg: String): Either[LoadTechniqueError, T] = {
      res match {
        case Right(v)  => Right(v)
        case Left(err) => Left(LoadTechniqueError.Chained(msg, err))
      }
    }
  }

}

