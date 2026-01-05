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

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.SimpleDiff
import com.normation.rudder.domain.policies.TriggerDeploymentDiff
import com.normation.rudder.domain.properties.GroupProperty
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.tenants.SecurityTag

/**
 * That file defines "diff" objects for NodeGroups (groups, etc).
 */
sealed trait NodeGroupDiff extends TriggerDeploymentDiff

//for change request, with add type tag to DirectiveDiff
sealed trait ChangeRequestNodeGroupDiff {
  def group: NodeGroup
}

final case class DeleteNodeGroupDiff(group: NodeGroup) extends NodeGroupDiff with ChangeRequestNodeGroupDiff {
  def needDeployment: Boolean = true
}

final case class AddNodeGroupDiff(group: NodeGroup) extends NodeGroupDiff with ChangeRequestNodeGroupDiff {
  def needDeployment: Boolean = false
}

final case class ModifyToNodeGroupDiff(group: NodeGroup) extends NodeGroupDiff with ChangeRequestNodeGroupDiff {
  // This case is undecidable, so it is always true
  def needDeployment: Boolean = true
}

final case class ModifyNodeGroupDiff(
    id:   NodeGroupId,
    name: String, // keep the name around to be able to display it as it was at that time

    modName:        Option[SimpleDiff[String]] = None,
    modDescription: Option[SimpleDiff[String]] = None,
    modProperties:  Option[SimpleDiff[List[GroupProperty]]] = None,
    modQuery:       Option[SimpleDiff[Option[Query]]] = None,
    modIsDynamic:   Option[SimpleDiff[Boolean]] = None,
    modNodeList:    Option[SimpleDiff[Set[NodeId]]] = None,
    modIsActivated: Option[SimpleDiff[Boolean]] = None,
    modIsSystem:    Option[SimpleDiff[Boolean]] = None,
    modCategory:    Option[SimpleDiff[NodeGroupCategoryId]] = None,
    modSecurityTag: Option[SimpleDiff[Option[SecurityTag]]] = None
) extends NodeGroupDiff {

  def needDeployment: Boolean = {
    modQuery.isDefined || modIsDynamic.isDefined || modNodeList.isDefined ||
    modIsActivated.isDefined || modName.isDefined || modProperties.isDefined ||
    modSecurityTag.isDefined
  }
}
