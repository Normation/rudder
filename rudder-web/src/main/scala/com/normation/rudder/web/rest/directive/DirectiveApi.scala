/*
*************************************************************************************
* Copyright 2013 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.web.rest.directive

import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.rudder.domain.policies.Directive

import net.liftweb.common.Box
import net.liftweb.common.Loggable
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.http.rest.RestHelper

class LatestDirectiveAPI (
    latestApi : DirectiveAPI
) extends RestHelper with Loggable {
    serve( "api" / "lastest" / "directives" prefix latestApi.requestDispatch)

}

trait DirectiveAPI {
  val requestDispatch : PartialFunction[Req, () => Box[LiftResponse]]

}

case class RestDirective(
      name             : Option[String] = None
    , shortDescription : Option[String] = None
    , longDescription  : Option[String] = None
    , enabled          : Option[Boolean] = None
    , parameters       : Option[Map[String, Seq[String]]] = None
    , priority         : Option[Int] = None
    , techniqueVersion : Option[TechniqueVersion] = None
  ) {

    val onlyName = name.isDefined           &&
                   shortDescription.isEmpty &&
                   longDescription.isEmpty  &&
                   parameters.isEmpty       &&
                   priority.isEmpty         &&
                   enabled.isEmpty

    def updateDirective(directive:Directive) = {
      val updateName = name.getOrElse(directive.name)
      val updateShort = shortDescription.getOrElse(directive.shortDescription)
      val updateLong = longDescription.getOrElse(directive.longDescription)
      val updateEnabled = enabled.getOrElse(directive.isEnabled)
      val updateTechniqueVersion = techniqueVersion.getOrElse(directive.techniqueVersion)
      val updateParameters = parameters.getOrElse(directive.parameters)
      val updatePriority = priority.getOrElse(directive.priority)
      directive.copy(
          name             = updateName
        , shortDescription = updateShort
        , longDescription  = updateLong
        , isEnabled        = updateEnabled
        , parameters       = updateParameters
        , techniqueVersion = updateTechniqueVersion
        , priority         = updatePriority
      )

    }
}