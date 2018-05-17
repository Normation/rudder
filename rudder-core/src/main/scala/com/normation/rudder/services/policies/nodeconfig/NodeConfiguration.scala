/*
*************************************************************************************
* Copyright 2014 Normation SAS
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

package com.normation.rudder.services.policies.nodeconfig

import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.Variable
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.policies.RuleId
import com.normation.utils.HashcodeCaching
import net.liftweb.common.Loggable
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.rudder.domain.parameters.Parameter
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.services.policies.write.Cf3PolicyDraftId
import com.normation.rudder.services.policies.write.Cf3PolicyDraft
import com.normation.rudder.domain.reports.NodeModeConfig


case class ParameterForConfiguration(
    name       : ParameterName
  , value      : String
) extends HashcodeCaching

case object ParameterForConfiguration {
  def fromParameter(param: Parameter) : ParameterForConfiguration = {
    ParameterForConfiguration(param.name, param.value)
  }
}



case class NodeConfiguration(
    nodeInfo    : NodeInfo
  , modesConfig : NodeModeConfig
  , policyDrafts: Set[Cf3PolicyDraft]
    //environment variable for that server
  , nodeContext : Map[String, Variable]
  , parameters  : Set[ParameterForConfiguration]
  , isRootServer: Boolean = false
) extends HashcodeCaching with Loggable {

  /**
   * Return a copy of that node configuration
   * with the "serial" value of given rules updated to the given value.
   *
   */
  def setSerial(rules : Map[RuleId,Int]) : NodeConfiguration = {

    val updatedCf3PolicyDrafts = this.policyDrafts.map { d =>
      d.copy(serial = rules.getOrElse(d.id.ruleId, d.serial))
    }

    this.copy(policyDrafts = updatedCf3PolicyDrafts)
  }

  def findDirectiveByTechnique(techniqueId : TechniqueId): Map[Cf3PolicyDraftId, Cf3PolicyDraft] = {
    policyDrafts.filter(x =>
      x.technique.id.name.value.equalsIgnoreCase(techniqueId.name.value) &&
      x.technique.id.version == techniqueId.version
    ).map(x => (x.id, x)).toMap
  }

  def getTechniqueIds() : Set[TechniqueId] = {
    policyDrafts.map( _.technique.id ).toSet
  }
}
