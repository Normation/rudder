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

import com.normation.inventory.domain.FullInventory
import com.normation.inventory.domain.KeyStatus
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.PublicKey
import com.normation.inventory.domain.SecurityToken
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.SimpleDiff
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.facts.nodes.SecurityTag
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.HeartbeatConfiguration
import com.normation.rudder.reports.ReportingConfiguration
import net.liftweb.http.S
import org.joda.time.DateTime

/**
 * The entry point for a REGISTERED node in Rudder.
 *
 * This is independant from inventory, and can exist without one.
 *
 */
final case class Node(
    id:                         NodeId,
    name:                       String,
    description:                String,
    state:                      NodeState,
    isSystem:                   Boolean,
    isPolicyServer:             Boolean,
    creationDate:               DateTime,
    nodeReportingConfiguration: ReportingConfiguration,
    properties:                 List[NodeProperty],
    policyMode:                 Option[PolicyMode],
    securityTag:                Option[SecurityTag]
)

case object Node {
  def apply(inventory: FullInventory): Node = {
    Node(
      inventory.node.main.id,
      inventory.node.main.hostname,
      inventory.node.description.getOrElse(""),
      NodeState.Enabled,
      false,
      false,
      inventory.node.inventoryDate.getOrElse(new DateTime(0)),
      ReportingConfiguration(None, None, None),
      Nil,
      None,
      None
    )
  }
}

sealed trait NodeState { def name: String }
object NodeState       {

  final case object Enabled       extends NodeState { val name = "enabled"        }
  final case object Ignored       extends NodeState { val name = "ignored"        }
  final case object EmptyPolicies extends NodeState { val name = "empty-policies" }
  final case object Initializing  extends NodeState { val name = "initializing"   }
  final case object PreparingEOL  extends NodeState { val name = "preparing-eol"  }

  def values = ca.mrvisser.sealerate.values[NodeState]

  // human readable, sorted list of (state, label)
  def labeledPairs = {
    val a = values.toList
    val b = a.map { x =>
      x match {
        case NodeState.Initializing  => (0, x, S.?("node.states.initializing"))
        case NodeState.Enabled       => (1, x, S.?("node.states.enabled"))
        case NodeState.EmptyPolicies => (2, x, S.?("node.states.empty-policies"))
        case NodeState.Ignored       => (3, x, S.?("node.states.ignored"))
        case NodeState.PreparingEOL  => (4, x, S.?("node.states.preparing-eol"))
      }
    }

    b.sortBy(_._1).map {
      case (_, x, label) =>
        (x, label)
    }
  }

  def parse(s: String): Either[String, NodeState] = {
    values.find(_.name == s.toLowerCase) match {
      case None    =>
        Left(s"Value '${s}' is not recognized as node state. Accepted values are: '${values.map(_.name).mkString("', '")}'")
      case Some(x) => Right(x)
    }
  }

}

/**
 * Node diff for event logs:
 * Change
 * - heartbeat frequency
 * - run interval
 * - properties
 *
 * For now, other simple properties are not handle.
 */

/**
 * Denote a change on the heartbeat frequency.
 */
object ModifyNodeHeartbeatDiff {
  def apply(id: NodeId, modHeartbeat: Option[SimpleDiff[Option[HeartbeatConfiguration]]]) =
    ModifyNodeDiff(id, modHeartbeat, None, None, None, None, None)
}

/**
 * Diff on a change on agent run period
 */
object ModifyNodeAgentRunDiff {
  def apply(id: NodeId, modAgentRun: Option[SimpleDiff[Option[AgentRunInterval]]]) =
    ModifyNodeDiff(id, None, modAgentRun, None, None, None, None)
}

/**
 * Diff on the list of properties
 */
object ModifyNodePropertiesDiff {
  def apply(id: NodeId, modProperties: Option[SimpleDiff[List[NodeProperty]]]) =
    ModifyNodeDiff(id, None, None, modProperties, None, None, None)
}

/**
 * Diff on the list of properties
 */
final case class ModifyNodeDiff(
    id:            NodeId,
    modHeartbeat:  Option[SimpleDiff[Option[HeartbeatConfiguration]]],
    modAgentRun:   Option[SimpleDiff[Option[AgentRunInterval]]],
    modProperties: Option[SimpleDiff[List[NodeProperty]]],
    modPolicyMode: Option[SimpleDiff[Option[PolicyMode]]],
    modKeyValue:   Option[SimpleDiff[SecurityToken]],
    modKeyStatus:  Option[SimpleDiff[KeyStatus]]
)

object ModifyNodeDiff {
  def apply(oldNode: Node, newNode: Node): ModifyNodeDiff = {
    val policy     = if (oldNode.policyMode == newNode.policyMode) None else Some(SimpleDiff(oldNode.policyMode, newNode.policyMode))
    val properties =
      if (oldNode.properties.toSet == newNode.properties.toSet) None else Some(SimpleDiff(oldNode.properties, newNode.properties))
    val agentRun   = {
      if (oldNode.nodeReportingConfiguration.agentRunInterval == newNode.nodeReportingConfiguration.agentRunInterval) None
      else
        Some(SimpleDiff(oldNode.nodeReportingConfiguration.agentRunInterval, newNode.nodeReportingConfiguration.agentRunInterval))
    }
    val heartbeat  = {
      if (
        oldNode.nodeReportingConfiguration.heartbeatConfiguration == newNode.nodeReportingConfiguration.heartbeatConfiguration
      ) {
        None
      } else {
        Some(
          SimpleDiff(
            oldNode.nodeReportingConfiguration.heartbeatConfiguration,
            newNode.nodeReportingConfiguration.heartbeatConfiguration
          )
        )
      }
    }

    ModifyNodeDiff(newNode.id, heartbeat, agentRun, properties, policy, None, None)
  }

  def keyInfo(
      nodeId:    NodeId,
      oldKeys:   List[SecurityToken],
      oldStatus: KeyStatus,
      key:       Option[SecurityToken],
      status:    Option[KeyStatus]
  ): ModifyNodeDiff = {
    val keyInfo   = key match {
      case None    => None
      case Some(k) =>
        oldKeys match {
          case Nil    => Some(SimpleDiff(PublicKey(""), k))
          case x :: _ => if (k == x) None else Some(SimpleDiff(x, k))
        }
    }
    val keyStatus = status match {
      case None    => None
      case Some(s) => if (s == oldStatus) None else Some(SimpleDiff(oldStatus, s))
    }

    ModifyNodeDiff(nodeId, None, None, None, None, keyInfo, keyStatus)
  }
}
