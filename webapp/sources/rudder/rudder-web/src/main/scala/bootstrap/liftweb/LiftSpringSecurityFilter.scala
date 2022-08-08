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

package bootstrap.liftweb

import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.spring.SecurityFilter

import org.springframework.http.HttpHeaders
import org.springframework.security.web.firewall.RequestRejectedException

import javax.servlet.FilterChain
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * Our Spring security filter, only redefined to override the way
 * the Spring Application context is accessed
 */
class LiftSpringSecurityFilter extends SecurityFilter {
  override def webApplicationContext = LiftSpringApplicationContext.springContext

  override def doFilter(request: ServletRequest, response: ServletResponse, filterChain: FilterChain): Unit = {
    try {
      super.doFilter(request, response, filterChain)
    } catch {
      case e: RequestRejectedException =>
        val req = request.asInstanceOf[HttpServletRequest]
        val res = response.asInstanceOf[HttpServletResponse]
        ApplicationLogger.warn(s"request_rejected: remote=${req.getRemoteHost()}, " +
                               s"user_agent=${req.getHeader(HttpHeaders.USER_AGENT)}, " +
                               s"request_url=${req.getRequestURL}, " +
                               s"reason: ${e.getMessage}"
        )
        res.sendError(HttpServletResponse.SC_NOT_FOUND)
    }
  }
}
