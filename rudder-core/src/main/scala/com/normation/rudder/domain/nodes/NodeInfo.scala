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

import com.normation.inventory.domain.AgentType
import org.joda.time.DateTime
import com.normation.inventory.domain.NodeId
import com.normation.utils.HashcodeCaching
import com.normation.inventory.domain.ServerRole
import com.normation.rudder.reports.ReportingConfiguration
import com.normation.inventory.domain.MachineType
import com.normation.inventory.domain.Manufacturer
import com.normation.inventory.domain.MemorySize
import com.normation.inventory.domain.OsDetails
import com.normation.inventory.domain.MachineUuid
import com.normation.inventory.domain.KeyStatus

final case class MachineInfo(
    id          : MachineUuid
  , machineType : MachineType
  , systemSerial: Option[String]
  , manufacturer: Option[Manufacturer]
)

/**
 * A NodeInfo is a read only object containing the information that will be
 * always useful about a node
 */
final case class NodeInfo(
    node           : Node
  , hostname       : String
  , machine        : Option[MachineInfo]
  , osDetails      : OsDetails
  , ips            : List[String]
  , inventoryDate  : DateTime
  , publicKey      : String
  , keyStatus      : KeyStatus
  , agentsName     : Seq[AgentType]
  , policyServerId : NodeId
  , localAdministratorAccountName: String
  //for now, isPolicyServer and server role ARE NOT
  //dependant. So EXPECTS inconsistencies.
  //TODO: remove isPolicyServer, and pattern match on
  //      on role everywhere.
  , serverRoles    : Set[ServerRole]
  , archDescription: Option[String]
  , ram            : Option[MemorySize]
) extends HashcodeCaching {

  val id                         = node.id
  val name                       = node.name
  val description                = node.description
  val isBroken                   = node.isBroken
  val isSystem                   = node.isSystem
  val isPolicyServer             = node.isPolicyServer
  val creationDate               = node.creationDate
  val nodeReportingConfiguration = node.nodeReportingConfiguration
  val properties                 = node.properties
  val policyMode                 = node.policyMode

}
