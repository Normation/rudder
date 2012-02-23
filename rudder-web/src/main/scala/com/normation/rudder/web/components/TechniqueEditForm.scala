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
import com.normation.rudder.repository._
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies._
import net.liftweb.http.js._
import JsCmds._ // For implicits
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
import com.normation.rudder.domain.log.RudderEventActor

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
  
  val htmlId_techniqueConf = "techniqueConfiguration"
  val htmlId_addPopup = "addPopup"
  val htmlId_addToUserLib = "addToUserLib"
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
      ClearClearable &
      //all the top level action are displayed only if the template is on the user library
      "#userTemplateAction" #> { xml:NodeSeq => currentActiveTechnique match {
        case e:EmptyBox => NodeSeq.Empty
        case Full(activeTechnique) => (
          ClearClearable &
          //activation button: show disactivate if activated
          "#disableButtonLabel" #> { if(activeTechnique.isEnabled) "Disable" else "Enable" } &
          "#dialogDisableButton" #> { disableButton(activeTechnique) % ("id", "disableButton") } &
          "#dialogDisableTitle" #> { if(activeTechnique.isEnabled) "Disable" else "Enable" } &
          "#dialogDisableLabel" #> { if(activeTechnique.isEnabled) "disable" else "enable" } &
          "#dialogdisableWarning" #> dialogDisableWarning(activeTechnique) &
          "#disableItemDependencies *" #> dialogDisableTree("disableItemDependencies", activeTechnique) & 
          "#dialogDeleteButton" #> { deleteButton(activeTechnique.id) % ("id", "deleteButton") } &
          "#deleteItemDependencies *" #> dialogDeleteTree("deleteItemDependencies", activeTechnique)
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
    )(body) ++ 
    Script(OnLoad(JsRaw("""
      correctButtons();
      
      $('#deleteButton').click(function() {
        createPopup("deleteActionDialog",140,850);
        return false;
      });

      $('#disableButton').click(function() {
        createPopup("disableActionDialog",100,850);
        return false;
      });
    """)))
  }

  ////////////// Callbacks //////////////
  
  private[this] def onSuccess() : JsCmd = {
    //MUST BE THIS WAY, because the parent may change some reference to JsNode
    //and so, our AJAX could be broken
    onSuccessCallback() & updateFormClientSide() & 
    //show success popup
    successPopup
  }
  
  private[this] def onFailure() : JsCmd = {
    onFailureCallback() & updateFormClientSide()
  }
  
  ///////////// Delete ///////////// 
    
  private[this] def deleteButton(id:ActiveTechniqueId) : Elem = {
    def deleteActiveTechnique() : JsCmd = {
      JsRaw("$.modal.close();") & 
      { 
        (for {
          deleted <- dependencyService.cascadeDeleteTechnique(id, CurrentUser.getActor)
          deploy <- {
            asyncDeploymentAgent ! AutomaticStartDeployment(RudderEventActor)
            Full("Deployment request sent")
          }
        } yield {
          deploy 
        }) match {
          case Full(x) => 
            onSuccessCallback() & 
            SetHtml(htmlId_technique, <div id={htmlId_technique}>Rule successfully deleted</div> ) & 
            //show success popup
            successPopup 
          case Empty => //arg. 
            //formTracker.addFormError(error("An error occurred while saving the Rule"))
            onFailure
          case Failure(m,_,_) =>
            //formTracker.addFormError(error("An error occurred while saving the Rule: " + m))
            onFailure
        }
      }
    }
    
    SHtml.ajaxButton(<span class="red">Delete</span>, deleteActiveTechnique _ )
  }
  
  private[this] def dialogDeleteTree(htmlId:String,activeTechnique:ActiveTechnique) : NodeSeq = {
    (new TechniqueTree(htmlId,activeTechnique.id, DontCare)).tree
  }  
  
  ///////////// Enable / disable /////////////

  private[this] def disableButton(activeTechnique:ActiveTechnique) : Elem = {
    def switchActivation(status:Boolean)() : JsCmd = {
      currentActiveTechnique = currentActiveTechnique.map( activeTechnique => activeTechnique.copy(isEnabled = status))
      JsRaw("$.modal.close();") & 
      statusAndDeployTechnique(activeTechnique.id, status)
    }
    
    if(activeTechnique.isEnabled) {
      SHtml.ajaxButton(<span class="red">Disable</span>, switchActivation(false) _ )
    } else {
      SHtml.ajaxButton(<span class="red">Enable</span>, switchActivation(true) _ )
    }
  }
 

  private[this] def dialogDisableWarning(activeTechnique:ActiveTechnique) : NodeSeq = {
    if(activeTechnique.isEnabled) {
      <h2>Disabling this Technique will also disable the following Directives and Rules which depend on it.</h2>
    } else {
      <h2>Enabling this Technique will also enable the following Directives and Rules which depend on it.</h2>
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
    <div id={htmlId_addToUserLib}>Client category: { 
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
                  activeTechniqueRepository.addTechniqueInUserLibrary(category.id, technique.id.name, techniqueRepository.getTechniqueVersions(technique.id.name).toSeq, CurrentUser.getActor) 
                  
                  //update UI
                  Replace(htmlId_addToUserLib, showTechniqueUserCategory() ) &
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
          {<b>Multi instance</b>} ++ Text(": several Directives derived from that template can be deployed on a given server")
        } else {
          {<b>Unique</b>} ++ Text(": an unique Directive derived from that template can be deployed on a given server")
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
    SetHtml(htmlId_technique, this.showForm )
  }
  
  private[this] def error(msg:String) = <span class="error">{msg}</span>

  
  private[this] def statusAndDeployTechnique(uactiveTechniqueId:ActiveTechniqueId, status:Boolean) : JsCmd = {
      (for {
        save <- activeTechniqueRepository.changeStatus(uactiveTechniqueId, status, CurrentUser.getActor)
        deploy <- {
          asyncDeploymentAgent ! AutomaticStartDeployment(RudderEventActor)
          Full("Deployment request sent")
        }
      } yield {
        deploy 
      }) match {
        case Full(x) => onSuccess
        case Empty => //arg. 
         // formTracker.addFormError(error("An error occurred while saving the Rule"))
          onFailure
        case f:Failure =>
         // formTracker.addFormError(error("An error occurred while saving the Rule: " + f.messageChain))
          onFailure
      }      
  }
 
  
  ///////////// success pop-up ///////////////
    private[this] def successPopup : JsCmd = {
    JsRaw(""" callPopupWithTimeout(200, "successConfirmationDialog", 100, 350)     
    """)
  }
    
  
}
  
  
  
