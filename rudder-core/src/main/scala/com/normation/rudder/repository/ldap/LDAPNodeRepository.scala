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

import net.liftweb.common._
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.archives.RuleArchiveId
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.nodes._
import com.normation.rudder.domain.{RudderDit,RudderLDAPConstants}
import RudderLDAPConstants._
import com.unboundid.ldap.sdk.{DN,Filter}
import com.normation.ldap.sdk._
import com.normation.rudder.services.user.PersonIdentService
import com.normation.rudder.domain.NodeDit


class WoLDAPNodeRepository(
    nodeDit             : NodeDit
  , mapper              : LDAPEntityMapper
  , ldap                : LDAPConnectionProvider[RwLDAPConnection]
  , diffMapper          : LDAPDiffMapper
  , actionLogger        : EventLogRepository
  , personIdentService  : PersonIdentService
  ) extends WoNodeRepository with Loggable {
  repo =>

  /**
   * Update the node with the given ID with the given
   * parameters.
   *
   * If the node is not in the repos, the method fails.
   * If the node is a system one, the methods fails.
   */
  def update(node:Node, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[Option[String]] = {
    repo.synchronized { for {
      con           <- ldap
      existingEntry <- con.get(nodeDit.NODES.NODE.dn(node.id.value)) ?~! s"Cannot update node with id ${node.id.value} : there is no node with that id"
      oldNode       <- mapper.entryToNode(existingEntry) ?~! "Error when transforming LDAP entry into a node for id ${node.id.value} . Entry: ${existingEntry}"
// here goes the check that we are not updating policy server
      nodeEntry     =  mapper.nodeToEntry(node)
      result        <- con.save(nodeEntry, true, Seq()) ?~! s"Error when saving node entry in repository: ${nodeEntry}"
// here goes the diff
    } yield {
      Some("yeah")
    } }
  }

}