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

import com.normation.rudder.repository.RoDirectiveRepository
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

class DirectiveAPI2 (
    readDirective : RoDirectiveRepository
  , restExtractor : RestExtractorService
  , apiV2       : DirectiveAPIService2
) extends RestHelper with DirectiveAPI with Loggable{


  val requestDispatch : PartialFunction[Req, () => Box[LiftResponse]] = {

    case Get(Nil, req) => apiV2.listDirectives(req)

    case Put(Nil, req) => {
      val restDirective = restExtractor.extractDirective(req.params)
      apiV2.createDirective(restDirective, req)
    }

    case Get(id :: Nil, req) => apiV2.directiveDetails(id, req)

    case Delete(id :: Nil, req) =>  apiV2.deleteDirective(id,req)

    case Post(id:: Nil, req) => {
      val restDirective = restExtractor.extractDirective(req.params)
      apiV2.updateDirective(id,req,restDirective)
    }

    case id :: Nil JsonPost body -> req => {
      req.json match {
        case Full(arg) =>
          val restDirective = restExtractor.extractDirectiveFromJSON(arg)
          apiV2.updateDirective(id,req,restDirective)
        case eb:EmptyBox=>    toJsonError(None, JString("No Json data sent"))("updateDirective",true)
      }
    }

  }
  serve( "api" / "2" / "directives" prefix requestDispatch)

}
