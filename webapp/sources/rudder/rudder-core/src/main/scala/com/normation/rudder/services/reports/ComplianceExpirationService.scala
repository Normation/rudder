/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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

package com.normation.rudder.services.reports

import com.normation.errors.IOResult
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.logger.ComplianceLogger
import com.normation.rudder.domain.logger.ComplianceLoggerPure
import com.normation.rudder.domain.properties.GenericProperty
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.domain.reports.NodeComplianceExpiration
import com.normation.rudder.properties.PropertiesRepository
import com.normation.rudder.tenants.QueryContext
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigRenderOptions
import zio.Chunk
import zio.ZIO
import zio.json.*
import zio.syntax.*

/**
 * This service is in charge of getting the duration of compliance validity for a node when
 * its status is no reports
 */
trait ComplianceExpirationService {

  /**
   * Get the expiration policy for the set of nodes given as parameter
   */
  def getExpirationPolicy(nodeIds: Iterable[NodeId]): IOResult[Map[NodeId, NodeComplianceExpiration]]

}

class DummyComplianceExpirationService(policy: NodeComplianceExpiration) extends ComplianceExpirationService {
  override def getExpirationPolicy(nodeIds: Iterable[NodeId]): IOResult[Map[NodeId, NodeComplianceExpiration]] = {
    nodeIds.map(id => (id, policy)).toMap.succeed
  }
}

/*
 * A version that uses node properties, but only direct one, not inherited ones
 * (that's NOT what we want, but it's free. The inherited version need to be
 * able to have a cache of computed node properties)
 */
class NodePropertyBasedComplianceExpirationService(
    propRepository: PropertiesRepository,
    propertyKey:    String,
    propertyName:   String
) extends ComplianceExpirationService {

  override def getExpirationPolicy(nodeIds: Iterable[NodeId]): IOResult[Map[NodeId, NodeComplianceExpiration]] = {
    val ids = nodeIds.toSet
    for {
      propMap <- propRepository.getNodesProp(ids, propertyKey)(using QueryContext.systemQC)
      res      = nodeIds.map { id =>
                   (
                     id,
                     propMap.get(id) match {
                       case Some(p) => NodePropertyBasedComplianceExpirationService.getPolicy(p.prop, propertyKey, propertyName, id)
                       case None    => NodeComplianceExpiration.default
                     }
                   )
                 }
      _       <- ComplianceLoggerPure.ifDebugEnabled(ZIO.foreachDiscard(res) {
                   case (id, r) => ComplianceLoggerPure.debug(s"Compliance expiration policy for node '${id.value}' is ${r}")
                 })
    } yield {
      res.toMap
    }
  }
}

object NodePropertyBasedComplianceExpirationService {
  // default key of the JSON structure containing the compliance duration configuration
  val PROP_NAME     = "rudder"
  // default name of the sub-key for compliance duration configuration value.
  val PROP_SUB_NAME = "compliance_expiration_policy"

  def getPolicy(p: GenericProperty[?], propKey: String, propName: String, debugId: NodeId): NodeComplianceExpiration = {
    try {
      // direct access to implementation details is so-so, but alternative force to go
      // to json and is quite convoluted, and ConfigValue is horrible.
      // We are looking for a json structure like:
      //  { "mode":"keep_last", "duration":"1 hour" }
      // Or for immediate expiration:
      //  { "mode":"expire_immediately" }
      p.config
        .getObject("value." + propName)
        .render(ConfigRenderOptions.concise())
        .fromJson[NodeComplianceExpiration]
        .getOrElse(NodeComplianceExpiration.default)
    } catch {
      case ex: ConfigException       =>
        NodeComplianceExpiration.default
      case ex: NumberFormatException =>
        ComplianceLogger.error(
          s"Node with id '${debugId.value}' has the keep expired compliance property set (${propKey}.${propName}) but value can not be parsed: ${ex.getMessage}"
        )
        NodeComplianceExpiration.default
    }
  }

  def getPolicyFromProp(
      properties: Chunk[NodeProperty],
      propKey:    String,
      propName:   String,
      debugId:    NodeId
  ): NodeComplianceExpiration = {
    properties.find(_.name == propKey) match {
      case Some(p) => getPolicy(p, propKey, propName, debugId)
      case None    => NodeComplianceExpiration.default
    }
  }
}
