/*
*************************************************************************************
* Copyright 2014 Normation SAS
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

package com.normation.rudder.web.rest

import com.normation.rudder.authorization.Read
import com.normation.rudder.authorization.Write
import com.normation.rudder.web.model.CurrentUser

import net.liftweb.common.Loggable
import net.liftweb.http.JsonResponse
import net.liftweb.http.LiftSession
import net.liftweb.http.rest.RestHelper
import net.liftweb.json.JsonDSL._

object RestAuthentication extends RestHelper with Loggable {


  serve {

    case Get("authentication" :: Nil,  req) => {
      val session = LiftSession(req)

      //the result depends upon the "acl" param value, defaulted to "non read" (write).
      val (message, status) = req.param("acl").openOr("write").toLowerCase match {
        case "read" => //checking if current user has read rights on techniques
          if(CurrentUser.checkRights(Read("technique"))) {
            (session.uniqueId, RestOk)
          } else {
            val msg = s"Authentication API forbids read access to Techniques for user ${CurrentUser.getActor.name}"
            logger.error(msg)
            (msg, RestError)
          }
        case _ => //checking for write access - by defaults, we look for the higher priority
          if(CurrentUser.checkRights(Write("technique"))) {
            (session.uniqueId, RestOk)
          } else {
            val msg = s"Authentication API forbids write access to Techniques for user ${CurrentUser.getActor.name}"
            logger.error(msg)
            (msg,RestError)
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
