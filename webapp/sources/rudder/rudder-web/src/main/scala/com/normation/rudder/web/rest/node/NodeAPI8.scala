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

package com.normation.rudder.web.rest.node

import com.normation.inventory.domain.NodeId
import net.liftweb.common.Box
import net.liftweb.common.Loggable
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.http.rest.RestHelper
import com.normation.rudder.web.rest.RestExtractorService
import com.normation.rudder.web.rest.RestUtils._
import net.liftweb.common._
import net.liftweb.json.JsonDSL._
import com.normation.rudder.web.rest.ApiVersion
import net.liftweb.http.OutputStreamResponse

class NodeAPI8 (
    apiV6        : NodeAPI6
  , apiV8service : NodeApiService8
  , extractor    : RestExtractorService
) extends RestHelper with NodeAPI with Loggable{

  val v8Dispatch : PartialFunction[Req, () => Box[LiftResponse]] = {

    case Post( "applyPolicy" ::Nil, req) => {
      implicit val prettify = extractor.extractPrettify(req.params)
      implicit val action = "applyPolicyAllNodes"

      (for {
        classes <- extractor.extractList("classes")(req)(json => Full(json))
        response <- apiV8service.runAllNodes(classes)
      } yield {
        toJsonResponse(None, response)
      }) match {
        case Full(response) => response
        case eb : EmptyBox => {
          implicit val prettify = extractor.extractPrettify(req.params)
          implicit val action = "applyPolicy"
          val fail = eb ?~! s"An error occured when applying policy on all Nodes"
          toJsonError(None, fail.messageChain)
        }
      }
    }

    case Post(id :: "applyPolicy" ::Nil, req) => {

      (for {
        classes <- extractor.extractList("classes")(req)(json => Full(json))
        response <- apiV8service.runNode(NodeId(id),classes)
      } yield {
        OutputStreamResponse(response)
      }) match {
        case Full(response) => response
        case eb : EmptyBox => {
          implicit val prettify = extractor.extractPrettify(req.params)
          implicit val action = "applyPolicy"
          val fail = eb ?~! s"An error occured when applying policy on Node '${id}'"
          toJsonError(Some(id), fail.messageChain)

        }
      }
    }

  }

  override def requestDispatch(apiVersion: ApiVersion) : PartialFunction[Req, () => Box[LiftResponse]] = {
    v8Dispatch orElse apiV6.requestDispatch(apiVersion)
  }
}
