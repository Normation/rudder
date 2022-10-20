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
 * Related Module is not consnameered as a part of the work and may be
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

import com.normation.box._
import com.normation.eventlog._
import com.normation.rudder.domain.secret.Secret
import com.normation.rudder.repository.EventLogRepository
import net.liftweb.common._

/**
 * Allow to query relevant information about change request
 * status.
 */
trait SecretEventLogService {

  def saveSecretLog(modId: ModificationId, principal: EventActor, secret: Secret, reason: Option[String]): Box[EventLog]

  def saveModifySecretLog(
      modId:     ModificationId,
      principal: EventActor,
      oldSec:    Secret,
      newSec:    Secret,
      reason:    Option[String]
  ): Box[EventLog]

  def saveDeleteSecretLog(
      modId:     ModificationId,
      principal: EventActor,
      oldSec:    Secret,
      newSec:    Secret,
      reason:    Option[String]
  ): Box[EventLog]
}

class SecretEventLogServiceImpl(
    eventLogRepository: EventLogRepository
) extends SecretEventLogService with Loggable {

  def saveSecretLog(modId: ModificationId, principal: EventActor, secret: Secret, reason: Option[String]): Box[EventLog] = {
    eventLogRepository.saveAddSecret(modId, principal, secret, reason).toBox
  }
  def saveModifySecretLog(
      modId:     ModificationId,
      principal: EventActor,
      oldSec:    Secret,
      newSec:    Secret,
      reason:    Option[String]
  ): Box[EventLog] = {
    eventLogRepository.saveModifySecret(modId, principal, oldSec, newSec, reason).toBox
  }

  def saveDeleteSecretLog(
      modId:     ModificationId,
      principal: EventActor,
      oldSec:    Secret,
      newSec:    Secret,
      reason:    Option[String]
  ): Box[EventLog] = {
    eventLogRepository.saveModifySecret(modId, principal, oldSec, newSec, reason).toBox
  }
}
