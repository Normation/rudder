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

package com.normation.rudder.web.services


import org.springframework.context.ApplicationListener
import org.springframework.context.ApplicationEvent
import org.springframework.security.core.session.SessionDestroyedEvent
import org.springframework.security.core.session.SessionCreationEvent
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.domain.log._
import net.liftweb.common.Loggable
import org.springframework.security.core.userdetails.UserDetails
import com.normation.eventlog.EventActor
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent
import com.normation.eventlog.EventLogDetails
import com.normation.eventlog.EventLog

/**
 * A class used to log user session creation/destruction events.
 * This linked to Spring Security, and so to Spring to. 
 * That creates a hard reference from/to spring for our app, 
 * hopefully a simple one to break.
 */
class UserSessionLogEvent(
    repository:EventLogRepository
) extends ApplicationListener[ApplicationEvent] with Loggable {

  def onApplicationEvent(event : ApplicationEvent):Unit = {
    event match {
      case login:AuthenticationSuccessEvent => 
        login.getAuthentication.getPrincipal match {
          case u:UserDetails => 
            repository.saveEventLog(
                LoginEventLog(
                    EventLogDetails( 
                        principal = EventActor(u.getUsername)
                      , details = EventLog.emptyDetails
                    )
                )
            )
          case x => 
            logger.warn("The application received an Authentication 'success' event with a parameter that is neither a principal nor some user details. I don't know how to log that event in database. Event parameter was: " +x)
        }

      case badLogin:AuthenticationFailureBadCredentialsEvent =>
        badLogin.getAuthentication.getPrincipal match {
          case u:String =>
            repository.saveEventLog(
                BadCredentialsEventLog(
                    EventLogDetails( 
                      principal = EventActor(u)
                      , details = EventLog.emptyDetails
                    )
                )
            )
          case x =>
            logger.warn("The application received an Authentication 'bad credential' event with a parameter that is not the principal login. I don't know how to log that event in database. Event parameter was: " +x)
        }

//  these events don't seem to work :/
//      case sessionCreation:SessionCreationEvent => 
//        println("Session creation " + sessionCreation.getSource.toString)
//      case sessionDestruction:SessionDestroyedEvent =>
//        println("Session destruction " + sessionDestruction.getSecurityContext().getAuthentication)
//
      case x => //ignore
    }
    
  }
}

