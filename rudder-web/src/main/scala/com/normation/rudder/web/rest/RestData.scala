/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

package com.normation.rudder.web.rest

import com.normation.rudder.rule.category._
import net.liftweb.common._
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.repository.FullNodeGroupCategory



sealed trait DetailLevel {
  def value : String
}

case object FullDetails extends DetailLevel {
  val value = "full"
}
case object MinimalDetails extends DetailLevel {
  val value = "minimal"
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