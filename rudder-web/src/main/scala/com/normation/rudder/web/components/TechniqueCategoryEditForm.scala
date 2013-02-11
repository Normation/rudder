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

package com.normation.rudder.web.components

import com.normation.rudder.web.model._
import com.normation.rudder.domain.policies._
import com.normation.rudder.web.services.DirectiveEditorService
import com.normation.cfclerk.domain.{
  TechniqueId,Technique,
  TechniqueCategoryId, TechniqueCategory
}
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.rudder.web.model.{JsTreeNode, CurrentUser}
import net.liftweb.common._
import net.liftweb.http.{SHtml,S}
import scala.xml._
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.js._
import JsCmds._
import JE._
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers._
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.repository._
import net.liftweb.http.LocalSnippet
import net.liftweb.json._
import net.liftweb.json.JsonAST._
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import com.normation.eventlog.ModificationId


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


  private[this] val htmlId_addUserCategoryForm = "addUserCategoryForm"
  private[this] val htmlId_categoryDetailsForm = "categoryDetailsForm"

  private[this] val activeTechniqueCategoryRepository = inject[ActiveTechniqueCategoryRepository]
  private[this] val uuidGen = inject[StringUuidGenerator]


  def dispatch = {
    case "showForm" => { _ =>  showForm }
  }


  private[this] var currentCategory = givenCategory
  def getCategory = currentCategory

  def showForm() : NodeSeq = {
    <div id={htmlId_form} class="object-details">
      <fieldset class="userCategoryDetailsFieldset"><legend>Category details</legend>
        {categoryDetailsForm}
          <div id="removeCategoryActionDialog" class="nodisplay">
            <div class="simplemodal-title">
              <h1>Delete a category</h1>
              <hr/>
            </div>
            <div class="simplemodal-content">
              <hr class="spacer" />
              <br />
              <img src="/images/icWarn.png" alt="Warning!" height="32" width="32" class="warnicon"/>
              <h2>Are you sure that you want to completely delete this item?</h2>
              <br/>
              <hr class="spacer" />
            </div>
            <div class="simplemodal-bottom">
              <hr/>
              <div class="popupButton">
                <span>
                 <button class="simplemodal-close" onClick="$.modal.close();">Cancel</button>
                {SHtml.ajaxButton(<span class="red">Delete</span>, deleteCategory _)}
                </span>
              </div>
            </div>
          </div>

      </fieldset>
    </div>
  }

  private[this] def deleteCategory() : JsCmd = {
    activeTechniqueCategoryRepository.delete(currentCategory.id, ModificationId(uuidGen.newUuid),CurrentUser.getActor, Some("User deleted technique category from UI")) match {
      case Full(id) =>
        //update UI
        JsRaw("$.modal.close();") &
        onSuccessCallback() & //Replace(htmlId_activeTechniquesTree, userLibrary) &
        SetHtml(htmlId_form, <span class="greenscala">Category successfully deleted</span>) &
        successPopup

      case e:EmptyBox =>
        logger.error("Error when deleting user lib category with ID %s".format(currentCategory.id), e)

        val xml = (
            ("#deleteCategoryMsg") #> <span class="error" id="deleteCategoryMsg">Error when deleting the categoy</span>
        ).apply(showForm)

        Replace(htmlId_form, xml)
    }
  }


  private[this] def error(msg:String) = <span class="error">{msg}</span>

  /////////////////////  Category Details Form  /////////////////////

  val categoryName = new WBTextField("Category name", currentCategory.name) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def validations =
      valMinLen(3, "The category name must have at least 3 characters") _ :: Nil
  }

  val categoryDescription = new WBTextAreaField("Category description", currentCategory.description.toString) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField = super.inputField  % ("style" -> "height:10em")

    override def validations = Nil

    override def toForm_! = bind("field",
    <div class="wbBaseField">
      <field:errors />
      <label for={id} class="wbBaseFieldLabel threeCol textright"><b><field:label /></b></label>
      <field:input />
      <field:infos />

    </div>,
    "label" -> displayHtml,
    "input" -> inputField % ( "id" -> id) % ("class" -> "largeCol"),
    "infos" -> (helpAsHtml openOr NodeSeq.Empty),
    "errors" -> {
      errors match {
        case Nil => NodeSeq.Empty
        case l =>
          <span><ul class="field_errors paddscala">{
            l.map(e => <li class="field_error lopaddscala">{e.msg}</li>)
          }</ul></span><hr class="spacer"/>
      }
    }
  )

  }

  val categorFormTracker = new FormTracker(categoryName, categoryDescription)

  var categoryNotifications = List.empty[NodeSeq]



  private[this] def categoryDetailsForm : NodeSeq = {
    val html = SHtml.ajaxForm(
      <div id={htmlId_categoryDetailsForm}>
        {Script(OnLoad(JsRaw("""correctButtons()""")))}
        <update:notifications />
        <update:name/>
        <hr class="spacer"/>
        <update:description />
        <hr class="spacer"/>
        <div class="spacerscala">
          <update:submit />
          <update:delete/>
        </div>
        <hr class="spacer"/>
      </div>)

    bind("update", html,
      "name" -> categoryName.toForm_!,
      "description" -> categoryDescription.toForm_!,
      "submit" -> SHtml.ajaxSubmit("Update", {
         () => {
           if(categorFormTracker.hasErrors) {
             categorFormTracker.addFormError( error("Some errors prevented completing the form") )
           } else {
             val updatedCategory = currentCategory.copy(
                 name = categoryName.is,
                 description = categoryDescription.is
             )
             activeTechniqueCategoryRepository.saveActiveTechniqueCategory(updatedCategory, ModificationId(uuidGen.newUuid), CurrentUser.getActor, Some("User updated category from UI")) match {
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
       } ),
       "delete" -> {
         if(currentCategory.id != rootCategoryId && currentCategory.children.isEmpty && currentCategory.items.isEmpty) {
              {Script(OnLoad(JsRaw("""$( "#deleteCategoryButton" ).click(function() {
                $("#removeCategoryActionDialog").modal();
                $('#simplemodal-container').css('height', 'auto');
                return false;
              })""")))} ++ {
              <button id="deleteCategoryButton">Delete</button>
              <span id="deleteCategoryMsg"/>
              }
            } else {
              {<button id="deleteCategoryButton" disabled="disabled">Delete</button>
              <br/><span class="catdelete">Only non root and empty category can be deleted</span>}
            }
       },
       "notifications" -> {
         categoryNotifications :::= categorFormTracker.formErrors
         categorFormTracker.cleanErrors

         if(categoryNotifications.isEmpty) NodeSeq.Empty
         else {
           val notifications = <div class="notify"><ul class="field_errors">{categoryNotifications.map( n => <li>{n}</li>) }</ul></div>
           categoryNotifications = Nil
           notifications
         }
       }
    )
  }



  ///////////// success pop-up ///////////////
    private[this] def successPopup : JsCmd = {
    JsRaw(""" callPopupWithTimeout(200, "successConfirmationDialog", 100, 350)
    """)
  }
}
