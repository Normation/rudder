/*
*************************************************************************************
* Copyright 2015 Normation SAS
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

package com.normation.rudder.web.rest.rule

import com.normation.rudder.web.rest.RestExtractorService
import net.liftweb.common.Box
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.http.rest.RestHelper
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.web.rest.RestUtils
import net.liftweb.json._
import com.normation.rudder.web.rest.ApiVersion

class RuleAPI6(
    serviceV6 : RuleApiService6
  , apiV2     : RuleAPI2
  , restExtractor : RestExtractorService
  , uuidGen   : StringUuidGenerator
) extends RestHelper with RuleAPI with Loggable{

  import RestUtils._
  val dataName = "ruleCategories"

  def response ( function : Box[JValue], req : Req, errorMessage : String)(implicit action : String) : LiftResponse = {
    RestUtils.response(restExtractor, dataName,None)(function, req, errorMessage)
  }

  def actionResponse ( function : Box[ActionType], req : Req, errorMessage : String)(implicit action : String) : LiftResponse = {
    RestUtils.actionResponse(restExtractor, dataName, uuidGen, None)(function, req, errorMessage)
  }

  val v6Dispatch : PartialFunction[Req, () => Box[LiftResponse]] = {

    case Get("tree" :: Nil, req) =>
      response(
          serviceV6.getCategoryTree
        , req
        , s"Could not fetch Rule category tree"
      ) ("GetRuleTree")

    case Get("categories"  :: id :: Nil, req) => {
      response (
          serviceV6.getCategoryDetails(RuleCategoryId(id))
        , req
        , s"Could not fetch Rule category '${id}' details"
     ) ("getRuleCategoryDetails")
    }

    case Delete("categories"  :: id :: Nil, req) => {
      actionResponse(
          Full(serviceV6.deleteCategory(RuleCategoryId(id)))
        , req
        , s"Could not delete Rule category '${id}'"
      ) ("deleteRuleCategory")
    }

    case "categories" :: id :: Nil JsonPost body -> req => {
      val action = for {
        json <- req.json ?~! "No JSON data sent"
        cat <- restExtractor.extractRuleCategory(json)
      } yield {
         serviceV6.updateCategory(RuleCategoryId(id), cat) _
      }
      actionResponse(
          action
        , req
        , s"Could not update Rule category '${id}'"
      ) ("updateRuleCategory")
    }

    case Post("categories" :: id :: Nil, req) => {

      val action =  for {
        restCategory <- restExtractor.extractRuleCategory(req.params)
      } yield {
        serviceV6.updateCategory(RuleCategoryId(id), restCategory) _
      }
      actionResponse(
          action
        , req
        , s"Could not update Rule category '${id}'"
      ) ("updateRuleCategory")
    }

    case "categories" :: Nil JsonPut body -> req => {
      val id = RuleCategoryId(uuidGen.newUuid)
      val action = for {
        cat <- restExtractor.extractRuleCategory(body)
      } yield {
        serviceV6.createCategory(id, cat) _
      }
      actionResponse(
          action
        , req
        , s"Could not create Rule category"
      ) ("createRuleCategory")
    }

    case Put("categories" :: Nil, req) => {
      val id = RuleCategoryId(uuidGen.newUuid)
      val action = for {
        restCategory <- restExtractor.extractRuleCategory(req.params)
      } yield {
        serviceV6.createCategory(id, restCategory) _
      }
      actionResponse(
          action
        , req
        , s"Could not update Rule category '${id}' details"
      ) ("createRuleCategory")
    }

  }

  // Rule API Version 6 fallback to Rule API v2 if request is not handled in V6
  override def requestDispatch(apiVersion: ApiVersion) : PartialFunction[Req, () => Box[LiftResponse]] = {
    v6Dispatch orElse apiV2.requestDispatch(apiVersion)
  }
}
