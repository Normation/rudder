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

import scala.xml.NodeSeq
import com.normation.eventlog.{EventLog,EventActor}
import org.joda.time.DateTime
import com.normation.utils.HashcodeCaching
import com.normation.eventlog.EventLogDetails
import com.normation.eventlog.EventLogFilter
import com.normation.eventlog.EventLogType

sealed trait UserEventLog extends EventLog {
  override final val details = EventLog.emptyDetails
  override final val eventLogCategory = UserLogCategory
}


final case class LoginEventLog(
    override val eventDetails : EventLogDetails
) extends UserEventLog with HashcodeCaching {
  
  override val eventType = LoginEventLog.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))
}

object LoginEventLog extends EventLogFilter {
  override val eventType = LoginEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : LoginEventLog = LoginEventLog(x._2) 
}

final case class BadCredentialsEventLog(
    override val eventDetails : EventLogDetails
) extends UserEventLog with HashcodeCaching {

  override val eventType = BadCredentialsEventLog.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))
}

object BadCredentialsEventLog extends EventLogFilter {
  override val eventType = BadCredentialsEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : BadCredentialsEventLog = BadCredentialsEventLog(x._2) 
}


final case class LogoutEventLog(
    override val eventDetails : EventLogDetails
) extends UserEventLog with HashcodeCaching {
  
  override val eventType = LogoutEventLog.eventType
  override def copySetCause(causeId:Int) = this.copy(eventDetails.copy(cause = Some(causeId)))
}

object LogoutEventLog extends EventLogFilter {
  override val eventType = LogoutEventType
 
  override def apply(x : (EventLogType, EventLogDetails)) : LogoutEventLog = LogoutEventLog(x._2) 
}


object UserEventLogsFilter {
  final val eventList : List[EventLogFilter] = List(
      LoginEventLog
    , LogoutEventLog
    , BadCredentialsEventLog
    )
}
