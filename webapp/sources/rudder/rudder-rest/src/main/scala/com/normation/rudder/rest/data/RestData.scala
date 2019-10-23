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

import com.normation.rudder.rule.category._
import net.liftweb.common._
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.domain.Technique
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.Tags
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeProperty
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.domain.workflows.ChangeRequestInfo


sealed trait DetailLevel {
  def value : String
}

case object FullDetails extends DetailLevel {
  val value = "full"
}
case object MinimalDetails extends DetailLevel {
  val value = "minimal"
}

case class APIChangeRequestInfo (
    name        : Option[String]
  , description : Option[String]
) {
  def updateCrInfo(crInfo : ChangeRequestInfo) : ChangeRequestInfo = {
    crInfo.copy(
        name        = name.getOrElse(crInfo.name)
      , description = description.getOrElse(crInfo.description)
    )
  }
}

case class RestRuleCategory(
      name : Option[String] = None
    , description : Option[String] = None
    , parent : Option[RuleCategoryId] = None
  ) {

  def update(ruleCategory:RuleCategory) = {
    val updateName = name.getOrElse(ruleCategory.name)
    val updateDescription = description.getOrElse(ruleCategory.description)
    ruleCategory.copy(
        name        = updateName
      , description = updateDescription
    )
  }

  def create(id : RuleCategoryId) : Box[RuleCategory]= {
    name match {
      case Some(name) =>
        Full(
          RuleCategory(
              id
            , name
            , description.getOrElse("")
            , Nil
          )

        )
        case None =>
          Failure("Could not create Rule Category, cause name is not defined")
      }
  }
}

case class RestGroupCategory(
      name : Option[String] = None
    , description : Option[String] = None
    , parent : Option[NodeGroupCategoryId] = None
  ) {

  def update(category:FullNodeGroupCategory) = {
    val updateName = name.getOrElse(category.name)
    val updateDescription = description.getOrElse(category.description)
    category.copy(
        name        = updateName
      , description = updateDescription
    )
  }

  def create(id : NodeGroupCategoryId) : Box[FullNodeGroupCategory]= {
    name match {
      case Some(name) =>
        Full(
          FullNodeGroupCategory(
              id
            , name
            , description.getOrElse("")
            , Nil
            , Nil
          )

        )
        case None =>
          Failure("Could not create Group Category, cause name is not defined")
      }
  }
}


case class RestDirective(
      name             : Option[String]
    , shortDescription : Option[String]
    , longDescription  : Option[String]
    , enabled          : Option[Boolean]
    , parameters       : Option[Map[String, Seq[String]]]
    , priority         : Option[Int]
    , techniqueName    : Option[TechniqueName]
    , techniqueVersion : Option[TechniqueVersion]
    , policyMode       : Option[Option[PolicyMode]]
    , tags             : Option[Tags]
  ) {

    val onlyName = name.isDefined           &&
                   shortDescription.isEmpty &&
                   longDescription.isEmpty  &&
                   enabled.isEmpty          &&
                   parameters.isEmpty       &&
                   priority.isEmpty         &&
                   techniqueName.isEmpty    &&
                   techniqueVersion.isEmpty &&
                   policyMode.isEmpty       &&
                   tags.isEmpty

    def updateDirective(directive:Directive) = {
      val updateName = name.getOrElse(directive.name)
      val updateShort = shortDescription.getOrElse(directive.shortDescription)
      val updateLong = longDescription.getOrElse(directive.longDescription)
      val updateEnabled = enabled.getOrElse(directive.isEnabled)
      val updateTechniqueVersion = techniqueVersion.getOrElse(directive.techniqueVersion)
      val updateParameters = parameters.getOrElse(directive.parameters)
      val updatePriority = priority.getOrElse(directive.priority)
      val updateMode = policyMode.getOrElse(directive.policyMode)
      val updateTags = tags.getOrElse(directive.tags)
      directive.copy(
          name             = updateName
        , shortDescription = updateShort
        , longDescription  = updateLong
        , _isEnabled       = updateEnabled
        , parameters       = updateParameters
        , techniqueVersion = updateTechniqueVersion
        , priority         = updatePriority
        , policyMode       = updateMode
        , tags             = updateTags
      )

    }
}

