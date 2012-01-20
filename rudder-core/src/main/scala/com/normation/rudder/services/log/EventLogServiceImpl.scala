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

package com.normation.rudder.services.log

import com.normation.rudder.domain.log.InventoryEventLog
import com.normation.eventlog._
import com.normation.rudder.services.log._
import java.security.Principal
import com.normation.rudder.repository._
import net.liftweb.common._
import scala.collection._
import com.normation.utils.Control.sequence
import com.normation.rudder.services.marshalling.DeploymentStatusUnserialisation
import com.normation.rudder.batch.CurrentDeploymentStatus
import com.normation.rudder.domain.log.ModificationWatchList
import com.normation.rudder.domain.log.SuccessfulDeploymentEventType
import com.normation.rudder.domain.log.FailedDeploymentEventType


trait EventLogDeploymentService {
  def repository : EventLogRepository
  def eventLogDetailsService : EventLogDetailsService
  
  /**
   * Fetch the last deployment (may it be failure or success)
   */
  def getLastDeployement() : Box[CurrentDeploymentStatus] = {
    repository.getEventLogByCriteria(Some("eventtype in ('" + SuccessfulDeploymentEventType.serialize +"', '"+FailedDeploymentEventType.serialize +"')"), Some(1), Some("creationdate desc") ) match {
      case Full(seq) if seq.size > 1 => Failure("Too many answer from last deployment")
      case Full(seq) if seq.size == 1 => 
        eventLogDetailsService.getDeploymentStatusDetails(seq.head.details)
      case Full(seq) if seq.size == 0 => Empty
      case f: EmptyBox => f
    }
  }
  
  /**
   * Fetch the last successful deployment (which may be empty)
   */
  def getLastSuccessfulDeployement() : Box[EventLog] = {
    repository.getEventLogByCriteria(Some("eventtype = '"+SuccessfulDeploymentEventType.serialize +"'"), Some(1), Some("creationdate desc") ) match {
      case Full(seq) if seq.size > 1 => Failure("Too many answer from last deployment")
      case Full(seq) if seq.size == 1 => Full(seq.head)
      case Full(seq) if seq.size == 0 => Empty
      case f: EmptyBox => f
    }
  }
  
  /**
   * Return the list of event corresponding at a modification since last successful deployement
   * 
   */
  def getListOfModificationEvents(lastSuccess : EventLog) = {
    val eventList = ModificationWatchList.events.map("'"+_.serialize+"'").mkString(",")
    
    repository.getEventLogByCriteria(Some("eventtype in (" +eventList+ ") and id > " +lastSuccess.id.get ), None, Some("id DESC") )
  }
  
}

class EventLogServiceImpl(
    override val repository : EventLogRepository
  , override val eventLogDetailsService : EventLogDetailsService) extends EventLogDeploymentService with EventLogService {

  /**
   * Save an entry 
   */
  def saveEventLog(entry : EventLog) : Box[EventLog] = {
	  repository.saveEventLog(entry)
	  //entry
  }
  
  /**
   * Save several entries at once. This is especially useful when saving a 
   * batch of operations depending each from another.
   * This is really the only way to save the entrylog along with their causes
   */
  def saveEventLogs(entries : EventLogNode) : Box[Seq[EventLog]] = {
	  repository.saveEventLog(entries.entry) match {
	 	  case Full(log) => 
	 	      recursiveWriteEntry(entries, log.id.get).map(logs => log +: logs)
	 	  case _ => Failure("Cannot save value")
	  }
  }
  
     
  
  private def recursiveWriteEntry(currentEntry : EventLogNode, causeId : Int) : Box[Seq[EventLog]] = {
    sequence(currentEntry.children) { child =>
      val parent = repository.saveEventLog(child.entry.copySetCause(causeId))
      parent match {
        case Full(log) => 
          recursiveWriteEntry(child, log.id.get) match {
            case Full(cLogs) => Full(log +: cLogs)
            case e:EmptyBox => e
          }
        case e:EmptyBox => e
      }
    }.map( _.flatten )
  }
}
