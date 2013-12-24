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

package com.normation.rudder.rule.category

import net.liftweb.common.Box
import net.liftweb.common.Full

class RuleCategoryService(
  roRuleCategoryService : RoRuleCategoryRepository
) {


  // from a rule category, get the full FQDN and the short fqdn
  // The root category element can be displayed in caps in the full fqdn
  def bothFqdn(id:RuleCategoryId,rootInCaps : Boolean = false) : Box[(String,String)] = {
    for {
      fqdn  <-roRuleCategoryService.getParents(id,true)
      short = toShortFqdn(fqdn)
    } yield {
      val full = {
        val complete =
        if (rootInCaps ) {
          fqdn.head.name.toUpperCase :: fqdn.tail.map(_.name)
        } else {
          fqdn.map(_.name)
        }
        complete.mkString(" » ")
      }
      (full,short)
    }
  }

  // transform a list of Rule categrory to a short fqdn for Rule category
  // short fqdn is the path list of all parents to the root category, minus the root category
  private[this] def toShortFqdn(parents  : List[RuleCategory]) : String = {
    parents.tail.map(_.name).mkString(" » ")
  }

  // Get the short fqdn from the id of a Rule category
  def shortFqdn (id : RuleCategoryId) : Box[String] = {
      roRuleCategoryService.getParents(id,true).map(toShortFqdn _)
  }
}