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
import com.normation.rudder.web.services.PolicyEditorService
import com.normation.cfclerk.domain.PolicyPackage
import net.liftweb.http.js._
import JsCmds._
import net.liftweb.util._
import Helpers._
import net.liftweb.http._
import com.normation.rudder.services.policies._
import com.normation.rudder.batch.{ AsyncDeploymentAgent, StartDeployment }
import com.normation.rudder.domain.log.RudderEventActor

// For implicits
import JE._
import net.liftweb.common._
import scala.xml._
import net.liftweb.util.Helpers._
import com.normation.rudder.web.model._
import com.normation.rudder.repository._
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import com.normation.rudder.domain.RudderLDAPConstants
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.domain.log._
import com.normation.eventlog.EventActor


object PolicyInstanceEditForm {

  /**
   * This is part of component static initialization.
   * Any page which contains (or may contains after an ajax request)
   * that component have to add the result of that method in it.
   */
  def staticInit: NodeSeq =
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentPolicyInstanceEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "staticInit", xml) ++
        ConfigurationRuleGrid.staticInit
    }) openOr Nil

  private def body =
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentPolicyInstanceEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "body", xml)
    }) openOr Nil

  private def itemDependencies =
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentPolicyInstanceEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "itemDependencies", xml)
    }) openOr Nil
}

/**
 * The form that handles policy instance edition
 * (not creation)
 * - update name, description, etc
 * - update parameters
 *
 * It handle save itself (TODO: see how to interact with the component parent)
 *
 * Parameters can not be null.
 *
 * Injection should not be used in components
 * ( WHY ? I will try to see...)
 *
 */
