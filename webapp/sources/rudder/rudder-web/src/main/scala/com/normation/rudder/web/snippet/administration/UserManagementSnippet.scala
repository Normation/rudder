/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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
package com.normation.rudder.web.snippet.administration

import com.normation.rudder.users.CurrentUser
import com.normation.rudder.web.snippet.WithNonce
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import org.apache.commons.text.StringEscapeUtils
import scala.xml.NodeSeq

class UserManagementSnippet extends DispatchSnippet {

  override def dispatch: DispatchIt = { case "render" => _ => scripts }

  private def scripts: NodeSeq = {
    val userId = StringEscapeUtils.escapeEcmaScript(CurrentUser.get.map(_.getUsername).getOrElse(""))
    WithNonce.scriptWithNonce(
      Script(
        OnLoad(
          JsRaw(
            s"""
               |var main = document.getElementById("user-management-content")
               |var initValues = {
               |  contextPath : contextPath,
               |  userId : "${userId}"
               |}
               |var app  = Elm.UserManagement.init({node: main, flags: initValues})
               |app.ports.successNotification.subscribe(function(str) {
               |  createSuccessNotification(str)
               |});
               |app.ports.errorNotification.subscribe(function(str) {
               |  createErrorNotification(str)
               |});
               |app.ports.initTooltips.subscribe(function (msg) {
               |  setTimeout(function () {
               |    initBsTooltips();
               |  }, 600);
               |});
               |""".stripMargin
          )
        )
      )
    )
  }
}
