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

import com.normation.eventlog._
sealed trait PromiseEventLog extends EventLog { final override val eventLogCategory: EventLogCategory = DeploymentLogCategory }

final case class AutomaticStartDeployement(
    override val eventDetails: EventLogDetails
) extends PromiseEventLog {
  override val eventType = AutomaticStartDeployement.eventType
}

object AutomaticStartDeployement extends EventLogFilter {
  override val eventType: EventLogType = AutomaticStartDeployementEventType

  override def apply(x: (EventLogType, EventLogDetails)): AutomaticStartDeployement = AutomaticStartDeployement(x._2)
}

final case class ManualStartDeployement(
    override val eventDetails: EventLogDetails
) extends PromiseEventLog {
  override val eventType = ManualStartDeployement.eventType
}

object ManualStartDeployement extends EventLogFilter {
  override val eventType: EventLogType = ManualStartDeployementEventType

  override def apply(x: (EventLogType, EventLogDetails)): ManualStartDeployement = ManualStartDeployement(x._2)
}

final case class SuccessfulDeployment(
    override val eventDetails: EventLogDetails
) extends PromiseEventLog {
  override val eventType = SuccessfulDeployment.eventType
}

object SuccessfulDeployment extends EventLogFilter {
  override val eventType: EventLogType = SuccessfulDeploymentEventType

  override def apply(x: (EventLogType, EventLogDetails)): SuccessfulDeployment = SuccessfulDeployment(x._2)
}

final case class FailedDeployment(
    override val eventDetails: EventLogDetails
) extends PromiseEventLog {
  override val eventType = FailedDeployment.eventType
}

object FailedDeployment extends EventLogFilter {
  override val eventType: EventLogType = FailedDeploymentEventType

  override def apply(x: (EventLogType, EventLogDetails)): FailedDeployment = FailedDeployment(x._2)
}

object PromisesEventLogsFilter {
  final val eventList: List[EventLogFilter] = List(
    AutomaticStartDeployement,
    ManualStartDeployement,
    SuccessfulDeployment,
    FailedDeployment
  )
}
