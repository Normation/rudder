/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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

package com.normation.rudder.domain.nodes

import com.normation.inventory.domain.*
import com.normation.rudder.domain.Constants
import enumeratum.*
import java.time.Instant

final case class MachineInfo(
    id:           MachineUuid,
    machineType:  MachineType,
    systemSerial: Option[String] = None,
    manufacturer: Option[Manufacturer] = None
)

sealed trait NodeKind extends EnumEntry      {
  def name:           String
  def isPolicyServer: Boolean
}
object NodeKind       extends Enum[NodeKind] {
  case object Root  extends NodeKind { val name = "root"; val isPolicyServer = true  }
  case object Relay extends NodeKind { val name = "relay"; val isPolicyServer = true }
  case object Node  extends NodeKind { val name = "node"; val isPolicyServer = false }

  def values: IndexedSeq[NodeKind] = findValues

  def parse(kind: String): Either[String, NodeKind] = {
    val lower = kind.toLowerCase()
    values
      .find(_.name == lower)
      .toRight(s"Kind '${kind}' is not recognized as a valid node kind, expecting: '${values.map(_.name).mkString("','")}'")
  }

}

/**
 * A NodeInfo is a read only object containing the information that will be
 * always useful about a node
 */
final case class NodeInfo(
    node:                          Node,
    hostname:                      String,
    machine:                       Option[MachineInfo],
    osDetails:                     OsDetails,
    ips:                           List[String],
    inventoryDate:                 Instant,
    keyStatus:                     KeyStatus,
    agentsName:                    Seq[AgentInfo],
    policyServerId:                NodeId,
    localAdministratorAccountName: String,
    archDescription:               Option[String],
    ram:                           Option[MemorySize],
    timezone:                      Option[NodeTimezone]
) {

  val id                         = node.id
  val name                       = node.name
  val description                = node.description
  val state                      = node.state
  val isSystem                   = node.isSystem
  val isPolicyServer             = node.isPolicyServer
  val creationDate               = node.creationDate
  val nodeReportingConfiguration = node.nodeReportingConfiguration
  val properties                 = node.properties
  val policyMode                 = node.policyMode

  // kind: root, relay or simple node ?
  val nodeKind: NodeKind = (id, isPolicyServer) match {
    case (Constants.ROOT_POLICY_SERVER_ID, _) => NodeKind.Root
    case (_, true)                            => NodeKind.Relay
    case (_, false)                           => NodeKind.Node
  }

}
