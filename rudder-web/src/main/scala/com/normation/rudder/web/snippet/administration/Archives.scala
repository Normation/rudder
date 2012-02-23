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
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.repository._
import com.normation.rudder.services.user.PersonIdentService
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.web.components.DateFormaterService
import net.liftweb.http.SHtml.PairStringPromoter
import org.eclipse.jgit.revwalk.RevTag
import org.joda.time.DateTime
import org.eclipse.jgit.lib.PersonIdent
import scala.xml.Text
import com.normation.eventlog.EventActor

class Archives extends DispatchSnippet with Loggable {

  private[this] val itemArchiver = inject[ItemArchiveManager]
  private[this] val personIdentService = inject[PersonIdentService]
  
  def dispatch = {
    case "allForm" => allForm 
    case "rulesForm" => rulesForm
    case "groupLibraryForm" => groupLibraryForm
    case "policyLibraryForm" => policyLibraryForm
  }
  
  /**
   * Export all items (CR, Active Techniques library, groups)
   * Advertise on success and error
   */
  private[this] def allForm = {
    actionFormBuilder(
        formName                  = "allForm"
      , archiveButtonId           = "exportAllButton"
      , archiveButtonName         = "Archive everything"
      , archiveFunction           = itemArchiver.exportAll
      , archiveErrorMessage       = "Error when exporting groups, Directive library and Rules."
      , archiveSuccessDebugMessage= s => "Exporting groups, Directive library and Rules on user request, archive id: %s".format(s)
      , archiveDateSelectId       = "importAllSelect"
      , archiveListFunction       = itemArchiver.getFullArchiveTags _
      , restoreButtonId           = "importAllButton"
      , restoreButtonName         = "Restore everything"
      , restoreFunction           = itemArchiver.importAll
      , restoreHeadFunction       = itemArchiver.importHeadAll
      , restoreErrorMessage       = "Error when importing groups, Directive library and Rules."
      , restoreSuccessDebugMessage= "Importing groups, Directive library and Rules on user request"
    )
  }
  
  private[this] def rulesForm = {
    actionFormBuilder(
        formName                  = "rulesForm"
      , archiveButtonId           = "exportRulesButton"
      , archiveButtonName         = "Archive Rules"
      , archiveFunction           = itemArchiver.exportRules
      , archiveErrorMessage       = "Error when exporting Rules."
      , archiveSuccessDebugMessage= s => "Exporting Rules on user request, archive id: %s".format(s)
      , archiveDateSelectId       = "importRulesSelect"
      , archiveListFunction       = itemArchiver.getRulesTags _
      , restoreButtonId           = "importRulesButton"
      , restoreButtonName         = "Restore Rules"
      , restoreFunction           = itemArchiver.importRules
      , restoreHeadFunction       = itemArchiver.importHeadRules
      , restoreErrorMessage       = "Error when imporing Rules."
      , restoreSuccessDebugMessage= "Importing Rules on user request"
    )
  }  

  private[this] def policyLibraryForm = {
    actionFormBuilder(
        formName                  = "policyLibraryForm"
      , archiveButtonId           = "exportTechniqueLibraryButton"
      , archiveButtonName         = "Archive Directive library"
      , archiveFunction           = itemArchiver.exportTechniqueLibrary
      , archiveErrorMessage       = "Error when exporting Directive library."
      , archiveSuccessDebugMessage= s => "Exporting Directive library on user request, archive id: %s".format(s)
      , archiveDateSelectId       = "importTechniqueLibrarySelect"
      , archiveListFunction       = itemArchiver.getTechniqueLibraryTags _
      , restoreButtonId           = "importTechniqueLibraryButton"
      , restoreButtonName         = "Restore Directive library"
      , restoreFunction           = itemArchiver.importTechniqueLibrary
      , restoreHeadFunction       = itemArchiver.importHeadTechniqueLibrary
      , restoreErrorMessage       = "Error when importing Directive library."
      , restoreSuccessDebugMessage= "Importing Directive library on user request"
    )
  }
  
  private[this] def groupLibraryForm =  {
    actionFormBuilder(
        formName                  = "groupLibraryForm"
      , archiveButtonId           = "exportGroupLibraryButton"
      , archiveButtonName         = "Archive groups"
      , archiveFunction           = itemArchiver.exportGroupLibrary
      , archiveErrorMessage       = "Error when exporting groups."
      , archiveSuccessDebugMessage= s => "Exporting groups on user request, archive id: %s".format(s)
      , archiveDateSelectId       = "importGroupLibrarySelect"
      , archiveListFunction       = itemArchiver.getGroupLibraryTags _
      , restoreButtonId           = "importGroupLibraryButton"
      , restoreButtonName         = "Restore groups"
      , restoreFunction           = itemArchiver.importGroupLibrary
      , restoreHeadFunction       = itemArchiver.importHeadGroupLibrary
      , restoreErrorMessage       = "Error when importing groups."
      , restoreSuccessDebugMessage= "Importing groups on user request"
    )
  
  }
  