class PolicyInstanceEditForm(
  htmlId_policyConf: String, //HTML id for the div around the form
  policyTemplate: PolicyPackage,
  userPolicyTemplate: UserPolicyTemplate,
  val pi: PolicyInstance,
  //JS to execute on form success (update UI parts)
  //there are call by name to have the context matching their execution when called
  onSuccessCallback: () => JsCmd = { () => Noop },
  onFailureCallback: () => JsCmd = { () => Noop },
  piCreation: Boolean = false // if set to true, it means that we are creating a PI, so the delete and disabled should have another meaning
  ) extends DispatchSnippet with Loggable {
  import PolicyInstanceEditForm._

  private[this] val policyInstanceRepository = inject[PolicyInstanceRepository]
  private[this] val dependencyService = inject[DependencyAndDeletionService]
  private[this] val policyEditorService = inject[PolicyEditorService]
  private[this] val asyncDeploymentAgent = inject[AsyncDeploymentAgent]  

  private[this] val htmlId_save = htmlId_policyConf + "Save"
  private[this] val parameterEditor = policyEditorService.get(
    policyTemplateId = policyTemplate.id,
    policyInstanceId = pi.id,
    withVars = pi.parameters) match {
      case Full(pe) => pe
      case Empty =>
        throw new IllegalArgumentException("Can not initialize the parameter editor for policy instance %s (template %s). No error returned".format(pi.id, policyTemplate.id))
      case Failure(m, _, _) =>
        throw new IllegalArgumentException("Can not initialize the parameter editor for policy instance %s (template %s). Error message: %s".format(pi.id, policyTemplate.id, m))
    }

  private[this] var piCurrentStatusIsActivated = pi.isActivated

  def dispatch = {
    case "showForm" => { _ => showForm }
  }

  def showForm(): NodeSeq = {

    //pop-up: their content should be retrieve lazily
    val removePopupGridXml = if (piCreation) {
      val cmp = new ConfigurationRuleGrid("remove_popup_grid", Seq(), None, false)
      cmp.configurationRulesGrid(linkCompliancePopup = false)
    } else {
      dependencyService.policyInstanceDependencies(pi.id).map(_.configurationRules) match {
        case e: EmptyBox => <div class="error">An error occurred while trying to find dependent item</div>
        case Full(crs) => {
          val cmp = new ConfigurationRuleGrid("remove_popup_grid", crs, None, false)
          cmp.configurationRulesGrid(linkCompliancePopup = false)
        }
      }
    }

    //if pi current inherited status is "activated", the two pop-up (disable and remove) show
    //the same content. Else, we have to build two different pop-up
    val switchStatusFilter = if (pi.isActivated) OnlyDisableable else OnlyEnableable
    val disablePopupGridXml = dependencyService.policyInstanceDependencies(pi.id, switchStatusFilter).map(_.configurationRules) match {
      case e: EmptyBox => <div class="error">An error occurred while trying to find dependent item</div>
      case Full(crs) => {
        val cmp = new ConfigurationRuleGrid("disable_popup_grid", crs, None, false)
        cmp.configurationRulesGrid(linkCompliancePopup = false)
      }
    }

    (
      "#editForm *" #> { (n: NodeSeq) => SHtml.ajaxForm(n) } andThen
      ClearClearable &
      //activation button: show disactivate if activated
      "#disactivateButtonLabel" #> { if (piCurrentStatusIsActivated) "Disable" else "Enable" } &
      "#dialogDisactivateButton" #> { disableButton % ("id", "disactivateButton") } &
      "#dialogDisactivateLabel" #> { if (piCurrentStatusIsActivated) "disable" else "enable" } &
      "#dialogDisactivateWarning *" #> dialogDisableWarning &
      //remove
      "#dialogRemoveButton" #> { removeButton % ("id", "removeButton") } &
      //dependency grid
      "#removeItemDependencies" #> {
        (ClearClearable & "#itemDependenciesGrid" #> removePopupGridXml)(itemDependencies)
      } &
      "#disableItemDependencies" #> {
        (ClearClearable & "#itemDependenciesGrid" #> disablePopupGridXml)(itemDependencies)
      } &
      //form and form fields
      "#policyTemplateName" #> <a href={ "/secure/configurationManager/policyTemplateLibraryManagement/" + policyTemplate.id.name.value }>{ policyTemplate.name }</a> &
      "#policyTemplateDescription" #> policyTemplate.description &
      "#nameField" #> piName.toForm_! &
      "#shortDescriptionField" #> piShortDescription.toForm_! &
      "#longDescriptionField" #> piLongDescription.toForm_! &
      "#priority" #> piPriority.toForm_! &
      "#parameters" #> parameterEditor.toFormNodeSeq &
      "#save" #> { SHtml.ajaxSubmit("Save", onSubmit _) % ("id" -> htmlId_save) } &
      "#notification *" #> updateAndDisplayNotifications() &
      "#isSingle *" #> showIsSingle &
      "#editForm [id]" #> htmlId_policyConf)(body) ++ Script(OnLoad(
        JsRaw("""activateButtonOnFormChange("%s", "%s");  """.format(htmlId_policyConf, htmlId_save)) &
          JsRaw("""
        correctButtons();
        $('#removeButton').click(function() {
          $('#removeActionDialog').modal({
            minHeight:140,
            minWidth: 850,
      			maxWidth: 850
          });
          $('#simplemodal-container').css('height', 'auto');
          return false;
        });

        $('#disactivateButton').click(function() {
          $('#desactivateActionDialog').modal({
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

  private[this] def onSuccess(): JsCmd = {
    //MUST BE IN WAY, because the parent may change some reference to JsNode
    //and so, our AJAX could be broken
    onSuccessCallback() & updateFormClientSide() &
      //show success popup
      successPopup
  }

  private[this] def onFailure(): JsCmd = {
    formTracker.addFormError(error("The form contains some errors, please correct them"))
    onFailureCallback() & updateFormClientSide() & JsRaw("""scrollToElement("errorNotification");""")
  }

  ///////////// Remove /////////////

  private[this] def removeButton: Elem = {
      def removeCr(): JsCmd = {
        JsRaw("$.modal.close();") &
          {
            if (piCreation) {
              onSuccessCallback() &
                SetHtml(htmlId_policyConf, <div id={ htmlId_policyConf }>Policy instance successfully deleted</div>) &
                //show success popup
                successPopup
            } else {
              (for {
                deleted <- dependencyService.cascadeDeletePolicyInstance(pi.id, CurrentUser.getActor)
                deploy <- {
                  asyncDeploymentAgent ! StartDeployment(RudderEventActor)
                  Full("Deployment request sent")
                }
              } yield {
                deploy
              }) match {
                case Full(x) =>
                  onSuccessCallback() &
                    SetHtml(htmlId_policyConf, <div id={ htmlId_policyConf }>Policy instance successfully deleted</div>) &
                    //show success popup
                    successPopup
                case Empty => //arg.
                  formTracker.addFormError(error("An error occurred while deleting the policy instance (no more information)"))
                  onFailure
                case f@Failure(m, _, _) =>
                  val msg = (f ?~! "An error occurred while deleting the policy instance: ").messageChain
                  logger.debug(msg, f)
                  formTracker.addFormError(error(msg + m))
                  onFailure
              }
            }
          }
      }

    SHtml.ajaxButton(<span class="red">Delete</span>, removeCr _)
  }

  ///////////// Enable / disable /////////////

  private[this] def disableButton: Elem = {
      def switchActivation(status: Boolean)(): JsCmd = {
        piCurrentStatusIsActivated = status
        JsRaw("$.modal.close();") & {
          if (piCreation == true) {
            onSuccess
          } else {
            saveAndDeployPolicyInstance(pi.copy(isActivated = status))
          }
        }

      }

    if (piCurrentStatusIsActivated) {
      SHtml.ajaxButton(<span class="red">Disable</span>, switchActivation(false) _)
    } else {
      SHtml.ajaxButton(<span class="red">Enable</span>, switchActivation(true) _)
    }
  }

  private[this] def dialogDisableWarning: String = {
    if (piCurrentStatusIsActivated) {
      "Disabling this policy will also disable the following configuration rules which depend on it."
    } else {
      "Enabling this policy will also enable the following configuration rules which depend on it."
    }
  }

  private[this] def showIsSingle(): NodeSeq = {
    <span>
      {
        if (policyTemplate.isMultiInstance) {
          { <b>Multi instance</b> } ++ Text(": several policies derived from that template can be deployed on a given server")
        } else {
          { <b>Unique</b> } ++ Text(": an unique policy derived from that template can be deployed on a given server")
        }
      }
    </span>
  }

  /////////////////////////////////////////////////////////////////////////
  /////////////////////////////// Edit form ///////////////////////////////
  /////////////////////////////////////////////////////////////////////////

  ///////////// fields for Policy Instance settings ///////////////////

  private[this] val piName = new WBTextField("Name: ", pi.name) {
    override def displayNameHtml = Some(<b>{ displayName }</b>)
    override def setFilter = notNull _ :: trim _ :: Nil
    override def validations =
      valMinLen(3, "The name must have at least 3 characters") _ :: Nil
  }

  private[this] val piShortDescription = new WBTextField("Short description: ", pi.shortDescription) {
    override def displayNameHtml = Some(<b>{ displayName }</b>)
    override def setFilter = notNull _ :: trim _ :: Nil
    override val maxLen = 255
    override def validations = Nil
  }

  private[this] val piLongDescription = new WBTextAreaField("Description: ", pi.longDescription.toString) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField = super.inputField % ("style" -> "width:50em;height:15em")
  }

  private[this] val piPriority = new WBSelectObjField("Priority:",
    (0 to 10).map(i => (i, i.toString)),
    defaultValue = pi.priority) {
    override val displayHtml = <span class="tooltipable" tooltipid="priorityId" style="border-bottom:dotted 1px grey;">Priority:<div class="tooltipContent" id="priorityId">
                                                                                                                                  If a node is configured with several policy instance derived from that template, 
the one with the higher priority will be applied first. If several policy instances have
the same priority, the application order between these two will be random. 
If the template is unique, only one policy derived from it may be used at a given time on
one given node. The one with the highest priority is chosen. If several policies have
the same priority, one of them will be applied at random. You should always try
to avoid that last case.<br/>
                                                                                                                                  The highest priority is 0
                                                                                                                                </div></span>

  }

  private[this] val formTracker = new FormTracker(piName, piShortDescription, piLongDescription)

  private[this] var notifications = List.empty[NodeSeq]

  private[this] def updateFormClientSide(): JsCmd = {
    SetHtml(htmlId_policyConf, showForm)
  }

  private[this] def error(msg: String) = <span class="error">{ msg }</span>

  private[this] def onSubmit(): JsCmd = {
    //keep only disctinct sub-section from multivalued section
    parameterEditor.removeDuplicateSections
    
    // check the variables
    for (vars <- parameterEditor.mapValueSeq) {
      try
        RudderLDAPConstants.variableToSeq(Seq((parameterEditor.variableSpecs(vars._1).toVariable(vars._2))))
      catch {
        case e: Exception => formTracker.addFormError(error(e.getMessage))
        //        case e: Exception => formTracker.addFormError(error("The value for variable '%s' is invalid".format(parameterEditor.variableSpecs(vars._1).description)))
      }
    }

    if (formTracker.hasErrors) {
      onFailure
    } else {
      //try to save the PI
      if (piCreation) {
        val newPi = pi.copy(
          parameters = parameterEditor.mapValueSeq,
          name = piName.is,
          shortDescription = piShortDescription.is,
          priority = piPriority.is,
          longDescription = piLongDescription.is,
          isActivated = piCurrentStatusIsActivated)

        saveAndDeployPolicyInstance(newPi)
      } else {
        val newPi = pi.copy(
          parameters = parameterEditor.mapValueSeq,
          name = piName.is,
          shortDescription = piShortDescription.is,
          priority = piPriority.is,
          longDescription = piLongDescription.is)

        saveAndDeployPolicyInstance(newPi)
      }
    }
  }

  private[this] def saveAndDeployPolicyInstance(pi: PolicyInstance): JsCmd = {
    (for {
      saved <- policyInstanceRepository.savePolicyInstance(userPolicyTemplate.id, pi, CurrentUser.getActor)
      deploy <- {
        asyncDeploymentAgent ! StartDeployment(RudderEventActor)
        Full("Deployment request sent")
      }
    } yield {
      deploy
    }) match {
      case Full(x) => onSuccess
      case Empty => //arg.
        formTracker.addFormError(error("An error occurred while saving the policy instance"))
        onFailure
      case Failure(m, _, _) =>
        formTracker.addFormError(error("An error occurred while saving the policy instance: " + m))
        onFailure
    }
  }

  private[this] def updateAndDisplayNotifications(): NodeSeq = {
    notifications :::= formTracker.formErrors
    formTracker.cleanErrors

    if (notifications.isEmpty) NodeSeq.Empty
    else {
      val html = <div id="errorNotification" style="text-align:center;margin:10px;"><ul>{ notifications.map(n => <li>{ n }</li>) }</ul></div>
      notifications = Nil
      html
    }
  }

  ///////////// success pop-up ///////////////
  private[this] def successPopup: JsCmd = {
    JsRaw("""
      setTimeout(function() { $("#succesConfirmationDialog").modal({
          minHeight:100,
          minWidth: 350
        });
        $('#simplemodal-container').css('height', 'auto');
      }, 200);
    """)
  }

}
  
  
  
