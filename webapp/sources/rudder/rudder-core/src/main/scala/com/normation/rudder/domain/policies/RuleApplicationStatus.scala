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

package com.normation.rudder.domain.policies

import com.normation.rudder.domain.nodes.NodeInfo

/**
 * Application status of a rule
 */

sealed trait ApplicationStatus
sealed trait NotAppliedStatus extends ApplicationStatus
sealed trait AppliedStatus    extends ApplicationStatus

case object NotAppliedNoPI       extends NotAppliedStatus
case object NotAppliedNoTarget   extends NotAppliedStatus
case object NotAppliedCrDisabled extends NotAppliedStatus

case object FullyApplied                                                 extends AppliedStatus
final case class PartiallyApplied(disabled: Seq[(ActiveTechnique, Directive)]) extends AppliedStatus

object ApplicationStatus {
  def details(
      rule:              Rule,
      applicationStatus: ApplicationStatus,
      targets:           Set[RuleTargetInfo],
      directives:        Set[(ActiveTechnique, Directive)],
      nodes:             Iterable[NodeInfo]
  ) = {
    applicationStatus match {
      case FullyApplied          => ("In application", None)
      case PartiallyApplied(seq) =>
        val why = seq.map { case (at, d) => "Directive '" + d.name + "' disabled" }.mkString(", ")
        ("Partially applied", Some(why))
      case x: NotAppliedStatus =>
        val (status, disabledMessage) = {
          if ((!rule.isEnabled) && (!rule.isEnabledStatus)) {
            ("Disabled", Some("This rule is disabled. "))
          } else {
            ("Not applied", None)
          }
        }
        val isAllTargetsEnabled       = targets.filter(t => !t.isEnabled).isEmpty

        val conditions = {
          Seq(
            (rule.isEnabledStatus && !rule.isEnabled, "Rule unapplied"),
            (directives.isEmpty, "No policy defined"),
            (!isAllTargetsEnabled, "Group disabled"),
            (nodes.isEmpty, "Empty groups")
          ) ++
          directives.flatMap {
            case (activeTechnique, directive) =>
              Seq(
                (!directive.isEnabled, "Directive '" + directive.name + "' disabled"),
                (!activeTechnique.isEnabled, "Technique for '" + directive.name + "' disabled")
              )
          }
        }
        val why        = (disabledMessage ++ (conditions.collect { case (ok, label) if (ok) => label })).mkString(", ")
        (status, Some(why))
    }
  }
}
