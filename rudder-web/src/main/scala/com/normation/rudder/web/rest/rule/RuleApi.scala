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

package com.normation.rudder.web.rest.rule

import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleTarget
import net.liftweb.common.Box
import net.liftweb.common.Loggable
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.http.rest.RestHelper
import com.normation.rudder.rule.category.RuleCategoryId

class LatestRuleAPI (
    latestApi : RuleAPI
) extends RestHelper with Loggable {
    serve( "api" / "lastest" / "rules" prefix latestApi.requestDispatch)

}

trait RuleAPI {
  val requestDispatch : PartialFunction[Req, () => Box[LiftResponse]]

}

case class RestRule(
      name             : Option[String] = None
    , category         : Option[RuleCategoryId] = None
    , shortDescription : Option[String] = None
    , longDescription  : Option[String] = None
    , directives       : Option[Set[DirectiveId]] = None
    , targets          : Option[Set[RuleTarget]] = None
    , enabled          : Option[Boolean]     = None
  ) {

    val onlyName = name.isDefined           &&
                   category.isEmpty         &&
                   shortDescription.isEmpty &&
                   longDescription.isEmpty  &&
                   directives.isEmpty       &&
                   targets.isEmpty          &&
                   enabled.isEmpty

    def updateRule(rule:Rule) = {
      val updateName = name.getOrElse(rule.name)
      val updateCategory = category.getOrElse(rule.category)
      val updateShort = shortDescription.getOrElse(rule.shortDescription)
      val updateLong = longDescription.getOrElse(rule.longDescription)
      val updateDirectives = directives.getOrElse(rule.directiveIds)
      val updateTargets = targets.getOrElse(rule.targets)
      val updateEnabled = enabled.getOrElse(rule.isEnabledStatus)
      rule.copy(
          name             = updateName
        , category         = updateCategory
        , shortDescription = updateShort
        , longDescription  = updateLong
        , directiveIds     = updateDirectives
        , targets          = updateTargets
        , isEnabledStatus  = updateEnabled
      )

    }
}