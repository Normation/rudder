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

package com.normation.rudder.web.components

import bootstrap.liftweb.RudderConfig
import com.normation.box.*
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.policies.*
import com.normation.rudder.users.CurrentUser
import com.normation.rudder.web.model.*
import net.liftweb.common.*
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.SHtml
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.FieldError
import net.liftweb.util.Helpers.*
import scala.xml.*

/**
 * This component allows to display and update a category
 * (change its name, add subcategories, etc)
 */
class TechniqueCategoryEditForm(
    htmlId_form:       String, // HTML id for the div around the form
    givenCategory:     ActiveTechniqueCategory,
    rootCategoryId:    ActiveTechniqueCategoryId,
    onSuccessCallback: () => JsCmd = { () => Noop },
    onFailureCallback: () => JsCmd = { () => Noop }
) extends DispatchSnippet with Loggable {

  private val htmlId_categoryDetailsForm = "categoryDetailsForm"

  private val activeTechniqueCategoryRepository = RudderConfig.woDirectiveRepository
  private val uuidGen                           = RudderConfig.stringUuidGenerator

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = { case "showForm" => { _ => showForm() } }

  private var currentCategory = givenCategory
  def getCategory             = currentCategory

  def showForm(): NodeSeq = {
    <div id={htmlId_form} class="object-details">
      <div class="section-title">Category details</div>
        {categoryDetailsForm}
    </div>
    <div>
    <div id="removeCategoryActionDialog" class="modal fade" tabindex="-1">
    <div class="modal-dialog">
        <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title">
                Delete a category
              </h5>
              <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
            </div>
            <div class="modal-body">
                <div class="row">
                    <h4 class="col-xl-12 col-md-12 col-sm-12 text-center">
                        Are you sure that you want to completely delete this item?
                    </h4>
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="btn btn-default" data-bs-dismiss="modal">Cancel</button>
                {SHtml.ajaxButton(<span>Delete</span>, () => deleteCategory()) % ("class" -> "btn btn-danger")}
            </div>
        </div>
    </div>
    </div>
    </div>
  }

  private def deleteCategory(): JsCmd = {
    activeTechniqueCategoryRepository
      .deleteCategory(
        currentCategory.id,
        ModificationId(uuidGen.newUuid),
        CurrentUser.actor,
        Some("User deleted technique category from UI")
      )
      .toBox match {
      case Full(id) =>
        // update UI
        JsRaw("hideBsModal('removeCategoryActionDialog');") & // JsRaw ok, const
        onSuccessCallback() &                                 // Replace(htmlId_activeTechniquesTree, userLibrary) &
        SetHtml(htmlId_form, <span class="text-success">Category successfully deleted</span>) &
        successPopup

      case e: EmptyBox =>
        logger.error("Error when deleting user lib category with ID %s".format(currentCategory.id), e)

        val xml = (
          ("#deleteCategoryMsg") #> <span class="error" id="deleteCategoryMsg">Error when deleting the category</span>
        ).apply(showForm())

        Replace(htmlId_form, xml)
    }
  }

  private def error(msg: String) = <span class="error">{msg}</span>

  /////////////////////  Category Details Form  /////////////////////

  val categoryName: WBTextField = new WBTextField("Category name", currentCategory.name) {
    override def setFilter   = notNull :: trim :: Nil
    override def validations =
      valMinLen(1, "Name must not be empty") :: Nil
  }

  val categoryDescription: WBTextAreaField = new WBTextAreaField("Category description", currentCategory.description.toString) {
    override def setFilter  = notNull :: trim :: Nil
    override def inputField = super.inputField % ("style" -> "height:10em")

    override def validations: List[String => List[FieldError]] = Nil

    override def toForm_! = (
      "field-label" #> displayHtml
        & "field-input" #> (inputField % ("id" -> id) % ("class" -> "largeCol"))
        & "field-infos" #> helpAsHtml
        & "field-errors" #> (errors match {
          case Nil => NodeSeq.Empty
          case l   =>
            <span><ul class="text-danger mt-1">{
              l.map(e => <li class="text-danger mt-1">{e.msg}</li>)
            }</ul></span><hr class="spacer"/>
        })
    )(
      <div class="wbBaseField">
        <field-errors></field-errors>
        <label for={id} class="wbBaseFieldLabel threeCol textright"><field-label></field-label></label>
        <field-input></field-input>
        <field-infos></field-infos>
      </div>
    )
  }

  val categorFormTracker = new FormTracker(categoryName, categoryDescription)

  var categoryNotifications: List[NodeSeq] = Nil

  private def categoryDetailsForm: NodeSeq = {
    val html = SHtml.ajaxForm(<div id={htmlId_categoryDetailsForm}>
        <update-notifications></update-notifications>
        <update-name></update-name>
        <hr class="spacer"/>
        <update-description></update-description>
        <hr class="spacer"/>
        <div class="spacerscala">
          <update-submit></update-submit>
          <update-delete></update-delete>
        </div>
        <hr class="spacer"/>
      </div>)

    (
      "update-name" #> categoryName.toForm_!
      & "update-description" #> categoryDescription.toForm_!
      & "update-submit" #> SHtml.ajaxSubmit(
        "Update",
        { () =>
          {
            if (categorFormTracker.hasErrors) {
              categorFormTracker.addFormError(error("Some errors prevented completing the form"))
            } else {
              val updatedCategory = currentCategory.copy(
                name = categoryName.get,
                description = categoryDescription.get
              )
              activeTechniqueCategoryRepository
                .saveActiveTechniqueCategory(
                  updatedCategory,
                  ModificationId(uuidGen.newUuid),
                  CurrentUser.actor,
                  Some("User updated category from UI")
                )
                .toBox match {
                case Failure(m, _, _) =>
                  categorFormTracker.addFormError(error("An error occured: " + m))
                case Empty            =>
                  categorFormTracker.addFormError(
                    error("An error occured without a message. Please try again later or contact your administrator")
                  )
                case Full(c)          =>
                  categorFormTracker.clean
                  currentCategory = c
              }
            }

            // update ui
            if (categorFormTracker.hasErrors) {
              SetHtml(htmlId_categoryDetailsForm, categoryDetailsForm) &
              onFailureCallback()
            } else {
              successPopup &
              SetHtml(htmlId_categoryDetailsForm, categoryDetailsForm) &
              onSuccessCallback() // Replace(htmlId_activeTechniquesTree, <lift:configuration.TechniqueLibraryManagement.userLibrary />) & buildUserLibraryJsTree
            }
          }
        },
        ("class", "btn btn-default")
      )
      & "update-delete" #> {
        if (currentCategory.id != rootCategoryId && currentCategory.children.isEmpty && currentCategory.items.isEmpty) {
          {
            Script(OnLoad(JsRaw("""$( "#deleteCategoryButton" ).click(function() {
                iniBsModal("removeCategoryActionDialog");
                return false;
              })"""))) // JsRaw ok, const
          } ++ {
            <button id="deleteCategoryButton" class="btn btn-danger">Delete</button>
              <span id="deleteCategoryMsg"/>
          }
        } else {
          <button id="deleteCategoryButton" disabled="disabled"  class="btn btn-danger">Delete</button>
              <br/><span class="ms-1">Only non root and empty category can be deleted</span>
        }
      }
      & "update-notifications" #> {
        categoryNotifications :::= categorFormTracker.formErrors
        categorFormTracker.cleanErrors

        if (categoryNotifications.isEmpty) NodeSeq.Empty
        else {
          val notifications = <div class="notify"><ul class="text-danger">{
            categoryNotifications.map(n => <li>{n}</li>)
          }</ul></div>
          categoryNotifications = Nil
          notifications
        }
      }
    )(html)
  }

  ///////////// success pop-up ///////////////
  private def successPopup: JsCmd = {
    JsRaw("""createSuccessNotification()""") // JsRaw ok, const
  }
}
