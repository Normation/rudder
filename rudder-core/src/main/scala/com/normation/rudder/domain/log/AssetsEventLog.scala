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
import scala.xml.NodeSeq
import org.joda.time.DateTime
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.servers.Srv
import com.normation.utils.HashcodeCaching


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

sealed trait InventoryEventLog extends EventLog 

object InventoryEventLog {
  
  val xmlVersion = "1.0"
    
  /**
   * Print to XML an inventory details, used
   * for "accept" and "refuse" actions. 
   */
  def toXml(
      logDetails: InventoryLogDetails
    , action    : String
  ) = {
    scala.xml.Utility.trim(
      <node action={action} fileFormat={xmlVersion}>
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
    override val id : Option[Int] = None
  , override val principal : EventActor
  , override val details : NodeSeq
  , override val creationDate : DateTime = new DateTime() 
  , override val severity : Int = 100
) extends InventoryEventLog with HashcodeCaching {
  
  override val eventLogCategory = AssetLogCategory
  override val cause = None
  override val eventType = "AcceptNode"
  override def copySetCause(causeId:Int) = this
}

object AcceptNodeEventLog {
  def fromInventoryLogDetails(
      id              : Option[Int] = None
    , principal       : EventActor
    , inventoryDetails: InventoryLogDetails
    , creationDate    : DateTime = new DateTime()
    , severity        : Int = 100         
  ) : AcceptNodeEventLog = {
    val details = EventLog.withContent(InventoryEventLog.toXml(
      inventoryDetails, "accept"
    ) )
    
    AcceptNodeEventLog(id,principal,details,creationDate,severity) 
  }
}

final case class RefuseNodeEventLog (
    override val id : Option[Int] = None
  , override val principal : EventActor
  , override val details : NodeSeq
  , override val creationDate : DateTime = new DateTime() 
  , override val severity : Int = 100
) extends InventoryEventLog with HashcodeCaching {
  override val eventLogCategory = AssetLogCategory
  override val cause = None
  override val eventType = "RefuseNode"
  override def copySetCause(causeId:Int) = this
}

object RefuseNodeEventLog {
  def fromInventoryLogDetails(
      id              : Option[Int] = None
    , principal       : EventActor
    , inventoryDetails: InventoryLogDetails
    , creationDate    : DateTime = new DateTime()
    , severity        : Int = 100         
  ) : RefuseNodeEventLog = {
    val details = EventLog.withContent(InventoryEventLog.toXml(
      inventoryDetails, "refuse"
    ) )
    
    RefuseNodeEventLog(id,principal,details,creationDate,severity) 
  }
}
