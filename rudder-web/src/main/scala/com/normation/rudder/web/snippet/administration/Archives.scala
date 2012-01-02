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
import com.normation.rudder.domain.policies.ConfigurationRuleId
import com.normation.rudder.repository.ConfigurationRuleRepository
import com.normation.rudder.repository.ItemArchiveManager
import com.normation.rudder.repository.ArchiveId


class Archives extends DispatchSnippet with Loggable {

  private[this] val itemArchiver = inject[ItemArchiveManager]
  
  
  def dispatch = {
    case "exportAll" => exportAllForm
    case "importAll" => importAllForm
  }
  
  /**
   * Export all items (CR, user policy library, groups)
   * Advertise on success and error
   */
  private[this] def exportAllForm : IdMemoizeTransform = SHtml.idMemoize { outerXml =>
    // our process method returns a
    // JsCmd which will be sent back to the browser
    // as part of the response
    def process(): JsCmd = {
      //clear errors
      S.clearCurrentNotices

      itemArchiver.saveAll() match {
        case empty:EmptyBox => 
          val e = empty ?~! "Error when exporting configuration rules."
          S.error(e.messageChain)
          Replace("exportAllForm", outerXml.applyAgain)
        case Full(aid) => 
          logger.debug("Exported configuration rules on user request, archive id: " + aid.value )
          Replace("exportAllForm", outerXml.applyAgain) &
          successPopup
      }
    }
    
    //process the list of networks
    "#exportAllButton" #> { 
      SHtml.ajaxSubmit("Export", process _) ++ Script(OnLoad(JsRaw(""" correctButtons(); """)))
    }
  }
  
  /**
   * Import all items (CR, user policy library, groups)
   * Advertise on success and error.
   */
  private[this] def importAllForm : IdMemoizeTransform = SHtml.idMemoize { outerXml =>    
    // our process method returns a
    // JsCmd which will be sent back to the browser
    // as part of the response
    def process(): JsCmd = {
      //clear errors
      S.clearCurrentNotices

      itemArchiver.importLastArchive() match {
        case empty:EmptyBox => 
          val e = empty ?~! "Error when importing configuration rules."
          S.error(e.messageChain)
          Replace("importAllForm", outerXml.applyAgain)
        case Full(x) => 
          logger.debug("Imported configuration rules on user request" )
          Replace("importAllForm", outerXml.applyAgain) &
          successPopup
      }
    }
    
    //process the list of networks
    "#importAllButton" #> { 
      SHtml.ajaxSubmit("Import", process _) ++ Script(OnLoad(JsRaw(""" correctButtons(); """)))
    }
  }  
  
  
  ///////////// success pop-up ///////////////
  private[this] def successPopup : JsCmd = {
    JsRaw(""" callPopupWithTimeout(200, "successConfirmationDialog", 100, 350) """)
  }  
}