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

package com.normation.cfclerk.services

import com.normation.cfclerk.domain.SystemVariableSpec
import com.normation.errors.RudderError


final case class MissingSystemVariable(name: String) extends RudderError {
  def msg = s"System variable ${name} is missing. This is likely a desynchronisation between your configuration " +
            s"repository and your Rudder variable. If not, please report it"
}

/**
 * Service that returns VariableSpec for a variable name. It is used only for system variable
 * (hard coded ones), and for deserializing the NodeConfiguration
 * @author Nicolas CHARLES
 *
 */
trait SystemVariableSpecService {
  /**
   * Get the spec for the system variable with
   * the given name.
   * @param varName
   * @return
   */
  def get(varName : String) : Either[MissingSystemVariable, SystemVariableSpec]

  /**
   * Get the list of all known system vars spec
   */
  def getAll() : Seq[SystemVariableSpec]
}


