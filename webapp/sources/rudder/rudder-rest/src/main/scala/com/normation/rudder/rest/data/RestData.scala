/*
 *************************************************************************************
 * Copyright 2013 Normation SAS
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

package com.normation.rudder.rest.data

import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.errors.PureResult
import com.normation.inventory.domain.KeyStatus
import com.normation.inventory.domain.SecurityToken
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.domain.policies.Tags
import com.normation.rudder.domain.properties.CompareProperties
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.GroupProperty
import com.normation.rudder.domain.properties.InheritMode
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.domain.properties.PropertyProvider
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.domain.workflows.ChangeRequestInfo
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.rule.category._
import com.typesafe.config.ConfigValue
import net.liftweb.common._

final case class APIChangeRequestInfo(
    name:        Option[String],
    description: Option[String]
) {
  def updateCrInfo(crInfo: ChangeRequestInfo): ChangeRequestInfo = {
    crInfo.copy(
      name = name.getOrElse(crInfo.name),
      description = description.getOrElse(crInfo.description)
    )
  }
}

final case class RestRuleCategory(
    name:        Option[String] = None,
    description: Option[String] = None,
    parent:      Option[RuleCategoryId] = None,
    id:          Option[RuleCategoryId] = None
) {

  def update(ruleCategory: RuleCategory) = {
    val updateName        = name.getOrElse(ruleCategory.name)
    val updateDescription = description.getOrElse(ruleCategory.description)
    ruleCategory.copy(
      name = updateName,
      description = updateDescription
    )
  }
}

final case class RestGroupCategory(
    id:          Option[NodeGroupCategoryId] = None,
    name:        Option[String] = None,
    description: Option[String] = None,
    parent:      Option[NodeGroupCategoryId] = None
) {

  def update(category: FullNodeGroupCategory) = {
    val updateId          = id.getOrElse(category.id)
    val updateName        = name.getOrElse(category.name)
    val updateDescription = description.getOrElse(category.description)
    category.copy(
      id = updateId,
      name = updateName,
      description = updateDescription
    )
  }

  def create(defaultId: () => NodeGroupCategoryId): Box[FullNodeGroupCategory] = {
    name match {
      case Some(name) =>
        Full(
          FullNodeGroupCategory(
            id.getOrElse(defaultId()),
            name,
            description.getOrElse(""),
            Nil,
            Nil
          )
        )
      case None       =>
        Failure("Could not create group Category, cause: name is not defined")
    }
  }
}

final case class RestDirective(
    name:             Option[String],
    shortDescription: Option[String],
    longDescription:  Option[String],
    enabled:          Option[Boolean],
    parameters:       Option[Map[String, Seq[String]]],
    priority:         Option[Int],
    techniqueName:    Option[TechniqueName],
    techniqueVersion: Option[TechniqueVersion],
    policyMode:       Option[Option[PolicyMode]],
    tags:             Option[Tags]
) {

  val onlyName = name.isDefined &&
    shortDescription.isEmpty &&
    longDescription.isEmpty &&
    enabled.isEmpty &&
    parameters.isEmpty &&
    priority.isEmpty &&
    techniqueName.isEmpty &&
    techniqueVersion.isEmpty &&
    policyMode.isEmpty &&
    tags.isEmpty

  def updateDirective(directive: Directive) = {
    val updateName             = name.getOrElse(directive.name)
    val updateShort            = shortDescription.getOrElse(directive.shortDescription)
    val updateLong             = longDescription.getOrElse(directive.longDescription)
    val updateEnabled          = enabled.getOrElse(directive.isEnabled)
    val updateTechniqueVersion = techniqueVersion.getOrElse(directive.techniqueVersion)
    val updateParameters       = parameters.getOrElse(directive.parameters)
    val updatePriority         = priority.getOrElse(directive.priority)
    val updateMode             = policyMode.getOrElse(directive.policyMode)
    val updateTags             = tags.getOrElse(directive.tags)
    directive.copy(
      name = updateName,
      shortDescription = updateShort,
      longDescription = updateLong,
      _isEnabled = updateEnabled,
      parameters = updateParameters,
      techniqueVersion = updateTechniqueVersion,
      priority = updatePriority,
      policyMode = updateMode,
      tags = updateTags
    )

  }
}

final case class DirectiveState(
    technique: Technique,
    directive: Directive
)

final case class DirectiveUpdate(
    activeTechnique: ActiveTechnique,
    before:          DirectiveState,
    after:           DirectiveState
)

final case class RestGroup(
    id:          Option[String] = None,
    name:        Option[String] = None,
    description: Option[String] = None,
    properties:  Option[List[GroupProperty]],
    query:       Option[Query] = None,
    isDynamic:   Option[Boolean] = None,
    enabled:     Option[Boolean] = None,
    category:    Option[NodeGroupCategoryId] = None
) {

  val onlyName = name.isDefined &&
    description.isEmpty &&
    query.isEmpty &&
    isDynamic.isEmpty &&
    enabled.isEmpty &&
    category.isEmpty

  def updateGroup(group: NodeGroup): PureResult[NodeGroup] = {
    CompareProperties.updateProperties(group.properties, properties).map { updateProperties =>
      val updateName      = name.getOrElse(group.name)
      val updateDesc      = description.getOrElse(group.description)
      val updateisDynamic = isDynamic.getOrElse(group.isDynamic)
      val updateEnabled   = enabled.getOrElse(group.isEnabled)
      val updateQuery     = query.orElse(group.query)
      group.copy(
        name = updateName,
        description = updateDesc,
        query = updateQuery,
        isDynamic = updateisDynamic,
        _isEnabled = updateEnabled,
        properties = updateProperties
      )
    }
  }
}

final case class RestNodeProperties(
    properties: Option[Seq[NodeProperty]]
)

final case class RestNode(
    properties:     Option[List[NodeProperty]],
    policyMode:     Option[Option[PolicyMode]],
    state:          Option[NodeState],
    agentKey:       Option[SecurityToken],
    agentKeyStatus: Option[KeyStatus]
)

sealed trait NodeStatusAction
final case object AcceptNode extends NodeStatusAction
final case object RefuseNode extends NodeStatusAction
final case object DeleteNode extends NodeStatusAction

final case class RestParameter(
    value:       Option[ConfigValue] = None,
    description: Option[String] = None,
    inheritMode: Option[InheritMode] = None
) {

  def updateParameter(parameter: GlobalParameter) = {
    val updateValue = (p: GlobalParameter) => (value.map(x => p.withValue(x))).getOrElse(p)
    val updateDesc  = (p: GlobalParameter) => (description.map(x => p.withDescription(x))).getOrElse(p)
    val updateMode  = (p: GlobalParameter) => (inheritMode.map(x => p.withMode(x))).getOrElse(p)

    updateMode(updateDesc(updateValue(parameter)).withProvider(PropertyProvider.defaultPropertyProvider))
  }
}

final case class RestRule(
    name:             Option[String] = None,
    category:         Option[RuleCategoryId] = None,
    shortDescription: Option[String] = None,
    longDescription:  Option[String] = None,
    directives:       Option[Set[DirectiveId]] = None,
    targets:          Option[Set[RuleTarget]] = None,
    enabled:          Option[Boolean] = None,
    tags:             Option[Tags] = None
) {

  val onlyName = name.isDefined &&
    category.isEmpty &&
    shortDescription.isEmpty &&
    longDescription.isEmpty &&
    directives.isEmpty &&
    targets.isEmpty &&
    enabled.isEmpty &&
    tags.isEmpty

  def updateRule(rule: Rule) = {
    val updateName       = name.getOrElse(rule.name)
    val updateCategory   = category.getOrElse(rule.categoryId)
    val updateShort      = shortDescription.getOrElse(rule.shortDescription)
    val updateLong       = longDescription.getOrElse(rule.longDescription)
    val updateDirectives = directives.getOrElse(rule.directiveIds)
    val updateTargets    = targets.getOrElse(rule.targets)
    val updateEnabled    = enabled.getOrElse(rule.isEnabledStatus)
    val updateTags       = tags.getOrElse(rule.tags)
    rule.copy(
      name = updateName,
      categoryId = updateCategory,
      shortDescription = updateShort,
      longDescription = updateLong,
      directiveIds = updateDirectives,
      targets = updateTargets,
      isEnabledStatus = updateEnabled,
      tags = updateTags
    )

  }
}
