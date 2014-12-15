/*
*************************************************************************************
* Copyright 2014 Normation SAS
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

package com.normation.rudder.repository

import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.nodes._
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.HeartbeatConfiguration
import net.liftweb.common._
import com.normation.inventory.domain.NodeId

/**
 * Node Repository
 * To update the Node Run Configuration
 */
trait WoNodeRepository {

  /**
   * Change the configuration of agent run period for the given node
   */
  def updateAgentRunPeriod(nodeId: NodeId, agentRun: AgentRunInterval, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[Node]

  /**
   * Change the configuration of heartbeat frequency for the given node
   */
  def updateNodeHeartbeat(nodeId: NodeId, heartbeat: HeartbeatConfiguration, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[Node]


  /**
   * Update the list of properties for the node, setting the content to exactly
   * what is given in paramater
   */
  def updateNodeProperties(nodeId: NodeId, properties: Seq[NodeProperty], modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[Node]
}
