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

import bootstrap.liftweb.RudderConfig
import com.normation.box.*
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.git.GitArchiveId
import com.normation.rudder.git.GitCommitId
import com.normation.rudder.repository.*
import com.normation.rudder.users.CurrentUser
import com.normation.utils.DateFormaterService
import net.liftweb.common.*
import net.liftweb.http.*
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.Helpers.*
import org.eclipse.jgit.lib.PersonIdent
import org.joda.time.DateTime
import scala.xml.NodeSeq

class Archives extends DispatchSnippet with Loggable {

  private[this] val DL_NAME = "Download as zip"

  private[this] val itemArchiver       = RudderConfig.itemArchiveManager
  private[this] val personIdentService = RudderConfig.personIdentService
  private[this] val uuidGen            = RudderConfig.stringUuidGenerator

  private[this] val noElements = NotArchivedElements(Seq(), Seq(), Seq())

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = {
    case "allForm"              => allForm
    case "rulesForm"            => rulesForm
    case "groupLibraryForm"     => groupLibraryForm
    case "directiveLibraryForm" => directiveLibraryForm
    case "parametersForm"       => parametersForm
  }

  /**
   * Export all items (CR, Active Techniques library, groups)
   * Advertise on success and error
   */
  private[this] def allForm = {
    actionFormBuilder(
      formName = "allForm",
      archiveButtonId = "exportAllButton",
      archiveButtonName = "Archive everything",
      archiveFunction = itemArchiver.exportAll,
      archiveErrorMessage = "Error when exporting groups, parameters, directive library and rules.",
      archiveSuccessDebugMessage =
        s => s"Exporting groups, parameters, directive library and rules on user request, archive id: ${s}",
      archiveDateSelectId = "importAllSelect",
      archiveListFunction = () => itemArchiver.getFullArchiveTags,
      restoreButtonId = "importAllButton",
      restoreButtonName = "Restore everything",
      restoreFunction = itemArchiver.importAll,
      restoreErrorMessage = "Error when importing groups, parameters, directive library and rules.",
      restoreSuccessDebugMessage = "Importing groups, parameters, directive library and rules on user request",
      downloadButtonId = "downloadAllButton",
      downloadButtonName = DL_NAME,
      downloadRestAction = "full"
    )
  }

  private[this] def rulesForm = {
    actionFormBuilder(
      formName = "rulesForm",
      archiveButtonId = "exportRulesButton",
      archiveButtonName = "Archive rules",
      archiveFunction = (a, b, c, d, e) => itemArchiver.exportRules(a, b, c, d, e).map(x => (x, noElements)),
      archiveErrorMessage = "Error when exporting rules.",
      archiveSuccessDebugMessage = s => "Exporting rules on user request, archive id: %s".format(s),
      archiveDateSelectId = "importRulesSelect",
      archiveListFunction = () => itemArchiver.getRulesTags,
      restoreButtonId = "importRulesButton",
      restoreButtonName = "Restore rules",
      restoreFunction = itemArchiver.importRules,
      restoreErrorMessage = "Error when importing rules.",
      restoreSuccessDebugMessage = "Importing rules on user request",
      downloadButtonId = "downloadRulesButton",
      downloadButtonName = DL_NAME,
      downloadRestAction = "rules"
    )
  }

  private[this] def directiveLibraryForm = {
    actionFormBuilder(
      formName = "directiveLibraryForm",
      archiveButtonId = "exportDirectiveLibraryButton",
      archiveButtonName = "Archive directive library",
      archiveFunction = itemArchiver.exportTechniqueLibrary,
      archiveErrorMessage = "Error when exporting directive library.",
      archiveSuccessDebugMessage = s => "Exporting directive library on user request, archive id: %s".format(s),
      archiveDateSelectId = "importDirectiveLibrarySelect",
      archiveListFunction = () => itemArchiver.getTechniqueLibraryTags,
      restoreButtonId = "importDirectiveLibraryButton",
      restoreButtonName = "Restore directive library",
      restoreFunction = itemArchiver.importTechniqueLibrary,
      restoreErrorMessage = "Error when importing directive library.",
      restoreSuccessDebugMessage = "Importing directive library on user request",
      downloadButtonId = "downloadDirectiveLibraryButton",
      downloadButtonName = DL_NAME,
      downloadRestAction = "directives"
    )
  }

