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

package com.normation.rudder.domain.policies
import com.normation.utils.HashcodeCaching



case class RuleId(value:String) extends HashcodeCaching 

case class SerialedRuleId(ruleId : RuleId, serial : Int) extends HashcodeCaching 

/**
 * A rule is a binding between a set of directives
 * and some target (group of node, etc) on which applying 
 * these directives. 
 * 
 * A rule may be stored in a pending state, for
 * example if it is not fully initialized. 
 * In that case, it *MUST* be considered desactivated, whatever
 * the isEnabledField say. 
 */
case class Rule(
  id:RuleId,
  name:String,
  serial:Int,
  target:Option[RuleTarget] = None, //is not mandatory, but if not present, rule is disabled
  directiveIds:Set[DirectiveId] = Set(), //is not mandatory, but if not present, rule is disabled
  shortDescription:String = "",
  longDescription:String = "",
  isEnabledStatus:Boolean = false,
  isSystem:Boolean = false
) extends HashcodeCaching {
  def isEnabled = isEnabledStatus & target.isDefined & directiveIds.size > 0
}
