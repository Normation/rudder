/*
 *************************************************************************************
 * Copyright 2021 Normation SAS
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
import com.normation.rudder.AuthorizationType.Administration
import com.normation.rudder.users.CurrentUser
import com.normation.zio.UnsafeRun
import net.liftweb.common.*
import net.liftweb.http.CurrentReq
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.js.JsCmd
import net.liftweb.http.js.JsCmds.*
import scala.xml.NodeSeq
import zio.syntax.*

class SetupRedirect extends DispatchSnippet with Loggable {

  private val pluginSettingsService = RudderConfig.pluginSettingsService

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = {
    case "display" => _ => WithNonce.scriptWithNonce(Script(display()))
  }

  def display(): JsCmd = {
    // Variables that need to be outside of ZIO context
    val hasAdminRights     = CurrentUser.checkRights(Administration.Write)
    val isSetupTab         = CurrentReq.value.request.url.contains("administration/settings#welcomeSetupTab")
    val redirectToSetupTab = RedirectTo("/secure/administration/settings#welcomeSetupTab")

    pluginSettingsService
      .checkIsSetup()
      .map {
        // only redirect if the user also is admin and is not already on the page for the setup
        case false if (hasAdminRights && !isSetupTab) =>
          redirectToSetupTab
        case _                                        =>
          Noop
      }
      .catchAll(_ => Noop.succeed)
      .runNow
  }
}
