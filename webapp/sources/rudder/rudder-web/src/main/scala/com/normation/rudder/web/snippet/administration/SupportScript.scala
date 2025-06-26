/*
 *************************************************************************************
 * Copyright 2018 Normation SAS
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

import bootstrap.liftweb.RudderConfig
import com.normation.zio.UnsafeRun
import java.util.Base64
import net.liftweb.common.Loggable
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.IdMemoizeTransform
import net.liftweb.http.SHtml
import net.liftweb.http.js.JsCmd
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.Helpers.*
import scala.xml.NodeSeq
import scala.xml.Text

class DebugScript extends DispatchSnippet with Loggable {

  private val debugInfoService = RudderConfig.debugScript

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = { case "render" => launchDebugScript }

  def launchDebugScript: IdMemoizeTransform = SHtml.idMemoize { outerXml =>
    // our process method returns a
    // JsCmd which will be sent back to the browser
    // as part of the response
    def process(): JsCmd = {
      val scriptResult = debugInfoService.launch().either.runNow
      val cmd          = scriptResult match {
        case Left(_)      =>
          """createErrorNotification("Error has occured while getting debug script result, please consult policy server logs");"""
        case Right(value) =>
          val base64 = Base64.getEncoder().encodeToString(value.result)
          s"""saveByteArray("${value.serverName}", "application/gzip", base64ToArrayBuffer("${base64}"));"""
      }
      Run(cmd)
    }

    // process the list of networks
    "#launchDebugScriptButton" #> {
      SHtml.ajaxButton(
        (<span class="fa fa-download"></span>: NodeSeq) ++ Text(" Download debug information"),
        () => process(),
        ("class", "btn btn-primary")
      )
    }
  }

}
