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

import com.normation.inventory.domain.Certificate
import com.normation.inventory.domain.FullInventory
import com.normation.inventory.domain.KeyStatus
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.SecurityToken
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.SimpleDiff
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.facts.nodes.MinimalNodeFactInterface
import com.normation.rudder.facts.nodes.MinimalNodeFactInterface.toNode
import com.normation.rudder.facts.nodes.SecurityTag
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.ReportingConfiguration
import enumeratum.*
import java.time.Instant

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
    creationDate:               Instant,
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
      isSystem = false,
      isPolicyServer = false,
      creationDate = inventory.node.inventoryDate.getOrElse(Instant.ofEpochMilli(0)),
      nodeReportingConfiguration = ReportingConfiguration(None, None),
      properties = Nil,
      policyMode = None,
      securityTag = None
    )
  }
}

// isEnabled is a generic marker to decide if that state should be ignored during generation etc.
sealed abstract class NodeState(override val entryName: String, val isEnabled: Boolean) extends EnumEntry {
  def name: String = entryName
}

object NodeState extends Enum[NodeState] {

  case object Initializing  extends NodeState("initializing", true)
  case object Enabled       extends NodeState("enabled", true)
  case object EmptyPolicies extends NodeState("empty-policies", true)
  case object Ignored       extends NodeState("ignored", false)
  case object PreparingEOL  extends NodeState("preparing-eol", false)

  def values: IndexedSeq[NodeState] = findValues

  // sorted list of (state, label)
  def labeledPairs: List[(NodeState, String)] = {
    values.toList.map {
      case NodeState.Initializing  => NodeState.Initializing  -> "node.states.initializing"
      case NodeState.Enabled       => NodeState.Enabled       -> "node.states.enabled"
      case NodeState.EmptyPolicies => NodeState.EmptyPolicies -> "node.states.empty-policies"
      case NodeState.Ignored       => NodeState.Ignored       -> "node.states.ignored"
      case NodeState.PreparingEOL  => NodeState.PreparingEOL  -> "node.states.preparing-eol"
    }
  }

  def parse(s: String): Either[String, NodeState] = {
    withNameInsensitiveOption(s)
      .toRight(
        s"Value '${s}' is not recognized as node state. Accepted values are: '${values.map(_.entryName).mkString("', '")}'"
      )
  }
}

/**
 * Node diff for event logs:
 * Change
 * - run interval
 * - properties
 * - key status and value
 * - node state
 * - node policy mode
 *
 * For now, other simple properties are not handle.
 */
final case class ModifyNodeDiff(
    id:               NodeId,
    modAgentRun:      Option[SimpleDiff[Option[AgentRunInterval]]],
    modProperties:    Option[SimpleDiff[List[NodeProperty]]],
    modPolicyMode:    Option[SimpleDiff[Option[PolicyMode]]],
    modKeyValue:      Option[SimpleDiff[SecurityToken]],
    modKeyStatus:     Option[SimpleDiff[KeyStatus]],
    modNodeState:     Option[SimpleDiff[NodeState]],
    modDocumentation: Option[SimpleDiff[String]]
)

object ModifyNodeDiff {
  def fromFacts(oldNode: MinimalNodeFactInterface, newNode: MinimalNodeFactInterface): ModifyNodeDiff = {
    val keyValue  = {
      if (oldNode.rudderAgent.securityToken == newNode.rudderAgent.securityToken) None
      else Some(SimpleDiff(oldNode.rudderAgent.securityToken, newNode.rudderAgent.securityToken))
    }
    val keyStatus = {
      if (oldNode.rudderSettings.keyStatus == newNode.rudderSettings.keyStatus) None
      else Some(SimpleDiff(oldNode.rudderSettings.keyStatus, newNode.rudderSettings.keyStatus))
    }
    compat(toNode(oldNode), toNode(newNode), keyValue, keyStatus)
  }

  def compat(
      oldNode:   Node,
      newNode:   Node,
      keyValue:  Option[SimpleDiff[SecurityToken]],
      keyStatus: Option[SimpleDiff[KeyStatus]]
  ): ModifyNodeDiff = {
    val policyMode =
      if (oldNode.policyMode == newNode.policyMode) None else Some(SimpleDiff(oldNode.policyMode, newNode.policyMode))

    val properties =
      if (oldNode.properties.toSet == newNode.properties.toSet) None else Some(SimpleDiff(oldNode.properties, newNode.properties))

    val agentRun = {
      if (oldNode.nodeReportingConfiguration.agentRunInterval == newNode.nodeReportingConfiguration.agentRunInterval) None
      else
        Some(SimpleDiff(oldNode.nodeReportingConfiguration.agentRunInterval, newNode.nodeReportingConfiguration.agentRunInterval))
    }

    val state = if (oldNode.state == newNode.state) None else Some(SimpleDiff(oldNode.state, newNode.state))

    val documentation =
      if (oldNode.description == newNode.description) None else Some(SimpleDiff(oldNode.description, newNode.description))

    ModifyNodeDiff(newNode.id, agentRun, properties, policyMode, keyValue, keyStatus, state, documentation)
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
          case Nil    => Some(SimpleDiff(Certificate(""), k))
          case x :: _ => if (k == x) None else Some(SimpleDiff(x, k))
        }
    }
    val keyStatus = status match {
      case None    => None
      case Some(s) => if (s == oldStatus) None else Some(SimpleDiff(oldStatus, s))
    }

    ModifyNodeDiff(nodeId, None, None, None, keyInfo, keyStatus, None, None)
  }
}
