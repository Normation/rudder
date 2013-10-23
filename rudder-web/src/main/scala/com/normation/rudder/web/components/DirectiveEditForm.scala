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
import com.normation.rudder.web.services.DirectiveEditorService
import com.normation.cfclerk.domain.Technique
import net.liftweb.http.js._
import JsCmds._
import net.liftweb.util._
import Helpers._
import net.liftweb.http._
import com.normation.rudder.services.policies._
import com.normation.rudder.batch.{ AsyncDeploymentAgent, AutomaticStartDeployment }
import com.normation.rudder.domain.eventlog.RudderEventActor
import JE._
import net.liftweb.common._
import scala.xml._
import net.liftweb.util.Helpers._
import com.normation.rudder.web.model._
import com.normation.rudder.repository._
import com.normation.rudder.domain.RudderLDAPConstants
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.domain.eventlog._
import com.normation.eventlog.EventActor
import com.normation.rudder.web.services.UserPropertyService
import com.normation.rudder.web.components.popup.CreateCloneDirectivePopup
import com.normation.rudder.web.components.popup.CreateDirectivePopup
import com.normation.rudder.web.snippet.configuration.DirectiveManagement
import com.normation.eventlog.ModificationId
import com.normation.utils.StringUuidGenerator
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.domain.workflows._
import org.joda.time.DateTime
import com.normation.cfclerk.domain.TechniqueName
import com.normation.rudder.web.components.popup.ModificationValidationPopup
import com.normation.cfclerk.domain.TechniqueId

object DirectiveEditForm {

  /**
   * This is part of component static initialization.
   * Any page which contains (or may contains after an ajax request)
   * that component have to add the result of that method in it.
   */
  def staticInit: NodeSeq =
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentDirectiveEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "staticInit", xml) ++
        RuleGrid.staticInit
    }) openOr Nil

  private def body =
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentDirectiveEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "body", xml)
    }) openOr Nil

  private def crForm =
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentDirectiveEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "form", xml)
    }) openOr Nil
}

/**
 * The form that handles Directive edition (not creation)
 * - update name, description, parameters
 *
 * Parameters can not be null.
 */
