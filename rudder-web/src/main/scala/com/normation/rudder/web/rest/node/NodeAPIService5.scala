/*
*************************************************************************************
* Copyright 2013 Normation SAS
*************************************************************************************
*
* This program is free soinvware: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Soinvware Foundation, either version 3 of the
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
* the Soinvware.
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

import com.normation.inventory.domain._
import com.normation.rudder.repository.WoNodeRepository
import com.normation.rudder.web.rest.RestDataSerializer
import com.normation.rudder.web.rest.RestExtractorService
import com.normation.rudder.web.rest.RestUtils._
import net.liftweb.common._
import net.liftweb.json.JsonDSL._
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.domain.nodes.NodeProperty
import com.normation.rudder.domain.nodes.JsonSerialisation._
import com.normation.eventlog.ModificationId
import com.normation.rudder.web.rest.RestUtils
import com.normation.eventlog.EventActor
import net.liftweb.common._
import com.normation.utils.StringUuidGenerator
import net.liftweb.json._
import net.liftweb.http.Req
import com.normation.rudder.domain.nodes.NodeProperty
import com.normation.rudder.domain.nodes.NodeProperty



class NodeApiService5 (
    nodeRepository : WoNodeRepository
  , nodeInfoService: NodeInfoService
  , uuidGen        : StringUuidGenerator
  , restExtractor  : RestExtractorService
) extends Loggable {


  def updateRestNode(nodeId: NodeId, boxRestNode: Box[RestNode], req: Req) = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
    implicit val action = "updateNodeProperties"
    val modId = ModificationId(uuidGen.newUuid)
    val actor = RestUtils.getActor(req)

    (for {
      reason   <- restExtractor.extractReason(req.params)
      restNode <- boxRestNode
      saved    <- restNode.properties match {
                    case None => nodeInfoService.getNode(nodeId)
                    case Some(properties) => nodeRepository.updateNodeProperties(nodeId, properties, modId, actor, reason)
                  }
    } yield {
      saved
    }) match {
      case Full(node) =>
          toJsonResponse(Some(nodeId.value), ( "properties" -> node.properties.toApiJson ))
        case eb: EmptyBox =>
          val message = (eb ?~ s"Could not update properties of node '${nodeId.value}'").messageChain
          toJsonError(Some(nodeId.value), message)
      }
  }


  /**
   * Update a set of properties with the map:
   * - if a key of the map matches a property name,
   *   use the map value for the key as value for
   *   the property
   * - if the value is the emtpy string, remove
   *   the property
   */
  private[this] def updateProperties(props: Seq[NodeProperty], updates: Option[Seq[NodeProperty]]) = {
    updates match {
      case None => props
      case Some(u) =>
        val values = u.map { case NodeProperty(k, v) => (k, v)}.toMap
        val existings = props.map(_.name).toSet
        //for news values, don't keep emptu
        val news = (values -- existings).collect { case(k,v) if(v.nonEmpty) => NodeProperty(k,v) }
        props.flatMap { case p@NodeProperty(name, value)  =>
          values.get(name) match {
            case None => Some(p)
            case Some("") => None
            case Some(x)  => Some(NodeProperty(name, x))
          }
        } ++ news
    }
  }
}


