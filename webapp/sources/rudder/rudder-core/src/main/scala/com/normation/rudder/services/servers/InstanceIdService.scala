/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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

package com.normation.rudder.services.servers

import better.files.File
import com.normation.errors.IOResult
import zio.ZIO

trait InstanceIdGenerator {
  def newInstanceId: InstanceId
}

/**
 * Default implementation use Java's UUID
 */
class InstanceIdGeneratorImpl extends InstanceIdGenerator {
  override def newInstanceId: InstanceId = InstanceId(java.util.UUID.randomUUID.toString)
}

/**
  * Holder for the instance ID of the policy server
  *
  * @param instanceId
  */
class InstanceIdService(
    val instanceId: InstanceId
)

object InstanceIdService {

  /**
    * Make the value from the content of a file :
    * If the file does not exist, just generate a new ID.
    * If not, make a new service with a newly generated ID.
    */
  def make(file: File, instanceIdGenerator: => InstanceIdGenerator): IOResult[InstanceIdService] = {
    ZIO
      .whenZIO(IOResult.attempt(file.exists()))(
        IOResult.attempt(new InstanceIdService(InstanceId(file.contentAsString)))
      )
      .someOrElse(new InstanceIdService(instanceIdGenerator.newInstanceId))
      .chainError(s"Could not use file ${file.pathAsString} to read instance ID")
  }
}