case class DirectiveState (
    technique : Technique
  , directive : Directive
)

case class DirectiveUpdate(
    activeTechnique: ActiveTechnique
  , before: DirectiveState
  , after : DirectiveState
)

case class RestGroup(
      name        : Option[String] = None
    , description : Option[String] = None
    , query       : Option[Query] = None
    , isDynamic   : Option[Boolean] = None
    , enabled     : Option[Boolean] = None
    , category    : Option[NodeGroupCategoryId] = None
  ) {

    val onlyName = name.isDefined      &&
                   description.isEmpty &&
                   query.isEmpty       &&
                   isDynamic.isEmpty   &&
                   enabled.isEmpty     &&
                   category.isEmpty

    def updateGroup(group: NodeGroup) = {
      val updateName  = name.getOrElse(group.name)
      val updateDesc  = description.getOrElse(group.description)
      val updateisDynamic = isDynamic.getOrElse(group.isDynamic)
      val updateEnabled = enabled.getOrElse(group.isEnabled)
      group.copy(
          name        = updateName
        , description = updateDesc
        , query       = query
        , isDynamic   = updateisDynamic
        , _isEnabled  = updateEnabled
      )

    }
}

case class RestNodeProperties(
    properties : Option[Seq[NodeProperty]]
)

case class RestNode (
    properties : Option[Seq[NodeProperty]]
  , policyMode : Option[Option[PolicyMode]]
  , state      : Option[NodeState]
)

sealed trait NodeStatusAction
case object AcceptNode extends NodeStatusAction
case object RefuseNode extends NodeStatusAction
case object DeleteNode extends NodeStatusAction

case class RestParameter(
      value       : Option[String] = None
    , description : Option[String] = None
    , overridable : Option[Boolean] = None
  ) {


    def updateParameter(parameter: GlobalParameter) = {
      val updateValue = value.getOrElse(parameter.value)
      val updateDescription = description.getOrElse(parameter.description)
      val updateOverridable = overridable.getOrElse(parameter.overridable)
      parameter.copy(
          value       = updateValue
        , description = updateDescription
        , overridable = updateOverridable
      )

    }
}

case class RestRule(
      name             : Option[String]           = None
    , category         : Option[RuleCategoryId]   = None
    , shortDescription : Option[String]           = None
    , longDescription  : Option[String]           = None
    , directives       : Option[Set[DirectiveId]] = None
    , targets          : Option[Set[RuleTarget]]  = None
    , enabled          : Option[Boolean]          = None
    , tags             : Option[Tags]              = None
  ) {

    val onlyName = name.isDefined           &&
                   category.isEmpty         &&
                   shortDescription.isEmpty &&
                   longDescription.isEmpty  &&
                   directives.isEmpty       &&
                   targets.isEmpty          &&
                   enabled.isEmpty          &&
                   tags.isEmpty

    def updateRule(rule: Rule) = {
      val updateName = name.getOrElse(rule.name)
      val updateCategory = category.getOrElse(rule.categoryId)
      val updateShort = shortDescription.getOrElse(rule.shortDescription)
      val updateLong = longDescription.getOrElse(rule.longDescription)
      val updateDirectives = directives.getOrElse(rule.directiveIds)
      val updateTargets = targets.getOrElse(rule.targets)
      val updateEnabled = enabled.getOrElse(rule.isEnabledStatus)
      val updateTags    = tags.getOrElse(rule.tags)
      rule.copy(
          name             = updateName
        , categoryId       = updateCategory
        , shortDescription = updateShort
        , longDescription  = updateLong
        , directiveIds     = updateDirectives
        , targets          = updateTargets
        , isEnabledStatus  = updateEnabled
        , tags             = updateTags
      )

    }
}