  /**
   * Create a form with a validation button for an export or an import
   */
  private[this] def actionFormBuilder(
      formName                  : String               //the element name to update on error/succes
    , archiveButtonId           : String               //input button
    , archiveButtonName         : String               //what is displayed on the button to the user
    , archiveFunction           : (PersonIdent, EventActor, Boolean) => Box[GitArchiveId] //the actual logic to execute the action
    , archiveErrorMessage       : String               //error message to display to the user
    , archiveSuccessDebugMessage: String => String     //debug log - the string param is the archive id
    , archiveDateSelectId       : String
    , archiveListFunction       : () => Box[Map[DateTime,GitArchiveId]]
    , restoreButtonId           : String               //input button
    , restoreButtonName         : String               //what is displayed on the button to the user
    , restoreFunction           : (GitCommitId, EventActor, Boolean) => Box[GitCommitId] //the actual logic to execute the action
    , restoreHeadFunction       : (EventActor, Boolean) => Box[GitCommitId] //the actual logic to execute the restore from HEAD
    , restoreErrorMessage       : String               //error message to display to the user
    , restoreSuccessDebugMessage: String               //debug log - the string param is the archive id
  ) : IdMemoizeTransform = SHtml.idMemoize { outerXml =>

    type RESTORER = () => Box[GitCommitId]
    
    val noticeId = formName + "Notice"
    
    var restoreFun = Option.empty[RESTORER]
    
    def error(eb:EmptyBox, msg:String) = {
      val e = eb ?~! msg
      logger.error(e.messageChain)
      logger.error(e.exceptionChain.mkString("", "\n", ""))
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
      (for {
        commiter <- personIdentService.getPersonIdentOrDefault(CurrentUser.getActor.name)
        archive  <- archiveFunction(commiter, CurrentUser.getActor, false)
      } yield {
        archive
      }) match {
        case eb:EmptyBox => error(eb, archiveErrorMessage)
        case Full(aid)   => success(archiveSuccessDebugMessage(aid.commit.value))
      }
    }
    
    def restore(): JsCmd = {
      S.clearCurrentNotices
      restoreFun match {
        case None    => error(Empty, "A valid archive must be chosen")
        case Some(f) => f() match {
          case eb:EmptyBox => error(eb, restoreErrorMessage)
          case Full( _ )   => success(restoreSuccessDebugMessage)
        }
      }
    }
    
    //the method that displays archive label: date, last, etc
    //we are tricky, and we store
    def archiveIdLabel : PairStringPromoter[AnyRef] = (x:AnyRef) => x match {
      case "HEAD"                 => "Get archive from current Git HEAD"
      case (d:DateTime, _:RevTag) => DateFormaterService.getFormatedDate(d)
      case _                      => "Choose an archive to restore..."
    }
    
    ("#"+archiveButtonId) #> { 
      SHtml.ajaxSubmit(archiveButtonName, archive _ , ("id" -> archiveButtonId)) ++ Script(OnLoad(JsRaw(""" correctButtons(); """)))
    } &
    ("#"+archiveDateSelectId) #> {
      //we have at least "Choose an archive to restore..." and "get archive from current Git HEAD"
      
      val baseOptions: List[(RESTORER , String )] = 
        ( () => Empty, "Choose an archive to restore...") ::
        ( () => restoreHeadFunction(CurrentUser.getActor, false) , "Latest Git commit") ::
        Nil
      
      //and perhaps we have also some dates/rev tags
      val tagOptions: List[(RESTORER , String )] = archiveListFunction() match {
        case Empty =>
          logger.debug("No archive available from tags")
          Nil
        case f:Failure =>
          logger.error(f ?~! "Error when looking for archives from tags")
          Nil
          
        case Full(m) => 
          m.toList.sortWith { 
            case ( (d1,_), (d2,_) ) => d1.isAfter(d2) 
          }.map { case (date,revTag) =>
            (() => restoreFunction(revTag.commit, CurrentUser.getActor, false), DateFormaterService.getFormatedDate(date) )
          }
      }

      SHtml.selectObj[RESTORER](baseOptions ::: tagOptions, Box(restoreFun), { f => restoreFun = Some(f)}, ("id" -> archiveDateSelectId) )
    } &
    ("#"+restoreButtonId) #> { 
      SHtml.ajaxSubmit(restoreButtonName, restore _, ("id" -> restoreButtonId), ("disabled" -> "disabled") ) ++ 
      Script(OnLoad(JsRaw(""" correctButtons(); enableIfNonEmpty("%s", "%s");""".format(archiveDateSelectId, restoreButtonId))))
    }
  }
  
  ///////////// success pop-up ///////////////
  private[this] def successPopup : JsCmd = {
    JsRaw("""callPopupWithTimeout(200, "successConfirmationDialog", 100, 350)""")
  }  
}
