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
import com.normation.utils.HashcodeCaching

sealed trait PromiseEventLog extends EventLog { override final val eventLogCategory = DeploymentLogCategory }

final case class AutomaticStartDeployement(
    override val eventDetails : EventLogDetails
) extends PromiseEventLog with HashcodeCaching {
  override val eventType = AutomaticStartDeployement.eventType
}

object AutomaticStartDeployement extends EventLogFilter {
  override val eventType = AutomaticStartDeployementEventType

  override def apply(x : (EventLogType, EventLogDetails)) : AutomaticStartDeployement = AutomaticStartDeployement(x._2)
}

final case class ManualStartDeployement(
    override val eventDetails : EventLogDetails
) extends PromiseEventLog with HashcodeCaching {
  override val eventType = ManualStartDeployement.eventType
}

object ManualStartDeployement extends EventLogFilter {
  override val eventType = ManualStartDeployementEventType

  override def apply(x : (EventLogType, EventLogDetails)) : ManualStartDeployement = ManualStartDeployement(x._2)
}

final case class SuccessfulDeployment (
    override val eventDetails : EventLogDetails
) extends PromiseEventLog with HashcodeCaching {
  override val eventType = SuccessfulDeployment.eventType
}

object SuccessfulDeployment extends EventLogFilter {
  override val eventType = SuccessfulDeploymentEventType

  override def apply(x : (EventLogType, EventLogDetails)) : SuccessfulDeployment = SuccessfulDeployment(x._2)
}

final case class FailedDeployment (
    override val eventDetails : EventLogDetails
) extends PromiseEventLog with HashcodeCaching {
  override val eventType = FailedDeployment.eventType
}

object FailedDeployment extends EventLogFilter {
  override val eventType = FailedDeploymentEventType

  override def apply(x : (EventLogType, EventLogDetails)) : FailedDeployment = FailedDeployment(x._2)
}

object PromisesEventLogsFilter {
  final val eventList : List[EventLogFilter] = List(
      AutomaticStartDeployement
    , ManualStartDeployement
    , SuccessfulDeployment
    , FailedDeployment
    )
}
