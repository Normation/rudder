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
import scala.xml.NodeSeq
import org.joda.time.DateTime
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.servers.Srv
import com.normation.utils.HashcodeCaching
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.Constants


/**
 * Accept/refuse new server
 */


final case class InventoryLogDetails(
    nodeId : NodeId
  , inventoryVersion: DateTime
  , hostname        : String
  , fullOsName      : String
  , actorIp         : String
) extends HashcodeCaching 

sealed trait AssetEventLog extends EventLog { override final val eventLogCategory = AssetLogCategory }

sealed trait InventoryEventLog extends AssetEventLog 

object InventoryEventLog {
      
  /**
   * Print to XML an inventory details, used
   * for "accept" and "refuse" actions. 
   */
  def toXml(
      logDetails: InventoryLogDetails
    , action    : String
  ) = {
    scala.xml.Utility.trim(
      <node action={action} fileFormat={Constants.XML_CURRENT_FILE_FORMAT.toString}>
        <id>{logDetails.nodeId.value}</id>
        <inventoryVersion>{logDetails.inventoryVersion}</inventoryVersion>
        <hostname>{logDetails.hostname}</hostname>
        <fullOsName>{logDetails.fullOsName}</fullOsName>
        <actorIp>{logDetails.actorIp}</actorIp>
      </node>
    )
  }
}


final case class AcceptNodeEventLog (
    override val eventDetails : EventLogDetails
) extends InventoryEventLog with HashcodeCaching {
  
  override val eventType = AcceptNodeEventLog.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))
}

object AcceptNodeEventLog extends EventLogFilter {
  override val eventType = AcceptNodeEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : AcceptNodeEventLog = AcceptNodeEventLog(x._2) 

  
  def fromInventoryLogDetails(
      id              : Option[Int] = None
    , principal       : EventActor
    , inventoryDetails: InventoryLogDetails
    , creationDate    : DateTime = DateTime.now()
    , severity        : Int = 100
    , description     : Option[String] = None
  ) : AcceptNodeEventLog = {
    val details = EventLog.withContent(InventoryEventLog.toXml(
      inventoryDetails, "accept"
    ) )
    
    AcceptNodeEventLog(EventLogDetails(id,None,principal,creationDate, None, severity, description, details)) 
  }
}

final case class RefuseNodeEventLog (
    override val eventDetails : EventLogDetails
) extends InventoryEventLog with HashcodeCaching {
  override val eventType = RefuseNodeEventLog.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))
}

object RefuseNodeEventLog extends EventLogFilter {
  
  override val eventType = RefuseNodeEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : RefuseNodeEventLog = RefuseNodeEventLog(x._2) 

  
  def fromInventoryLogDetails(
      id              : Option[Int] = None
    , principal       : EventActor
    , inventoryDetails: InventoryLogDetails
    , creationDate    : DateTime = DateTime.now()
    , severity        : Int = 100         
    , description     : Option[String] = None
  ) : RefuseNodeEventLog = {
    val details = EventLog.withContent(InventoryEventLog.toXml(
      inventoryDetails, "refuse"
    ) )
    
    RefuseNodeEventLog(EventLogDetails(id,None,principal,creationDate, None, severity, description, details)) 
  }
}



// Accepted node part

final case class NodeLogDetails(
    node: NodeInfo
) extends HashcodeCaching 

sealed trait NodeEventLog extends AssetEventLog

object NodeEventLog {
      
  /**
   * Print to XML a node detail
   */
  def toXml(
      node  : NodeInfo
    , action: String
  ) = {
    scala.xml.Utility.trim(
      <node action={action} fileFormat={Constants.XML_CURRENT_FILE_FORMAT.toString}>
        <id>{node.id.value}</id>
        <name>{node.name}</name>
        <hostname>{node.hostname}</hostname>
        <description>{node.description}</description>
        <os>{node.os}</os>
        <ips>{node.ips.map(ip => <ip>{ip}</ip>)}</ips>
        <inventoryDate>{node.inventoryDate}</inventoryDate>        
        <publicKey>{node.publicKey}</publicKey>
        <agentsName>{node.agentsName.map(agentName => <agentName>{agentName}</agentName>)}</agentsName>        
        <policyServerId>{node.policyServerId}</policyServerId>
        <localAdministratorAccountName>{node.localAdministratorAccountName}</localAdministratorAccountName>
        <creationDate>{node.creationDate}</creationDate>
        <isBroken>{node.isBroken}</isBroken>
        <isSystem>{node.isSystem}</isSystem>
      </node>
    )
  }
}


final case class DeleteNodeEventLog (
    override val eventDetails : EventLogDetails
) extends NodeEventLog with HashcodeCaching {
  override val eventType = DeleteNodeEventLog.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))

}

object DeleteNodeEventLog extends EventLogFilter {
  override val eventType = DeleteNodeEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : DeleteNodeEventLog = DeleteNodeEventLog(x._2) 

  
  
  def fromNodeLogDetails(
      id              : Option[Int] = None
    , principal       : EventActor
    , node            : NodeInfo
    , creationDate    : DateTime = DateTime.now()
    , severity        : Int = 100
    , description     : Option[String] = None
  ) : DeleteNodeEventLog = {
    val details = EventLog.withContent(NodeEventLog.toXml(
      node, "delete"
    ) )
    
    DeleteNodeEventLog(EventLogDetails(id,None,principal,creationDate, None, severity, description, details)) 
  }
}

object AssetsEventLogsFilter {
  final val eventList : List[EventLogFilter] = List(
      AcceptNodeEventLog 
    , RefuseNodeEventLog 
    , DeleteNodeEventLog
    )
}
