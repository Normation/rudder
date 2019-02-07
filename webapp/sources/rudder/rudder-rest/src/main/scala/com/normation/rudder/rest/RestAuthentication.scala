/*
*************************************************************************************
* Copyright 2014 Normation SAS
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

package com.normation.rudder.rest

import net.liftweb.common.Loggable
import net.liftweb.http.JsonResponse
import net.liftweb.http.LiftSession
import net.liftweb.http.rest.RestHelper
import net.liftweb.json.JsonDSL._
import com.normation.rudder.UserService
import com.normation.rudder.AuthorizationType

class RestAuthentication(
  userService : UserService
) extends RestHelper with Loggable {

  serve {

    case Get("api" :: "authentication" :: Nil | "authentication" :: Nil,  req) => {
      val session = LiftSession(req)

      val currentUser = userService.getCurrentUser
      //the result depends upon the "acl" param value, defaulted to "non read" (write).
      val (message, status) = req.param("acl").openOr("write").toLowerCase match {
        case "read" => //checking if current user has read rights on techniques
          if(currentUser.checkRights(AuthorizationType.Technique.Read)) {
            (session.uniqueId, RestOk)
          } else {
            val msg = s"Authentication API forbids read access to Techniques for user ${currentUser.actor.name}"
            logger.error(msg)
            (msg, ForbiddenError)
          }
        case _ => //checking for write access - by defaults, we look for the higher priority
          if(currentUser.checkRights(AuthorizationType.Technique.Write)) {
            (session.uniqueId, RestOk)
          } else {
            val msg = s"Authentication API forbids write access to Techniques for user ${currentUser.actor.name}"
            logger.error(msg)
            (msg,ForbiddenError)
          }
      }

      val content =
        ( "action" -> "authentication" ) ~
        ( "result" -> status.status ) ~
        ( status.container -> message )

      JsonResponse(content, status.code)
    }
  }
}
