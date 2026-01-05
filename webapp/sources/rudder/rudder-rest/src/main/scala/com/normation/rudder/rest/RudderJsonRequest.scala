/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

import cats.syntax.either.*
import cats.syntax.monadError.*
import com.normation.errors.Chained
import com.normation.errors.Inconsistency
import com.normation.errors.PureResult
import com.normation.rudder.apidata.ZioJsonExtractor
import com.normation.rudder.config.ReasonBehavior
import com.normation.rudder.config.ReasonBehavior.*
import net.liftweb.http.Req
import zio.json.*

/**
  * This class exposes utility methods regarding JSON requests.
  *
  * @see RudderJsonResponse for the response counterpart.
  */
object RudderJsonRequest {

  extension (req: Req) {
    def fromJson[A](using JsonDecoder[A]): PureResult[A] = {
      ZioJsonExtractor.parseJson(req)
    }
  }

  def extractReason(req: Req)(implicit reasonBehavior: ReasonBehavior): PureResult[Option[String]] = {
    def reason = req.params.get("reason").flatMap(_.headOption)
    (reasonBehavior match {
      case Disabled  => Right(None)
      case Mandatory =>
        reason
          .toRight[String]("Reason field is mandatory and should be at least 5 characters long")
          .reject {
            case s if s.lengthIs < 5 => "Reason field should be at least 5 characters long"
          }
          .map(Some(_))
      case Optional  => Right(reason)
    }).leftMap(err => Chained("There was an error while extracting reason message", Inconsistency(err)))
  }
}
