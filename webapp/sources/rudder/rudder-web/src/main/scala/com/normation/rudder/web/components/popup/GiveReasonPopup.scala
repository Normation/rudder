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

package com.normation.rudder.web.components.popup

import bootstrap.liftweb.RudderConfig
import com.normation.box.*
import com.normation.cfclerk.domain.TechniqueName
import com.normation.rudder.config.ReasonBehavior.*
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.rudder.domain.policies.PolicyTypes
import com.normation.rudder.users.CurrentUser
import com.normation.rudder.web.ChooseTemplate
import com.normation.rudder.web.model.FormTracker
import com.normation.rudder.web.model.WBTextAreaField
import net.liftweb.common.*
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.SHtml
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.Helpers.*
import org.apache.commons.text.StringEscapeUtils
import scala.xml.*

class GiveReasonPopup(
    onSuccessCallback:        (ActiveTechniqueId) => JsCmd = { (ActiveTechniqueId) => Noop },
    onFailureCallback:        (String, String) => JsCmd = { (String1, String2) => Noop },
    refreshActiveTreeLibrary: () => JsCmd = { () => Noop },
    sourceActiveTechniqueId:  ActiveTechniqueId,
    destCatId:                ActiveTechniqueCategoryId
) extends DispatchSnippet with Loggable {

  // Load the template from the popup
  def popupTemplate: NodeSeq = ChooseTemplate(
    List("templates-hidden", "Popup", "giveReason"),
    "reason-givereasonpopup"
  )

  private val rwActiveTechniqueRepository = RudderConfig.woDirectiveRepository
  private val userPropertyService         = RudderConfig.userPropertyService
  private val techniqueRepository         = RudderConfig.techniqueRepository

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = { case "popupContent" => popupContent }

  def popupContent(html: NodeSeq): NodeSeq = {
    SHtml.ajaxForm(
      (
        "item-reason" #> crReasons.map { f =>
          <div>
            <h4 class="col-xl-12 col-md-12 col-sm-12 audit-title">Change Audit Log</h4>
            {f.toForm_!}
         </div>
        }
        & "item-cancel" #> SHtml
          .ajaxButton("Cancel", () => closePopup() & refreshActiveTreeLibrary(), ("tabindex", "4"), ("class", "btn btn-default"))
        & "item-save" #> SHtml.ajaxSubmit(
          "Save",
          onSubmit,
          ("id", "createATCSaveButton"),
          ("tabindex", "3"),
          ("class", "btn btn-success")
        )
      )(popupTemplate)
    )
  }

///////////// fields for category settings ///////////////////
  private val crReasons = {
    userPropertyService.reasonsFieldBehavior match {
      case Disabled  => None
      case Mandatory => Some(buildReasonField(true, "px-1"))
      case Optional  => Some(buildReasonField(false, "px-1"))
    }
  }

  def buildReasonField(mandatory: Boolean, containerClass: String = "twoCol"): WBTextAreaField = {
    new WBTextAreaField("Change audit message", "") {
      override def setFilter  = notNull :: trim :: Nil
      override def inputField = super.inputField %
        ("style" -> "height:8em;") % ("placeholder" -> { userPropertyService.reasonsFieldExplanation })
      override def subContainerClassName = containerClass
      override def validations           = {
        if (mandatory) {
          valMinLen(5, "The reason must have at least 5 characters.") :: Nil
        } else {
          Nil
        }
      }
    }
  }

  private val formTracker = {
    val fields = List() ++ crReasons
    new FormTracker(fields)
  }

  private def error(msg: String) = <span class="col-xl-12 errors-container">{msg}</span>

  private def closePopup(): JsCmd = {
    JsRaw("""hideBsModal('createActiveTechniquePopup');""") // JsRaw ok, const
  }

  /**
   * Update the form when something happened
   */
  private def updateFormClientSide(): JsCmd = {
    SetHtml("createActiveTechniquesContainer", popupContent(NodeSeq.Empty))
  }

  private def onSubmit(): JsCmd = {
    if (formTracker.hasErrors) {
      onFailure & onFailureCallback(sourceActiveTechniqueId.value, destCatId.value)
    } else {
      val ptName    = TechniqueName(sourceActiveTechniqueId.value)
      val errorMess = "Error while trying to add Rudder internal " +
        "Technique with requested id '%s' in user library category '%s'"
      (for {
        result <- (
                    rwActiveTechniqueRepository
                      .addTechniqueInUserLibrary(
                        ActiveTechniqueCategoryId(destCatId.value),
                        ptName,
                        techniqueRepository.getTechniqueVersions(ptName).toSeq,
                        policyTypes = PolicyTypes.rudderBase
                      )(using CurrentUser.changeContext(crReasons.map(_.get)))
                      .toBox
                    ?~! errorMess.format(sourceActiveTechniqueId.value, destCatId.value)
                  )
      } yield {
        result
      }) match {
        case Full(res) =>
          val jsString = """setTimeout(function() { $("[activeTechniqueId=%s]")
                .attempt("highlight", {}, 2000)}, 100)"""
          formTracker.clean
          closePopup() &
          onSuccessCallback(res.id) &
          JsRaw(jsString.format(StringEscapeUtils.escapeEcmaScript(sourceActiveTechniqueId.value)))
        case f: Failure =>
          Alert(f.messageChain + "\nPlease reload the page")
        case Empty     =>
          val errorMess = "Error while trying to move Active Technique with " +
            "requested id '%s' to category id '%s'\nPlease reload the page."
          Alert(errorMess.format(sourceActiveTechniqueId.value, destCatId.value))
      }
    }
  }

  private def onFailure: JsCmd = {
    formTracker.addFormError(error("There was a problem with your request"))
    updateFormClientSide()
  }
}
