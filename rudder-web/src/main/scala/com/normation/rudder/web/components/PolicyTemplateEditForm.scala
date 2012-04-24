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
  PolicyPackageId,PolicyPackage,
  PolicyPackageCategoryId, PolicyPackageCategory
}
import com.normation.rudder.repository._
import com.normation.cfclerk.services.PolicyPackageService
import com.normation.rudder.web.services.PolicyEditorService
import com.normation.rudder.services.policies._
import com.normation.rudder.batch.{AsyncDeploymentAgent,StartDeployment}
import com.normation.rudder.domain.log.RudderEventActor

object PolicyTemplateEditForm {
  
  /**
   * This is part of component static initialization.
   * Any page which contains (or may contains after an ajax request)
   * that component have to add the result of that method in it.
   */
  def staticInit:NodeSeq = 
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentPolicyTemplateEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "staticInit", xml)
    }) openOr Nil
    
  private def body = 
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentPolicyTemplateEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "body", xml)
    }) openOr Nil
  
  val htmlId_policyTemplateConf = "policyTemplateConfiguration"
  val htmlId_addPopup = "addPopup"
  val htmlId_addToUserLib = "addToUserLib"
  val htmlId_userCategoryDetails = "userCategoryDetails"
  val htmlId_addUserCategoryForm = "addUserCategoryForm"
}

/**
 * showPolicyTemplate(pt, policyPackageCategoryService.getReferencePolicyTemplateLibrary, userPolicyTemplateCategoryRepository.getUserPolicyTemplateLibrary)
 */
