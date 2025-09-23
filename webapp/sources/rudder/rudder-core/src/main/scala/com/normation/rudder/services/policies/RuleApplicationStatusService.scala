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

package com.normation.rudder.services.policies

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.*
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.FullNodeGroupCategory

trait RuleApplicationStatusService {
  def isApplied(
      rule:             Rule,
      groupLib:         FullNodeGroupCategory,
      directiveLib:     FullActiveTechniqueCategory,
      arePolicyServers: Map[NodeId, Boolean],      // we need both all nodes and if they are policy server
      appliedOnNodes:   Option[Set[NodeId]] = None // Optional parameter: list of node target of this rule
      // exists because it is already computed in the API call
  ): ApplicationStatus
}

class RuleApplicationStatusServiceImpl extends RuleApplicationStatusService {

  /**
   * Knowing if a rule is applied
   */

  // is a cr applied for real ?
  def isApplied(
      rule:             Rule,
      groupLib:         FullNodeGroupCategory,
      directiveLib:     FullActiveTechniqueCategory,
      arePolicyServers: Map[NodeId, Boolean],
      appliedOnNodes:   Option[Set[NodeId]] = None // Optional parameter: list of node target of this rule
      // exists because it is already computed in the API call
  ): ApplicationStatus = {

    if (rule.isEnabled) {
      val isAllTargetsEnabled = rule.targets.flatMap(groupLib.allTargets.get(_)).filter(!_.isEnabled).isEmpty
      val nodesList           = appliedOnNodes.getOrElse(groupLib.getNodeIds(rule.targets, arePolicyServers))
      if (nodesList.nonEmpty) {
        if (isAllTargetsEnabled) {
          val disabled = (rule.directiveIds
            .flatMap(directiveLib.allDirectives.get(_))
            .filterNot { case (activeTechnique, directive) => activeTechnique.isEnabled && directive.isEnabled })
          if (disabled.isEmpty) {
            FullyApplied
          } else if (rule.directiveIds.size - disabled.size > 0) {
            PartiallyApplied(disabled.toSeq.map { case (at, d) => (at.toActiveTechnique(), d) })
          } else {
            NotAppliedNoPI
          }
        } else {
          NotAppliedNoTarget
        }
      } else {
        NotAppliedNoTarget
      }
    } else {
      NotAppliedCrDisabled
    }
  }
}
