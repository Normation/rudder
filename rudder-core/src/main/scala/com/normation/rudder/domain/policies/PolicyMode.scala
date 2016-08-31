/*
*************************************************************************************
* Copyright 2016 Normation SAS
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
package com.normation.rudder.domain.policies

import net.liftweb.common.{Box,Full,Failure}
import ca.mrvisser.sealerate.values

sealed trait PolicyMode {                        def name : String    }

final object PolicyMode {

  final case object Verify  extends PolicyMode { val name = "verify"  }
  final case object Enforce extends PolicyMode { val name = "enforce" }

  def allModes: Set[PolicyMode] = values[PolicyMode]

  //get from string, case insensitive
  def parse (value : String) : Box[PolicyMode] = {
    allModes.find { _.name == value.toLowerCase() } match {
      case None =>
        Failure(s"Unable to parse policy mode name '${value}'. was expecting ${allModes.map(_.name).mkString("'", "' or '", "'")}.")
      case Some(mode) =>
        Full(mode)
    }
  }

  //get from string, 'default' will result in a None
  def parseDefault (value : String) : Box[Option[PolicyMode]] = {
    value match {
      case "default" => Full(None)
      case _ => parse(value).map(Some(_))
    }
  }

  /*
   * Combine different modes and get the resulting one. Typically used when we
   * want to know what is the policy mode on a given node, for a given directive,
   * and we know from the context the default global value and the value of
   * other directives for the same technique also on that node.
   */
  def computeMode(globalValue : GlobalPolicyMode, nodeMode : Option[PolicyMode], directiveMode : Seq[Option[PolicyMode]]) = {
    globalValue.overridable match {
      case PolicyModeOverrides.Always =>
        nodeMode match {
          case Some(Verify) => Verify
          case _ =>
            (((None : Option[PolicyMode]) /: directiveMode) {
              case (None,current) => current
              case (Some(Verify),_) | (_,Some(Verify)) => Some(Verify)
              case _ => Some(Enforce)
            }).getOrElse(globalValue.mode)

        }

      case PolicyModeOverrides.Unoverridable =>
        globalValue.mode
    }
  }
}

/*
 * What is allowed to override the global value for policy mode
 */
sealed trait PolicyModeOverrides
final object PolicyModeOverrides {
  //nothing can override. Global is the sole value
  final case object Unoverridable extends PolicyModeOverrides
  //anything can override. Combination rules applies.
  final case object Always        extends PolicyModeOverrides
  //directives, groups, ...
}

final case class GlobalPolicyMode (
    mode       : PolicyMode
  , overridable: PolicyModeOverrides
)
