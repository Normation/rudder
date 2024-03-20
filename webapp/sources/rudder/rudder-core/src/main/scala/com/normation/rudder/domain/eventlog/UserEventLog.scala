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

package com.normation.rudder.domain.eventlog

import com.normation.eventlog.EventLog
import com.normation.eventlog.EventLogCategory
import com.normation.eventlog.EventLogDetails
import com.normation.eventlog.EventLogFilter
import com.normation.eventlog.EventLogType

sealed trait UserEventLog extends EventLog {
  final override val details = EventLog.emptyDetails
  final override val eventLogCategory: EventLogCategory = UserLogCategory
}

final case class LoginEventLog(
    override val eventDetails: EventLogDetails
) extends UserEventLog {

  override val eventType = LoginEventLog.eventType
}

object LoginEventLog extends EventLogFilter {
  override val eventType: EventLogType = LoginEventType

  override def apply(x: (EventLogType, EventLogDetails)): LoginEventLog = LoginEventLog(x._2)
}

final case class BadCredentialsEventLog(
    override val eventDetails: EventLogDetails
) extends UserEventLog {

  override val eventType = BadCredentialsEventLog.eventType
}

object BadCredentialsEventLog extends EventLogFilter {
  override val eventType: EventLogType = BadCredentialsEventType

  override def apply(x: (EventLogType, EventLogDetails)): BadCredentialsEventLog = BadCredentialsEventLog(x._2)
}

final case class LogoutEventLog(
    override val eventDetails: EventLogDetails
) extends UserEventLog {

  override val eventType = LogoutEventLog.eventType
}

object LogoutEventLog extends EventLogFilter {
  override val eventType: EventLogType = LogoutEventType

  override def apply(x: (EventLogType, EventLogDetails)): LogoutEventLog = LogoutEventLog(x._2)
}

///////////////////////////////
// API Account part
///////////////////////////////

sealed trait APIAccountEventLog extends EventLog {
  final override val eventLogCategory: EventLogCategory = APIAccountCategory
}

final case class CreateAPIAccountEventLog(
    override val eventDetails: EventLogDetails
) extends APIAccountEventLog {

  override val eventType: EventLogType = CreateAPIAccountEventLog.eventType
}

object CreateAPIAccountEventLog extends EventLogFilter {
  override val eventType: EventLogType = CreateAPIAccountEventType

  override def apply(x: (EventLogType, EventLogDetails)): CreateAPIAccountEventLog = CreateAPIAccountEventLog(x._2)
}

final case class DeleteAPIAccountEventLog(
    override val eventDetails: EventLogDetails
) extends APIAccountEventLog {

  override val eventType = DeleteAPIAccountEventLog.eventType
}

object DeleteAPIAccountEventLog extends EventLogFilter {
  override val eventType: EventLogType = DeleteAPIAccountEventType

  override def apply(x: (EventLogType, EventLogDetails)): DeleteAPIAccountEventLog = DeleteAPIAccountEventLog(x._2)
}

final case class ModifyAPIAccountEventLog(
    override val eventDetails: EventLogDetails
) extends APIAccountEventLog {

  override val eventType = ModifyAPIAccountEventLog.eventType
}

object ModifyAPIAccountEventLog extends EventLogFilter {
  override val eventType: EventLogType = ModifyAPITokenEventType

  override def apply(x: (EventLogType, EventLogDetails)): ModifyAPIAccountEventLog = ModifyAPIAccountEventLog(x._2)
}

object UserEventLogsFilter {
  final val eventList: List[EventLogFilter] = List(
    LoginEventLog,
    LogoutEventLog,
    BadCredentialsEventLog
  )
}

object APIAccountEventLogsFilter {
  final val eventList: List[EventLogFilter] = List(
    CreateAPIAccountEventLog,
    DeleteAPIAccountEventLog,
    ModifyAPIAccountEventLog
  )

}
