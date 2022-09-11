/*
 *************************************************************************************
 * Copyright 2021 Normation SAS
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
import com.normation.eventlog.EventLogDetails
import com.normation.eventlog.EventLogFilter
import com.normation.eventlog.EventLogType
import com.normation.rudder.domain.policies.SimpleDiff
import com.normation.rudder.domain.secret.Secret

//final case class Secret(name: String, value: String, description: String)

sealed trait SecretEventLog extends EventLog { final override val eventLogCategory = SecretLogCategory }

final case class AddSecret(
    override val eventDetails: EventLogDetails
) extends SecretEventLog {
  override val cause     = None
  override val eventType = AddSecret.eventType
}

object AddSecret extends EventLogFilter {
  override val eventType = AddSecretEventType

  override def apply(x: (EventLogType, EventLogDetails)): AddSecret = AddSecret(x._2)
}

final case class ModifySecret(
    override val eventDetails: EventLogDetails
) extends SecretEventLog {
  override val cause     = None
  override val eventType = ModifySecret.eventType
}

object ModifySecret extends EventLogFilter {
  override val eventType = ModifySecretEventType

  override def apply(x: (EventLogType, EventLogDetails)): ModifySecret = ModifySecret(x._2)
}

final case class DeleteSecret(
    override val eventDetails: EventLogDetails
) extends SecretEventLog {
  override val cause     = None
  override val eventType = DeleteSecret.eventType
}

object DeleteSecret extends EventLogFilter {
  override val eventType = DeleteSecretEventType

  override def apply(x: (EventLogType, EventLogDetails)): DeleteSecret = DeleteSecret(x._2)
}

object SecretEventsLogsFilter {
  final val eventList: List[EventLogFilter] = List(
    AddSecret,
    ModifySecret,
    DeleteSecret
  )
}

sealed trait SecretDiff

final case class AddSecretDiff(secret: Secret) extends SecretDiff

final case class DeleteSecretDiff(secret: Secret) extends SecretDiff

final case class ModifySecretDiff(
    name:           String,
    description:    String,
    modValue:       Boolean,
    modDescription: Option[SimpleDiff[String]] = None
) extends SecretDiff

object ModifySecretDiff {
  def apply(newSecret: Secret, oldSecret: Secret): ModifySecretDiff = {
    val modDesc  = {
      if (newSecret.description == oldSecret.description)
        None
      else
        Some(SimpleDiff(oldSecret.description, newSecret.description))
    }
    val modValue = {
      if (newSecret.value == oldSecret.value)
        false
      else
        true
    }
    ModifySecretDiff(oldSecret.name, oldSecret.description, modValue, modDesc)
  }
}
