/*
*************************************************************************************
* Copyright 2014 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.web.snippet

import scala.xml.NodeSeq
import net.liftweb.http.LiftRules
import net.liftweb.http.S


class BootstrapMenu {

  def render(in: NodeSeq): NodeSeq = {
    val menuEntries = {
      ( for {
        sm <- LiftRules.siteMap
        req <- S.request
      } yield {
        sm.buildMenu(req.location).lines
      } ).getOrElse(Nil)

    }

    val menu = {
      for {
          item <- menuEntries
      } yield {
        val style = if (item.current || item.kids.exists(_.current)) "active" else ""

        item.kids match {
          case Nil =>
            <li class={style}><a href={item.uri}>{item.text}</a></li>

          case kids =>
            <li class={style + " dropdown"}>
              <a href={item.uri} class="dropdown-toggle" data-toggle="dropdown">{item.text} <span class="caret"></span></a>
              <ul class="dropdown-menu" role="menu"> {
                for (kid <- kids) yield {
                  <li><a href={kid.uri}>{kid.text}</a></li>
                }
              } </ul>
            </li>
        }
      }
    }

    <ul class="nav navbar-nav">
      {menu}
    </ul>

  }

}