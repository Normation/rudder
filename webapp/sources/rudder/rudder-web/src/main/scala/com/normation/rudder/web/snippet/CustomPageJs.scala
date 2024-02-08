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

package com.normation.rudder.web.snippet

import net.liftweb.http.DispatchSnippet
import net.liftweb.http.LiftRules
import net.liftweb.http.RequestVar
import net.liftweb.http.S
import scala.xml.NodeBuffer

/**
  * We need to provide both lift.js and page js to the client with a custom nonce.
  * This object provides a way to check if custom page js was added, this may be necessary to avoid duplicate lift.js script tags.
  */
object CustomPageJs extends DispatchSnippet {

  private object hasCustomPageScript extends RequestVar(false)

  val liftJsScriptSrc = s"${S.contextPath}/${LiftRules.resourceServerPath}/lift.js"

  def pageJsScriptSrc = s"${S.contextPath}/${LiftRules.liftContextRelativePath}/page/${S.renderVersion}.js"

  def dispatch: DispatchIt = { case "pageScript" => _ => pageScript }

  def pageScript: NodeBuffer = {
    val _ = hasCustomPageScript.set(true)
    <script data-lift="with-nonce" src={s"/${LiftRules.resourceServerPath}/lift.js"}></script>
    <script type="text/javascript" data-lift="with-nonce" src={scriptUrl(s"page/${S.renderVersion}.js")}></script>
  }

  def hasDuplicateLiftScripts: Boolean = {
    hasCustomPageScript.get
  }

  private def scriptUrl(scriptFile: String) = {
    S.encodeURL(s"/${LiftRules.liftContextRelativePath}/$scriptFile")
  }
}
