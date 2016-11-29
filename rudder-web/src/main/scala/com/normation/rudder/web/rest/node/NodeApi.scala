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

package com.normation.rudder.web.rest.node

import net.liftweb.common.Box
import net.liftweb.common.Loggable
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.http.rest.RestHelper
import com.normation.rudder.web.rest.RestAPI
import com.normation.rudder.domain.nodes.NodeProperty
import com.normation.rudder.domain.policies.PolicyMode
import net.liftweb.json.JsonAST.JString
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.authorization._

trait NodeAPI extends RestAPI {
  val kind = "nodes"

  override protected def checkSecure : PartialFunction[Req, Boolean] = {
    case Get(_,_) => CurrentUser.checkRights(Read("node"))
    case Post(_,_) | Put(_,_) | Delete(_,_) => CurrentUser.checkRights(Write("node")) || CurrentUser.checkRights(Edit("node"))
    case _=> false

  }
}

case class RestNodeProperties(
    properties : Option[Seq[NodeProperty]]
)

case class RestNode (
    properties : Option[Seq[NodeProperty]]
  , policyMode : Option[Option[PolicyMode]]
)

object CompareProperties {
  /**
   * Update a set of properties with the map:
   * - if a key of the map matches a property name,
   *   use the map value for the key as value for
   *   the property
   * - if the value is the emtpy string, remove
   *   the property
   */
  def updateProperties(props: Seq[NodeProperty], updates: Option[Seq[NodeProperty]]) = {
    updates match {
      case None => props
      case Some(u) =>
        val values = u.map { case NodeProperty(k, v) => (k, v)}.toMap
        val existings = props.map(_.name).toSet
        //for news values, don't keep empty
        val news = (values -- existings).collect { case(k,v) if(v != JString("")) => NodeProperty(k,v) }
        props.flatMap { case p@NodeProperty(name, value)  =>
          values.get(name) match {
            case None              => Some(p)
            case Some(JString("")) => None
            case Some(x)           => Some(NodeProperty(name, x))
          }
        } ++ news
    }
  }

}
