/*
*************************************************************************************
* Copyright 2013 Normation SAS
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


import com.normation.eventlog._
import com.normation.utils.HashcodeCaching


sealed trait ParameterEventLog extends EventLog { override final val eventLogCategory = ParameterLogCategory }

final case class AddGlobalParameter(
    override val eventDetails : EventLogDetails
) extends ParameterEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = AddGlobalParameter.eventType
}

object AddGlobalParameter extends EventLogFilter {
  override val eventType = AddGlobalParameterEventType

  override def apply(x : (EventLogType, EventLogDetails)) : AddGlobalParameter = AddGlobalParameter(x._2)
}

final case class ModifyGlobalParameter(
    override val eventDetails : EventLogDetails
) extends ParameterEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = ModifyGlobalParameter.eventType
}

object ModifyGlobalParameter extends EventLogFilter {
  override val eventType = ModifyGlobalParameterEventType

  override def apply(x : (EventLogType, EventLogDetails)) : ModifyGlobalParameter = ModifyGlobalParameter(x._2)
}

final case class DeleteGlobalParameter(
    override val eventDetails : EventLogDetails
) extends ParameterEventLog with HashcodeCaching {
  override val cause = None
  override val eventType = DeleteGlobalParameter.eventType
}

object DeleteGlobalParameter extends EventLogFilter {
  override val eventType = DeleteGlobalParameterEventType

  override def apply(x : (EventLogType, EventLogDetails)) : DeleteGlobalParameter = DeleteGlobalParameter(x._2)
}

object ParameterEventsLogsFilter {
  final val eventList : List[EventLogFilter] = List(
      AddGlobalParameter
    , ModifyGlobalParameter
    , DeleteGlobalParameter
    )
}
