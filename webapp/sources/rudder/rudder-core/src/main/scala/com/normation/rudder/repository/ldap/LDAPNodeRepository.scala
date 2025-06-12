/*
 *************************************************************************************
 * Copyright 2014 Normation SAS
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
package com.normation.rudder.repository.ldap

import com.normation.NamedZioLogger
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.AgentInfo
import com.normation.inventory.domain.Certificate
import com.normation.inventory.domain.KeyStatus
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.SecurityToken
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.ldap.ldif.LDIFNoopChangeRecord
import com.normation.ldap.sdk.*
import com.normation.ldap.sdk.LDAPIOResult.*
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.NodeDit
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.repository.WoNodeRepository
import com.normation.rudder.services.reports.CacheComplianceQueueAction
import com.normation.rudder.services.reports.CacheExpectedReportAction
import com.normation.rudder.services.reports.InvalidateCache
import java.time.Instant
import zio.*
import zio.json.*
import zio.syntax.*

class WoLDAPNodeRepository(
    nodeDit:      NodeDit,
    acceptedDit:  InventoryDit,
    mapper:       LDAPEntityMapper,
    ldap:         LDAPConnectionProvider[RwLDAPConnection],
    actionLogger: EventLogRepository,
    nodeLibMutex: ScalaReadWriteLock, // that's a scala-level mutex to have some kind of consistency with LDAP

    cacheExpectedReports: InvalidateCache[CacheExpectedReportAction],
    cacheConfiguration:   InvalidateCache[CacheComplianceQueueAction]
) extends WoNodeRepository with NamedZioLogger {
  repo =>

  override def loggerName: String = this.getClass.getName

  def updateNode(node: Node, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[Node] = {
    import com.normation.rudder.services.nodes.NodeInfoService.nodeInfoAttributes as attrs
    nodeLibMutex.writeLock(
      for {
        con           <- ldap
        existingEntry <- con
                           .get(nodeDit.NODES.NODE.dn(node.id.value), attrs*)
                           .notOptional(s"Cannot update node with id ${node.id.value} : there is no node with that id")
        oldNode       <-
          mapper
            .entryToNode(existingEntry)
            .toIO
            .chainError(s"Error when transforming LDAP entry into a node for id ${node.id.value} . Entry: ${existingEntry}")
        _             <- checkNodeModification(oldNode, node)
        // here goes the check that we are not updating policy server
        nodeEntry      = mapper.nodeToEntry(node)
        result        <- con
                           .save(nodeEntry, removeMissingAttributes = true)
                           .chainError(s"Error when saving node entry in repository: ${nodeEntry}")
        // only record an event log if there is an actual change
        _             <- result match {
                           case LDIFNoopChangeRecord(_) => ZIO.unit
                           case _                       =>
                             val diff = ModifyNodeDiff.compat(oldNode, node, None, None)
                             actionLogger.saveModifyNode(modId, actor, diff, reason, Instant.now())
                         }
      } yield {
        node
      }
    )
  }

  def updateNodeKeyInfo(
      nodeId:         NodeId,
      agentKey:       Option[SecurityToken],
      agentKeyStatus: Option[KeyStatus],
      modId:          ModificationId,
      actor:          EventActor,
      reason:         Option[String]
  ): IOResult[Unit] = {
    def updateInfo(
        oldInfo:        (Seq[AgentInfo], KeyStatus),
        agentKey:       Option[SecurityToken],
        agentKeyStatus: Option[KeyStatus]
    ): (List[AgentInfo], KeyStatus) = {
      val agents = agentKey match {
        case None    => oldInfo._1
        case Some(k) => oldInfo._1.map(_.copy(securityToken = k))
      }
      val status = agentKeyStatus.getOrElse(oldInfo._2)

      (agents.toList, status)
    }
    import com.normation.inventory.ldap.core.LDAPConstants.{A_KEY_STATUS, A_AGENT_NAME}
    if (agentKey.isEmpty && agentKeyStatus.isEmpty) ZIO.unit
    else {
      nodeLibMutex.writeLock(for {
        // check that in the case of a certificate, we are using a certificate for the node
        _             <- agentKey match {
                           case Some(Certificate(value)) => SecurityToken.checkCertificateForNode(nodeId, Certificate(value))
                           case _                        => ZIO.unit
                         }
        con           <- ldap
        existingEntry <- con
                           .get(acceptedDit.NODES.NODE.dn(nodeId.value), A_KEY_STATUS :: A_AGENT_NAME :: Nil*)
                           .notOptional(
                             s"Cannot update node with id ${nodeId.value}: there is no node with that id"
                           )
        agentsInfo    <-
          mapper
            .parseAgentInfo(nodeId, existingEntry)
            .chainError(
              s"Error when getting agent key information from LDAP entry for node with id ${nodeId.value} . Entry: ${existingEntry}"
            )
        newInfo        = updateInfo(agentsInfo, agentKey, agentKeyStatus)
        dn             = acceptedDit.NODES.NODE.dn(nodeId.value)
        result        <- if (agentsInfo == newInfo) LDIFNoopChangeRecord(dn).succeed
                         else {
                           val e = LDAPEntry(dn)
                           e.addValues(A_AGENT_NAME, newInfo._1.map(_.toJson)*)
                           e.addValues(A_KEY_STATUS, newInfo._2.value)

                           con.save(e).chainError(s"Error when saving node entry in repository: ${e}")
                         }
        // only record an event log if there is an actual change
        _             <- result match {
                           case LDIFNoopChangeRecord(_) => ZIO.unit
                           case _                       =>
                             val diff =
                               ModifyNodeDiff.keyInfo(nodeId, agentsInfo._1.map(_.securityToken), agentsInfo._2, agentKey, agentKeyStatus)
                             actionLogger.saveModifyNode(modId, actor, diff, reason, Instant.now())
                         }
      } yield ())
    }
  }

  /**
   * This method allows to check if the modification that will be made on a node are licit.
   * (in particular on root node)
   */
  def checkNodeModification(oldNode: Node, newNode: Node): IOResult[Unit] = {
    // use cats validation
    import cats.data.*
    import cats.implicits.*

    type ValidationResult = ValidatedNel[String, Unit]
    val ok = ().validNel

    def rootIsEnabled(node: Node): ValidationResult = {
      if (node.state == NodeState.Enabled) ok
      else s"Root node must always be in '${NodeState.Enabled.name}' lifecycle state.".invalidNel
    }

    def rootIsPolicyServer(node: Node): ValidationResult = {
      if (node.isPolicyServer) ok
      else "You can't change the 'policy server' nature of Root policy server".invalidNel
    }

    def rootIsSystem(node: Node): ValidationResult = {
      if (node.isSystem) ok
      else "You can't change the 'system' nature of Root policy server".invalidNel
    }

    def validateRoot(node: Node): IOResult[Unit] = {
      // transform a validation result to a Full | Failure
      implicit def toIOResult(validation: ValidationResult): IOResult[Unit] = {
        validation.fold(
          nel => nel.toList.mkString("; ").fail,
          _ => ZIO.unit
        )
      }

      List(rootIsEnabled(node), rootIsPolicyServer(node), rootIsSystem(node)).sequence.map(_ => ())
    }

    ZIO.when(newNode.id == Constants.ROOT_POLICY_SERVER_ID)(validateRoot(newNode)).unit
  }

}
