/*
*************************************************************************************
* Copyright 2016 Normation SAS
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

package com.normation.rudder.web.rest.technique

import scala.util.{ Failure => TryFailure }
import scala.util.Success
import scala.util.Try

import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.rudder.web.rest.RestExtractorService
import com.normation.rudder.web.rest.RestUtils.response
import com.normation.rudder.web.rest.ApiVersion

import net.liftweb.common.Box
import net.liftweb.common.Failure
import net.liftweb.common.Loggable
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req

class TechniqueAPI6 (
    restExtractor : RestExtractorService
  , apiV6         : TechniqueAPIService6
) extends TechniqueAPI with Loggable{

  override def requestDispatch(apiVersion: ApiVersion) : PartialFunction[Req, () => Box[LiftResponse]] = {

    case Get( Nil, req) =>
      response(
        restExtractor
      , "techniques"
      , None
      )(
          apiV6.listTechniques
        , req
        , s"Could not find list of techniques"
       ) ("listTechniques")

    case Get(name :: "directives" :: Nil, req) =>
      val techniqueName = TechniqueName(name)
      response(
        restExtractor
      , "directives"
      , Some(name)
      )(
          apiV6.listDirectives(techniqueName, None)
        , req
        , s"Could not find list of directives based on '${techniqueName}' Technique"
       ) ("listTechniquesDirectives")

    case Get(name :: version :: "directives" :: Nil, req) =>
      val techniqueName = TechniqueName(name)
      val directives = Try(TechniqueVersion(version)) match {
        case Success(techniqueVersion) =>
              apiV6.listDirectives(techniqueName, Some(techniqueVersion :: Nil))
        case TryFailure(exception) =>
          Failure(s"Could not find list of directives based on '${techniqueName}' Technique, because we could not parse '${version}' as a valid technique version")
      }
      response(
          restExtractor
        , "directives"
        , Some(s"${name}/${version}")
      ) ( directives
        , req
        , s"Could not find list of directives based on version '${version}' of '${techniqueName}' Technique"
      ) ("listTechniqueDirectives")

  }

}
