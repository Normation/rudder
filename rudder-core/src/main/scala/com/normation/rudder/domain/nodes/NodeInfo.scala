/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.rudder.domain.nodes

import com.normation.inventory.domain.AgentType
import org.joda.time.DateTime
import com.normation.inventory.domain.NodeId
import com.normation.utils.HashcodeCaching

/**
 * A NodeInfo is a read only object containing the information that will be
 * always useful about a node
 * @author Nicolas CHARLES
 *
 */
case class NodeInfo(
    id            : NodeId
  , name          : String
  , description   : String
  , hostname      : String
  , os            : String
  , ips           : List[String]
  , inventoryDate : DateTime
  , publicKey     : String
  , agentsName    : Seq[AgentType]
  , policyServerId: NodeId
  , localAdministratorAccountName: String
  , creationDate  : DateTime
  , isBroken      : Boolean
  , isSystem      : Boolean
  , isPolicyServer: Boolean
) extends HashcodeCaching
