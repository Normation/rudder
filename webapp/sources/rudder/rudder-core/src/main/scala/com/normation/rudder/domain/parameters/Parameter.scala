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
package com.normation.rudder.domain.parameters
import java.util.regex.Pattern

final case class ParameterName(value:String) extends AnyVal

object ParameterName {
  val patternName = Pattern.compile("[a-zA-Z0-9_]+");
}

/**
 * A Parameter is an object that has a name and a value, to store and reuse
 * values at different places within Rudder
 */
sealed trait Parameter {
  def name        : ParameterName
  def value       : String
  def description : String
  def overridable : Boolean
}

/**
 * A Global Parameter is a parameter globally defined, that may be overriden
 */
final case class GlobalParameter(
    override val name       : ParameterName
  , override val value      : String
  , override val description: String
  , override val overridable: Boolean
) extends Parameter {

}

