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

package com.normation.rudder.domain.log


import com.normation.eventlog._
import scala.xml._
import org.joda.time.DateTime
import net.liftweb.common._
import com.normation.rudder.domain.nodes._
import com.normation.rudder.domain.policies.SimpleDiff
import com.normation.rudder.domain.queries.Query
import com.normation.inventory.domain.NodeId
import com.normation.utils.HashcodeCaching

sealed trait NodeGroupEventLog extends EventLog

object NodeGroupEventLog {
  
  val xmlVersion = "1.0"
  
  /**
   * Print to XML a configuration rule, used
   * for "add" and "delete" actions. 
   */
  def toXml(group:NodeGroup,action:String) =
    scala.xml.Utility.trim(<nodeGroup changeType={action} fileFormat={xmlVersion}>
        <id>{group.id.value}</id>
        <displayName>{group.name}</displayName>
        <description>{group.description}</description>
        <query>{ group.query.map( _.toJSONString ).getOrElse("") }</query>
        <isDynamic>{group.isDynamic}</isDynamic>
        <nodeIds>{
          group.serverList.map { id => <id>{id.value}</id> } 
        }</nodeIds>
        <isActivated>{group.isActivated}</isActivated>
        <isSystem>{group.isSystem}</isSystem>
      </nodeGroup>
    )
    
  
}

final case class AddNodeGroup(
    override val id : Option[Int] = None
  , override val principal : EventActor
  , override val details : NodeSeq
  , override val creationDate : DateTime = DateTime.now()
  , override val severity : Int = 100
) extends NodeGroupEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = "NodeGroupAdded"
  override val eventLogCategory = NodeGroupLogCategory
  override def copySetCause(causeId:Int) = this
}

object AddNodeGroup {
  def fromDiff(
      id : Option[Int] = None
    , principal : EventActor
    , addDiff:AddNodeGroupDiff
    , creationDate : DateTime = DateTime.now()
    , severity : Int = 100
  ) : AddNodeGroup = {
    val details = EventLog.withContent(NodeGroupEventLog.toXml(addDiff.group, "add"))
    
    AddNodeGroup(id, principal, details, creationDate, severity)
  }
}

final case class DeleteNodeGroup(
    override val id : Option[Int] = None
  , override val principal : EventActor
  , override val details : NodeSeq
  , override val creationDate : DateTime = DateTime.now()
  , override val severity : Int = 100
) extends NodeGroupEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = "NodeGroupDeleted"
  override val eventLogCategory = NodeGroupLogCategory
  override def copySetCause(causeId:Int) = this
}

object DeleteNodeGroup {
  def fromDiff(    
      id : Option[Int] = None
    , principal : EventActor
    , deleteDiff:DeleteNodeGroupDiff
    , creationDate : DateTime = DateTime.now()
    , severity : Int = 100
  ) : DeleteNodeGroup = {
    val details = EventLog.withContent(NodeGroupEventLog.toXml(deleteDiff.group, "delete"))

    DeleteNodeGroup(id, principal, details, creationDate, severity)
  }
}

final case class ModifyNodeGroup(
    override val id : Option[Int] = None
  , override val principal : EventActor
  , override val details : NodeSeq
  , override val creationDate : DateTime = DateTime.now()
  , override val severity : Int = 100
) extends NodeGroupEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = "NodeGroupModified"
  override val eventLogCategory = NodeGroupLogCategory
  override def copySetCause(causeId:Int) = this
}

object ModifyNodeGroup {
  def fromDiff(    
      id : Option[Int] = None
    , principal : EventActor
    , modifyDiff:ModifyNodeGroupDiff
    , creationDate : DateTime = DateTime.now()
    , severity : Int = 100
  ) : ModifyNodeGroup = {
    val details = EventLog.withContent{
      scala.xml.Utility.trim(<nodeGroup changeType="modify" fileFormat={NodeGroupEventLog.xmlVersion}>
        <id>{modifyDiff.id.value}</id>
        <displayName>{modifyDiff.name}</displayName>{
          modifyDiff.modName.map(x => SimpleDiff.stringToXml(<name/>, x) ) ++
          modifyDiff.modDescription.map(x => SimpleDiff.stringToXml(<description/>, x ) ) ++
          modifyDiff.modQuery.map(x => SimpleDiff.toXml[Option[Query]](<query/>, x){ t =>
            t match {
              case None => <none/>
              case Some(y) => Text(y.toJSONString)
            }
          } ) ++
          modifyDiff.modIsDynamic.map(x => SimpleDiff.booleanToXml(<isDynamic/>, x ) ) ++
          modifyDiff.modServerList.map(x => SimpleDiff.toXml[Set[NodeId]](<nodeIds/>, x){ ids =>
              ids.toSeq.map { id => <id>{id.value}</id> }
            } ) ++
          modifyDiff.modIsActivated.map(x => SimpleDiff.booleanToXml(<isActivated/>, x ) ) ++
          modifyDiff.modIsSystem.map(x => SimpleDiff.booleanToXml(<isSystem/>, x ) )
        }
      </nodeGroup>)
    }
    ModifyNodeGroup(id, principal, details, creationDate, severity)
  }
}
