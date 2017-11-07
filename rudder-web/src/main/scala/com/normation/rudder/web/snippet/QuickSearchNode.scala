/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.rudder.web.snippet

import scala.xml.NodeSeq
import net.liftweb.util.Helpers._
import net.liftweb.common._
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.S
import com.normation.rudder.services.quicksearch.QSObject
import com.normation.rudder.services.quicksearch.QSMapping

/**
 * This snippet allow to display the node "quick search" field.
 * It autocompletes on node hostname or id, and redirect
 * to the search node page to display node details.
 */
class QuickSearchNode extends DispatchSnippet with Loggable {

  def dispatch = {
    case "render" => quickSearchEveryting
  }

  def quickSearchEveryting(html: NodeSeq) : NodeSeq = {
    val bind = (
      "#angucomplete-ie8-quicksearch  [remote-url]" #> s"${S.contextPath}/secure/api/quicksearch?value="
    )
    (bind(html))
  }

  //json view of the aliases
  val jsonDocinfo = {
    import net.liftweb.json._
    import net.liftweb.json.JsonAST.{compactRender => _, _}
    import net.liftweb.json.JsonDSL._
    import com.normation.rudder.services.quicksearch.QSObject._

    val objs: List[JObject] = QSObject.all.toList.sortWith(sortQSObject).map { obj =>

      (
          ( "name" -> obj.name )
        ~ ( "attributes" -> obj.attributes.toSeq.map ( attr => (
                ( "name" -> attr.name )
              ~ ( "aliases" -> QSMapping.attributeNames.getOrElse(attr, Set()).toList )
          ) ) )
      )
    }

    "'" + compactRender(objs) + "'"
  }

}
