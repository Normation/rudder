/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

package com.normation.rudder.repository
import net.liftweb.common._
import com.normation.rudder.domain.parameters._
import com.normation.eventlog.ModificationId
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.archives.ParameterArchiveId

/**
 * The Parameter Repository (Read Only) to read parameters from LDAP
 */
trait RoParameterRepository {

  def getGlobalParameter(parameterName : ParameterName) : Box[GlobalParameter]
  
  def getAllGlobalParameters() : Box[Seq[GlobalParameter]]
  
  def getAllOverridable() : Box[Seq[GlobalParameter]]
}

trait WoParameterRepository {
  def saveParameter(parameter : GlobalParameter, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[AddGlobalParameterDiff]

  def updateParameter(parameter : GlobalParameter, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[Option[ModifyGlobalParameterDiff]]

  def delete(parameterName:ParameterName, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[DeleteGlobalParameterDiff]
  
  /**
   * A (dangerous) method that replace all existing parameters
   * by the list given in parameter.
   * If succeed, return an identifier of the place were
   * are stored the old parameters - it is the
   * responsibility of the user to delete them.
   */
  def swapParameters(newParameters:Seq[GlobalParameter]) : Box[ParameterArchiveId]

  /**
   * Delete a set of saved rules.
   */
  def deleteSavedParametersArchiveId(saveId:ParameterArchiveId) : Box[Unit]
}