  private[this] def groupLibraryForm = {
    actionFormBuilder(
      formName = "groupLibraryForm",
      archiveButtonId = "exportGroupLibraryButton",
      archiveButtonName = "Archive groups",
      archiveFunction = (a, b, c, d, e) => itemArchiver.exportGroupLibrary(a, b, c, d, e).map(x => (x, noElements)),
      archiveErrorMessage = "Error when exporting groups.",
      archiveSuccessDebugMessage = s => "Exporting groups on user request, archive id: %s".format(s),
      archiveDateSelectId = "importGroupLibrarySelect",
      archiveListFunction = () => itemArchiver.getGroupLibraryTags,
      restoreButtonId = "importGroupLibraryButton",
      restoreButtonName = "Restore groups",
      restoreFunction = itemArchiver.importGroupLibrary,
      restoreErrorMessage = "Error when importing groups.",
      restoreSuccessDebugMessage = "Importing groups on user request",
      downloadButtonId = "downloadGroupLibraryButton",
      downloadButtonName = DL_NAME,
      downloadRestAction = "groups"
    )
  }

  private[this] def parametersForm = {
    actionFormBuilder(
      formName = "parametersForm",
      archiveButtonId = "exportParametersButton",
      archiveButtonName = "Archive global properties",
      archiveFunction = (a, b, c, d, e) => itemArchiver.exportParameters(a, b, c, d, e).map(x => (x, noElements)),
      archiveErrorMessage = "Error when exporting global properties.",
      archiveSuccessDebugMessage = s => "Exporting global properties on user request, archive id: %s".format(s),
      archiveDateSelectId = "importParametersSelect",
      archiveListFunction = () => itemArchiver.getParametersTags,
      restoreButtonId = "importParametersButton",
      restoreButtonName = "Restore Parameters",
      restoreFunction = itemArchiver.importParameters,
      restoreErrorMessage = "Error when importing global properties.",
      restoreSuccessDebugMessage = "Importing global properties on user request",
      downloadButtonId = "downloadParametersButton",
      downloadButtonName = DL_NAME,
      downloadRestAction = "parameters"
    )
  }

