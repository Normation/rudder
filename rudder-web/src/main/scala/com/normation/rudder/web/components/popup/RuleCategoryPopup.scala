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

package com.normation.rudder.web.components.popup


import org.slf4j.LoggerFactory
import net.liftweb.http.LocalSnippet
import net.liftweb.http.Templates
import net.liftweb.http.js._
import JsCmds._
import com.normation.utils.StringUuidGenerator
import JE._
import net.liftweb.common._
import net.liftweb.http.{SHtml,S,DispatchSnippet,Templates}
import scala.xml._
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers._
import com.normation.rudder.web.model.{
  WBTextField, FormTracker, WBTextAreaField, WBSelectField, WBRadioField
}
import com.normation.rudder.repository._
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.web.services.UserPropertyService
import com.normation.rudder.web.services.CategoryHierarchyDisplayer
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.eventlog.ModificationId
import com.normation.rudder.rule.category.RuleCategory

/**
 * Create a group or a category
 * This is a popup that allows for the creation of a group or a category, or
 * if a group is passed as an argument, will force the creation of a new group based on the query
 * contained
 */
class RuleCategoryPopup(
    rootCategory      : RuleCategory
  , targetCategory    : Option[RuleCategory]
  , onSuccessCategory : (RuleCategory) => JsCmd
  , onSuccessCallback : (String) => JsCmd = { _ => Noop }
  , onFailureCallback : () => JsCmd = { () => Noop }
) extends DispatchSnippet with Loggable {

  // Load the template from the popup
  private def html = {
    val path = "templates-hidden" :: "Popup" :: "RuleCategoryPopup" :: Nil
    (for {
      xml <- Templates(path)
    } yield {
      chooseTemplate("component", "creationPopup", xml)
    }) openOr {
      logger.error("Missing template <component:creationPopup> at path: %s.html".format(path.mkString("/")))
      <div/>
    }
  }

  private def deleteHtml = {
    val path = "templates-hidden" :: "Popup" :: "RuleCategoryPopup" :: Nil
    (for {
      xml <- Templates(path)
    } yield {
      chooseTemplate("component", "deletePopup", xml)
    }) openOr {
      logger.error("Missing template <component:creationPopup> at path: %s.html".format(path.mkString("/")))
      <div/>
    }
  }

  private[this] val woRulecategoryRepository   = RudderConfig.woRuleCategoryRepository
  private[this] val categoryHierarchyDisplayer = RudderConfig.categoryHierarchyDisplayer
  private[this] val uuidGen                    = RudderConfig.stringUuidGenerator
  private[this] val userPropertyService        = RudderConfig.userPropertyService

  def dispatch = {
    case "popupContent" => { _ => popupContent }
  }

  val title = {
    targetCategory match {
      case None => "Create new Rule category"
      case Some(c) => s"Update Category ${c.name}"
    }
  }

  def popupContent() : NodeSeq = {
     (

      "#creationForm *"          #> { (xml:NodeSeq) => SHtml.ajaxForm(xml) } andThen
      "#dialogTitle *"           #> title &
      "#categoryName * "          #> categoryName.toForm_! &
      "#categoryParent *"        #> categoryParent.toForm_! &
      "#categoryDescription *" #> categoryDescription.toForm_! &
      "#saveCategory"          #> SHtml.ajaxSubmit("Save", () => onSubmit(), ("id", "createRuleCategorySaveButton") , ("tabindex","3"), ("style","margin-left:5px;")) andThen
      ".notifications *"       #> updateAndDisplayNotifications()

    )(html ++ Script(OnLoad(JsRaw("updatePopup();"))))
  }


  def deletePopupContent(canBeDeleted : Boolean) : NodeSeq = {
    val action = () => if (canBeDeleted)  onSubmitDelete else closePopup
    val disabled  = if (canBeDeleted) ("","") else ("disabled","true")
     (
      "#dialogTitle *"  #> s"Delete Rule category ${s"'${rootCategory.name}'"}" &
      "#text * "        #> (if(canBeDeleted) "Are you sure you want to delete this rule category?" else "This Rule category is not empty and therefore cannot be deleted")  &
      "#deleteCategoryButton" #> SHtml.ajaxButton("Confirm", action, ("id", "createRuleCategorySaveButton") ,("tabindex","3") , ("style","margin-left:5px;"), disabled )
    )(deleteHtml ++ Script(OnLoad(JsRaw("updatePopup();"))))
  }

  ///////////// fields for category settings ///////////////////
  private[this] val categoryName = new WBTextField("Name", targetCategory.map(_.name).getOrElse("")) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def subContainerClassName = "twoColPopup"
    override def errorClassName = "threeColErrors"
    override def inputField = super.inputField %("onkeydown" , "return processKey(event , 'createCOGSaveButton')") % ("tabindex","2")
    override def validations =
      valMinLen(1, "The name must not be empty.") _ :: Nil
  }

  private[this] val categoryDescription = new WBTextAreaField("Description", targetCategory.map(_.description).getOrElse("")) {
    override def subContainerClassName = "twoColPopup"
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField = super.inputField  % ("style" -> "height:5em") % ("tabindex","4")
    override def errorClassName = "threeColErrors"
    override def validations =  Nil

  }

  val categories = targetCategory.map(rootCategory.filter(_)).getOrElse(rootCategory)

  private[this] val categoryParent =
    new WBSelectField(
        "Parent category"
      , categoryHierarchyDisplayer.getRuleCategoryHierarchy(categories, None).map { case (id, name) => (id.value -> name)}
      , targetCategory.flatMap(rootCategory.findParent(_)).map(_.id.value).getOrElse(rootCategory.id.value)
    ) {
    override def subContainerClassName = "twoColPopup"
    override def className = "rudderBaseFieldSelectClassName"
  }

  private[this] val formTracker = new FormTracker(categoryName, categoryDescription, categoryParent)

  private[this] var notifications = List.empty[NodeSeq]

  private[this] def error(msg:String) = <span class="error">{msg}</span>


  private[this] def closePopup() : JsCmd = {
    JsRaw(""" $.modal.close();""")
  }
  /**
   * Update the form when something happened
   */
  private[this] def updateFormClientSide() : JsCmd = {
    SetHtml("RuleCategoryCreationContainer", popupContent())
  }

  private[this] def onSubmitDelete() : JsCmd = {
    targetCategory match {
      case Some(category) =>
        val modId = new ModificationId(uuidGen.newUuid)
        woRulecategoryRepository.delete(category.id, modId , CurrentUser.getActor, None, true) match {
         case Full(x) => closePopup() & onSuccessCallback(x.value) & onSuccessCategory(category)
              case Empty =>
                logger.error("An error occurred while deleting the category")
                formTracker.addFormError(error("An error occurred while deleting the category"))
                onFailure & onFailureCallback()
              case Failure(m,_,_) =>
                logger.error("An error occurred while deleting the category:" + m)
                formTracker.addFormError(error(m))
                onFailure & onFailureCallback()
        }
      case None =>
        // Should not happen, close popup
        closePopup()
    }
  }

  private[this] def onSubmit() : JsCmd = {
    if(formTracker.hasErrors) {
      onFailure & onFailureCallback()
    } else {
      (targetCategory match {
        case None =>

          val newCategory = RuleCategory(
                                RuleCategoryId(uuidGen.newUuid)
                              , categoryName.is
                              , categoryDescription.is
                              , Nil
                              , false
                            )

          val parent = RuleCategoryId(categoryParent.is)
          val modId = new ModificationId(uuidGen.newUuid)
          woRulecategoryRepository.create(newCategory, parent,modId , CurrentUser.getActor, None)
        case Some(category) =>
          val updated = category.copy(
                            name        =  categoryName.is
                          , description = categoryDescription.is
                        )

          val parent = RuleCategoryId(categoryParent.is)
          val modId = new ModificationId(uuidGen.newUuid)
          woRulecategoryRepository.updateAndMove(updated, parent,modId , CurrentUser.getActor, None)
      }) match {
          case Full(x) => closePopup() & onSuccessCallback(x.id.value) & onSuccessCategory(x)
          case Empty =>
            logger.error("An error occurred while saving the category")
            formTracker.addFormError(error("An error occurred while saving the category"))
            onFailure & onFailureCallback()
          case Failure(m,_,_) =>
            logger.error("An error occurred while saving the category:" + m)
            formTracker.addFormError(error(m))
            onFailure & onFailureCallback()
        }
      }
  }

  private[this] def onFailure : JsCmd = {
    updateFormClientSide()
  }



  private[this] def updateAndDisplayNotifications() : NodeSeq = {
    notifications :::= formTracker.formErrors
    formTracker.cleanErrors

    if(notifications.isEmpty) NodeSeq.Empty
    else {
      val html = <div id="notifications" class="notify"><ul>{notifications.map( n => <li>{n}</li>) }</ul></div>
      notifications = Nil
      html
    }
  }
}
