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

package com.normation.rudder.rule.category

import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.tenants.HasSecurityTag
import com.normation.rudder.tenants.SecurityTag
import net.liftweb.common.*

/**
 * The Id for the server group category
 */
final case class RuleCategoryId(value: String) extends AnyVal

/**
 * A rule category is quite similar to a Technique category :
 * an id
 * a name
 * a description
 * some subcategories
 * some items
 */
final case class RuleCategory(
    id:          RuleCategoryId,
    name:        String,
    description: String,
    childs:      List[RuleCategory],
    isSystem:    Boolean = false,
    // security so that in json is becomes: { "security": { "tenants": [...] }, ...}
    security:    Option[SecurityTag] // optional for backward compat. None means "no tenant"
) {
  def findParent(category: RuleCategory): Box[RuleCategory] = {
    if (childs.contains(category)) {
      Full(this)
    } else {
      childs.map(_.findParent(category)).collect { case Full(c) => c } match {
        case c :: Nil => Full(c)
        case Nil      => Failure(s"cannot find parent category of ${category.name}")
        case _        => Failure(s"too much parents for category ${category.name}")
      }
    }
  }

  // From a category find if a category if is contained in within it
  // Return that category and its parent category id
  def find(categoryId: RuleCategoryId): Box[(RuleCategory, RuleCategoryId)] = {
    childPath(categoryId) match {
      // special case for long hierarchy
      case Right(list) if list.size > 2     =>
        val parent :: category :: Nil =
          list.takeRight(2): @unchecked // because we checked size
        Full((category, parent.id))
      case Right(parent :: category :: Nil) => Full((category, parent.id))
      case Right(category :: Nil)           => Full((category, category.id))
      case Right(_)                         =>
        Failure(s"could not find category '${categoryId.value}' in category '${id.value}'")
      case Left(s)                          =>
        Failure(s) ?~! s"could not find category '${categoryId.value}' in category '${id.value}'"
    }
  }

  // Path to a Children, including this child
  def childPath(childrenId: RuleCategoryId): Either[String, List[RuleCategory]] = {
    if (this.id == childrenId) {
      Right(this :: Nil)
    } else {
      // Try to find children id in childs, collect to get only positive results
      val paths = childs.map(_.childPath(childrenId)).collect { case Right(c) => c }
      paths match {
        case c :: Nil => Right(this :: c)
        case Nil      => Left(s"cannot find parent category of ID '${childrenId.value}'")
        case _        => Left(s"too much parents for category of ID '${childrenId.value}'")
      }
    }
  }

  // Path to a children not containing the children
  def findParents(childrenId: RuleCategoryId): Either[String, List[RuleCategory]] = {
    childPath(childrenId).map(_.init)
  }

  // Filter a category from children categories
  def filter(category: RuleCategory): RuleCategory = {
    if (childs.contains(category)) {
      this.copy(childs = this.childs.filter(_ != category))
    } else {
      // Not in that category, filter its children
      this.copy(childs = this.childs.map(_.filter(category)))
    }
  }

  def canBeDeleted(rules: List[Rule]): Boolean = {
    childs.isEmpty && rules.filter(_.categoryId == this.id).isEmpty
  }

  def contains(categoryId: RuleCategoryId): Boolean = {
    childs.exists(_.id == categoryId) || childs.exists(_.contains(categoryId))
  }

  type ChildMap = Map[List[RuleCategoryId], List[RuleCategory]]
  // Create containing all categories of this node grouped by parent categories
  def childrenMap: ChildMap = {

    // Merge current map with already merged map
    def mergeChildMaps(
        alreadyMerged: ChildMap,
        currentMap:    ChildMap
    ): ChildMap = {
      // get all distinct keys from both map
      val keys  = (currentMap.keys ++ alreadyMerged.keys).toList.distinct
      // For all keys
      val merge = keys.map {
        case key =>
          // Get value from key
          val childValue   = currentMap.get(key).getOrElse(Nil)
          val parentValue  = alreadyMerged.get(key).getOrElse(Nil)
          // merge them (distinct is not mandatory, a Category could not be into two separeted branch
          val mergedValues = (childValue ++ parentValue).distinct
          // Make a couple so it can be converted into a Map
          (key, mergedValues)
      }
      merge.toMap
    }
    // Transform all childs into their map of childrens
    val childMap: List[ChildMap] = childs.map(_.childrenMap)

    // Add the current category id to all keys in the map
    val augmentedChildMap: List[ChildMap] = childMap.map(_.map { case (k, v) => (id :: k, v) })

    // baseMap is the current level, it should have an empty list as key, it will be augmented by the parent by recursion
    val baseMap: ChildMap = Map((List.empty[RuleCategoryId]) -> (this :: Nil))

    // fold all maps together
    augmentedChildMap.foldLeft(baseMap)(mergeChildMaps)
  }
}

object RuleCategory {
  given HasSecurityTag[RuleCategory] with {
    extension (a: RuleCategory) {
      override def security: Option[SecurityTag] = a.security
      override def debugId:  String              = a.id.value
      override def updateSecurityContext(security: Option[SecurityTag]): RuleCategory = a.copy(security = security)
    }
  }
}
