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

package com.normation.rudder.web.model

import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.context.SecurityContextHolder
import com.normation.eventlog.EventActor
import net.liftweb.http.SessionVar
import bootstrap.liftweb.RudderUserDetail
import com.normation.rudder.authorization._
import com.normation.authorization._

/**
 * An utility class that get the currently logged user
 * (if any)
 *
 */
object CurrentUser extends SessionVar[Option[RudderUserDetail]]({
  SecurityContextHolder.getContext.getAuthentication match {
    case null => None
    case auth => auth.getPrincipal match {
      case u:RudderUserDetail => Some(u)
      case _ => None
    }
  }
  
}) {

  def getRights : Rights = this.is match {
    case Some(u) => u.authz
    case None => new Rights(NoRights)
  }
  
  def getActor : EventActor = this.is match {
    case Some(u) => EventActor(u.getUsername)
    case None => EventActor("unknown")
  }
  
  def checkRights(auth:AuthorizationType) : Boolean = {
    val authz = getRights.authorizationTypes
    if (authz.contains(NoRights)) false
    else auth match{
      case NoRights => false
      case _ =>  authz.contains(auth)
    }
    
  }
}