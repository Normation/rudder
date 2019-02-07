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

package com.normation.rudder.web.rest.group

import com.normation.rudder.authorization.{Edit, Read, Write}
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.web.rest.RestAPI
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.web.model.CurrentUser
import net.liftweb.http.Req


trait GroupAPI extends RestAPI {
  val kind = "groups"

  override protected def checkSecure : PartialFunction[Req, Boolean] = {
    case Get(_,_) => CurrentUser.checkRights(Read("group"))
    case Post(_,_) | Put(_,_) | Delete(_,_) => CurrentUser.checkRights(Write("group")) || CurrentUser.checkRights(Edit("group"))
    case _=> false

  }
}

case class RestGroup(
      name        : Option[String] = None
    , description : Option[String] = None
    , query       : Option[Query] = None
    , isDynamic   : Option[Boolean] = None
    , enabled     : Option[Boolean] = None
    , category    : Option[NodeGroupCategoryId] = None
  ) {

    val onlyName = name.isDefined      &&
                   description.isEmpty &&
                   query.isEmpty       &&
                   isDynamic.isEmpty   &&
                   enabled.isEmpty     &&
                   category.isEmpty

    def updateGroup(group:NodeGroup) = {
      val updateName  = name.getOrElse(group.name)
      val updateDesc  = description.getOrElse(group.description)
      val updateisDynamic = isDynamic.getOrElse(group.isDynamic)
      val updateEnabled = enabled.getOrElse(group.isEnabled)
      group.copy(
          name        = updateName
        , description = updateDesc
        , query       = query
        , isDynamic   = updateisDynamic
        , _isEnabled  = updateEnabled
      )

    }
}
