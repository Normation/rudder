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
package ldap

import com.normation.eventlog.EventActor
import com.normation.eventlog.EventLog
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.NodeId
import com.normation.ldap.sdk._
import com.normation.rudder.domain.NodeDit
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.rudder.domain.nodes._
import com.normation.rudder.domain.policies.SimpleDiff
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.HeartbeatConfiguration



import net.liftweb.common._


class WoLDAPNodeRepository(
    nodeDit             : NodeDit
  , mapper              : LDAPEntityMapper
  , ldap                : LDAPConnectionProvider[RwLDAPConnection]
  , actionLogger        : EventLogRepository
  ) extends WoNodeRepository with Loggable {
  repo =>

  /**
   * Change the configuration of agent run pertion for the given node
   */
  def updateAgentRunPeriod(nodeId: NodeId, agentRun: AgentRunInterval, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[Node] = {
    val updateNode = (node: Node) => {
      val reportConf  = node.nodeReportingConfiguration.copy( agentRunInterval = Some(agentRun) )
      node.copy(nodeReportingConfiguration = reportConf)
    }
    val log = (oldNode: Node, newNode: Node) => {
      val diff = ModifyNodeAgentRunDiff(nodeId, if(oldNode.nodeReportingConfiguration.agentRunInterval == newNode.nodeReportingConfiguration.agentRunInterval) None
                                             else Some(SimpleDiff(oldNode.nodeReportingConfiguration.agentRunInterval, newNode.nodeReportingConfiguration.agentRunInterval))
      )
      actionLogger.saveModifyNodeAgentRun(modId, principal = actor, modifyDiff = diff, reason = reason)
    }
    update(nodeId, updateNode, log)
  }

  /**
   * Change the configuration of heartbeat frequency for the given node
   */
  def updateNodeHeartbeat(nodeId: NodeId, heartbeat: HeartbeatConfiguration, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[Node] = {
    val updateNode = (node: Node) => {
      val reportConf  = node.nodeReportingConfiguration.copy( heartbeatConfiguration = Some(heartbeat))
      node.copy(nodeReportingConfiguration = reportConf)
    }
    val log = (oldNode: Node, newNode: Node) => {
      val diff = ModifyNodeHeartbeatDiff(nodeId, if(oldNode.nodeReportingConfiguration.heartbeatConfiguration == newNode.nodeReportingConfiguration.heartbeatConfiguration) None
                                             else Some(SimpleDiff(oldNode.nodeReportingConfiguration.heartbeatConfiguration, newNode.nodeReportingConfiguration.heartbeatConfiguration))
      )
      actionLogger.saveModifyNodeHeartbeat(modId, principal = actor, modifyDiff = diff, reason = reason)
    }
    update(nodeId, updateNode, log)
  }


  /**
   * Update the list of properties for the node, setting the content to exactly
   * what is given in paramater
   */
  def updateNodeProperties(nodeId: NodeId, properties: Seq[NodeProperty], modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[Node] = {
    val updateNode = (node: Node) => node.copy(properties = properties)
    val log = (oldNode: Node, newNode: Node) => {
      val diff = ModifyNodePropertiesDiff(nodeId, if(oldNode.properties.toSet == newNode.properties.toSet) None
                                                  else Some(SimpleDiff(oldNode.properties, newNode.properties))
      )
      actionLogger.saveModifyNodeProperties(modId, principal = actor, modifyDiff = diff, reason = reason)
    }
    update(nodeId, updateNode, log)
  }

  /**
   * Update the node with the given ID with the given
   * parameters.
   *
   * If the node is not in the repos, the method fails.
   * If the node is a system one, the methods fails.
   */
  private[this] def update(nodeId: NodeId, updateNode: Node => Node, logAction: (Node, Node) => Box[EventLog]) : Box[Node] = {
    import com.normation.rudder.services.nodes.NodeInfoService.{nodeInfoAttributes => attrs}
    repo.synchronized { for {
      con           <- ldap
      existingEntry <- con.get(nodeDit.NODES.NODE.dn(nodeId.value), attrs:_*) ?~! s"Cannot update node with id ${nodeId.value} : there is no node with that id"
      oldNode       <- mapper.entryToNode(existingEntry) ?~! s"Error when transforming LDAP entry into a node for id ${nodeId.value} . Entry: ${existingEntry}"
      // here goes the check that we are not updating policy server
      node          =  updateNode(oldNode)
      nodeEntry     =  mapper.nodeToEntry(node)
      result        <- con.save(nodeEntry, true, Seq()) ?~! s"Error when saving node entry in repository: ${nodeEntry}"
      loggedAction  <- logAction(oldNode, node)
      // here goes the diff
    } yield {
      node
    } }
  }

}