  /**
   * Create a form with a validation button for an export or an import
   */
  private[this] def actionFormBuilder(
      formName: String, // the element name to update on error/succes

      archiveButtonId: String, // input button

      archiveButtonName: String, // what is displayed on the button to the user

      archiveFunction: (
          PersonIdent,
          ModificationId,
          EventActor,
          Option[String],
          Boolean
      ) => IOResult[(GitArchiveId, NotArchivedElements)], // the actual logic to execute the action

      archiveErrorMessage: String, // error message to display to the user

      archiveSuccessDebugMessage: String => String, // debug log - the string param is the archive id

      archiveDateSelectId: String,
      archiveListFunction: () => IOResult[Map[DateTime, GitArchiveId]],
      restoreButtonId:     String, // input button id to restore an archive

      restoreButtonName: String, // what is displayed on the button to the user

      restoreFunction: (
          GitCommitId,
          PersonIdent,
          ModificationId,
          EventActor,
          Option[String],
          Boolean
      ) => IOResult[GitCommitId], // the actual logic to execute the action

      restoreErrorMessage: String, // error message to display to the user

      restoreSuccessDebugMessage: String, // debug log - the string param is the archive id

      downloadButtonId: String, // input button id to download the zip of an archive

      downloadButtonName: String, // what is displayed to download the zip of an archive

      downloadRestAction: String // the specific action for the REST api, i.e the %s in: /api/system/archives/%s/zip
  ): IdMemoizeTransform = SHtml.idMemoize { outerXml =>
    var selectedCommitId = Option.empty[GitCommitId]

    def error(eb: EmptyBox, msg: String) = {
      val e = eb ?~! msg
      logger.error(e.messageChain)
      logger.error(e.exceptionChain.mkString("", "\n", ""))
      JsRaw(s"""createErrorNotification('${msg}')""") &
      Replace(formName, outerXml.applyAgain())
    }

    def success[T](msg: String, elements: NotArchivedElements) = {
      logger.debug(msg)

      if (!elements.isEmpty) {
        val cats = elements.categories.map {
          case CategoryNotArchived(catId, f) => "Error when archiving category with id '%s': %s".format(catId.value, f.fullMsg)
        }
        val ats  = elements.activeTechniques.map {
          case ActiveTechniqueNotArchived(atId, f) =>
            "Error when archiving active technique with id '%s': %s".format(atId.value, f.fullMsg)
        }
        val dirs = elements.directives.map {
          case DirectiveNotArchived(dirId, f) => "Error when archiving directive with id '%s': %s".format(dirId.value, f.fullMsg)
        }

        val all = cats ++ ats ++ dirs

        all.foreach(logger.warn(_))

        val error = "The archive was created but some element have not been archived." ++ all.map(msg => msg).mkString(". ")
        JsRaw(s"""createWarningNotification(${error})""")
      }

      Replace(formName, outerXml.applyAgain()) &
      successPopup
    }

    // our process method returns a
    // JsCmd which will be sent back to the browser
    // as part of the response
    def archive(): JsCmd = {
      (for {
        commiter <- personIdentService.getPersonIdentOrDefault(CurrentUser.actor.name)
        archive  <- archiveFunction(
                      commiter,
                      ModificationId(uuidGen.newUuid),
                      CurrentUser.actor,
                      Some("User requested archive creation"),
                      false
                    )
      } yield {
        archive
      }).toBox match {
        case eb: EmptyBox => error(eb, archiveErrorMessage)
        case Full((aid, notArchiveElements)) => success(archiveSuccessDebugMessage(aid.commit.value), notArchiveElements)
      }
    }

    def restore(): JsCmd = {
      selectedCommitId match {
        case None         => error(Empty, "A valid archive must be chosen")
        case Some(commit) =>
          (for {
            commiter <- personIdentService.getPersonIdentOrDefault(CurrentUser.actor.name)
            archive  <- restoreFunction(
                          commit,
                          commiter,
                          ModificationId(uuidGen.newUuid),
                          CurrentUser.actor,
                          Some("User requested archive restoration to commit %s".format(commit.value)),
                          false
                        )
          } yield archive).toBox match {
            case eb: EmptyBox => error(eb, restoreErrorMessage)
            case Full(_) => success(restoreSuccessDebugMessage, noElements)
          }
      }
    }

    def download(): JsCmd = {
      selectedCommitId match {
        case None         => error(Empty, "A valid archive must be chosen")
        case Some(commit) =>
          S.redirectTo(s"/secure/api/system/archives/${downloadRestAction}/zip/${commit.value}")
      }

    }

    def buildCommitIdList: List[(Option[GitCommitId], String)] = {
      val baseOptions: List[(Option[GitCommitId], String)] = {
        (None, "Choose an archive to restore...") ::
        (Some(GitCommitId("HEAD")), "Latest Git commit") ::
        Nil
      }

      // and perhaps we have also some dates/rev tags
      val tagOptions: List[(Option[GitCommitId], String)] = archiveListFunction().toBox match {
        case Empty =>
          logger.debug("No archive available from tags")
          Nil
        case f: Failure =>
          logger.error(f ?~! "Error when looking for archives from tags")
          Nil

        case Full(m) =>
          m.toList.sortWith { case ((d1, _), (d2, _)) => d1.isAfter(d2) }.map {
            case (date, revTag) =>
              (Some(revTag.commit), DateFormaterService.getDisplayDate(date))
          }
      }
      baseOptions ::: tagOptions
    }

    ////////// Template filling //////////

    ("#" + archiveButtonId) #> {
      SHtml.ajaxSubmit(archiveButtonName, archive _, ("id" -> archiveButtonId), ("class", "btn btn-primary btn-archive"))
    } &
    ("#" + archiveDateSelectId) #> {
      // we have at least "Choose an archive to restore..." and "get archive from current Git HEAD"
      SHtml.selectObj[Option[GitCommitId]](
        buildCommitIdList,
        Full(selectedCommitId),
        id => selectedCommitId = id,
        ("id" -> archiveDateSelectId),
        ("class", "form-select")
      )
    } &
    ("#" + restoreButtonId) #> {
      (SHtml.ajaxSubmit(restoreButtonName, restore _, ("id" -> restoreButtonId), ("class", "btn btn-default")) ++
      Script(
        OnLoad(
          JsRaw(
            """enableIfNonEmpty("%s", "%s");$("#%s").prop("disabled",true);"""
              .format(archiveDateSelectId, restoreButtonId, restoreButtonId)
          )
        )
      )): NodeSeq
    } &
    ("#" + downloadButtonId) #> {
      (SHtml.ajaxSubmit(downloadButtonName, download _, ("id" -> downloadButtonId), ("class", "btn btn-default")) ++
      Script(
        OnLoad(
          JsRaw(
            """enableIfNonEmpty("%s", "%s");$("#%s").prop("disabled",true);"""
              .format(archiveDateSelectId, downloadButtonId, downloadButtonId)
          )
        )
      )): NodeSeq
    }
  }

  ///////////// success pop-up ///////////////
  private[this] def successPopup: JsCmd = {
    JsRaw("""createSuccessNotification()""")
  }
}
