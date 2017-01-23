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
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.web.rest.ApiVersion
import com.normation.rudder.web.rest.RestDataSerializer
import com.normation.rudder.web.rest.RestExtractorService
import com.normation.rudder.web.rest.RestUtils
import com.normation.rudder.web.rest.RestUtils.toJsonResponse

import net.liftweb.common.Box
import net.liftweb.common.Loggable
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.http.rest.RestHelper
import net.liftweb.json.JsonAST.JString
import com.normation.rudder.datasources.DataSourceId

class NodeAPI9 (
    apiV8        : NodeAPI8
  , apiV9service : NodeApiService9
  , extractor    : RestExtractorService
) extends RestHelper with NodeAPI with Loggable{

  val v9Dispatch : PartialFunction[Req, () => Box[LiftResponse]] = {

    case Post( "reloadDatasources" :: Nil, req) => {
      implicit val prettify = extractor.extractPrettify(req.params)
      implicit val action = "reloadAllDatasourcesAllNodes"
      val actor = RestUtils.getActor(req)

      apiV9service.reloadDataAllNodes(actor)

      toJsonResponse(None, JString("Data for all nodes, for all configured data sources are going to be updated"))
    }


    case Post( "reloadDatasources" :: datasourceId :: Nil, req) => {
      implicit val prettify = extractor.extractPrettify(req.params)
      implicit val action = "reloadOneDatasourceAllNodes"
      val actor = RestUtils.getActor(req)

      apiV9service.reloadDataAllNodesFor(actor, DataSourceId(datasourceId))

      toJsonResponse(None, JString("Data for all nodes, for all configured data sources are going to be updated"))
    }

    case Post(nodeId :: "reloadDatasources" :: Nil, req) => {
      implicit val prettify = extractor.extractPrettify(req.params)
      implicit val action = "reloadAllDatasourcesOneNode"
      val actor = RestUtils.getActor(req)

      apiV9service.reloadDataOneNode(actor, NodeId(nodeId))

      toJsonResponse(None, JString(s"Data for node '${nodeId}', for all configured data sources, is going to be updated"))
    }

    case Post(nodeId :: "reloadDatasources" :: datasourceId :: Nil, req) => {
      implicit val prettify = extractor.extractPrettify(req.params)
      implicit val action = "reloadOneDatasourceOneNode"
      val actor = RestUtils.getActor(req)

      apiV9service.reloadDataOneNodeFor(actor, NodeId(nodeId), DataSourceId(datasourceId))

      toJsonResponse(None, JString(s"Data for node '${nodeId}', for all configured data sources, is going to be updated"))
    }
  }

  override def requestDispatch(apiVersion: ApiVersion) : PartialFunction[Req, () => Box[LiftResponse]] = {
    v9Dispatch orElse apiV8.requestDispatch(apiVersion)
  }
}
