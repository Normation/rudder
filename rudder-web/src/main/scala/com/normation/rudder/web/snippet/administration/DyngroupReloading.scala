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

package com.normation.rudder.web.snippet.administration

import net.liftweb._
import http._
import common._
import util.Helpers._
import js._
import JsCmds._
import bootstrap.liftweb.RudderConfig


class DyngroupReloading extends DispatchSnippet with Loggable {

  private[this] val updateDynamicGroups = RudderConfig.updateDynamicGroups
  private[this] val updateDynamicGroupsInterval = RudderConfig.RUDDER_BATCH_DYNGROUP_UPDATEINTERVAL

  def dispatch = {
    case "render" => reload
  }


  def reload : IdMemoizeTransform = SHtml.idMemoize { outerXml =>

    // our process method returns a
    // JsCmd which will be sent back to the browser
    // as part of the response
    def process(): JsCmd = {
      //clear errors
      S.clearCurrentNotices

      updateDynamicGroups.startManualUpdate

      S.notice("dyngroupReloadingNotice", "Dynamic group reloading started")

      Replace("dyngroupReloadingForm", outerXml.applyAgain)
    }

    val initJs = SetHtml("dynGroupUpdateInterval", <span>{updateDynamicGroupsInterval}</span>)
    //process the list of networks
    "#dyngroupReloadingButton" #> {
      SHtml.ajaxSubmit("Reload dynamic groups", process _, ("class","btn btn-primary dyngroupReloadingButton")) ++ Script(OnLoad(initJs))
    }
  }
}