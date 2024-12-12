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
import scala.xml.*

class Maintenance extends DispatchSnippet with DefaultExtendableSnippet[Maintenance] {
  import com.normation.rudder.web.snippet.administration.Maintenance.*

  override def mainDispatch: Map[String, NodeSeq => NodeSeq] = {
    Map(
      "body"               -> identity,
      "healthCheckTab"     -> healthCheckTab,
      "reportsDatabaseTab" -> reportsDatabaseTab,
      "policyBackupTab"    -> policyBackupTab,
      "hooksTab"           -> hooksTab,
      "techniqueTreeTab"   -> techniqueTreeTab
    )
  }
}

object Maintenance {
  private def healthCheckTab(xml: NodeSeq) =
    ChooseTemplate("templates-hidden" :: "components" :: "administration" :: "healthcheck" :: Nil, "component-body")

  private def reportsDatabaseTab(xml: NodeSeq) =
    ChooseTemplate("templates-hidden" :: "components" :: "administration" :: "reportsDatabase" :: Nil, "component-body")

  private def policyBackupTab(xml: NodeSeq) =
    ChooseTemplate("templates-hidden" :: "components" :: "administration" :: "policyBackup" :: Nil, "component-body")

  private def hooksTab(xml: NodeSeq) =
    ChooseTemplate("templates-hidden" :: "components" :: "administration" :: "hooksManagement" :: Nil, "component-body")

  private def techniqueTreeTab(xml: NodeSeq) = {
    ChooseTemplate(
      "templates-hidden" :: "components" :: "administration" :: "techniqueLibraryManagement" :: Nil,
      "component-body"
    )
  }

  /*
   * Create a new tab (the clickable header part).
   * The id must not contain the `#`
   */
  def tabMenu(tabId: String, name: String) = Settings.tabMenu(tabId, name)

  // an utility method that provides the correct cssSel to add a menu item
  def addTabMenu(tabId: String, name: String): CssSel = Settings.addTabMenu(tabId, name)

  def tabContent(tabId: String, content: NodeSeq): Elem = Settings.tabContent(tabId, content)

  def addTabContent(tabId: String, content: NodeSeq): CssSel = Settings.addTabContent(tabId, content)

  def addTab(tabId: String, name: String, content: NodeSeq): CssSel = Settings.addTab(tabId, name, content)
}
