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

package com.normation.rudder.services.eventlog

import com.normation.rudder.domain.eventlog._
import com.normation.eventlog._
import com.normation.rudder.repository._
import net.liftweb.common._
import com.normation.rudder.batch.CurrentDeploymentStatus
import com.normation.rudder.repository.EventLogRepository

import com.normation.zio._

class EventLogDeploymentService(
    val repository             : EventLogRepository
  , val eventLogDetailsService : EventLogDetailsService
) {
  /**
   * Fetch the last deployment (may it be failure or success)
   */
  def getLastDeployement() : Box[CurrentDeploymentStatus] = {
    val query = "eventtype in ('" + SuccessfulDeploymentEventType.serialize +"', '"+FailedDeploymentEventType.serialize +"')"
    repository.getEventLogByCriteria(Some(query), Some(1), Some("creationdate desc") ).toBox match {
      case Full(seq) if seq.size > 1 => Failure("Too many answer from last policy update")
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
    val query = "eventtype = '"+SuccessfulDeploymentEventType.serialize +"'"
    repository.getEventLogByCriteria(Some(query), Some(1), Some("creationdate desc") ).toBox match {
      case Full(seq) if seq.size > 1 => Failure("Too many answer from last policy update")
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
    val query = "eventtype in (" +eventList+ ") and id > " +lastSuccess.id.getOrElse(0)
    repository.getEventLogByCriteria(Some(query), None, Some("id DESC") )
  }

}
