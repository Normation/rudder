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

import net.liftweb.util.CssSel
import net.liftweb.util.Helpers.*
import scala.xml.*

/**
 * Helper for adding menu using our convention on DOM structure for menu item and content
 */
trait TabUtils {
  import TabUtils.*

  /**
   * The HTML ID for the container for menu tabs.
   * It should not start with "#".
   */
  def tabMenuId: String

  /**
   * The HTML ID for the container for menu contents.
   * It should not start with "#".
   */
  def tabContentId: String

  def addTabMenu(tabId: String, name: String, mode: AddTabMode): CssSel = {
    mode(tabMenuId)(tabMenu(tabId, name))
  }

  def addTabContent(tabId: String, content: NodeSeq, mode: AddTabMode): CssSel = {
    mode(tabContentId)(tabContent(tabId, content))
  }

  /**
   * Create a new tab using a human name and a new tabId and content.
   * Adds the tab with the name by default as the last tab, and and its content as last content. 
   */
  def addTab(tabId: String, name: String, content: NodeSeq, mode: AddTabMode = AppendTab): CssSel =
    addTabMenu(tabId, name, mode) & addTabContent(tabId, content, mode)

  private def tabMenu(tabId: String, name: String) = {
    <li class="nav-item" role="presentation">
      <button class="nav-link" data-bs-toggle="tab" data-bs-target={
      "#" + tabId
    } type="button" role="tab" aria-controls={tabId}>{name}</button>
    </li>
  }

  private def tabContent(tabId: String, content: NodeSeq): Elem = {
    <div id={tabId} class="tab-pane">{content}</div>
  }
}

object TabUtils {

  /**
   * Mode used for abstracting over liftweb selectors and apply content on 
   * predefined tab selectors
   */
  sealed trait AddTabMode {
    def apply(id: String)(content: NodeSeq): CssSel
  }

  /**
   * Default exposed mode
   */
  case object AppendTab extends AddTabMode {
    def apply(id: String)(content: NodeSeq) = s"#${id} *+" #> content
  }

  case object PrependTab extends AddTabMode {
    def apply(id: String)(content: NodeSeq) = s"#${id} -*" #> content
  }

  /**
   * Mode that can replace within a given selector.
   * Useful when tabs have specific position (hence they need a special ID for replacement,
   * so they can be used on e.g. span with id="myInner").
   * You can define attributes, and class attributes will remain except for "d-none"
   */
  case class ReplaceInTab(replaceSel: String) extends AddTabMode {
    def apply(id: String)(content: NodeSeq): CssSel = {
      s"#${id}" #> (
        s"#${replaceSel}" #> content
      ) & (
        s"#${id}" #> (
          s"#${replaceSel} [class!]" #> "d-none"
        )
      )
    }
  }
}
