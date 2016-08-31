/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

package com.normation.rudder.web.rest.directive

import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.rudder.domain.policies.Directive
import net.liftweb.common.Box
import net.liftweb.common.Loggable
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.http.rest.RestHelper
import com.normation.rudder.web.rest.RestAPI
import com.normation.cfclerk.domain.TechniqueName
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.cfclerk.domain.Technique
import com.normation.rudder.domain.policies.PolicyMode

trait DirectiveAPI extends RestAPI {
  val kind = "directives"
}

case class RestDirective(
      name             : Option[String]
    , shortDescription : Option[String]
    , longDescription  : Option[String]
    , enabled          : Option[Boolean]
    , parameters       : Option[Map[String, Seq[String]]]
    , priority         : Option[Int]
    , techniqueName    : Option[TechniqueName]
    , techniqueVersion : Option[TechniqueVersion]
    , policyMode       : Option[Option[PolicyMode]]
  ) {

    val onlyName = name.isDefined           &&
                   shortDescription.isEmpty &&
                   longDescription.isEmpty  &&
                   enabled.isEmpty          &&
                   parameters.isEmpty       &&
                   priority.isEmpty         &&
                   techniqueName.isEmpty    &&
                   techniqueVersion.isEmpty &&
                   policyMode.isEmpty

    def updateDirective(directive:Directive) = {
      val updateName = name.getOrElse(directive.name)
      val updateShort = shortDescription.getOrElse(directive.shortDescription)
      val updateLong = longDescription.getOrElse(directive.longDescription)
      val updateEnabled = enabled.getOrElse(directive.isEnabled)
      val updateTechniqueVersion = techniqueVersion.getOrElse(directive.techniqueVersion)
      val updateParameters = parameters.getOrElse(directive.parameters)
      val updatePriority = priority.getOrElse(directive.priority)
      val updateMode = policyMode.getOrElse(directive.policyMode)
      directive.copy(
          name             = updateName
        , shortDescription = updateShort
        , longDescription  = updateLong
        , _isEnabled       = updateEnabled
        , parameters       = updateParameters
        , techniqueVersion = updateTechniqueVersion
        , priority         = updatePriority
        , policyMode       = updateMode
      )

    }
}

case class DirectiveState (
    technique : Technique
  , directive : Directive
)

case class DirectiveUpdate(
    activeTechnique: ActiveTechnique
  , before: DirectiveState
  , after : DirectiveState
)
