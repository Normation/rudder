/*
 *************************************************************************************
 * Copyright 2021 Normation SAS
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

package com.normation.rudder.web.services

import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyMode.Audit
import com.normation.rudder.domain.policies.PolicyMode.Enforce
import com.normation.rudder.domain.policies.PolicyModeOverrides

object ComputePolicyMode {
  def ruleMode(globalMode: GlobalPolicyMode, directives: Set[Directive], nodeModes: Iterable[Option[PolicyMode]]) = {
    val mixed          =
      "This rule is applied on at least one node or directive that will <b class='text-Enforce'>enforces</b> configurations, and at least one that will <b class='text-Audit'>audits</b> them."
    val directivesMode = directives.map(_.policyMode)
    val nodeModeSet    = nodeModes.toSet
    // All directives and nodes are default, will use Rule mode
    if (directivesMode.forall(_.isEmpty) && nodeModeSet.forall(_.isEmpty)) {
      genericComputeMode(None, "Rule", Set(), "node or directive", globalMode, mixed)
    } else if (directivesMode.forall(_.isEmpty)) {
      genericComputeMode(None, "Rule", nodeModeSet, "node", globalMode, mixed)
    } else if (nodeModeSet.forall(_.isEmpty)) {
      genericComputeMode(None, "Rule", directivesMode, "directive", globalMode, mixed)
    } else {
      genericComputeMode(None, "Rule", directivesMode ++ nodeModeSet, "node or directive", globalMode, mixed)
    }

  }

  def directiveModeOnNode(nodeMode: Option[PolicyMode], globalMode: GlobalPolicyMode)(directiveMode: Option[PolicyMode]) = {
    globalMode.overridable match {
      case PolicyModeOverrides.Unoverridable =>
        (
          globalMode.mode.name,
          s"""Rudder's global agent policy mode is set to <i><b>${globalMode.mode.name}</b></i> and is not overridable on a per-node or per-directive basis. Please check your Settings or contact your Rudder administrator."""
        )
      case PolicyModeOverrides.Always        =>
        val (mode, expl) = matchMode(nodeMode, directiveMode, globalMode.mode)
        (mode.name, expl)
    }
  }

  // Here we are resolving conflict when we have an overridable global mode between mode of a Node, mode of a Directive and we are using global mode to use as default when both are not overriding
  // we do not pass a GlobalPolicyMode, but only the PolicyMode from it so we indicate that we don't want to treat overridabilty here
  private[this] def matchMode(
      nodeMode:      Option[PolicyMode],
      directiveMode: Option[PolicyMode],
      globalMode:    PolicyMode
  ): (PolicyMode, String) = {
    (nodeMode, directiveMode) match {
      case (None, None)                   =>
        (globalMode, "This mode is the globally defined default. You can change it in the global <i><b>settings</b></i>.")
      case (Some(Enforce), Some(Enforce)) =>
        (Enforce, "<b>Enforce</b> is forced by both this <i><b>Node</b></i> and this <i><b>Directive</b></i> mode")
      case (Some(Enforce), None)          =>
        (Enforce, "<b>Enforce</b> is forced by this <i><b>Node</b></i> mode")
      case (None, Some(Enforce))          =>
        (Enforce, "<b>Enforce</b> is forced by this <i><b>Directive</b></i> mode")
      case (Some(Audit), Some(Audit))     =>
        (Audit, "<b>Audit</b> is forced by both this <i><b>Node</b></i> and this <i><b>Directive</b></i> mode")
      case (Some(Audit), None)            =>
        (Audit, "<b>Audit</b> is forced by this <i><b>Node</b></i> mode")
      case (Some(Audit), Some(Enforce))   =>
        (
          Audit,
          "The <i><b>Directive</b></i> is configured to <b class='text-Enforce'>enforce</b> but is overriden to <b>audit</b> by this <i><b>Node</b></i>. "
        )
      case (None, Some(Audit))            =>
        (Audit, "<b>Audit</b> is forced by this <i><b>Directive</b></i> mode")
      case (Some(Enforce), Some(Audit))   =>
        (
          Audit,
          "The <i><b>Node</b></i> is configured to <b class='text-Enforce'>enforce</b> but is overriden to <b>audit</b> by this <i><b>Directive</b></i>. "
        )
    }
  }

  // The goal of that function is to compute the effective mode applied by a combination of a element of one type and a set of element of another type
  // and explain why it's result. (ie : Compute the policy mode applied on a RUle depending on mode of One Node and all Directive from that Rule

  // It uses:
  // * The global defined mode
  // * A unique policy mode of one element of type A as base (generally a Directive or a Node)
  // * multiples Policy modes of a set elements of type B that we will look into it to understand what's going on
  // The other parameters (uniqueKind/multipleKind, mixedExplnation) are used to build explanation message
  private[this] def genericComputeMode(
      uniqueMode:       Option[PolicyMode],
      uniqueKind:       String,
      multipleModes:    Set[Option[PolicyMode]],
      multipleKind:     String,
      globalMode:       GlobalPolicyMode,
      mixedExplanation: String
  ): (String, String) = {

    // Is global mode overridable ?
    globalMode.overridable match {
      // No => We have global mode
      case PolicyModeOverrides.Unoverridable =>
        (
          globalMode.mode.name,
          s"""Rudder's global agent policy mode is set to <i><b>${globalMode.mode.name}</b></i> and is not overridable on a per-node or per-directive basis. Please check your Settings or contact your Rudder administrator."""
        )
      // Yes, Look for our unique mode
      case PolicyModeOverrides.Always        =>
        uniqueMode match {
          // Our unique Mode overrides Audit => We are in Audit mode
          case Some(Audit) =>
            (Audit.name, s"<b>${Audit.name}</b> mode is forced by this <i><b>${uniqueKind}</b></i>")

          // We are not overriding global mode or we are enforcing Audit
          // Scala type system / pattern matching does not allow here to state that we have an Option[Enforce] So we will still have to treat audit case ...
          case uniqueMode  =>
            // If something is missing or multiple mode does not override global mode we will fallback to this
            // Use unique mode if defined
            // else use the global mode
            val (defaultMode, expl) = uniqueMode match {
              case Some(mode) =>
                (mode, s"<b>${mode.name}</b> mode is forced by this <i><b>${uniqueKind}</b></i>")
              case None       =>
                (
                  globalMode.mode,
                  "This mode is the globally defined default. You can change it in the global <i><b>settings</b></i>."
                )
            }
            val default             = (defaultMode.name, expl)

            // We have a Set here so we only have 3 elem max  in it (None, Some(audit), some(enforce))
            // transform to list for pattern matching
            multipleModes.toList match {
              // Nothing defined ... , fallback to default
              case Nil         => default
              // We only have one element!! So all multiple elements are defined on the same mode
              case mode :: Nil =>
                mode match {
                  // Not overriding => Default
                  case None          => default
                  // Audit mode, we will have audit mode but explanation differs depending on which unique mode we have
                  case Some(Audit)   =>
                    uniqueMode match {
                      case Some(Enforce) =>
                        (
                          Audit.name,
                          s"The <i><b>${uniqueKind}</b></i> is configured to <b class='text-Enforce'>enforce</b> but is overriden to <b>audit</b> by all <i><b>${multipleKind}</b></i>. "
                        )
                      // Audit is treated above ... maybe we should not treat it in that sense and always provide a more precise message
                      case Some(Audit)   =>
                        (
                          Audit.name,
                          s"<b>Audit</b> mode is forced by both the <i><b>${uniqueKind}</b></i> and all <i><b>${multipleKind}</b></i>"
                        )
                      case None          =>
                        (Audit.name, s"<b>Audit</b> mode is forced by all <i><b>${multipleKind}</b></i>")
                    }
                  // Enforce mode, since we already treated unique Audit, above, only enforce cases are possible
                  case Some(Enforce) =>
                    uniqueMode match {
                      case Some(Audit)   =>
                        // Audit is treated above ... maybe we should not treat it in that sense and always provide a more precise message
                        (
                          Audit.name,
                          s"All <i><b>${multipleKind}</b></i> are configured to <b class='text-Enforce'>enforce</b> but is overriden to <b>audit</b> by the <i><b>${uniqueKind}</b></i>. "
                        )
                      case Some(Enforce) =>
                        (
                          Enforce.name,
                          s"<b>Enforce</b> mode is forced by both this <i><b>${uniqueKind}</b></i> and all <i><b>${multipleKind}</b></i>"
                        )
                      case None          =>
                        (Enforce.name, s"<b>Enforce</b> mode is forced by all <i><b>${multipleKind}</b></i>")
                    }

                }

              // We have multiple modes ! We need to go deeper
              case modes       =>
                // Now we will replace None (non overriding mode) by it's effective mode (default one defined above)

                multipleModes.map(_.getOrElse(defaultMode)).toList match {
                  // That is treated above on the first match on the list, but still need to treat empty case
                  case Nil         => default
                  // All modes defined are now replaced by default mode but there is only mode mode left
                  case mode :: Nil =>
                    mode match {
                      // Audit means that unique mode was not overriding and we that some 'multiple' modes are overriding to Audit and global mode is defined as audit
                      case Audit   => (Audit.name, s"<b>${Audit.name}</b> mode is forced by some <i><b>${multipleKind}</b></i>")
                      // That means that unique mode was not overriding or is in enforce Mode and that some directive are overrding to default and global mode is defined as audit
                      // Maybe we could look into unique mode to state if it depends on it or not
                      case Enforce =>
                        (
                          Enforce.name,
                          s"<b>${Enforce.name}</b> mode is forced by the <i><b>${uniqueKind}</b></i> and some <i><b>${multipleKind}</b></i>"
                        )
                    }

                  // We still have more that one mode, we are in mixed state, use the explanation povided
                  case _           =>
                    ("mixed", mixedExplanation)
                }
            }
        }
    }
  }

  // Used to compute mode applied on a Rule depending on the Node state and directive from the Rule
  // Used in compliance table in node page
  def ruleModeOnNode(nodeMode: Option[PolicyMode], globalMode: GlobalPolicyMode)(
      directivesMode:          Set[Option[PolicyMode]]
  ): (String, String) = {
    val mixed =
      "This Rule has at least one Directive that will <b class='text-Enforce'>enforces</b> configurations, and at least one that will <b class='text-Audit'>audits</b> them."
    genericComputeMode(nodeMode, "Node", directivesMode, "Directives", globalMode, mixed)
  }

  // Used to computed mode applied by a Node depending on it's mode and directive it applies
  // Used in Node compliance table in Rule page
  def nodeModeOnRule(nodeMode: Option[PolicyMode], globalMode: GlobalPolicyMode)(
      directivesMode:          Set[Option[PolicyMode]]
  ): (String, String) = {
    val mixed =
      "This Node applies at least one Directive that will <b class='text-Enforce'>enforces</b> configurations, and at least one that will <b class='text-Audit'>audits</b> them."
    genericComputeMode(nodeMode, "Node", directivesMode, "Directives", globalMode, mixed)
  }

  // Used to computed mode applied on a Directive depending on it's mode and and all Nodes appliying it
  // Used in Node compliance table in Rule page
  def directiveModeOnRule(nodeModes: Set[Option[PolicyMode]], globalMode: GlobalPolicyMode)(directiveMode: Option[PolicyMode]) = {
    val mixed =
      "This Directive is applied on at least one Node that will <b class='text-Enforce'>enforces</b> configurations, and at least one that will <b class='text-Audit'>audits</b> them."
    genericComputeMode(directiveMode, "Node", nodeModes, "Directives", globalMode, mixed)
  }
}
