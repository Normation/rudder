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

import com.normation.rudder.web.model._
import com.normation.rudder.domain.policies._
import com.normation.rudder.web.model.CurrentUser
import net.liftweb.common._
import net.liftweb.http.SHtml
import scala.xml._
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.js._
import JsCmds._
import JE._
import net.liftweb.util.Helpers._
import com.normation.eventlog.ModificationId
import bootstrap.liftweb.RudderConfig

import com.normation.box._

/**
 * This component allows to display and update a category
 * (change its name, add subcategories, etc)
 */
class TechniqueCategoryEditForm(
   htmlId_form:String, //HTML id for the div around the form
   givenCategory:ActiveTechniqueCategory,
   rootCategoryId:ActiveTechniqueCategoryId,
   onSuccessCallback : () => JsCmd = { () => Noop },
   onFailureCallback : () => JsCmd = { () => Noop }
) extends DispatchSnippet with Loggable {

  private[this] val htmlId_categoryDetailsForm = "categoryDetailsForm"

  private[this] val activeTechniqueCategoryRepository = RudderConfig.woDirectiveRepository
  private[this] val uuidGen                           = RudderConfig.stringUuidGenerator


  def dispatch = {
    case "showForm" => { _ =>  showForm }
  }


  private[this] var currentCategory = givenCategory
  def getCategory = currentCategory

  def showForm() : NodeSeq = {
    <div id={htmlId_form} class="object-details">
      <div class="section-title">Category details</div>
        {categoryDetailsForm}
    </div>
    <div>
    <div id="removeCategoryActionDialog" class="modal fade" data-keyboard="true" tabindex="-1">
    <div class="modal-backdrop fade in" style="height: 100%;"></div>
    <div class="modal-dialog">
        <div class="modal-content">
            <div class="modal-header">
                <div class="close" data-dismiss="modal">
                <span aria-hidden="true">&times;</span>
                <span class="sr-only">Close</span>
                </div>
                <h4 class="modal-title">
                    Delete a category
                </h4>
            </div>
            <div class="modal-body">
                <div class="row">
                    <h4 class="col-lg-12 col-sm-12 col-xs-12 text-center">
                        Are you sure that you want to completely delete this item?
                    </h4>
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="btn btn-default" data-dismiss="modal">Cancel</button>
                {SHtml.ajaxButton(<span>Delete</span>, deleteCategory _) % ("class" -> "btn btn-danger")}
            </div>
        </div><!-- /.modal-content -->
    </div><!-- /.modal-dialog -->
    </div>
    </div>
  }

  private[this] def deleteCategory() : JsCmd = {
    activeTechniqueCategoryRepository.delete(currentCategory.id, ModificationId(uuidGen.newUuid),CurrentUser.actor, Some("User deleted technique category from UI")).toBox match {
      case Full(id) =>
        //update UI
        JsRaw("$('#removeCategoryActionDialog').bsModal('hide');") &
        onSuccessCallback() & //Replace(htmlId_activeTechniquesTree, userLibrary) &
        SetHtml(htmlId_form, <span class="greenscala">Category successfully deleted</span>) &
        successPopup

      case e:EmptyBox =>
        logger.error("Error when deleting user lib category with ID %s".format(currentCategory.id), e)

        val xml = (
            ("#deleteCategoryMsg") #> <span class="error" id="deleteCategoryMsg">Error when deleting the category</span>
        ).apply(showForm)

        Replace(htmlId_form, xml)
    }
  }


  private[this] def error(msg:String) = <span class="error">{msg}</span>

  /////////////////////  Category Details Form  /////////////////////

  val categoryName = new WBTextField("Category name", currentCategory.name) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def validations =
      valMinLen(1, "Name must not be empty") _ :: Nil
  }

  val categoryDescription = new WBTextAreaField("Category description", currentCategory.description.toString) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField = super.inputField  % ("style" -> "height:10em")

    override def validations = Nil

    override def toForm_! = (
        "field-label" #> displayHtml
      & "field-input" #> (inputField % ( "id" -> id) % ("class" -> "largeCol"))
      & "field-infos" #> helpAsHtml
      & "field-errors" #> ( errors match {
          case Nil => NodeSeq.Empty
          case l =>
            <span><ul class="field_errors paddscala">{
              l.map(e => <li class="text-danger lopaddscala">{e.msg}</li>)
            }</ul></span><hr class="spacer"/>
        })
    )(
      <div class="wbBaseField">
        <field-errors></field-errors>
        <label for={id} class="wbBaseFieldLabel threeCol textright"><b><field-label></field-label></b></label>
        <field-input></field-input>
        <field-infos></field-infos>
      </div>
    )
  }

  val categorFormTracker = new FormTracker(categoryName, categoryDescription)

  var categoryNotifications = List.empty[NodeSeq]



  private[this] def categoryDetailsForm : NodeSeq = {
    val html = SHtml.ajaxForm(
      <div id={htmlId_categoryDetailsForm}>
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
      & "update-submit" #> SHtml.ajaxSubmit("Update", {
           () => {
             if(categorFormTracker.hasErrors) {
               categorFormTracker.addFormError( error("Some errors prevented completing the form") )
             } else {
               val updatedCategory = currentCategory.copy(
                   name = categoryName.get,
                   description = categoryDescription.get
               )
               activeTechniqueCategoryRepository.saveActiveTechniqueCategory(updatedCategory, ModificationId(uuidGen.newUuid), CurrentUser.actor, Some("User updated category from UI")).toBox match {
                 case Failure(m,_,_) =>
                   categorFormTracker.addFormError(  error("An error occured: " + m) )
                 case Empty =>
                   categorFormTracker.addFormError( error("An error occured without a message. Please try again later or contact your administrator") )
                 case Full(c) =>
                   categorFormTracker.clean
                   currentCategory = c
               }
             }

             //update ui
             if(categorFormTracker.hasErrors) {
               SetHtml(htmlId_categoryDetailsForm, categoryDetailsForm ) &
               onFailureCallback()
             } else {
               successPopup &
               SetHtml(htmlId_categoryDetailsForm, categoryDetailsForm) &
               onSuccessCallback() //Replace(htmlId_activeTechniquesTree, <lift:configuration.TechniqueLibraryManagement.userLibrary />) & buildUserLibraryJsTree
             }
           }
         }, ("class","btn btn-default"))
       & "update-delete" #> {
           if(currentCategory.id != rootCategoryId && currentCategory.children.isEmpty && currentCategory.items.isEmpty) {
              {Script(OnLoad(JsRaw("""$( "#deleteCategoryButton" ).click(function() {
                $("#removeCategoryActionDialog").bsModal('show');
                return false;
              })""")))} ++ {
              <button id="deleteCategoryButton" class="btn btn-danger">Delete</button>
              <span id="deleteCategoryMsg"/>
              }
            } else {
              {<button id="deleteCategoryButton" disabled="disabled"  class="btn btn-danger">Delete</button>
              <br/><span class="catdelete">Only non root and empty category can be deleted</span>}
            }
         }
       & "update-notifications" #> {
           categoryNotifications :::= categorFormTracker.formErrors
           categorFormTracker.cleanErrors

           if(categoryNotifications.isEmpty) NodeSeq.Empty
           else {
             val notifications = <div class="notify"><ul class="field_errors">{categoryNotifications.map( n => <li>{n}</li>) }</ul></div>
             categoryNotifications = Nil
             notifications
           }
         }
    )(html)
  }



  ///////////// success pop-up ///////////////
    private[this] def successPopup : JsCmd = {
      JsRaw("""createSuccessNotification()""")
  }
}
