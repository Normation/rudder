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

package com.normation.rudder.services.queries

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.{NodeGroup,NodeGroupId}
import com.unboundid.ldap.sdk.{DN,Filter}
import com.normation.ldap.sdk._
import BuildFilter._
import com.normation.rudder.domain.{RudderDit,RudderLDAPConstants}
import com.normation.inventory.ldap.core.LDAPConstants.{A_OC, A_NAME}
import RudderLDAPConstants._
import com.normation.utils.Control.sequence
import com.normation.inventory.ldap.core.LDAPConstants
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import net.liftweb.common._
import com.normation.rudder.repository.NodeGroupRepository
import com.normation.eventlog.EventActor


/**
 * A container for a dynamic group update.
 * members are the list of members post-update, 
 * removed/added members are compared with the
 * state pre-update. 
 */
case class DynGroupDiff(
    members:Seq[NodeId],
    removed:Seq[NodeId],
    added:Seq[NodeId]
)


trait DynGroupUpdaterService {
  /**
   * Update the given dynamic group, returning the diff
   * from the pre-update. 
   * 
   * IMPORTANT NOTE: system group are not updated with 
   * that service !
   * 
   * @return
   */
  def update(dynGroupId:NodeGroupId, actor:EventActor) : Box[DynGroupDiff]
}


class DynGroupUpdaterServiceImpl(
  nodeGroupRepository:NodeGroupRepository,
  queryProcessor : QueryProcessor  
) extends DynGroupUpdaterService with Loggable {
  
  
  
  override def update(dynGroupId:NodeGroupId, actor:EventActor) : Box[DynGroupDiff] = {
    for {
      group <- nodeGroupRepository.getNodeGroup(dynGroupId)
      isDynamic <- if(group.isDynamic) Full("OK") else Failure("Can not update a not dynamic group")
      query <- Box(group.query) ?~! "Can not a group if its query is not defined"
      newMembers <- queryProcessor.process(query) ?~! "Error when processing request for updating dynamic group with id %s".format(dynGroupId)
      //save
      val newMemberIdsSet = newMembers.map( _.id).toSet
      savedGroup <- nodeGroupRepository.update(group.copy(serverList = newMemberIdsSet ), actor) ?~! "Error when saving update for dynmic group '%s'".format(dynGroupId)
    } yield {
      val plus = newMemberIdsSet -- group.serverList
      val minus = group.serverList -- newMemberIdsSet
      DynGroupDiff(newMemberIdsSet.toSeq, minus.toSeq, plus.toSeq)
    }
  }
  
}
