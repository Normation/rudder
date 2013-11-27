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

package com.normation.rudder.web.services
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import net.liftweb.common._
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategoryId

/**
 * Display the hierarchy of category in the NodeGroup and NodeCategory
 * drop down in a nicely way
 */
class CategoryHierarchyDisplayer() {
  def getCategoriesHierarchy(rootCategory:FullNodeGroupCategory, exclude: Option[FullNodeGroupCategory => Boolean]) : Seq[(NodeGroupCategoryId, String)] = {
    //always exclude system categories (minus root one)
    val excludeSystem = (cat: FullNodeGroupCategory) => (cat.id != rootCategory.id && cat.isSystem)
    val ex = exclude match {
      case None =>
        (cat: FullNodeGroupCategory) => excludeSystem(cat)
      case Some(ex1) =>
        (cat: FullNodeGroupCategory) => excludeSystem(cat) || ex1(cat)
    }
    rootCategory.getSortedCategories((a,b) => a.name > b.name, ex).map { case (list, nodeGroupCategory) =>
       if(list.size <= 1) {
         (nodeGroupCategory.id, nodeGroupCategory.name)
       } else {
         (nodeGroupCategory.id, ("\u00a0\u00a0\u00a0\u00a0\u00a0"*(list.size-2) + "\u2514"+"\u2500 " + nodeGroupCategory.name))
       }
    }
  }


  def getRuleCategoryHierarchy(rootCategory:RuleCategory, exclude: Option[RuleCategory => Boolean], size:Int = 0) : List[(RuleCategoryId, String)] = {
    //always exclude system categories (minus root one)
    val excludeSystem = (cat: RuleCategory) => (cat.id != rootCategory.id && cat.isSystem)
    val ex = exclude match {
      case None =>
        (cat: RuleCategory) => excludeSystem(cat)
      case Some(ex1) =>
        (cat: RuleCategory) => excludeSystem(cat) || ex1(cat)
    }

         (if (size == 0) {
           (rootCategory.id, rootCategory.name)
         }
         else {
          (rootCategory.id, ("\u00a0\u00a0\u00a0\u00a0\u00a0"*(size-1) + "\u2514"+"\u2500 " + rootCategory.name))
         }) :: rootCategory.childs.sortBy(_.name).flatMap { case category => getRuleCategoryHierarchy(category, exclude, size+1)
    }
  }
}