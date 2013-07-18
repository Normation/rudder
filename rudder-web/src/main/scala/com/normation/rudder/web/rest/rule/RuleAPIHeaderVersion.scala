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

import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.web.rest.RestUtils.notValidVersionResponse
import com.normation.rudder.web.rest.RestUtils.toJsonError
import com.normation.rudder.web.rest.RestExtractorService

import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.http.rest.RestHelper
import net.liftweb.json.JString

class RuleAPIHeaderVersion (
    readRule             : RoRuleRepository
  , restExtractor        : RestExtractorService
  , apiV2              : RuleApiService2
) extends RestHelper with RuleAPI with Loggable{


  val requestDispatch : PartialFunction[Req, () => Box[LiftResponse]] = {

    case Get(Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("2") => apiV2.listRules(req)
        case _ => notValidVersionResponse("listRules")
      }
    }

    case Put(Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("2") =>
          val restRule = restExtractor.extractRule(req.params)
          apiV2.createRule(restRule, req)
        case _ => notValidVersionResponse("createRule")
      }
    }

    case Get(id :: Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("2") => apiV2.ruleDetails(id, req)
        case _ => notValidVersionResponse("listRules")
      }
    }

    case Delete(id :: Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("2") => apiV2.deleteRule(id,req)
        case _ => notValidVersionResponse("deleteRule")
      }
    }

    case Post(id:: Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("2") =>
          val restRule = restExtractor.extractRule(req.params)
          apiV2.updateRule(id,req,restRule)
        case _ => notValidVersionResponse("updateRule")
      }
    }

    case id :: Nil JsonPost body -> req => {
      req.header("X-API-VERSION") match {
        case Full("2") =>
      req.json match {
        case Full(arg) =>
          val restRule = restExtractor.extractRuleFromJSON(arg)
          apiV2.updateRule(id,req,restRule)
        case eb:EmptyBox=>    toJsonError(Some(id), JString("no Json Data sent"))("updateRule",true)
      }
        case _ => notValidVersionResponse("updateRule")
      }

    }
  }
  serve( "api" / "rules" prefix requestDispatch)

}
