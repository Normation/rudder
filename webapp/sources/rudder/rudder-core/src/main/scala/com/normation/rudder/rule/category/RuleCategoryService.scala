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

import net.liftweb.common.Box
import net.liftweb.common.Failure
import net.liftweb.common.Full

class RuleCategoryService {

  // from a rule category, get the full FQDN and the short fqdn
  // The root category element can be displayed in caps in the full fqdn
  def bothFqdn(rootCategory: RuleCategory, id: RuleCategoryId, rootInCaps : Boolean = false) : Box[(String,String)] = {
    for {
      fqdn  <- rootCategory.childPath(id) match {
                 case Left(x)  => Failure(x)
                 case Right(x) => Full(x)
               }
      short =  toShortFqdn(fqdn)
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
  def shortFqdn(rootCategory: RuleCategory, id : RuleCategoryId) : Box[String] = {
    rootCategory.childPath(id).map(toShortFqdn _) match {
      case Left(value)  => Failure(value)
      case Right(value) => Full(value)
    }
  }
}
