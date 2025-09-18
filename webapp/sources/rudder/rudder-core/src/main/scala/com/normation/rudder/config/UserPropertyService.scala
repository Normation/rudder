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
package com.normation.rudder.config
import com.normation.box.*
import com.normation.errors.*

sealed trait ReasonBehavior

object ReasonBehavior {
  case object Disabled  extends ReasonBehavior
  case object Mandatory extends ReasonBehavior
  case object Optional  extends ReasonBehavior
}

/**
 * Expose application configuration choices from users
 */
trait UserPropertyService {

  /**
   * Strategy to manage reasons message field; for example in Rule.
   * @return the strategy
   */
  // def reasonsFieldEnabled() : Boolean
  def reasonsFieldBehavior:    ReasonBehavior
  def reasonsFieldExplanation: String

}

/**
 * Reloadable service
 */
class StatelessUserPropertyService(
    getEnable:      () => IOResult[Boolean],
    getMandatory:   () => IOResult[Boolean],
    getExplanation: () => IOResult[String]
) extends UserPropertyService {
  import ReasonBehavior.*

  // TODO: handle errors here!

  override def reasonsFieldBehavior:    ReasonBehavior = {
    (getEnable().toBox.getOrElse(true), getMandatory().toBox.getOrElse(true)) match {
      case (true, true)  => Mandatory
      case (true, false) => Optional
      case (false, _)    => Disabled
    }
  }
  override def reasonsFieldExplanation: String         = getExplanation().toBox.getOrElse("")
}
