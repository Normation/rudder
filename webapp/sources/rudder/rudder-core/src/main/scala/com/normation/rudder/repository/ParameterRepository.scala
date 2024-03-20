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

package com.normation.rudder.repository
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.archives.ParameterArchiveId
import com.normation.rudder.domain.properties.AddGlobalParameterDiff
import com.normation.rudder.domain.properties.DeleteGlobalParameterDiff
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.ModifyGlobalParameterDiff
import com.normation.rudder.domain.properties.PropertyProvider

/**
 * The Parameter Repository (Read Only) to read parameters from LDAP
 */
trait RoParameterRepository {

  def getGlobalParameter(parameterName: String): IOResult[Option[GlobalParameter]]

  def getAllGlobalParameters(): IOResult[Seq[GlobalParameter]]
}

trait WoParameterRepository {
  def saveParameter(
      parameter: GlobalParameter,
      modId:     ModificationId,
      actor:     EventActor,
      reason:    Option[String]
  ): IOResult[AddGlobalParameterDiff]

  def updateParameter(
      parameter: GlobalParameter,
      modId:     ModificationId,
      actor:     EventActor,
      reason:    Option[String]
  ): IOResult[Option[ModifyGlobalParameterDiff]]

  def delete(
      parameterName: String,
      provider:      Option[PropertyProvider],
      modId:         ModificationId,
      actor:         EventActor,
      reason:        Option[String]
  ): IOResult[Option[DeleteGlobalParameterDiff]]

  /**
   * A (dangerous) method that replace all existing parameters
   * by the list given in parameter.
   * If succeed, return an identifier of the place were
   * are stored the old parameters - it is the
   * responsibility of the user to delete them.
   */
  def swapParameters(newParameters: Seq[GlobalParameter]): IOResult[ParameterArchiveId]

  /**
   * Delete a set of saved rules.
   */
  def deleteSavedParametersArchiveId(saveId: ParameterArchiveId): IOResult[Unit]
}
