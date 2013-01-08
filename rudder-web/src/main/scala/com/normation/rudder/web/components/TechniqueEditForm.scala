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

import com.normation.rudder.domain.policies._
import com.normation.rudder.web.services.UserPropertyService
import com.normation.rudder.repository._
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies._
import net.liftweb.http.js._
import JsCmds._
import JE._
import net.liftweb.common._
import net.liftweb.http._
import scala.xml._
import net.liftweb.util._
import net.liftweb.util.Helpers._
import com.normation.rudder.web.model._
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import com.normation.utils.StringUuidGenerator
import com.normation.cfclerk.domain.{
  TechniqueId,Technique,
  TechniqueCategoryId, TechniqueCategory
}
import com.normation.rudder.repository._
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.rudder.web.services.DirectiveEditorService
import com.normation.rudder.services.policies._
import com.normation.rudder.batch.{AsyncDeploymentAgent,AutomaticStartDeployment}
import com.normation.rudder.domain.eventlog.RudderEventActor
import org.joda.time.DateTime

object TechniqueEditForm {
  
  /**
   * This is part of component static initialization.
   * Any page which contains (or may contains after an ajax request)
   * that component have to add the result of that method in it.
   */
  def staticInit:NodeSeq = 
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentTechniqueEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "staticInit", xml)
    }) openOr Nil
    
  private def body = 
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentTechniqueEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "body", xml)
    }) openOr Nil
    
  private def popupRemoveForm = 
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentTechniqueEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "popupRemoveForm", xml)
    }) openOr Nil
    
  private def popupDisactivateForm = 
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentTechniqueEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "popupDisactivateForm", xml)
    }) openOr Nil
    
  private def crForm = 
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentTechniqueEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "form", xml)
    }) openOr Nil
  
  val htmlId_techniqueConf = "techniqueConfiguration"
  val htmlId_addPopup = "addPopup"
  val htmlId_addToActiveTechniques = "addToActiveTechniques"
  val htmlId_userCategoryDetails = "userCategoryDetails"
  val htmlId_addUserCategoryForm = "addUserCategoryForm"
}

/**
 * showTechnique(technique, policyPackageCategoryService.getReferenceTechniqueLibrary, activeTechniqueCategoryRepository.getActiveTechniqueLibrary)
 */