class DirectiveEditForm(
    htmlId_policyConf : String
  , technique         : Technique
  , activeTechnique   : ActiveTechnique
  , val directive     : Directive
  , oldDirective      : Option[Directive]
  , onSuccessCallback : (Either[Directive,ChangeRequestId]) => JsCmd = { (Directive) => Noop }
  , onFailureCallback : () => JsCmd = { () => Noop }
  , isADirectiveCreation : Boolean = false
  , onRemoveSuccessCallBack : () => JsCmd = { () => Noop }
) extends DispatchSnippet with Loggable {

  import DirectiveEditForm._

  val currentDirectiveSettingForm = new LocalSnippet[DirectiveEditForm]

  private[this] val directiveEditorService = RudderConfig.directiveEditorService
  private[this] val woChangeRequestRepo    = RudderConfig.woChangeRequestRepository
  private[this] val roChangeRequestRepo    = RudderConfig.roChangeRequestRepository
  private[this] val techniqueRepo          = RudderConfig.techniqueRepository
  private[this] val workflowEnabled        = RudderConfig.RUDDER_ENABLE_APPROVAL_WORKFLOWS

  private[this] val htmlId_save = htmlId_policyConf + "Save"
  private[this] val parameterEditor = {
    directiveEditorService.get(technique.id, directive.id, directive.parameters) match {
      case Full(pe) => pe
      case Empty => {
        val errMsg = "Can not initialize the parameter editor for Directive %s " +
        		"(template %s). No error returned"
        throw new IllegalArgumentException(errMsg.format(directive.id, technique.id))
      }
      case Failure(m, _, _) => {
        val errMsg = "Can not initialize the parameter editor for Directive %s " +
        		"(template %s). Error message: %s"
        throw new IllegalArgumentException(errMsg.format(directive.id, technique.id, m))
      }
    }
  }


  def dispatch = {
    case "showForm" => { _ => showForm }
  }

  def showForm(): NodeSeq = {
    (
      "#container [id]" #> htmlId_policyConf &
      "#editForm" #> showDirectiveForm()
    )(body)
  }

  def showDirectiveForm(): NodeSeq = {
    (
      "#editForm *" #> { (n: NodeSeq) => SHtml.ajaxForm(n) } andThen
      // don't show the action button when we are creating a popup
      "#pendingChangeRequestNotification" #> { xml:NodeSeq =>
          PendingChangeRequestDisplayer.checkByDirective(xml, directive.id)
        } &
      "#existingPrivateDrafts" #> displayPrivateDrafts &
      "#existingChangeRequests" #> displayChangeRequests &
      ".topLevelAction" #> ( (xml:NodeSeq) =>
        if (isADirectiveCreation) NodeSeq.Empty
        else xml ) andThen
      ClearClearable &
      //activation button: show disactivate if activated
      "#disactivateButtonLabel" #> {
        if (directive.isEnabled) "Disable" else "Enable"
       } &
       "#removeAction *" #> {
         SHtml.ajaxSubmit("Delete", () => onSubmitDelete(),("class" ,"dangerButton"))
       } &
       "#desactivateAction *" #> {
         val status = directive.isEnabled ? "disable" | "enable"
         SHtml.ajaxSubmit(status.capitalize, () => onSubmitDisable(status))
       } &
       "#clone" #> SHtml.ajaxButton(
            { Text("Clone") },
            { () =>  clone() },
            ("class", "autoWidthButton twoColumns twoColumnsRight"),("type", "button")
       ) &
       //form and form fields
      "#techniqueName" #>
        <a href={ "/secure/administration/techniqueLibraryManagement/" +
          technique.id.name.value }>
          { technique.name } version {technique.id.version}
        </a> &
      "#techniqueDescription" #> technique.description &
      "#nameField" #> {piName.toForm_!} &
      "#rudderID" #> {directive.id.value.toUpperCase} &
      "#shortDescriptionField" #> piShortDescription.toForm_! &
      "#longDescriptionField" #> piLongDescription.toForm_! &
      "#priority" #> piPriority.toForm_! &
      "#parameters" #> parameterEditor.toFormNodeSeq &
      "#save" #> { SHtml.ajaxSubmit("Save", onSubmitSave _) % ("id" -> htmlId_save) } &
      "#notifications *" #> updateAndDisplayNotifications() &
      "#isSingle *" #> showIsSingle// &
    )(crForm) ++
    Script(OnLoad(
      JsRaw("""activateButtonOnFormChange("%s", "%s");  """
        .format(htmlId_policyConf, htmlId_save)) &
      JsRaw("""
        correctButtons();
      """) &
      JsVar("""
          $("input").not("#treeSearch").keydown( function(event) {
            processKey(event , '%s');
          } );
          """.format(htmlId_save)))
    )
  }

  private[this] def clone(): JsCmd = {
    SetHtml(CreateCloneDirectivePopup.htmlId_popup,
        newCreationPopup(technique, activeTechnique)) &
    JsRaw(s""" createPopup("${CreateCloneDirectivePopup.htmlId_popup}"); """)
  }

  ////////////// Callbacks //////////////

  private[this] def onFailure(): JsCmd = {
    formTracker.addFormError(error("The form contains some errors, please correct them."))
    showErrorNotifications()
  }

  private[this] def onNothingToDo() : JsCmd = {
    formTracker.addFormError(error("There are no modifications to save."))
    showErrorNotifications()
  }

  private[this] def showErrorNotifications() : JsCmd = {
    onFailureCallback() & Replace("editForm", showDirectiveForm) &
    JsRaw("""scrollToElement("notifications");""")
  }

  def initJs : JsCmd = {
    JsRaw("correctButtons();")
  }


  private[this] def showIsSingle(): NodeSeq = {
    <span>
      {
        if (technique.isMultiInstance) {
          { <b>Multi instance</b> } ++
          Text(": several Directives derived from that template can be deployed on a given node")
        } else {
          { <b>Unique</b> } ++
          Text(": an unique Directive derived from that template can be deployed on a given node")
        }
      }
    </span>
  }

  private[this] def displayPrivateDrafts : Option[NodeSeq] = {
//TODO
//    for {
//      drafts <- roDraftChangeRequestRepository.getAll(actor, directive.id)
//    }
    None
  }

  private[this] def displayChangeRequests : Option[NodeSeq] = {
    None
  }

  ///////////// fields for Directive settings ///////////////////

  private[this] val piName = new WBTextField("Name", directive.name) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def className = "twoCol"
    override def validations =
      valMinLen(3, "The name must have at least 3 characters") _ :: Nil
  }

  private[this] val piShortDescription = {
    new WBTextField("Short description", directive.shortDescription) {
      override def className = "twoCol"
      override def setFilter = notNull _ :: trim _ :: Nil
      override val maxLen = 255
      override def validations = Nil
    }
  }

  private[this] val piLongDescription = {
    new WBTextAreaField("Description", directive.longDescription.toString) {
      override def setFilter = notNull _ :: trim _ :: Nil
      override def inputField = super.inputField % ("style" -> "height:15em")
    }
  }

  private[this] val piPriority = new WBSelectObjField("Priority:",
    (0 to 10).map(i => (i, i.toString)),
    defaultValue = directive.priority) {
    override val displayHtml =
      <span class="tooltipable greytooltip" title="" tooltipid="priorityId">
        <b>Priority:</b>
        <div class="tooltipContent" id="priorityId">
          If a node is configured with several Directive derived from that template,
          the one with the higher priority will be applied first. If several Directives
          have the same priority, the application order between these two will be random.
          If the template is unique, only one Directive derived from it may be used at
          a given time onone given node. The one with the highest priority is chosen.
          If several Directives have the same priority, one of them will be applied at
          random. You should always try to avoid that last case.<br/>
          The highest priority is 0.
        </div>
      </span>

    override def className = "twoCol"

  }

  private[this] val formTracker = {
    val l = List(piName, piShortDescription, piLongDescription) //++ crReasons
    new FormTracker(l)
  }

  private[this] def error(msg: String) = <span class="error">{ msg }</span>

  private[this] def checkVariables(): Unit = {
    for (vars <- parameterEditor.mapValueSeq) {
      try {
        val s = Seq((parameterEditor.variableSpecs(vars._1).toVariable(vars._2)))
        RudderLDAPConstants.variableToSeq(s)
      }
      catch {
        case e: Exception => formTracker.addFormError(error(e.getMessage))
      }
    }
  }

  private[this] def onSubmitSave(): JsCmd = {
    parameterEditor.removeDuplicateSections

    checkVariables()

    if (formTracker.hasErrors) {
      onFailure
    } else {
      if (isADirectiveCreation) {
        // On creation, don't create workflow
        val newDirective = directive.copy(
          parameters = parameterEditor.mapValueSeq,
          name = piName.is,
          shortDescription = piShortDescription.is,
          priority = piPriority.is,
          longDescription = piLongDescription.is,
          isEnabled = directive.isEnabled)
        displayConfirmationPopup(
            "create"
          , newDirective
        )
      } else {
        //check if it's a migration - old directive present with a different technique version
        val isMigration = oldDirective.map( _.techniqueVersion != directive.techniqueVersion).getOrElse(false)

        val updatedDirective = directive.copy(
          parameters = parameterEditor.mapValueSeq,
          name = piName.is,
          shortDescription = piShortDescription.is,
          priority = piPriority.is,
          longDescription = piLongDescription.is)

        if (!isMigration && directive == updatedDirective) {
          onNothingToDo()
        } else {
          displayConfirmationPopup(
              "save"
            , updatedDirective
          )
        }
      }

      //display confirmation pop-up that also manage workflows

    }
  }

  //action must be 'enable' or 'disable'
  private[this] def onSubmitDisable(action:String): JsCmd = {
    displayConfirmationPopup(
        action
      , directive.copy(isEnabled = !directive.isEnabled)
    )
  }

  private[this] def onSubmitDelete(): JsCmd = {
    displayConfirmationPopup(
        "delete"
      , directive
    )
  }

  /*
   * Create the confirmation pop-up
   */
  private[this] def displayConfirmationPopup(
      action      :String
    , newDirective:Directive
  ) : JsCmd = {
    val optOriginal = { if(isADirectiveCreation) None else if(oldDirective.isEmpty) Some(directive) else oldDirective }
    // Find old root section if there is an initial State
    val rootSection = optOriginal.flatMap(old => techniqueRepo.get(TechniqueId(activeTechnique.techniqueName,old.techniqueVersion)).map(_.rootSection)).getOrElse(technique.rootSection)

    val popup = {
      // if it's not a creation and we have workflow, then we redirect to the CR
      if (!isADirectiveCreation) {
        if (workflowEnabled) {
          new ModificationValidationPopup(
              Left(technique.id.name,activeTechnique.id, rootSection, newDirective, optOriginal)
            , action
            , isADirectiveCreation
            , cr => onSuccessCallback(Right(cr))
            , xml => JsRaw("$.modal.close();") & onFailure
            , parentFormTracker = formTracker
          )
        } else {
          val callback = {
            if (action == "delete") {
                val nSeq = <div id={ htmlId_policyConf }>Directive successfully deleted</div>
                cr : ChangeRequestId => JsRaw("$.modal.close();") &onRemoveSuccessCallBack() & SetHtml(htmlId_policyConf, nSeq) &
                successPopup(NodeSeq.Empty)
              } else {
                cr : ChangeRequestId  => JsRaw("$.modal.close();") & successPopup(NodeSeq.Empty) & onSuccessCallback(Left(newDirective))
              }
            }
          new ModificationValidationPopup(
              Left(technique.id.name,activeTechnique.id, rootSection, newDirective, optOriginal)
            , action
            , isADirectiveCreation
            , callback
            , xml => JsRaw("$.modal.close();") & onFailure
            , parentFormTracker = formTracker
          )
        }
      } else {
        new ModificationValidationPopup(
            Left(technique.id.name,activeTechnique.id, rootSection, newDirective, optOriginal)
          , action
          , isADirectiveCreation
          , onCreateSuccessCallBack = ( result => onSuccessCallback(result) & successPopup(NodeSeq.Empty))
          , onCreateFailureCallBack = onFailure
          , parentFormTracker = formTracker
        )
      }
    }

    popup.popupWarningMessages match {
      case None =>
        popup.onSubmit
      case Some(_) =>
        SetHtml("confirmUpdateActionDialog", popup.popupContent) &
        JsRaw("""createPopup("confirmUpdateActionDialog")""")
    }
  }

  private[this] def updateAndDisplayNotifications() : NodeSeq = {

    val notifications = formTracker.formErrors
    formTracker.cleanErrors

    if(notifications.isEmpty) {
      NodeSeq.Empty
    }
    else {
      <div id="notifications" class="notify">
        <ul class="field_errors">{notifications.map( n => <li>{n}</li>) }</ul>
      </div>
    }
  }

  private[this] def newCreationPopup(
    technique:Technique, activeTechnique:ActiveTechnique) : NodeSeq = {

    val popup = new CreateCloneDirectivePopup(
      technique.name, technique.description,
      technique.id.version, directive,
      onSuccessCallback =  dir => onSuccessCallback(Left(dir)))

    popup.popupContent
  }

  ///////////// success pop-up ///////////////

  private[this] def successPopup(message: NodeSeq) : JsCmd = {
    JsRaw(""" callPopupWithTimeout(200, "successConfirmationDialog") """) &
    JsRaw(s""" $$("#successDialogContent").html('${message}') """)
  }

  private[this] def failurePopup(message: NodeSeq) : JsCmd = {
    JsRaw(""" callPopupWithTimeout(200, "successConfirmationDialog") """) &
    JsRaw(s""" $$("#successDialogContent").html('${message}') """)
  }
}

