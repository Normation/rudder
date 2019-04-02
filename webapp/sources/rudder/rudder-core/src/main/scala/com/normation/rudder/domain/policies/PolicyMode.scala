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

import ca.mrvisser.sealerate.values
import com.normation.errors._

sealed trait PolicyMode {                        def name : String    }
final object PolicyMode {
  final case object Audit  extends PolicyMode  { val name = "audit"  }
  final case object Enforce extends PolicyMode { val name = "enforce" }

  def allModes: Set[PolicyMode] = values[PolicyMode]

  //get from string, case insensitive
  def parse (value : String) : Either[RudderError, PolicyMode] = {
    allModes.find { _.name == value.toLowerCase() } match {
      case None =>
        Left(Unexpected(s"Unable to parse policy mode name '${value}'. was expecting ${allModes.map(_.name).mkString("'", "' or '", "'")}."))
      case Some(mode) =>
        Right(mode)
    }
  }

  //get from string, with null, '' and 'default' will result in a None
  def parseDefault (value : String) : Either[RudderError, Option[PolicyMode]] = {
    value match {
      case null | "" | "default" => Right(None)
      case _                     => parse(value).map(Some(_))
    }
  }

  /*
   * Compute the mode for directives,typically in a Technique.
   *
   * This method ensure that all directives have compatible policy mode, and
   * overwhise return an error.
   *
   * Typically used when we want to know what is the policy mode on a given node, for a given directive,
   * and we know from the context the default global value and the value of
   * other directives for the same technique also on that node.
   */
  def computeMode(globalValue : GlobalPolicyMode, nodeMode : Option[PolicyMode], directiveMode : Seq[Option[PolicyMode]]) : Either[RudderError, PolicyMode] = {
    globalValue.overridable match {
      case PolicyModeOverrides.Always =>
        nodeMode match {
          case Some(Audit) => Right(Audit)
          case _ =>

            // Here, we must ensure that all the directive have consistant policy mode - i.e the same.
            // For that, we start by calculating what is the mode for directives that don't have a
            // dedicated mode, and then we check homogeneity.
            val default = nodeMode.getOrElse(globalValue.mode)
            val finalMode = directiveMode.map { _.getOrElse(default) }.toList.distinct

            finalMode match {
              case Nil =>
                //here, we didn't passed any directive in the call, so we just get the node|global computed mode
                Right(default)
              case mode :: Nil => //ok
                Right(mode)
              case _ => // too many modes, verboten
                Left(Unexpected(s"Inconsistant policy mode: both audit and enforce applied"))
            }
        }

      case PolicyModeOverrides.Unoverridable =>
        Right(globalValue.mode)
    }
  }

  /*
   * Compute policy mode for only one directive, taking into account the fact that it is system
   * (and in that case, it's always "Enforce")
   */
  def directivePolicyMode(globalValue : GlobalPolicyMode, nodeMode : Option[PolicyMode], directiveMode : Option[PolicyMode], isSystem: Boolean): PolicyMode = {
    if(isSystem) Enforce
    else globalValue.overridable match {
      case PolicyModeOverrides.Always =>
        nodeMode match {
          case Some(Audit) => Audit
          case _           => directiveMode match {
            case Some(Audit) => Audit
            case _           => directiveMode.getOrElse(nodeMode.getOrElse(globalValue.mode))
          }
        }

      case PolicyModeOverrides.Unoverridable =>
        globalValue.mode
    }
  }

  def computeMode(globalValue : GlobalPolicyMode, nodeMode : Option[PolicyMode]): PolicyMode = {
    globalValue.overridable match {
      case PolicyModeOverrides.Always =>
        nodeMode match {
          case Some(Audit) => Audit
          case _           => nodeMode.getOrElse(globalValue.mode)
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
