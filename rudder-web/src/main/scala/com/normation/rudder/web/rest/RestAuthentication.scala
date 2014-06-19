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

import net.liftweb.http.rest.RestHelper
import net.liftweb.common.Loggable
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.authentication.AnonymousAuthenticationToken
import net.liftweb.http.LiftSession
import net.liftweb.json.JsonDSL._
import net.liftweb.http.JsonResponse

object RestAuthentication extends RestHelper with Loggable {

  serve {

    case Get("authentication" :: Nil,  req) => {

      val session = LiftSession(req)

      // Authentication is done via a cookie "JSESSIONID", Spring security checks it from the security context of the session
      val (message,status) = SecurityContextHolder.getContext.getAuthentication match {
        case null =>
          val msg = s"Could not authenticate with authentication API: No cookie available for session ${session.uniqueId} to authenticate"
          logger.error(msg)
          (msg,RestError)
        case auth => auth match {
          case a : AnonymousAuthenticationToken =>
            val msg = s"Could not authenticate with authentication API: cookie for session ${session.uniqueId} is not valid anymore"
            logger.error(msg)
            (msg,RestError)
          case _ =>
          ( session.uniqueId, RestOk)
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