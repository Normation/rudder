/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

import bootstrap.liftweb.RudderConfig
import com.normation.rudder.users.RudderPasswordEncoder.SecurityLevel
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.RenderDispatch
import scala.xml.NodeSeq

class UserManagementInfo extends DispatchSnippet with RenderDispatch {

  private[this] val fileUserDetailListProvider = RudderConfig.rudderUserListProvider

  def render(in: NodeSeq): NodeSeq = {
    fileUserDetailListProvider.authConfig.encoder.securityLevel match {
      case SecurityLevel.Modern => NodeSeq.Empty
      case SecurityLevel.Legacy =>
        val tooltipContent =
          "<h4><i class='fa fa-exclamation-triangle'></i> User configuration require your attention</h4><div class=\"tooltip-content\">Passwords configuration is insecure and will be deprecated, please open the page to see the details.</div>"
        <i class={
          "fa fa-exclamation-triangle plugin-icon icon-info text-danger"
        } data-bs-toggle="tooltip" data-bs-placement="right" title={
          tooltipContent
        } data-bs-container="body"></i>
    }
  }
}
