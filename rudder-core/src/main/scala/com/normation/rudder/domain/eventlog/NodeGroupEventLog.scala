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

package com.normation.rudder.domain.eventlog


import com.normation.eventlog._
import scala.xml._
import org.joda.time.DateTime
import net.liftweb.common._
import com.normation.rudder.domain.nodes._
import com.normation.rudder.domain.policies.SimpleDiff
import com.normation.rudder.domain.queries.Query
import com.normation.inventory.domain.NodeId
import com.normation.utils.HashcodeCaching

sealed trait NodeGroupEventLog extends EventLog { override final val eventLogCategory = NodeGroupLogCategory }


final case class AddNodeGroup(
    override val eventDetails : EventLogDetails
) extends NodeGroupEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = AddNodeGroup.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))
}

object AddNodeGroup extends EventLogFilter {
  override val eventType = AddNodeGroupEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : AddNodeGroup = AddNodeGroup(x._2) 
}


final case class DeleteNodeGroup(
    override val eventDetails : EventLogDetails
) extends NodeGroupEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = DeleteNodeGroup.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))
}

object DeleteNodeGroup extends EventLogFilter {
  override val eventType = DeleteNodeGroupEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : DeleteNodeGroup = DeleteNodeGroup(x._2) 
}

final case class ModifyNodeGroup(
    override val eventDetails : EventLogDetails
) extends NodeGroupEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = ModifyNodeGroup.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))
}

object ModifyNodeGroup extends EventLogFilter {
  override val eventType = ModifyNodeGroupEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : ModifyNodeGroup = ModifyNodeGroup(x._2) 
}

object NodeGroupEventLogsFilter {
  final val eventList : List[EventLogFilter] = List(
      AddNodeGroup 
    , DeleteNodeGroup 
    , ModifyNodeGroup
    )
}
