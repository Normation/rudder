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
    case "allForm" => allForm 
    case "configurationRulesForm" => configurationRulesForm
    case "groupLibraryForm" => groupLibraryForm
    case "policyLibraryForm" => policyLibraryForm
  }
  
  /**
   * Export all items (CR, user policy library, groups)
   * Advertise on success and error
   */
  private[this] def allForm = actionFormBuilder(
      formName                  = "allForm"
    , archiveButtonSelector     = "#exportAllButton"
    , archiveButtonName         = "Archive everything"
    , archiveFunction           = itemArchiver.exportAll
    , archiveErrorMessage       = "Error when exporting groups, policy library and configuration rules."
    , archiveSuccessDebugMessage= s => "Exporting groups, policy library and configuration rules on user request, archive id: %s".format(s)
    , restoreButtonSelector     = "#importAllButton"
    , restoreButtonName         = "Restore everything"
    , restoreFunction           = itemArchiver.importAll
    , restoreErrorMessage       = "Error when importing groups, policy library and configuration rules."
    , restoreSuccessDebugMessage= "Importing groups, policy library and configuration rules on user request"
  )

  
  private[this] def configurationRulesForm = actionFormBuilder(
      formName                  = "configurationRulesForm"
    , archiveButtonSelector     = "#exportConfigurationRulesButton"
    , archiveButtonName         = "Archive configuration rules"
    , archiveFunction           = itemArchiver.exportConfigurationRules
    , archiveErrorMessage       = "Error when exporting configuration rules."
    , archiveSuccessDebugMessage= s => "Exporting configuration rules on user request, archive id: %s".format(s)
    , restoreButtonSelector     = "#importConfigurationRulesButton"
    , restoreButtonName         = "Restore configuration rules"
    , restoreFunction           = itemArchiver.importConfigurationRules
    , restoreErrorMessage       = "Error when imporing configuration rules."
    , restoreSuccessDebugMessage= "Importing configuration rules on user request"
  )
  

  private[this] def policyLibraryForm = actionFormBuilder(
      formName                  = "policyLibraryForm"
    , archiveButtonSelector     = "#exportPolicyLibraryButton"
    , archiveButtonName         = "Archive policy library"
    , archiveFunction           = itemArchiver.exportPolicyLibrary
    , archiveErrorMessage       = "Error when exporting policy library."
    , archiveSuccessDebugMessage= s => "Exporting policy library on user request, archive id: %s".format(s)
    , restoreButtonSelector     = "#importPolicyLibraryButton"
    , restoreButtonName         = "Restore policy library"
    , restoreFunction           = itemArchiver.importPolicyLibrary
    , restoreErrorMessage       = "Error when importing policy library."
    , restoreSuccessDebugMessage= "Importing policy library on user request"
  )
  
  private[this] def groupLibraryForm = actionFormBuilder(
      formName                  = "groupLibraryForm"
    , archiveButtonSelector     = "#exportGroupLibraryButton"
    , archiveButtonName         = "Archive groups"
    , archiveFunction           = itemArchiver.exportGroupLibrary
    , archiveErrorMessage       = "Error when exporting groups."
    , archiveSuccessDebugMessage= s => "Exporting groups on user request, archive id: %s".format(s)
    , restoreButtonSelector     = "#importGroupLibraryButton"
    , restoreButtonName         = "Restore groups"
    , restoreFunction           = itemArchiver.importGroupLibrary
    , restoreErrorMessage       = "Error when importing groups."
    , restoreSuccessDebugMessage= "Importing groups on user request"
  )
  
  /**
   * Create a form with a validation button for an export or an import
   */
  private[this] def actionFormBuilder(
      formName                  : String               //the element name to update on error/succes
    , archiveButtonSelector     : String               //input button
    , archiveButtonName         : String               //what is displayed on the button to the user
    , archiveFunction           : Boolean => Box[ArchiveId] //the actual logic to execute the action
    , archiveErrorMessage       : String               //error message to display to the user
    , archiveSuccessDebugMessage: String => String     //debug log - the string param is the archive id
    , restoreButtonSelector     : String               //input button
    , restoreButtonName         : String               //what is displayed on the button to the user
    , restoreFunction           : Boolean => Box[Unit] //the actual logic to execute the action
    , restoreErrorMessage       : String               //error message to display to the user
    , restoreSuccessDebugMessage: String               //debug log - the string param is the archive id
  ) : IdMemoizeTransform = SHtml.idMemoize { outerXml =>
    
    val noticeId = formName + "Notice"
    
    def error(eb:EmptyBox, msg:String) = {
      val e = eb ?~! msg
      logger.error(eb)
      S.error(noticeId, msg)
      Replace(formName, outerXml.applyAgain)
    }
    
    def success[T](msg:String) = {
          logger.debug( msg )
          Replace(formName, outerXml.applyAgain) &
          successPopup
    }
    
    // our process method returns a
    // JsCmd which will be sent back to the browser
    // as part of the response
    def archive(): JsCmd = {
      S.clearCurrentNotices
      archiveFunction(false) match {
        case eb:EmptyBox => error(eb, archiveErrorMessage)
        case Full(aid)   => success(archiveSuccessDebugMessage(aid.value))
      }
    }
    
    def restore(): JsCmd = {
      S.clearCurrentNotices
      restoreFunction(false) match {
        case eb:EmptyBox => error(eb, restoreErrorMessage)
        case Full( _ )   => success(restoreSuccessDebugMessage)
      }
    }
    
    archiveButtonSelector #> { 
      SHtml.ajaxSubmit(archiveButtonName, archive _ ) ++ Script(OnLoad(JsRaw(""" correctButtons(); """)))
    } &
    restoreButtonSelector #> { 
      SHtml.ajaxSubmit(restoreButtonName, restore _ ) ++ Script(OnLoad(JsRaw(""" correctButtons(); """)))
    }
  }
  
  ///////////// success pop-up ///////////////
  private[this] def successPopup : JsCmd = {
    JsRaw("""callPopupWithTimeout(200, "successConfirmationDialog", 100, 350)""")
  }  
}
