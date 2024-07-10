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
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.domain.reports.NodeComplianceExpiration
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.typesafe.config.ConfigException
import zio.Chunk
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
class NodePropertyBasedComplianceExpirationService(factRepo: NodeFactRepository, propertyKey: String, propertyName: String)
    extends ComplianceExpirationService {

  override def getExpirationPolicy(nodeIds: Iterable[NodeId]): IOResult[Map[NodeId, NodeComplianceExpiration]] = {
    val ids = nodeIds.toSet
    factRepo
      .getAll()(QueryContext.systemQC)
      .map(_.collect {
        case (id, fact) if (ids.contains(id)) =>
          val p = NodePropertyBasedComplianceExpirationService.getPolicyFromProp(fact.properties, propertyKey, propertyName, id)
          (id, p)
      }.toMap)
  }
}

object NodePropertyBasedComplianceExpirationService {
  def getPolicy(p: NodeProperty, propKey: String, propName: String, debugId: NodeId): NodeComplianceExpiration = {
    try {
      // direct access to implementation details is so-so, but alternative force to go
      // to json and is quite convoluted, and ConfigValue is horrible.
      val v = p.config.getString("value." + propName)
      NodeComplianceExpiration.KeepLast(scala.concurrent.duration.Duration(v))
    } catch {
      case ex: ConfigException       =>
        ex.printStackTrace()
        NodeComplianceExpiration.ExpireImmediately
      case ex: NumberFormatException =>
        ComplianceLogger.error(
          s"Node with id '${debugId.value}' has the keep expired compliance property set (${propKey}.${propName}) but value can not be parsed: ${ex.getMessage}"
        )
        NodeComplianceExpiration.ExpireImmediately
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
      case None    => NodeComplianceExpiration.ExpireImmediately
    }
  }
}
