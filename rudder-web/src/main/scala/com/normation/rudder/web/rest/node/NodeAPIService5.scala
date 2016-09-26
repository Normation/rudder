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

package com.normation.rudder.web.rest.node

import com.normation.eventlog.ModificationId

import com.normation.inventory.domain._
import com.normation.rudder.batch.AsyncDeploymentAgent
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.nodes.JsonSerialisation._
import com.normation.rudder.domain.nodes.NodeProperty
import com.normation.rudder.repository.WoNodeRepository
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.web.rest.RestExtractorService
import com.normation.rudder.web.rest.RestUtils._
import com.normation.rudder.web.rest.RestUtils
import com.normation.utils.StringUuidGenerator

import net.liftweb.common._
import net.liftweb.http.Req
import net.liftweb.json._
import net.liftweb.json.JsonDSL._

class NodeApiService5 (
    nodeRepository : WoNodeRepository
  , nodeInfoService: NodeInfoService
  , uuidGen        : StringUuidGenerator
  , restExtractor  : RestExtractorService
  , asyncRegenerate: AsyncDeploymentAgent
) extends Loggable {

  def updateRestNode(nodeId: NodeId, boxRestNode: Box[RestNodeProperties], req: Req) = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
    implicit val action = "updateNodeProperties"
    val modId = ModificationId(uuidGen.newUuid)
    val actor = RestUtils.getActor(req)

    (for {
      reason   <- restExtractor.extractReason(req)
      node     <- nodeInfoService.getNode(nodeId)
      restNode <- boxRestNode
      updated  =  node.copy(properties = CompareProperties.updateProperties(node.properties, restNode.properties))
      saved    <- if(updated == node) Full(node)
                  else nodeRepository.updateNode(updated, modId, actor, reason)
    } yield {
      if(node != updated) {
        asyncRegenerate ! AutomaticStartDeployment(ModificationId(uuidGen.newUuid), CurrentUser.getActor)
      }
      saved
    }) match {
      case Full(node) =>
          toJsonResponse(Some(nodeId.value), ( "properties" -> node.properties.toApiJson ))
        case eb: EmptyBox =>
          val message = (eb ?~ s"Could not update properties of node '${nodeId.value}'").messageChain
          toJsonError(Some(nodeId.value), message)
      }
  }
}
