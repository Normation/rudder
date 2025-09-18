/*
 *************************************************************************************
 * Copyright 2014 Normation SAS
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

import net.liftweb.http.LiftRules
import net.liftweb.http.S
import scala.xml.NodeSeq

class BootstrapMenu {

  def render(in: NodeSeq): NodeSeq = {
    val menuEntries = {
      (for {
        sm  <- LiftRules.siteMap
        req <- S.request
      } yield {
        sm.buildMenu(req.location).lines
      }).getOrElse(Nil)
    }

    val menu = {
      for {
        item <- menuEntries
      } yield {
        val style = if (item.current || item.kids.exists(_.current)) "active " else ""

        item.kids match {
          case Nil  =>
            <li class={style + "treeview"}>
              <a href={item.uri}>{item.text}</a>
            </li>
          case kids =>
            <li class={style + "treeview treview-toggle"}>
              <a href="#">
                {item.text}
                <i class="fa fa-angle-right pull-right"></i>
              </a>
              <ul class="treeview-menu"> {
              for (kid <- kids) yield {
                val styleKid = if (kid.current) "active" else ""
                <li><a href={kid.uri} class={styleKid}><span class="fa fa-arrow-right"></span>{kid.text}</a></li>
              }
            } </ul>
            </li>
        }
      }
    }
    { menu }
  }
}
