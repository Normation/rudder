/*
*************************************************************************************
* Copyright 2014 Normation SAS
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

package com.normation.rudder.services.policies.nodeconfig

import org.joda.time.DateTime
import com.normation.cfclerk.domain.Cf3PolicyDraftContainer
import com.normation.cfclerk.domain.Cf3PolicyDraftId
import com.normation.cfclerk.domain.ParameterEntry
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.Variable
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleWithCf3PolicyDraft
import com.normation.utils.HashcodeCaching
import net.liftweb.common.Loggable
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.rudder.domain.parameters.Parameter
import com.normation.cfclerk.domain.Cf3PolicyDraftId


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
  , policyDrafts: Set[RuleWithCf3PolicyDraft]
    //environment variable for that server
  , nodeContext : Map[String, Variable]
  , parameters  : Set[ParameterForConfiguration]
  , writtenDate : Option[DateTime] = None
  , isRootServer: Boolean = false
) extends HashcodeCaching with Loggable {

  /**
   * Return a copy of that node configuration
   * with the "serial" value of given rules updated to the given value.
   *
   */
  def setSerial(rules : Map[RuleId,Int]) : NodeConfiguration = {

    val newRuleWithCf3PolicyDrafts = this.policyDrafts.map { r =>
      val s = rules.getOrElse(r.ruleId, r.cf3PolicyDraft.serial)
      r.copy(cf3PolicyDraft = r.cf3PolicyDraft.copy(serial = s))
    }

    this.copy(policyDrafts = newRuleWithCf3PolicyDrafts)
  }


  def toContainer(outPath: String) : Cf3PolicyDraftContainer = {
    val container = new Cf3PolicyDraftContainer(
        outPath
      , parameters.map(x => ParameterEntry(x.name.value, x.value)).toSet
    )
    policyDrafts.foreach (x =>  container.add(x.cf3PolicyDraft))
    container
  }

  def findDirectiveByTechnique(techniqueId : TechniqueId): Map[Cf3PolicyDraftId, RuleWithCf3PolicyDraft] = {
    policyDrafts.filter(x =>
      x.cf3PolicyDraft.technique.id.name.value.equalsIgnoreCase(techniqueId.name.value) &&
      x.cf3PolicyDraft.technique.id.version == techniqueId.version
    ).map(x => (x.draftId, x)).toMap
  }

  def getTechniqueIds() : Set[TechniqueId] = {
    policyDrafts.map( _.cf3PolicyDraft.technique.id ).toSet
  }
}