class TechniqueEditForm(
  htmlId_technique:String, //HTML id for the div around the form
  val technique:Technique,
  userCategoryLibrary:Option[ActiveTechniqueCategory],
  //JS to execute on form success (update UI parts)
  //there are call by name to have the context matching their execution when called
  onSuccessCallback : () => JsCmd = { () => Noop },
  onFailureCallback : () => JsCmd = { () => Noop }
) extends DispatchSnippet with Loggable {
  import TechniqueEditForm._
  

  //find Technique
  private[this] val techniqueRepository = inject[TechniqueRepository]
  //find & create user categories
  private[this] val activeTechniqueCategoryRepository = inject[ActiveTechniqueCategoryRepository]
  //find & create Active Techniques
  private[this] val activeTechniqueRepository = inject[ActiveTechniqueRepository]
  //generate new uuid
  private[this] val uuidGen = inject[StringUuidGenerator]
  //transform Technique variable to human viewable HTML fields
  private[this] val directiveEditorService = inject[DirectiveEditorService]
  private[this] val dependencyService = inject[DependencyAndDeletionService]
  private[this] val asyncDeploymentAgent = inject[AsyncDeploymentAgent]
  private[this] val userPropertyService = inject[UserPropertyService]


  
//  private[this] val directives = directiveRepository.getAll() match {
//    case Full(seq) => seq
//    case Empty => throw new ComponentInitializationException(Failure("Error while getting the list of available Directives"))
//    case f:Failure => throw new ComponentInitializationException(f)
//  }
    
  
  private[this] var currentActiveTechnique = activeTechniqueRepository.getActiveTechnique(technique.id.name)
  private[this] var uptCurrentStatusIsActivated = currentActiveTechnique.map( _.isEnabled)
  
 
  //////////////////////////// public methods ////////////////////////////

  def dispatch = { 
    case "showForm" => { _:NodeSeq => showForm }
  }
  
  def showForm() : NodeSeq = {
    (
      "#editForm" #> showCrForm() &
      "#removeActionDialog" #> showRemovePopupForm() &
      "#disactivateActionDialog" #> showDisactivatePopupForm()
    )(body)
  }
  
  def showRemovePopupForm() : NodeSeq = {    
        currentActiveTechnique match {
          case e:EmptyBox => NodeSeq.Empty
          case Full(activeTechnique) => 
    (
          "#deleteActionDialog *" #> { (n:NodeSeq) => SHtml.ajaxForm(n) } andThen
          "#dialogDeleteButton" #> { deleteButton(activeTechnique.id) % ("id", "deleteButton") } &
          "#deleteItemDependencies *" #> dialogDeleteTree("deleteItemDependencies", activeTechnique)&
          ".reasonsFieldset" #> { crReasonsRemovePopup.map { f =>
            "#explanationMessage" #> <div>{userPropertyService.reasonsFieldExplanation}</div> &
            "#reasonsField" #> f.toForm_!
          } } &
          "#errorDisplay" #> { updateAndDisplayNotifications(formTrackerRemovePopup) }
      )(popupRemoveForm)
        }
  }
  
  def showDisactivatePopupForm() : NodeSeq = {    
        currentActiveTechnique match {
          case e:EmptyBox => NodeSeq.Empty
          case Full(activeTechnique) => 
    (
          "#disableActionDialog *" #> { (n:NodeSeq) => SHtml.ajaxForm(n) } andThen
          "#dialogDisableButton" #> { disableButton(activeTechnique) % ("id", "disableButton") } &
          "#dialogDisableTitle" #> { if(activeTechnique.isEnabled) "Disable" else "Enable" } &
          "#dialogDisableLabel" #> { if(activeTechnique.isEnabled) "disable" else "enable" } &
          "#disableItemDependencies *" #> dialogDisableTree("disableItemDependencies", activeTechnique) & 
          ".reasonsFieldset" #> { crReasonsDisablePopup.map { f =>
            "#explanationMessage" #> <div>{userPropertyService.reasonsFieldExplanation}</div> &
            "#reasonsField" #> f.toForm_!
          } } &
          "#time" #> <div>{ DateTime.now } </div>&
          "#errorDisplay" #> { updateAndDisplayNotifications(formTrackerDisactivatePopup) }
      )(popupDisactivateForm)  
  }
  }
    
  def showCrForm() : NodeSeq = {    
    (
      ClearClearable &
      //all the top level action are displayed only if the template is on the user library
      "#userTemplateAction" #> { xml:NodeSeq => currentActiveTechnique match {
        case e:EmptyBox => NodeSeq.Empty
        case Full(activeTechnique) => (
          ClearClearable &
          //activation button: show disactivate if activated
          "#disableButtonLabel" #> { if(activeTechnique.isEnabled) "Disable" else "Enable" } &
          "#dialogDisableTitle" #> { if(activeTechnique.isEnabled) "Disable" else "Enable" } &
          "#dialogdisableWarning" #> dialogDisableWarning(activeTechnique) &
          ".reasonsFieldset" #> { crReasons.map { f =>
            "#explanationMessage" #> <div>{userPropertyService.reasonsFieldExplanation}</div> &
            "#reasonsField" #> f.toForm_!
          } } 
        )(xml)
      } } &
      "#techniqueName" #> technique.name &
      "#compatibility" #> (if (!technique.compatible.isEmpty) technique.compatible.head.toHtml else NodeSeq.Empty) &
      "#techniqueDescription" #>  technique.description &
      "#techniqueLongDescription" #>  technique.longDescription &
      "#breadcrumpReferenceCategory" #> showReferenceLibBreadcrump &
      "#clientCategoryZone" #> showTechniqueUserCategory &
      "#templateParameters" #> showParameters() &
      "#isSingle *" #> showIsSingle &
      "#editForm [id]" #> htmlId_technique
    )(crForm) ++ 
    Script(OnLoad(JsRaw("""
      correctButtons();
      
      $('#deleteButton').click(function() {
        createPopup("deleteActionDialog",140,400);
        return false;
      });

      $('#disableButton').click(function() {
        createPopup("disableActionDialog",140,500);
        return false;
      });
    """)))
  }
  
  private[this] val crReasons = {
    import com.normation.rudder.web.services.ReasonBehavior._
    userPropertyService.reasonsFieldBehavior match {
      case Disabled => None
      case Mandatory => Some(buildReasonField(true, "subContainerReasonField"))
      case Optionnal => Some(buildReasonField(false, "subContainerReasonField"))
    }
  }
  
  private[this] val crReasonsRemovePopup = {
    import com.normation.rudder.web.services.ReasonBehavior._
    userPropertyService.reasonsFieldBehavior match {
      case Disabled => None
      case Mandatory => Some(buildReasonField(true, "subContainerReasonField"))
      case Optionnal => Some(buildReasonField(false, "subContainerReasonField"))
    }
  }
  
  private[this] val crReasonsDisablePopup = {
    import com.normation.rudder.web.services.ReasonBehavior._
    userPropertyService.reasonsFieldBehavior match {
      case Disabled => None
      case Mandatory => Some(buildReasonField(true, "subContainerReasonField"))
      case Optionnal => Some(buildReasonField(false, "subContainerReasonField"))
    }
  }
    
  
  def buildReasonField(mandatory:Boolean, containerClass:String = "twoCol") = {
    new WBTextAreaField("Message", "") {
      override def setFilter = notNull _ :: trim _ :: Nil
      override def inputField = super.inputField  % 
        ("style" -> "height:8em;")
      override def subContainerClassName = containerClass
      override def validations() = {
        if(mandatory){
          valMinLen(5, "The reason must have at least 5 characters.") _ :: Nil
        } else {
          Nil
        }
      }
    }
  }
    
  private[this] val formTracker = {
    new FormTracker(crReasons.toList) 
  }
  
  private[this] val formTrackerRemovePopup = {
    new FormTracker(crReasonsRemovePopup.toList) 
  }
  
  private[this] val formTrackerDisactivatePopup = {
    new FormTracker(crReasonsDisablePopup.toList) 
  }
  
  ////////////// Callbacks //////////////
  
  private[this] def onSuccess() : JsCmd = {
    //MUST BE THIS WAY, because the parent may change some reference to JsNode
    //and so, our AJAX could be broken
    cleanTrackers
    onSuccessCallback() & updateFormClientSide() & 
    //show success popup
    successPopup
  }
  
  private[this] def cleanTrackers() {
    formTracker.clean
    formTrackerRemovePopup.clean
    formTrackerDisactivatePopup.clean
  }
  
  private[this] def onFailure() : JsCmd = {
    formTracker.addFormError(error("The form contains some errors, please correct them"))
    updateFormClientSide()
  }
  
  private[this] def onFailureRemovePopup() : JsCmd = {
    val elemError = error("The form contains some errors, please correct them")
    formTrackerRemovePopup.addFormError(elemError)
    updateRemoveFormClientSide()
  }
  
  private[this] def onFailureDisablePopup() : JsCmd = {
    val elemError = error("The form contains some errors, please correct them")
    formTrackerDisactivatePopup.addFormError(elemError)
    updateDisableFormClientSide()
  }
  
  private[this] def updateRemoveFormClientSide() : JsCmd = {
    val jsDisplayRemoveDiv = JsRaw("""$("#deleteActionDialog").removeClass('nodisplay')""")
    Replace("deleteActionDialog", this.showRemovePopupForm()) & 
    jsDisplayRemoveDiv &
    initJs
  }
  
  private[this] def updateDisableFormClientSide() : JsCmd = {
    val jsDisplayDisableDiv = JsRaw("""$("#disableActionDialog").removeClass('nodisplay')""")
    Replace("disableActionDialog", this.showDisactivatePopupForm()) & 
    jsDisplayDisableDiv &
    initJs
  }
  
  def initJs : JsCmd = {
    JsRaw("correctButtons();")
  }
  
  ///////////// Delete ///////////// 
    
  private[this] def deleteButton(id:ActiveTechniqueId) : Elem = {
    
    def deleteActiveTechnique() : JsCmd = {
      if(formTrackerRemovePopup.hasErrors) {
        onFailureRemovePopup
      } else {
        JsRaw("$.modal.close();") & 
        { 
          (for {
            deleted <- dependencyService.cascadeDeleteTechnique(id, CurrentUser.getActor, crReasonsRemovePopup.map (_.is))
            deploy <- {
              asyncDeploymentAgent ! AutomaticStartDeployment(RudderEventActor)
              Full("Deployment request sent")
            }
          } yield {
            deploy 
          }) match {
            case Full(x) => 
              formTrackerRemovePopup.clean
              onSuccessCallback() & 
              SetHtml(htmlId_technique, <div id={htmlId_technique}>Technique successfully deleted</div> ) & 
              //show success popup
              successPopup 
            case Empty => //arg. 
              formTrackerRemovePopup.addFormError(error("An error occurred while deleting the Technique."))
              onFailure
            case Failure(m,_,_) =>
              formTrackerRemovePopup.addFormError(error("An error occurred while deleting the Technique: " + m))
              onFailure
          }
        }
      }
    }
    
    SHtml.ajaxSubmit("Delete", deleteActiveTechnique _ )
  }
  
  private[this] def dialogDeleteTree(htmlId:String,activeTechnique:ActiveTechnique) : NodeSeq = {
    (new TechniqueTree(htmlId,activeTechnique.id, DontCare)).tree
  }  
  
  ///////////// Enable / disable /////////////

  private[this] def disableButton(activeTechnique:ActiveTechnique) : Elem = {
    def switchActivation(status:Boolean)() : JsCmd = {
      if(formTrackerDisactivatePopup.hasErrors) {
        onFailureDisablePopup
      } else {
        currentActiveTechnique = currentActiveTechnique.map( 
            activeTechnique => activeTechnique.copy(isEnabled = status))
        JsRaw("$.modal.close();") & 
        statusAndDeployTechnique(activeTechnique.id, status)
      }
    }
    
    if(activeTechnique.isEnabled) {
      SHtml.ajaxSubmit("Disable", switchActivation(false) _ )
    } else {
      SHtml.ajaxSubmit("Enable", switchActivation(true) _ )
    }
  }
 

  private[this] def dialogDisableWarning(activeTechnique:ActiveTechnique) : NodeSeq = {
    if(activeTechnique.isEnabled) {
      <h2>Disabling this Technique will also affect the following Directives and Rules.</h2>
    } else {
      <h2>Enabling this Technique will also affect the following Directives and Rules.</h2>
    }
  }

  private[this] def dialogDisableTree(htmlId:String,activeTechnique:ActiveTechnique) : NodeSeq = {
    val switchFilterStatus = if(activeTechnique.isEnabled) OnlyDisableable else OnlyEnableable
    (new TechniqueTree(htmlId,activeTechnique.id,switchFilterStatus)).tree
  }
 
  
  /////////////////////////////////////////////////////////////////////////


  def showReferenceLibBreadcrump() : NodeSeq = {
    <ul class="inlinenotop">{findBreadCrump(technique).map { cat => 
      <li class="inlineml">&#187; {cat.name}</li> } }
    </ul>
  }
  
  /**
   * Display user library category information in the details of a Technique.
   * That detail has its own snippet because it is multi-stated and state are 
   * updated by ajax:
   * - display the category breadcrump if Technique is already in user lib;
   * - display a "click on a category" message if not set in user lib and no category previously chosen
   * - else display an add button to add in the current category
   */
  def showTechniqueUserCategory() : NodeSeq = {
    <div id={htmlId_addToActiveTechniques}>Client category: { 
        findUserBreadCrump(technique) match {
          case Some(listCat) => 
            <ul class="inlinenotop">
                {listCat.map { cat => <li class="inlineml">&#187; {cat.name}</li> } } 
            </ul>
          case None => //display the add button if a user lib category is defined
            userCategoryLibrary match {
              case None => <span class="greenscala">Click on a category in the user library</span>
              case Some(category) => {
                /*
                 * Actually add the Technique to category:
                 * - add it in the backend storage
                 * - trigger user lib js tree update
                 * - trigger Technique details update
                 */
                def onClickAddTechniqueToCategory() : JsCmd = {
                  //back-end action
                  activeTechniqueRepository.addTechniqueInUserLibrary(
                      category.id
                    , technique.id.name
                    , techniqueRepository.getTechniqueVersions(technique.id.name).toSeq
                    , CurrentUser.getActor
                    , Some("User added a technique from UI")
                  ) 
                  
                  //update UI
                  Replace(htmlId_addToActiveTechniques, showTechniqueUserCategory() ) &
                  onSuccessCallback()
                }
  
                SHtml.ajaxButton(
                   Text("Add this Technique to user library category ")++ <b>{category.name}</b>,
                   onClickAddTechniqueToCategory _
                )
              }
            }
          }
    }</div>
  }
  
  private[this] def showParameters() : NodeSeq = {
    directiveEditorService.get(technique.id, DirectiveId("just-for-read-only")) match {
      case Full(pe) => pe.toHtmlNodeSeq
      case e:EmptyBox => 
        val msg = "Error when fetching parameter of Technique."
        logger.error(msg, e)
        <span class="error">{msg}</span>
    }    
  }
  
  private[this] def showIsSingle() : NodeSeq = {
    <span>
      {
        if(technique.isMultiInstance) {
          {<b>Multi instance</b>} ++ Text(": several Directives derived from that template can be deployed on a given node")
        } else {
          {<b>Unique</b>} ++ Text(": an unique Directive derived from that template can be deployed on a given node")
        }
      }
    </span>
  }
  
  /**
   * Display details about a Technique.
   * A Technique is in the context of a reference library (it's an error if the Directive 
   * template is not in it) and an user Library (it may not be in it)
   */
//  private def showTechnique(
//      technique : Technique, 
//      referenceLib:TechniqueCategory,
//      userLib:ActiveTechniqueCategory
//  ) : NodeSeq = {
//    <div id={htmlId_techniqueConf} class="object-details">
//    <h3>{technique.name}</h3>
//    <h4>{technique.description}</h4>
//    <p>{technique.longDescription}</p>
//
//    <fieldset><legend>Category</legend>
//      <div>Reference category: <a href="#" onclick="alert('TODO:goto node in tree');return false">
//        <ul class="inline">{findBreadCrump(technique).map { cat => 
//          <li class="inlineml">&#187; {cat.name}</li> } }
//        </ul>       
//      </a></div>
//      <lift:configuration.TechniqueLibraryManagement.showTechniqueUserCategory />
//    </fieldset>  
//
//    <fieldset><legend>Parameters</legend>
//    {
//      directiveEditorService.get(technique.id, DirectiveId("just-for-read-only")) match {
//        case Full(pe) => pe.toHtml
//        case _ => <span class="error">TODO</span>
//      }
//          
//    }
//    </fieldset>
//
//    <span>{<input type="checkbox" name="Single" disabled="disabled" /> % {if(technique.isMultiInstance) Null else ("checked" -> "checked") } }
//      Single</span>  
//
//    <fieldset><legend>Actions</legend>
//      <input type="submit" value="Delete from user library"/>
//    </fieldset>
//    </div>
//  }
  

  /**
   * Build the breadcrump of categories that leads to given target Technique in the context
   * of given root Technique library.
   * The template must be in the library. 
   * @throws 
   *   RuntimeException if the library does not contain the Technique
   */
  @throws(classOf[RuntimeException])
  private def findBreadCrump(target:Technique) : Seq[TechniqueCategory] = {
    techniqueRepository.getTechniqueCategoriesBreadCrump(target.id) match {
      case Full(b) => b
      case e:EmptyBox => 
        logger.debug("Bread crumb error: %s".format(e) )
        throw new RuntimeException("The reference Technique category does not hold target node %s".format(target.name))
    }
  }
  
  /**
   * Build the breadcrump of categories that leads to given target Technique in the context
   * of given root Active Technique library.
   * The template may not be present in the library. 
   */
  private def findUserBreadCrump(target:Technique) : Option[List[ActiveTechniqueCategory]] = {
    //find the potential WBUsreTechnique for given WBTechnique
    ( for {
      activeTechnique <- activeTechniqueRepository.getActiveTechnique(target.id.name) 
      crump <- activeTechniqueRepository.activeTechniqueBreadCrump(activeTechnique.id) 
    } yield {
      crump.reverse
    } ) match {
      case Full(b) => Some(b)
      case e:EmptyBox => 
        logger.debug("User bread crumb error: %s".format(e) )
        None
    }
  }
  
     
  /////////////////////////////////////////////////////////////////////////
  /////////////////////////////// Edit form ///////////////////////////////
  /////////////////////////////////////////////////////////////////////////


  private[this] def updateFormClientSide() : JsCmd = {
    SetHtml(htmlId_technique, this.showCrForm )
  }
  
  private[this] def error(msg:String) = <span class="error">{msg}</span>

  
  private[this] def statusAndDeployTechnique(uactiveTechniqueId:ActiveTechniqueId, status:Boolean) : JsCmd = {
      (for {
        save <- activeTechniqueRepository.changeStatus(uactiveTechniqueId, status, 
                  CurrentUser.getActor, crReasonsDisablePopup.map(_.is))
        deploy <- {
          asyncDeploymentAgent ! AutomaticStartDeployment(RudderEventActor)
          Full("Deployment request sent")
        }
      } yield {
        save 
      }) match {
        case Full(x) => onSuccess
        case Empty => 
          formTracker.addFormError(error("An error occurred while saving the Technique"))
          onFailure
        case f:Failure =>
          formTracker.addFormError(error("An error occurred while saving the Technique: " + f.messageChain))
          onFailure
      }      
  }
  
  private[this] def updateAndDisplayNotifications(formTracker : FormTracker) : NodeSeq = {
    val notifications = formTracker.formErrors
    formTracker.cleanErrors
    if(notifications.isEmpty) {
      NodeSeq.Empty
    }
    else {
      val html = <div id="notifications" class="notify">
        <ul class="field_errors">{notifications.map( n => <li>{n}</li>) }</ul></div>
      html
    }
  }
 
  
  ///////////// success pop-up ///////////////
    private[this] def successPopup : JsCmd = {
    JsRaw(""" callPopupWithTimeout(200, "successConfirmationDialog", 100, 350)     
    """)
  }
    
  
}
  
  
  
