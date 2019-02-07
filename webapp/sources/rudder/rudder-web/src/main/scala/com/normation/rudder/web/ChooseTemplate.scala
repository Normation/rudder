/*
*************************************************************************************
* Copyright 2016 Normation SAS
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

package com.normation.rudder.web

import net.liftweb.http._
import net.liftweb.util.Helpers._
import net.liftweb.common._
import scala.xml.NodeSeq

/**
 * A replacement for "chooseTemplate" from Lift 2.6.
 * It is based on CSS selector.
 * It should be just a temporary replacement of real
 * use of CSS selector with correct HTML5 pages.
 *
 * If the template is missing, it produces a sys.error (fatale).
 * Better for dev, but you should have your template.
 */
object ChooseTemplate {

  /**
   * Typical use case to replace chooseTemplate:
   * ChooseTemplate("templates-hidden" :: "foo" :: Nil, "component-plop")
   *
   */
  def apply(templatePath: List[String], selector: String): NodeSeq = {
    val xml = Templates(templatePath) match {
      case eb:EmptyBox =>
       sys.error(s"Template for path ${templatePath.mkString("/")}.html not found.")
      case Full(x) => x
    }
    //chose children. The right part is ignored.
    val select = (s"$selector ^*" #> "not relevant")
    select(xml)
  }

}