class PolicyTemplateEditForm(
  htmlId_policyTemplate:String, //HTML id for the div around the form
  val policyTemplate:PolicyPackage,
  userCategoryLibrary:Option[UserPolicyTemplateCategory],
  //JS to execute on form success (update UI parts)
  //there are call by name to have the context matching their execution when called
  onSuccessCallback : () => JsCmd = { () => Noop },
  onFailureCallback : () => JsCmd = { () => Noop }
) extends DispatchSnippet with Loggable {
  import PolicyTemplateEditForm._
  

  //find policy template
  private[this] val policyPackageService = inject[PolicyPackageService]
  //find & create user categories
  private[this] val userPolicyTemplateCategoryRepository = inject[UserPolicyTemplateCategoryRepository]
  //find & create user policy templates
  private[this] val userPolicyTemplateRepository = inject[UserPolicyTemplateRepository]
  //generate new uuid
  private[this] val uuidGen = inject[StringUuidGenerator]
  //transform policy template variable to human viewable HTML fields
  private[this] val policyEditorService = inject[PolicyEditorService]
  private[this] val dependencyService = inject[DependencyAndDeletionService]
  private[this] val asyncDeploymentAgent = inject[AsyncDeploymentAgent]


  
//  private[this] val pis = policyInstanceRepository.getAll() match {
//    case Full(seq) => seq
//    case Empty => throw new ComponentInitializationException(Failure("Error while getting the list of available policies"))
//    case f:Failure => throw new ComponentInitializationException(f)
//  }
    
  
  private[this] var currentUpt = userPolicyTemplateRepository.getUserPolicyTemplate(policyTemplate.id.name)
  private[this] var uptCurrentStatusIsActivated = currentUpt.map( _.isActivated)
  
 
  //////////////////////////// public methods ////////////////////////////

  def dispatch = { 
    case "showForm" => { _:NodeSeq => showForm }
  }

  def showForm() : NodeSeq = {    
    (
      ClearClearable &
      //all the top level action are displayed only if the template is on the user library
      "#userTemplateAction" #> { xml:NodeSeq => currentUpt match {
        case e:EmptyBox => NodeSeq.Empty
        case Full(upt) => (
          ClearClearable &
          //activation button: show disactivate if activated
          "#disableButtonLabel" #> { if(upt.isActivated) "Disable" else "Enable" } &
          "#dialogDisableButton" #> { disableButton(upt) % ("id", "disableButton") } &
          "#dialogDisableTitle" #> { if(upt.isActivated) "Disable" else "Enable" } &
          "#dialogDisableLabel" #> { if(upt.isActivated) "disable" else "enable" } &
          "#dialogdisableWarning" #> dialogDisableWarning(upt) &
          "#disableItemDependencies *" #> dialogDisableTree("disableItemDependencies", upt) & 
          "#dialogDeleteButton" #> { deleteButton(upt.id) % ("id", "deleteButton") } &
          "#deleteItemDependencies *" #> dialogDeleteTree("deleteItemDependencies", upt)
        )(xml)
      } } &
      "#policyTemplateName" #> policyTemplate.name &
      "#compatibility" #> (if (!policyTemplate.compatible.isEmpty) policyTemplate.compatible.head.toHtml else NodeSeq.Empty) &
      "#policyTemplateDescription" #>  policyTemplate.description &
      "#policyTemplateLongDescription" #>  policyTemplate.longDescription &
      "#breadcrumpReferenceCategory" #> showReferenceLibBreadcrump &
      "#clientCategoryZone" #> showPolicyTemplateUserCategory &
      "#templateParameters" #> showParameters() &
      "#isSingle *" #> showIsSingle &
      "#editForm [id]" #> htmlId_policyTemplate
    )(body) ++ 
    Script(OnLoad(JsRaw("""
      correctButtons();
      
      $('#deleteButton').click(function() {
        $('#deleteActionDialog').modal({
          minHeight:140,
          minWidth: 850,
      		maxWidth: 850
        });
        $('#simplemodal-container').css('height', 'auto');
        return false;
      });

      $('#disableButton').click(function() {
        $('#disableActionDialog').modal({
          minHeight:100,
          minWidth: 850,
      		maxWidth: 850
        });
        $('#simplemodal-container').css('height', 'auto');
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
    
  private[this] def deleteButton(id:UserPolicyTemplateId) : Elem = {
    def deleteUpt() : JsCmd = {
      JsRaw("$.modal.close();") & 
      { 
        (for {
          deleted <- dependencyService.cascadeDeletePolicyTemplate(id, CurrentUser.getActor)
          deploy <- {
            asyncDeploymentAgent ! StartDeployment(RudderEventActor)
            Full("Deployment request sent")
          }
        } yield {
          deploy 
        }) match {
          case Full(x) => 
            onSuccessCallback() & 
            SetHtml(htmlId_policyTemplate, <div id={htmlId_policyTemplate}>Configuration rule successfully deleted</div> ) & 
            //show success popup
            successPopup 
          case Empty => //arg. 
            //formTracker.addFormError(error("An error occurred while saving the configuration rule"))
            onFailure
          case Failure(m,_,_) =>
            //formTracker.addFormError(error("An error occurred while saving the configuration rule: " + m))
            onFailure
        }
      }
    }
    
    SHtml.ajaxButton(<span class="red">Delete</span>, deleteUpt _ )
  }
  
  private[this] def dialogDeleteTree(htmlId:String,upt:UserPolicyTemplate) : NodeSeq = {
    (new PolicyTemplateTree(htmlId,upt.id, DontCare)).tree
  }  
  
  ///////////// Enable / disable /////////////

  private[this] def disableButton(upt:UserPolicyTemplate) : Elem = {
    def switchActivation(status:Boolean)() : JsCmd = {
      currentUpt = currentUpt.map( upt => upt.copy(isActivated = status))
      JsRaw("$.modal.close();") & 
      statusAndDeployPolicyTemplate(upt.id, status)
    }
    
    if(upt.isActivated) {
      SHtml.ajaxButton(<span class="red">Disable</span>, switchActivation(false) _ )
    } else {
      SHtml.ajaxButton(<span class="red">Enable</span>, switchActivation(true) _ )
    }
  }
 

  private[this] def dialogDisableWarning(upt:UserPolicyTemplate) : NodeSeq = {
    if(upt.isActivated) {
      <h2>Disabling this policy template will affect the following policies and configuration rules.</h2>
    } else {
      <h2>Enabling this policy template will affect the following policies and configuration.</h2>
    }
  }

  private[this] def dialogDisableTree(htmlId:String,upt:UserPolicyTemplate) : NodeSeq = {
    val switchFilterStatus = if(upt.isActivated) OnlyDisableable else OnlyEnableable
    (new PolicyTemplateTree(htmlId,upt.id,switchFilterStatus)).tree
  }
 
  
  /////////////////////////////////////////////////////////////////////////


  def showReferenceLibBreadcrump() : NodeSeq = {
    <ul style="display:inline;padding-top:0px;">{findBreadCrump(policyTemplate).map { cat => 
      <li style="display:inline; margin-left:3px;">&#187; {cat.name}</li> } }
    </ul>
  }
  
  /**
   * Display user library category information in the details of a Policy Template.
   * That detail has its own snippet because it is multi-stated and state are 
   * updated by ajax:
   * - display the category breadcrump if policy template is already in user lib;
   * - display a "click on a category" message if not set in user lib and no category previously chosen
   * - else display an add button to add in the current category
   */
  def showPolicyTemplateUserCategory() : NodeSeq = {
    <div id={htmlId_addToUserLib}>Client category: { 
        findUserBreadCrump(policyTemplate) match {
          case Some(listCat) => 
            <ul style="display:inline;padding-top:0px;">
                {listCat.map { cat => <li style="display:inline; margin-left:3px;">&#187; {cat.name}</li> } } 
            </ul>
          case None => //display the add button if a user lib category is defined
            userCategoryLibrary match {
              case None => <span style="color: green;">Click on a category in the user library</span>
              case Some(category) => {
                /*
                 * Actually add the policy template to category:
                 * - add it in the backend storage
                 * - trigger user lib js tree update
                 * - trigger policy template details update
                 */
                def onClickAddPolicyTemplateToCategory() : JsCmd = {
                  //back-end action
                  userPolicyTemplateRepository.addPolicyTemplateInUserLibrary(category.id, policyTemplate.id.name, policyPackageService.getVersions(policyTemplate.id.name).toSeq) 
                  
                  //update UI
                  Replace(htmlId_addToUserLib, showPolicyTemplateUserCategory() ) &
                  onSuccessCallback()
                }
  
                SHtml.ajaxButton(
                   Text("Add this policy template to user library category ")++ <b>{category.name}</b>,
                   onClickAddPolicyTemplateToCategory _
                )
              }
            }
          }
    }</div>
  }
  
  private[this] def showParameters() : NodeSeq = {
    policyEditorService.get(policyTemplate.id, PolicyInstanceId("just-for-read-only")) match {
      case Full(pe) => pe.toHtmlNodeSeq
      case e:EmptyBox => 
        val msg = "Error when fetching parameter of policy template."
        logger.error(msg, e)
        <span class="error">{msg}</span>
    }    
  }
  
  private[this] def showIsSingle() : NodeSeq = {
    <span>
      {
        if(policyTemplate.isMultiInstance) {
          {<b>Multi instance</b>} ++ Text(": several policies derived from that template can be deployed on a given server")
        } else {
          {<b>Unique</b>} ++ Text(": an unique policy derived from that template can be deployed on a given server")
        }
      }
    </span>
  }
  
  /**
   * Display details about a policy template.
   * A policy template is in the context of a reference library (it's an error if the policy 
   * template is not in it) and an user Library (it may not be in it)
   */
//  private def showPolicyTemplate(
//      pt : PolicyPackage, 
//      referenceLib:PolicyPackageCategory,
//      userLib:UserPolicyTemplateCategory
//  ) : NodeSeq = {
//    <div id={htmlId_policyTemplateConf} class="object-details">
//    <h3>{pt.name}</h3>
//    <h4>{pt.description}</h4>
//    <p>{pt.longDescription}</p>
//
//    <fieldset><legend>Category</legend>
//      <div>Reference category: <a href="#" onclick="alert('TODO:goto node in tree');return false">
//        <ul style="display:inline;">{findBreadCrump(policyTemplate).map { cat => 
//          <li style="display:inline; margin-left:3px;">&#187; {cat.name}</li> } }
//        </ul>       
//      </a></div>
//      <lift:PolicyTemplateLibraryManagement.showPolicyTemplateUserCategory />
//    </fieldset>  
//
//    <fieldset><legend>Parameters</legend>
//    {
//      policyEditorService.get(pt.id, PolicyInstanceId("just-for-read-only")) match {
//        case Full(pe) => pe.toHtml
//        case _ => <span class="error">TODO</span>
//      }
//          
//    }
//    </fieldset>
//
//    <span>{<input type="checkbox" name="Single" disabled="disabled" /> % {if(pt.isMultiInstance) Null else ("checked" -> "checked") } }
//      Single</span>  
//
//    <fieldset><legend>Actions</legend>
//      <input type="submit" value="Delete from user library"/>
//    </fieldset>
//    </div>
//  }
  

  /**
   * Build the breadcrump of categories that leads to given target policy template in the context
   * of given root policy template library.
   * The template must be in the library. 
   * @throws 
   *   RuntimeException if the library does not contain the policy template
   */
  @throws(classOf[RuntimeException])
  private def findBreadCrump(target:PolicyPackage) : Seq[PolicyPackageCategory] = {
    policyPackageService.getPolicyTemplateBreadCrump(target.id) match {
      case Full(b) => b
      case e:EmptyBox => 
        logger.debug("Bread crumb error: %s".format(e) )
        throw new RuntimeException("The reference policy template category does not hold target node %s".format(target.name))
    }
  }
  
  /**
   * Build the breadcrump of categories that leads to given target policy template in the context
   * of given root user policy template library.
   * The template may not be present in the library. 
   */
  private def findUserBreadCrump(target:PolicyPackage) : Option[List[UserPolicyTemplateCategory]] = {
    //find the potential WBUsrePolicyTemplate for given WBPolicyTemplate
    ( for {
      upt <- userPolicyTemplateRepository.getUserPolicyTemplate(target.id.name) 
      crump <- userPolicyTemplateRepository.userPolicyTemplateBreadCrump(upt.id) 
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
    SetHtml(htmlId_policyTemplate, this.showForm )
  }
  
  private[this] def error(msg:String) = <span class="error">{msg}</span>

  
  private[this] def statusAndDeployPolicyTemplate(uptId:UserPolicyTemplateId, status:Boolean) : JsCmd = {
      (for {
        save <- userPolicyTemplateRepository.changeStatus(uptId, status)
        deploy <- {
          asyncDeploymentAgent ! StartDeployment(RudderEventActor)
          Full("Deployment request sent")
        }
      } yield {
        deploy 
      }) match {
        case Full(x) => onSuccess
        case Empty => //arg. 
         // formTracker.addFormError(error("An error occurred while saving the configuration rule"))
          onFailure
        case f:Failure =>
         // formTracker.addFormError(error("An error occurred while saving the configuration rule: " + f.messageChain))
          onFailure
      }      
  }
 
  
  ///////////// success pop-up ///////////////
  private[this] def successPopup : JsCmd = {
    JsRaw("""
      setTimeout(function() { $("#succesConfirmationDialog").modal({
        minHeight:100,
        minWidth: 350
      });
       $('#simplemodal-container').css('height', 'auto');}, 200);
    """)
  }
    
  
}
  
  
  
