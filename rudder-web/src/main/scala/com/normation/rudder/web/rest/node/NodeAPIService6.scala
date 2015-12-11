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

package com.normation.rudder.web.rest.node

import com.normation.rudder.services.nodes._
import net.liftweb.common._
import com.normation.inventory.ldap.core._
import com.normation.rudder.web.rest._
import com.normation.rudder.web.rest.RestUtils._
import com.normation.rudder.services.queries._
import com.normation.inventory.domain._
import com.normation.rudder.domain.nodes._
import net.liftweb.json._
import net.liftweb.json.JsonDSL._
import com.normation.rudder.domain.queries.Query

case class NodeApiService6 (
    nodeInfoService   : NodeInfoService
  , inventoryRepository : LDAPFullInventoryRepository
  , restExtractor     : RestExtractorService
  , restSerializer    : RestDataSerializer
  , acceptedNodeQueryProcessor: QueryProcessor
) extends Loggable {

  import restSerializer._
  def listNodes ( state: InventoryStatus, detailLevel : NodeDetailLevel, nodeFilter : Option[Seq[NodeId]], version : ApiVersion)( implicit prettify : Boolean) = {
    implicit val action = s"list${state.name.capitalize}Nodes"
    ( for {
        inventories <- inventoryRepository.getAllInventories(state)
        nodes <- state match {
          case AcceptedInventory => nodeInfoService.getAllNodes
          case _ => Full(inventories.mapValues(Node(_)))
        }
      } yield {
        val nodeIds = nodeFilter.getOrElse(inventories.keys)
        for {
          nodeId <- nodeIds
          inventory <- inventories.get(nodeId)
          node <- nodes.get(nodeId)
        } yield {
          serializeInventory(node, inventory, detailLevel, version)
        }

      }
    ) match {
      case Full(nodes) => {
        toJsonResponse(None, ( "nodes" -> JArray(nodes.toList)))
      }
      case eb: EmptyBox => {
        val message = (eb ?~ (s"Could not fetch ${state.name} Nodes")).msg
        toJsonError(None, message)
      }
    }
  }

  def queryNodes ( query: Query, state: InventoryStatus, detailLevel : NodeDetailLevel, version : ApiVersion)( implicit prettify : Boolean) = {
    implicit val action = s"list${state.name.capitalize}Nodes"
    ( for {
        nodeIds <-  acceptedNodeQueryProcessor.process(query).map(_.map(_.id))
      } yield {
        listNodes(state,detailLevel,Some(nodeIds),version)
      }
    ) match {
      case Full(resp) => {
        resp
      }
      case eb: EmptyBox => {
        val message = (eb ?~ (s"Could not find ${state.name} Nodes")).msg
        toJsonError(None, message)
      }
    }
  }

}
