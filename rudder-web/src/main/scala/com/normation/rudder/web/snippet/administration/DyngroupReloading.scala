/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.rudder.web.snippet.administration

import net.liftweb._
import http._
import common._
import util.Helpers._
import js._
import JsCmds._
import JE._
import scala.xml.NodeSeq
import collection.mutable.Buffer
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import com.normation.rudder.services.servers.PolicyServerManagementService
import com.normation.utils.NetUtils.isValidNetwork
import com.normation.rudder.domain.Constants
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.services.servers.NodeConfigurationService
import com.normation.rudder.batch.{AsyncDeploymentAgent,AutomaticStartDeployment}
import com.normation.rudder.batch.UpdateDynamicGroups


class DyngroupReloading extends DispatchSnippet with Loggable {

  private[this] val nodeConfigurationService = inject[UpdateDynamicGroups]
  
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

      nodeConfigurationService.startManualUpdate
      
      S.notice("dyngroupReloadingNotice", "Dynamic group reloading started")
      
      Replace("dyngroupReloadingForm", outerXml.applyAgain) 
    }
    
    //process the list of networks
    "#dyngroupReloadingButton" #> { 
      SHtml.ajaxSubmit("Reload dynamic groups", process _) ++ Script(OnLoad(JsRaw(""" correctButtons(); """)))
    }
  }  
}