/*
*************************************************************************************
* Copyright 2011-2013 Normation SAS
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

package com.normation.rudder.services.modification

import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupDiff
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.cfclerk.domain.SectionSpec
import com.normation.rudder.domain.nodes.ModifyNodeGroupDiff
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.rudder.domain.parameters.ModifyGlobalParameterDiff

/**
 * A service that allows to build diff between
 * objects.
 */
trait DiffService {

  def diffDirective(
      reference:Directive
    , refRootSection : SectionSpec
    , newItem:Directive
    , newRootSection : SectionSpec
  ) : ModifyDirectiveDiff

  def diffNodeGroup(reference:NodeGroup, newItem:NodeGroup) : ModifyNodeGroupDiff

  def diffRule(reference:Rule, newItem:Rule) : ModifyRuleDiff

  def diffGlobalParameter(reference:GlobalParameter, newItem:GlobalParameter) : ModifyGlobalParameterDiff
}

class DiffServiceImpl (
    roDirectiveRepo : RoDirectiveRepository
) extends DiffService {

  def diffDirective(
      reference:Directive
    , refRootSection : SectionSpec
    , newItem:Directive
    , newRootSection : SectionSpec) : ModifyDirectiveDiff = {
    import SectionVal._
    val refSectionVal = directiveValToSectionVal(refRootSection,reference.parameters)
    val newSectionVal = directiveValToSectionVal(newRootSection,newItem.parameters)
    val diffName = if (reference.name == newItem.name) None else Some(SimpleDiff(reference.name,newItem.name))
    val diffShortDescription = if (reference.shortDescription == newItem.shortDescription) None else Some(SimpleDiff(reference.shortDescription,newItem.shortDescription))
    val diffLongDescription = if (reference.longDescription == newItem.longDescription) None else Some(SimpleDiff(reference.longDescription,newItem.longDescription))
    val diffTechniqueVersion = if (reference.techniqueVersion == newItem.techniqueVersion) None else Some(SimpleDiff(reference.techniqueVersion,newItem.techniqueVersion))
    val diffPriority  = if (reference.priority == newItem.priority) None else Some(SimpleDiff(reference.priority,newItem.priority))
    val diffParameters = if (refSectionVal == newSectionVal) None else Some(SimpleDiff(refSectionVal,newSectionVal))
    val diffSystem = if (reference.isSystem == newItem.isSystem) None else Some(SimpleDiff(reference.isSystem,newItem.isSystem))
    val diffEnable = if (reference.isEnabled == newItem.isEnabled) None else Some(SimpleDiff(reference.isEnabled,newItem.isEnabled))
    val techniqueName = roDirectiveRepo.getActiveTechnique(reference.id).map(_.techniqueName).get
    ModifyDirectiveDiff(
        techniqueName
      , reference.id
      , reference.name
      , diffName
      , diffTechniqueVersion
      , diffParameters
      , diffShortDescription
      , diffLongDescription
      , diffPriority
      , diffEnable
      , diffSystem
      )
  }


  def diffNodeGroup(reference:NodeGroup, newItem:NodeGroup) : ModifyNodeGroupDiff = {
    val diffName = if (reference.name == newItem.name) None else Some(SimpleDiff(reference.name,newItem.name))
    val diffDescription = if (reference.description == newItem.description) None else Some(SimpleDiff(reference.description,newItem.description))
    val diffEnable = if (reference.isEnabled == newItem.isEnabled) None else Some(SimpleDiff(reference.isEnabled,newItem.isEnabled))
    val diffDynamic = if (reference.isDynamic == newItem.isDynamic) None else Some(SimpleDiff(reference.isDynamic,newItem.isDynamic))
    val diffServerList = if (reference.serverList == newItem.serverList) None else Some(SimpleDiff(reference.serverList,newItem.serverList))
    val diffQuery = if (reference.query == newItem.query) None else Some(SimpleDiff(reference.query,newItem.query))

    ModifyNodeGroupDiff (
        reference.id
      , reference.name
      , diffName
      , diffDescription
      , diffQuery
      , diffDynamic
      , diffServerList
      , diffEnable
      , None
      )

  }

  def diffRule(reference:Rule, newItem:Rule) : ModifyRuleDiff = {
    val diffName = if (reference.name == newItem.name) None else Some(SimpleDiff(reference.name,newItem.name))
    val diffShortDescription = if (reference.shortDescription == newItem.shortDescription) None else Some(SimpleDiff(reference.shortDescription,newItem.shortDescription))
    val diffLongDescription = if (reference.longDescription == newItem.longDescription) None else Some(SimpleDiff(reference.longDescription,newItem.longDescription))
    val diffSystem = if (reference.isSystem == newItem.isSystem) None else Some(SimpleDiff(reference.isSystem,newItem.isSystem))
    val diffEnable = if (reference.isEnabled == newItem.isEnabled) None else Some(SimpleDiff(reference.isEnabled,newItem.isEnabled))
    val diffTarget = if (reference.targets == newItem.targets) None else Some(SimpleDiff(reference.targets,newItem.targets))
    val diffDirectives = if (reference.directiveIds == newItem.directiveIds) None else Some(SimpleDiff(reference.directiveIds,newItem.directiveIds))

    ModifyRuleDiff(
        reference.id
      , reference.name
      , diffName
      , None
      , diffTarget
      , diffDirectives
      , diffShortDescription
      , diffLongDescription
      , None
      , diffEnable
      , diffSystem
    )
  }

  def diffGlobalParameter(reference:GlobalParameter, newItem:GlobalParameter) : ModifyGlobalParameterDiff = {
    val diffValue = if (reference.value == newItem.value) None else Some(SimpleDiff(reference.value,newItem.value))
    val diffDescription = if (reference.description == newItem.description) None else Some(SimpleDiff(reference.description,newItem.description))
    val diffOverridable = if (reference.overridable == newItem.overridable) None else Some(SimpleDiff(reference.overridable,newItem.overridable))

    ModifyGlobalParameterDiff(
        reference.name
      , diffValue
      , diffDescription
      , diffOverridable
    )
  }
}