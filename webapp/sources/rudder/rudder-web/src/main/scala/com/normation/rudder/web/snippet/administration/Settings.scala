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

import com.normation.plugins.DefaultExtendableSnippet
import com.normation.rudder.web.ChooseTemplate
import net.liftweb.http.DispatchSnippet
import net.liftweb.util.CssSel
import net.liftweb.util.Helpers.*
import scala.xml.*

class Settings extends DispatchSnippet with DefaultExtendableSnippet[Settings] {
  private def settingsTabContent(xml: NodeSeq) =
    ChooseTemplate("templates-hidden" :: "components" :: "administration" :: "policyServerManagement" :: Nil, "component-body")

  override def mainDispatch: Map[String, NodeSeq => NodeSeq] = {
    Map(
      "body"           -> identity,
      "mainTabContent" -> settingsTabContent
    )
  }
}

object Settings {

  /*
   * Create a new tab (the clickable header part).
   * The id must not contain the `#`
   */
  def tabMenu(tabId: String, name: String) = {
    <li class="nav-item" role="presentation">
      <button class="nav-link" data-bs-toggle="tab" data-bs-target={
      "#" + tabId
    } type="button" role="tab" aria-controls={tabId}>{name}</button>
    </li>
  }

  // an utility method that provides the correct cssSel to add a menu item
  def addTabMenu(tabId: String, name: String): CssSel = {
    "#settingsTabMenu *+" #> Settings.tabMenu(tabId, name)
  }

  def tabContent(tabId: String, content: NodeSeq): Elem = {
    <div id={tabId} class="tab-pane">{content}</div>
  }

  def addTabContent(tabId: String, content: NodeSeq): CssSel =
    "#settingsTabContent *+" #> Settings.tabContent(tabId, content)

  def addTab(tabId: String, name: String, content: NodeSeq): CssSel =
    Settings.addTabMenu(tabId, name) & Settings.addTabContent(tabId, content)

}
