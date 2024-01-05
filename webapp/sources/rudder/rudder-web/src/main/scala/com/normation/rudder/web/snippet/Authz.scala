/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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

import com.normation.rudder.AuthorizationType
import com.normation.rudder.users.CurrentUser
import net.liftweb.common._
import net.liftweb.http._
import scala.xml.NodeSeq

class Authz extends DispatchSnippet with Loggable {

  def dispatch = {
    case "render"        => testRight
    case "whennorights"  => whenNoRights
    case "whenhasrights" => whenHasRights
  }

  /*
   * Check if no authorizations are defined for the
   * current user - having even one "no_rights"
   * make it true
   */
  private[this] def noRights(): Boolean = {
    CurrentUser.getRights.authorizationTypes.contains(AuthorizationType.NoRights)
  }

  /*
   * Display xml when the user has no rights
   */
  def whenNoRights(xml: NodeSeq): NodeSeq = {
    if (noRights()) xml else NodeSeq.Empty
  }

  /*
   * Display xml when the user has some rights
   * (and no "no rights")
   */
  def whenHasRights(xml: NodeSeq): NodeSeq = {
    if (noRights()) NodeSeq.Empty else xml
  }

  def testRight(xml: NodeSeq): NodeSeq = {
    S.attr("role") match {
      case Full(role) =>
        AuthorizationType.parseRight(role) match {
          case Left(err)    => NodeSeq.Empty
          case Right(authz) =>
            if (CurrentUser.checkRights(authz.headOption.getOrElse(AuthorizationType.NoRights))) xml else NodeSeq.Empty
        }
      case x          => NodeSeq.Empty
    }
  }
}
