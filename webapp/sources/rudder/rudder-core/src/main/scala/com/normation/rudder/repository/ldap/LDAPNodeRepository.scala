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

import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.ldap.ldif.LDIFNoopChangeRecord
import com.normation.ldap.sdk.LdapResult._
import com.normation.ldap.sdk._
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.NodeDit
import com.normation.rudder.domain.nodes._
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.repository.WoNodeRepository
import net.liftweb.common._

class WoLDAPNodeRepository(
    nodeDit             : NodeDit
  , mapper              : LDAPEntityMapper
  , ldap                : LDAPConnectionProvider[RwLDAPConnection]
  , actionLogger        : EventLogRepository
  ) extends WoNodeRepository with Loggable {
  repo =>

  def updateNode(node: Node, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[Node] = {
    import com.normation.rudder.services.nodes.NodeInfoService.{nodeInfoAttributes => attrs}
    repo.synchronized { for {
      con           <- ldap
      existingEntry <- con.get(nodeDit.NODES.NODE.dn(node.id.value), attrs:_*).notOptional(s"Cannot update node with id ${node.id.value} : there is no node with that id")
      oldNode       <- (mapper.entryToNode(existingEntry) ?~! s"Error when transforming LDAP entry into a node for id ${node.id.value} . Entry: ${existingEntry}").toLdapResult
      _             <- checkNodeModification(oldNode, node).toLdapResult
      // here goes the check that we are not updating policy server
      nodeEntry     =  mapper.nodeToEntry(node)
      result        <- con.save(nodeEntry, true, Seq()) ?~! s"Error when saving node entry in repository: ${nodeEntry}"
      // only record an event log if there is an actual change
      _             <- result match {
                         case LDIFNoopChangeRecord(_) => "ok".success
                         case _                       =>
                           val diff = ModifyNodeDiff(oldNode, node)
                           actionLogger.saveModifyNode(modId, actor, diff, reason).toLdapResult
                       }
    } yield {
      node
    } }
  }.toBox

  /**
   * This method allows to check if the modification that will be made on a node are licit.
   * (in particular on root node)
   */
  def checkNodeModification(oldNode: Node, newNode: Node): Box[Unit] = {
    // use cats validation
    import cats.data._
    import cats.implicits._

    type ValidationResult = ValidatedNel[String, Unit]
    val ok = ().validNel

    def rootIsEnabled(node: Node): ValidationResult = {
      if(node.state == NodeState.Enabled) ok
      else s"Root node must always be in '${NodeState.Enabled.name}' lifecycle state.".invalidNel
    }

    def rootIsPolicyServer(node: Node): ValidationResult = {
      if(node.isPolicyServer) ok
      else "You can't change the 'policy server' nature of Root policy server".invalidNel
    }

    def rootIsSystem(node: Node): ValidationResult = {
      if(node.isSystem) ok
      else "You can't change the 'system' nature of Root policy server".invalidNel
    }

    def validateRoot(node: Node): Box[Unit] = {
      // transform a validation result to a Full | Failure
      implicit def toBox(validation: ValidationResult): Box[Unit] = {
        validation.fold(
          nel => Failure(nel.toList.mkString("; "))
        ,
          _ => Full(())
        )
      }

      List(rootIsEnabled(node), rootIsPolicyServer(node), rootIsSystem(node)).sequence.map( _ => ())
    }


    if(newNode.id == Constants.ROOT_POLICY_SERVER_ID) {
      validateRoot(newNode)
    } else Full(())
  }
}
