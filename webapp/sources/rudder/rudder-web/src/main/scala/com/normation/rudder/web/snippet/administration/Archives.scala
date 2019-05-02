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
import JE._
import com.normation.rudder.repository._
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.web.components.DateFormaterService
import org.joda.time.DateTime
import org.eclipse.jgit.lib.PersonIdent
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import bootstrap.liftweb.RudderConfig

import com.normation.box._
import com.normation.errors._
import scalaz.zio._
import scalaz.zio.syntax._

class Archives extends DispatchSnippet with Loggable {

  private[this] val DL_NAME = "Download as zip"

  private[this] val itemArchiver        = RudderConfig.itemArchiveManager
  private[this] val personIdentService  = RudderConfig.personIdentService
  private[this] val uuidGen             = RudderConfig.stringUuidGenerator

  private[this] val noElements = NotArchivedElements(Seq(),Seq(),Seq())

  def dispatch = {
    case "allForm" => allForm
    case "rulesForm" => rulesForm
    case "groupLibraryForm" => groupLibraryForm
    case "directiveLibraryForm" => directiveLibraryForm
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
      , restoreErrorMessage       = "Error when importing groups, Directive library and Rules."
      , restoreSuccessDebugMessage= "Importing groups, Directive library and Rules on user request"
      , downloadButtonId          = "downloadAllButton"
      , downloadButtonName        = DL_NAME
      , downloadRestAction        = "all"
    )
  }

  private[this] def rulesForm = {
    actionFormBuilder(
        formName                  = "rulesForm"
      , archiveButtonId           = "exportRulesButton"
      , archiveButtonName         = "Archive Rules"
      , archiveFunction           = (a,b,c,d,e) => itemArchiver.exportRules(a,b,c,d,e).map(x=> (x, noElements))
      , archiveErrorMessage       = "Error when exporting Rules."
      , archiveSuccessDebugMessage= s => "Exporting Rules on user request, archive id: %s".format(s)
      , archiveDateSelectId       = "importRulesSelect"
      , archiveListFunction       = itemArchiver.getRulesTags _
      , restoreButtonId           = "importRulesButton"
      , restoreButtonName         = "Restore Rules"
      , restoreFunction           = itemArchiver.importRules
      , restoreErrorMessage       = "Error when imporing Rules."
      , restoreSuccessDebugMessage= "Importing Rules on user request"
      , downloadButtonId          = "downloadRulesButton"
      , downloadButtonName        = DL_NAME
      , downloadRestAction        = "rules"
    )
  }

  private[this] def directiveLibraryForm = {
    actionFormBuilder(
        formName                  = "directiveLibraryForm"
      , archiveButtonId           = "exportDirectiveLibraryButton"
      , archiveButtonName         = "Archive Directive library"
      , archiveFunction           = itemArchiver.exportTechniqueLibrary
      , archiveErrorMessage       = "Error when exporting Directive library."
      , archiveSuccessDebugMessage= s => "Exporting Directive library on user request, archive id: %s".format(s)
      , archiveDateSelectId       = "importDirectiveLibrarySelect"
      , archiveListFunction       = itemArchiver.getTechniqueLibraryTags _
      , restoreButtonId           = "importDirectiveLibraryButton"
      , restoreButtonName         = "Restore Directive library"
      , restoreFunction           = itemArchiver.importTechniqueLibrary
      , restoreErrorMessage       = "Error when importing Directive library."
      , restoreSuccessDebugMessage= "Importing Directive library on user request"
      , downloadButtonId          = "downloadDirectiveLibraryButton"
      , downloadButtonName        = DL_NAME
      , downloadRestAction        = "directives"
    )
  }

  private[this] def groupLibraryForm =  {
    actionFormBuilder(
        formName                  = "groupLibraryForm"
      , archiveButtonId           = "exportGroupLibraryButton"
      , archiveButtonName         = "Archive groups"
      , archiveFunction           = (a,b,c,d,e) => itemArchiver.exportGroupLibrary(a,b,c,d,e).map(x=> (x, noElements))
      , archiveErrorMessage       = "Error when exporting groups."
      , archiveSuccessDebugMessage= s => "Exporting groups on user request, archive id: %s".format(s)
      , archiveDateSelectId       = "importGroupLibrarySelect"
      , archiveListFunction       = itemArchiver.getGroupLibraryTags _
      , restoreButtonId           = "importGroupLibraryButton"
      , restoreButtonName         = "Restore groups"
      , restoreFunction           = itemArchiver.importGroupLibrary
      , restoreErrorMessage       = "Error when importing groups."
      , restoreSuccessDebugMessage= "Importing groups on user request"
      , downloadButtonId          = "downloadGroupLibraryButton"
      , downloadButtonName        = DL_NAME
      , downloadRestAction        = "groups"
    )

  }

  /**
   * Create a form with a validation button for an export or an import
   */
  private[this] def actionFormBuilder(
      formName                  : String               //the element name to update on error/succes
    , archiveButtonId           : String               //input button
    , archiveButtonName         : String               //what is displayed on the button to the user
    , archiveFunction           : (PersonIdent, ModificationId, EventActor, Option[String], Boolean) => IOResult[(GitArchiveId, NotArchivedElements)] //the actual logic to execute the action
    , archiveErrorMessage       : String               //error message to display to the user
    , archiveSuccessDebugMessage: String => String     //debug log - the string param is the archive id
    , archiveDateSelectId       : String
    , archiveListFunction       : () => IOResult[Map[DateTime,GitArchiveId]]
    , restoreButtonId           : String               //input button id to restore an archive
    , restoreButtonName         : String               //what is displayed on the button to the user
    , restoreFunction           : (GitCommitId, PersonIdent, ModificationId, EventActor, Option[String], Boolean) => IOResult[GitCommitId] //the actual logic to execute the action
    , restoreErrorMessage       : String               //error message to display to the user
    , restoreSuccessDebugMessage: String               //debug log - the string param is the archive id
    , downloadButtonId          : String               //input button id to download the zip of an archive
    , downloadButtonName        : String               //what is displayed to download the zip of an archive
    , downloadRestAction        : String               //the specific action for the REST api, i.e the %s in: /api/archives/zip/%s
  ) : IdMemoizeTransform = SHtml.idMemoize { outerXml =>


    val noticeId = formName + "Notice"

    var selectedCommitId = Option.empty[GitCommitId]

    def error(eb:EmptyBox, msg:String) = {
      val e = eb ?~! msg
      logger.error(e.messageChain)
      logger.error(e.exceptionChain.mkString("", "\n", ""))
      S.error(noticeId, msg)
      Replace(formName, outerXml.applyAgain)
    }

    def success[T](msg:String, elements:NotArchivedElements) = {
          logger.debug( msg )

          if(!elements.isEmpty) {
            val cats = elements.categories.map { case CategoryNotArchived(catId, f) => "Error when archiving Category with id '%s': %s".format(catId.value, f.fullMsg) }
            val ats = elements.activeTechniques.map { case ActiveTechniqueNotArchived(atId, f) => "Error when rchiving Active Technique with id '%s': %s".format(atId.value, f.fullMsg) }
            val dirs = elements.directives.map { case DirectiveNotArchived(dirId, f) => "Error when archiving Directive with id '%s': %s".format(dirId.value, f.fullMsg) }

            val all = cats ++ ats ++ dirs

            all.foreach( logger.warn( _ ) )

            val error = <div>
                <b>The archive was created but some element have not been archived:</b>
                <ul>
                  {all.map(msg => <li>{msg}</li>)}
                </ul>
              </div>

            S.warning(noticeId, error)
          }

          Replace(formName, outerXml.applyAgain) &
          successPopup
    }

    // our process method returns a
    // JsCmd which will be sent back to the browser
    // as part of the response
    def archive(): JsCmd = {
      S.clearCurrentNotices
      (for {
        commiter <- personIdentService.getPersonIdentOrDefault(CurrentUser.actor.name)
        archive  <- archiveFunction(commiter, ModificationId(uuidGen.newUuid), CurrentUser.actor, Some("User requested archive creation"), false)
      } yield {
        archive
      }).toBox match {
        case eb:EmptyBox => error(eb, archiveErrorMessage)
        case Full((aid, notArchiveElements))   => success(archiveSuccessDebugMessage(aid.commit.value), notArchiveElements)
      }
    }

    def restore(): JsCmd = {
      S.clearCurrentNotices
      selectedCommitId match {
        case None    => error(Empty, "A valid archive must be chosen")
        case Some(commit) => (
          for {
            commiter <- personIdentService.getPersonIdentOrDefault(CurrentUser.actor.name)
            archive <- restoreFunction(commit, commiter, ModificationId(uuidGen.newUuid), CurrentUser.actor, Some("User requested archive restoration to commit %s".format(commit.value)), false)
          } yield
            archive ).toBox match {
          case eb:EmptyBox => error(eb, restoreErrorMessage)
          case Full( _ )   => success(restoreSuccessDebugMessage, noElements)
        }
      }
    }

    def download() : JsCmd = {
      S.clearCurrentNotices
      selectedCommitId match {
        case None    => error(Empty, "A valid archive must be chosen")
        case Some(commit) =>
          S.redirectTo("/secure/utilities/archiveManagement/zip/%s/%s".format(downloadRestAction, commit.value))
      }

    }

    def buildCommitIdList : List[(Option[GitCommitId], String )] = {
      val baseOptions: List[(Option[GitCommitId], String )] =
        ( None, "Choose an archive to restore...") ::
        ( Some(GitCommitId("HEAD")) , "Latest Git commit") ::
        Nil

      //and perhaps we have also some dates/rev tags
      val tagOptions: List[(Option[GitCommitId], String )] = archiveListFunction().toBox match {
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
            ( Some(revTag.commit), DateFormaterService.getFormatedDate(date) )
          }
      }
      baseOptions ::: tagOptions
    }

    ////////// Template filling //////////

    ("#"+archiveButtonId) #> {
      SHtml.ajaxSubmit(archiveButtonName, archive _ , ("id" -> archiveButtonId), ("class","btn btn-default"))
    } &
    ("#"+archiveDateSelectId) #> {
      //we have at least "Choose an archive to restore..." and "get archive from current Git HEAD"
      SHtml.selectObj[Option[GitCommitId]](buildCommitIdList, Full(selectedCommitId), { id => selectedCommitId = id}, ("id" -> archiveDateSelectId), ("class","form-control") )
    } &
    ("#"+restoreButtonId) #> {
      SHtml.ajaxSubmit(restoreButtonName, restore _, ("id" -> restoreButtonId) , ("class","btn btn-default")) ++
      Script(OnLoad(JsRaw("""enableIfNonEmpty("%s", "%s");$("#%s").prop("disabled",true);""".format(archiveDateSelectId, restoreButtonId, restoreButtonId))))
    } &
    ("#"+downloadButtonId) #> {
      SHtml.ajaxSubmit(downloadButtonName, download _, ("id" -> downloadButtonId) , ("class","btn btn-default")) ++
      Script(OnLoad(JsRaw("""enableIfNonEmpty("%s", "%s");$("#%s").prop("disabled",true);""".format(archiveDateSelectId, downloadButtonId, downloadButtonId))))
    }
  }

  ///////////// success pop-up ///////////////
  private[this] def successPopup : JsCmd = {
    JsRaw("""createSuccessNotification()""")
  }
}
