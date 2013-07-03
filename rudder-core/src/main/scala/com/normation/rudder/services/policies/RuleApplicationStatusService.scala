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

package com.normation.rudder.services.policies

import com.normation.rudder.domain.policies._
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.repository.FullActiveTechniqueCategory

trait RuleApplicationStatusService {
    def isApplied(
      rule        : Rule
    , groupLib    : FullNodeGroupCategory
    , directiveLib: FullActiveTechniqueCategory
    , allNodeInfos: Set[NodeInfo]
  ) : ApplicationStatus
}

class RuleApplicationStatusServiceImpl extends RuleApplicationStatusService {
  /**
   * Knowing if a rule is applied
   */

  //is a cr applied for real ?
  def isApplied(
      rule        : Rule
    , groupLib    : FullNodeGroupCategory
    , directiveLib: FullActiveTechniqueCategory
    , allNodeInfos: Set[NodeInfo]
  ) : ApplicationStatus = {

    if(rule.isEnabled) {
      val isAllTargetsEnabled = rule.targets.flatMap( groupLib.allTargets.get(_) ).filter(!_.isEnabled).isEmpty
      val nodeTargetSize = groupLib.getNodeIds(rule.targets, allNodeInfos).size
      if (nodeTargetSize != 0) {
        if(isAllTargetsEnabled) {
          val disabled = (rule.directiveIds
              .flatMap( directiveLib.allDirectives.get(_) )
              .filterNot { case (activeTechnique, directive) => activeTechnique.isEnabled && directive.isEnabled }
          )
          if(disabled.size == 0) {
            FullyApplied
          } else if(rule.directiveIds.size - disabled.size > 0) {
            PartiallyApplied(disabled.toSeq.map{ case(at,d) => (at.toActiveTechnique,d) })
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
