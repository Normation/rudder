/*
*************************************************************************************
* Copyright 2015 Normation SAS
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
import com.normation.inventory.domain._


case class NodeAPI6 (
    apiV5     : NodeAPI5
  , serviceV6 : NodeApiService6
  , restExtractor : RestExtractorService
) extends NodeAPI with Loggable{


  val v6Dispatch : PartialFunction[Req, () => Box[LiftResponse]] = {

    case Get(Nil, req) =>
      implicit val prettify = restExtractor.extractPrettify(req.params)
      restExtractor.extractNodeDetailLevel(req.params) match {
        case Full(level) =>
          restExtractor.extractQuery(req.params) match {
            case Full(None) =>
              serviceV6.listNodes(AcceptedInventory, level, None)
            case Full(Some(query)) =>
              serviceV6.queryNodes(query,AcceptedInventory, level)

          }

        case eb:EmptyBox =>
          val failMsg = eb ?~ "node detail level not correctly sent"
          toJsonError(None, failMsg.msg)("listAcceptedNodes",prettify)
      }

    case Get("pending" :: Nil, req) =>
      implicit val prettify = restExtractor.extractPrettify(req.params)
      restExtractor.extractNodeDetailLevel(req.params) match {
        case Full(level) => serviceV6.listNodes(PendingInventory, level, None)
        case eb:EmptyBox =>
          val failMsg = eb ?~ "node detail level not correctly sent"
          toJsonError(None, failMsg.msg)("listAcceptedNodes",prettify)
      }
  }


  // Node API Version 6 fallback to Node API v5 if request is not handled in V6
  val requestDispatch : PartialFunction[Req, () => Box[LiftResponse]] = v6Dispatch orElse apiV5.requestDispatch
}
