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

package com.normation.rudder.rule.category

import com.normation.utils.HashcodeCaching
import net.liftweb.common.Box
import net.liftweb.common.Full
import net.liftweb.common.Failure
import com.normation.rudder.domain.policies.RuleId

/**
 * The Id for the server group category
 */
case class RuleCategoryId(value:String) extends HashcodeCaching


/**
 * A rule category is quite similar to a Technique category :
 * an id
 * a name
 * a description
 * some subcategories
 * some items
 */
case class RuleCategory(
    id          : RuleCategoryId
  , name        : String
  , description : String
  , childs      : List[RuleCategory]
  , isSystem    : Boolean = false
) {
  def findParent (category : RuleCategory) :  Box[RuleCategory]= {
    if (childs.contains(category)) {
      Full(this)
    } else {
      childs.map(_.findParent(category)).collect {case Full(c) => c } match {
        case c :: Nil => Full(c)
        case Nil => Failure(s"cannot find parent category of ${category.name}")
        case _ => Failure(s"too much parents for category ${category.name}")
      }
    }
  }


  def filter(category : RuleCategory) : RuleCategory = {
    if (childs.contains(category)) {
      this.copy(childs = this.childs.filter(_ != category))
    } else {
      this.copy(childs = this.childs.map(filter))
    }
  }
}

