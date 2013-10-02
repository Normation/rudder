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
package com.normation.rudder.web.services
import net.liftweb.common.Box
import net.liftweb.common.Full

object ReasonBehavior extends Enumeration {
  type ReasonBehavior = Value
  val Disabled, Mandatory, Optionnal = Value
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
  def reasonsFieldBehavior : ReasonBehavior.ReasonBehavior
  def reasonsFieldExplanation : String

}

class UserPropertyServiceImpl( val opt : ReasonsMessageInfo ) extends UserPropertyService {

  private[this] val impl = new StatelessUserPropertyService(() => Full(opt.enabled), () => Full(opt.mandatory), () => Full(opt.explanation))

  override val reasonsFieldBehavior = impl.reasonsFieldBehavior
  override val reasonsFieldExplanation : String = impl.reasonsFieldExplanation
}

/**
 * Reloadable service
 */
class StatelessUserPropertyService(getEnable: () => Box[Boolean], getMandatory: () => Box[Boolean], getExplanation: () => Box[String]) extends UserPropertyService {

  // TODO: handle errors here!


  override def reasonsFieldBehavior = ( getEnable().getOrElse(true), getMandatory().getOrElse(true) ) match {
    case ( true, true )  => ReasonBehavior.Mandatory
    case ( true, false ) => ReasonBehavior.Optionnal
    case ( false, _ )    => ReasonBehavior.Disabled
  }
  override def reasonsFieldExplanation : String = getExplanation().getOrElse("")
}


class ReasonsMessageInfo(
  val enabled : Boolean,
  val mandatory : Boolean,
  val explanation : String )