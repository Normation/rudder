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
import bootstrap.liftweb.LiftSpringApplicationContext.inject
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

  private def itemDependencies =
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentDirectiveEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "itemDependencies", xml)
    }) openOr Nil

  private def popupDependentUpdateForm =
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentDirectiveEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "popupDependentUpdateForm", xml)
    }) openOr Nil

  private def popupRemoveForm =
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentDirectiveEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "popupRemoveForm", xml)
    }) openOr Nil

  private def popupDisactivateForm =
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentDirectiveEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "popupDisactivateForm", xml)
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
  htmlId_policyConf: String,
  technique: Technique,
  activeTechnique: ActiveTechnique,
  val directive: Directive,
  onSuccessCallback: (Directive) => JsCmd = { (Directive) => Noop },
  onRemoveSuccessCallback: () => JsCmd = { () => Noop },
  onFailureCallback: () => JsCmd = { () => Noop },
  isADirectiveCreation: Boolean = false
) extends DispatchSnippet with Loggable {

  import DirectiveEditForm._

  val currentDirectiveSettingForm = new LocalSnippet[DirectiveEditForm]

  private[this] val directiveRepository    = inject[DirectiveRepository]
  private[this] val dependencyService      = inject[DependencyAndDeletionService]
  private[this] val directiveEditorService = inject[DirectiveEditorService]
  private[this] val asyncDeploymentAgent   = inject[AsyncDeploymentAgent]
  private[this] val userPropertyService    = inject[UserPropertyService]
  private[this] val uuidGen                = inject[StringUuidGenerator]


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
  private[this] var piCurrentStatusIsActivated = directive.isEnabled
  private[this] var directiveCurrentStatusCreationStatus = isADirectiveCreation

  lazy val dependentRules =  dependencyService.directiveDependencies(directive.id).map(_.rules)

  // pop-up: their content should be retrieve lazily
  lazy val removePopupGridXml = {
    if (directiveCurrentStatusCreationStatus) {
      NodeSeq.Empty
    } else {
      dependentRules match {
        case e: EmptyBox =>
          <div class="error">An error occurred while trying to find dependent item</div>
        case Full(rules) => {
          val cmp = new RuleGrid("remove_popup_grid", rules.toSeq, None, false)
          cmp.rulesGrid(linkCompliancePopup = false)
        }
      }
    }
  }

  // popup for the list of dependent rules when updating the directives
  lazy val updatePopupGridXml = {
    if (directiveCurrentStatusCreationStatus) {
      NodeSeq.Empty
    } else {
      dependentRules match {
        case e: EmptyBox =>
          <div class="error">An error occurred while trying to find dependent item</div>
        case Full(rules) => {
          val cmp = new RuleGrid("dependent_popup_grid", rules.toSeq, None, false)
          cmp.rulesGrid(popup = true,linkCompliancePopup = false)
        }
      }
    }
  }

  // if directive current inherited status is "activated", the two pop-up (disable and
  // remove) show the same content. Else, we have to build two different pop-up
  val switchStatusFilter = if (directive.isEnabled) OnlyDisableable else OnlyEnableable

  lazy val disablePopupGridXml = {
    if (directiveCurrentStatusCreationStatus) {
      NodeSeq.Empty
    } else {
      dependencyService
        .directiveDependencies(directive.id, switchStatusFilter)
        .map(_.rules) match {
          case e: EmptyBox =>
            <div class="error">An error occurred while trying to find dependent item</div>
          case Full(rules) => {
            val cmp = new RuleGrid("disable_popup_grid", rules.toSeq, None, false)
            cmp.rulesGrid(linkCompliancePopup = false)
          }
        }
    }
  }

  def dispatch = {
    case "showForm" => { _ => showForm }
  }

  def showForm(): NodeSeq = {
    (
      "#container [id]" #> htmlId_policyConf &
      "#editForm" #> showDirectiveForm() &
      "#removeActionDialog" #> showRemovePopupForm() &
      "#disableActionDialog" #> showDisactivatePopupForm()
    )(body)
  }

  def showRemovePopupForm() : NodeSeq = {
    (
       "#removeActionDialog *" #> { (n:NodeSeq) => SHtml.ajaxForm(n) } andThen
       "#dialogRemoveButton" #> { removeButton % ("id", "removeButton") } &
       "#removeItemDependencies" #> {
        (ClearClearable & "#itemDependenciesGrid" #> removePopupGridXml)(itemDependencies)
      } &
       ".reasonsFieldsetPopup" #> { crReasonsRemovePopup.map { f =>
         "#explanationMessage" #> <div>{userPropertyService.reasonsFieldExplanation}</div> &
         "#reasonsField" #> f.toForm_!
       } } &
        "#errorDisplay *" #> { updateAndDisplayNotifications(formTrackerRemovePopup) }
    )(popupRemoveForm)
  }

  def showDisactivatePopupForm() : NodeSeq = {
    (
      "#disableActionDialog *" #> { (n:NodeSeq) => SHtml.ajaxForm(n) } andThen
      "#dialogDisableTitle" #> { dialogDisableTitle } &
      "#dialogDisableLabel" #> { dialogDisableTitle.toLowerCase } &
      "#dialogDisableButton" #> { disableButton % ("id", "disactivateButton") } &
      "#dialogDisableWarning *" #> dialogDisableWarning &
      "#disableItemDependencies" #> {
        (ClearClearable & "#itemDependenciesGrid" #> disablePopupGridXml)(itemDependencies)
      } &
      ".reasonsFieldsetPopup" #> {
        crReasonsDisactivatePopup.map { f =>
          "#explanationMessage" #> <div>{userPropertyService.reasonsFieldExplanation}</div> &
          "#reasonsField" #> f.toForm_!
        }
      } &
      "#errorDisplay *" #> { updateAndDisplayNotifications(formTrackerDisactivatePopup) }
    )(popupDisactivateForm)
  }

  def showUpdatePopupForm( onConfirmCallback:  => JsCmd) : NodeSeq = {
    (
       "#dialogSaveButton" #> SHtml.ajaxButton("Save", () => JsRaw("$.modal.close();") & onConfirmCallback ) &
       "#directiveDisabled [class]" #> ( if (piCurrentStatusIsActivated) "nodisplay" else "" ) &
       "#updateItemDependencies" #> {
        (ClearClearable & "#itemDependenciesGrid" #> updatePopupGridXml)(itemDependencies)
      }
    )(popupDependentUpdateForm)
  }

  def showDirectiveForm(): NodeSeq = {
    (
      "#editForm *" #> { (n: NodeSeq) => SHtml.ajaxForm(n) } andThen
      // don't show the action button when we are creating a popup
      ".topLevelAction" #> ( xml =>
        if (directiveCurrentStatusCreationStatus) NodeSeq.Empty
        else xml ) andThen
      ClearClearable &
      //activation button: show disactivate if activated
      "#disactivateButtonLabel" #> {
        if (piCurrentStatusIsActivated) "Disable" else "Enable"
       } &
       "#removeAction *" #> {
         SHtml.ajaxSubmit("Delete", () => createPopup("removeActionDialog",140,850))
       } &
       "#desactivateAction *" #> {
         val status = piCurrentStatusIsActivated ? "Disable" | "Enable"
         SHtml.ajaxSubmit(   status, () => createPopup("disableActionDialog",100,850))
       } &
       "#clone" #> SHtml.ajaxButton(
            { Text("Clone") },
            { () =>  clone() },
            ("class", "autoWidthButton twoColumns twoColumnsRight"),("type", "button")
       ) &
       //form and form fields
      "#techniqueName" #>
        <a href={ "/secure/configurationManager/techniqueLibraryManagement/" +
          technique.id.name.value }>
          { technique.name }
        </a> &
      "#techniqueDescription" #> technique.description &
      "#nameField" #> {piName.toForm_!} &
      "#rudderID" #> {directive.id.value.toUpperCase} &
      "#shortDescriptionField" #> piShortDescription.toForm_! &
      "#longDescriptionField" #> piLongDescription.toForm_! &
      "#priority" #> piPriority.toForm_! &
      ".reasonsFieldset" #> { crReasons.map { f =>
        "#explanationMessage" #> <div>{userPropertyService.reasonsFieldExplanation}</div> &
        "#reasonsField" #> f.toForm_!
      } } &
      "#parameters" #> parameterEditor.toFormNodeSeq &
      "#save" #> { SHtml.ajaxSubmit("Save", onSubmit _) % ("id" -> htmlId_save) } &
      "#notifications *" #> updateAndDisplayNotifications(formTracker) &
      "#isSingle *" #> showIsSingle// &
      //"#editForm [id]" #> htmlId_policyConf
    )(crForm) ++
    Script(OnLoad(
      JsRaw("""activateButtonOnFormChange("%s", "%s");  """
        .format(htmlId_policyConf, htmlId_save)) &
      JsRaw("""
        correctButtons();
      """) &
      JsVar("""
          $("input").not("#treeSearch").keydown( function(event) {
            processKey(event , '%s')
          } );
          """.format(htmlId_save)))
    )
  }

  private[this] def clone(): JsCmd = {
    SetHtml(CreateCloneDirectivePopup.htmlId_popup,
        newCreationPopup(technique, activeTechnique)) &
    JsRaw(""" createPopup("%s",300,400) """
        .format(CreateCloneDirectivePopup.htmlId_popup))
  }

  ////////////// Callbacks //////////////

  private[this] def onSuccess(directive: Directive): JsCmd = {
    directiveCurrentStatusCreationStatus = false
    onSuccessCallback(directive) & successPopup
  }

  private[this] def onFailure(): JsCmd = {
    formTracker.addFormError(error("The form contains some errors, please correct them."))
    onFailureCallback() & Replace("editForm", showDirectiveForm) &
    JsRaw("""scrollToElement("notifications");""")
  }

  private[this] def onFailureRemovePopup() : JsCmd = {
    val elemError = error("The form contains some errors, please correct them")
    formTrackerRemovePopup.addFormError(elemError)
    updateRemoveFormClientSide() &
    onFailureCallback()
  }

  private[this] def onFailureDisablePopup() : JsCmd = {
    val elemError = error("The form contains some errors, please correct them")
    formTrackerDisactivatePopup.addFormError(elemError)
    onFailureCallback() &
    updateDisableFormClientSide()
  }

  private[this] def updateRemoveFormClientSide() : JsCmd = {
    val jsDisplayRemoveDiv = JsRaw("""$("#removeActionDialog").removeClass('nodisplay')""")
    Replace("removeActionDialog", this.showRemovePopupForm()) &
    jsDisplayRemoveDiv &
    initJs
  }

  private[this] def updateDisableFormClientSide() : JsCmd = {
    val jsDisplayDisableDiv = JsRaw("""$("#disableActionDialog").removeClass('nodisplay')""")
    Replace("disableActionDialog", this.showDisactivatePopupForm()) &
    jsDisplayDisableDiv &
    initJs
  }

  def createPopup(name:String,height:Int,width:Int) :JsCmd = {
    JsRaw("""createPopup("%s",%s,%s);""".format(name,height,width))
  }

  def initJs : JsCmd = {
    JsRaw("correctButtons();")
  }

  ///////////// Remove /////////////

  private[this] def removeButton: Elem = {
    def removeCr(): JsCmd = {
      if(formTrackerRemovePopup.hasErrors) {
        onFailureRemovePopup
      } else {
        JsRaw("$.modal.close();") &
        {
          if (directiveCurrentStatusCreationStatus) {
            val nSeq = <div id={ htmlId_policyConf }>Directive successfully deleted</div>
            onRemoveSuccessCallback() & SetHtml(htmlId_policyConf, nSeq) &
            successPopup
          } else {
            val modId = ModificationId(uuidGen.newUuid)
            dependencyService.cascadeDeleteDirective(directive.id, modId, CurrentUser.getActor, crReasons.map(_.is)) match {
              case Full(x) =>
                asyncDeploymentAgent ! AutomaticStartDeployment(modId, RudderEventActor)
                Full("Deployment request sent")
                val nSeq = <div id={ htmlId_policyConf }>Directive successfully deleted</div>
                onRemoveSuccessCallback() & SetHtml(htmlId_policyConf, nSeq) & successPopup
              case Empty => //arg.
                val errMsg = "An error occurred while deleting the Directive (no more information)"
                formTracker.addFormError(error(errMsg))
                onFailure
              case f@Failure(m, _, _) =>
                val msg = (f ?~! "An error occurred while deleting the Directive: ").messageChain
                logger.debug(msg, f)
                formTracker.addFormError(error(msg + m))
                onFailure
            }
          }
        }
      }
    }

    SHtml.ajaxSubmit("Delete", removeCr _)
  }

  ///////////// Enable / disable /////////////

  private[this] def disableButton: Elem = {
    def switchActivation(status: Boolean)(): JsCmd = {
      if(formTrackerDisactivatePopup.hasErrors) {
        onFailureDisablePopup
      } else {
        piCurrentStatusIsActivated = status
        JsRaw("$.modal.close();") & {
          if (directiveCurrentStatusCreationStatus == true) {
            onSuccess(directive)
          } else {
            val changeStatus= if(status) "enabled" else "disabled"
            val defaultReason = Some("Directive %s by user".format(changeStatus))
            val reason = crReasonsDisactivatePopup.map( _.is).orElse(defaultReason)
            saveAndDeployDirective(directive.copy(isEnabled = status), reason)
          }
        }
      }
    }

    if (piCurrentStatusIsActivated) {
      SHtml.ajaxSubmit("Disable", switchActivation(false) _)
    } else {
      SHtml.ajaxSubmit("Enable", switchActivation(true) _)
    }
  }

  private[this] def dialogDisableTitle : String = {
    if (piCurrentStatusIsActivated) {
      "Disable"
    } else {
      "Enable"
    }
  }

  private[this] def dialogDisableWarning: String = {
    if (piCurrentStatusIsActivated) {
      "Disabling this directive will affect the following Rules."
    } else {
      "Enabling this directive will affect the following Rules"
    }
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

  private[this] val crReasonsDisactivatePopup = {
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
    val l = List(piName, piShortDescription, piLongDescription) ++ crReasons
    new FormTracker(l)
  }

  private[this] val formTrackerRemovePopup =
    new FormTracker(crReasonsRemovePopup.toList)

  private[this] val formTrackerDisactivatePopup =
    new FormTracker(crReasonsDisactivatePopup.toList)

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

  private[this] def onSubmit(): JsCmd = {
    parameterEditor.removeDuplicateSections

    checkVariables()

    if (formTracker.hasErrors) {
      onFailure
    } else {
      val newDirective = if (directiveCurrentStatusCreationStatus) {
        directive.copy(
          parameters = parameterEditor.mapValueSeq,
          name = piName.is,
          shortDescription = piShortDescription.is,
          priority = piPriority.is,
          longDescription = piLongDescription.is,
          isEnabled = piCurrentStatusIsActivated
        )
      } else {
        directive.copy(
          parameters = parameterEditor.mapValueSeq,
          name = piName.is,
          shortDescription = piShortDescription.is,
          priority = piPriority.is,
          longDescription = piLongDescription.is
        )
      }
      // here everything is good, I can display the popup if there are
      // dependent rules
      dependentRules match {
        case e: EmptyBox => saveAndDeployDirective(newDirective, crReasons.map(_.is))
        case Full(rules) if rules.size == 0 => saveAndDeployDirective(newDirective, crReasons.map(_.is))
        case _ => displayDependenciesPopup(newDirective, crReasons.map(_.is))
      }

    }
  }

  // Fill the content of the popup with the list of dependant rules (this list is computed
  // at the load of page), and wait for user confirmation
  private[this] def displayDependenciesPopup(directive: Directive, why:Option[String]): JsCmd = {
    SetHtml("confirmUpdateActionDialog", showUpdatePopupForm(saveAndDeployDirective(directive, why))) &
    createPopup("updateActionDialog",140,850)
  }

  private[this] def saveAndDeployDirective(directive: Directive, why:Option[String]): JsCmd = {
    val modId = ModificationId(uuidGen.newUuid)
     directiveRepository.saveDirective(activeTechnique.id, directive, modId, CurrentUser.getActor, why) match {
      case Full(optChanges) =>
        optChanges match {
          case Some(_) => // There is a modification diff, launch a deployment.
            asyncDeploymentAgent ! AutomaticStartDeployment(modId, RudderEventActor)
          case None => // No change, don't launch a deployment
        }
        onSuccess(directive)
      case Empty =>
        formTracker.addFormError(error("An error occurred while saving the Directive"))
        onFailure
      case Failure(m, _, _) =>
        formTracker.addFormError(error(m))
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
      onSuccessCallback =  onSuccessCallback)

    popup.popupContent
  }

  private[this] def updateCf3PolicyDraftInstanceSettingFormComponent(
    technique:Technique, activeTechnique:ActiveTechnique, directive:Directive,
    isADirectiveCreation : Boolean = false) : Unit = {

    val dirEditForm = new DirectiveEditForm(
      DirectiveManagement.htmlId_policyConf, technique, activeTechnique,
      directive, onSuccessCallback = onSuccessCallback,
      onRemoveSuccessCallback = onRemoveSuccessCallback,
      isADirectiveCreation = isADirectiveCreation
    )

    currentDirectiveSettingForm.set(Full(dirEditForm))
  }

  private[this] def showDirectiveDetails() : NodeSeq = {
    currentDirectiveSettingForm.is match {
      case Failure(m, _, _) =>
        <div id={DirectiveManagement.htmlId_policyConf} class="error">
          An error happened when trying to load Directive configuration.
          Error message was: {m}
        </div>
      case Empty => <div id={DirectiveManagement.htmlId_policyConf}></div>
      case Full(formComponent) => formComponent.showForm()
    }
  }

  ///////////// success pop-up ///////////////

  private[this] def successPopup : JsCmd = {
    JsRaw(""" callPopupWithTimeout(200, "successConfirmationDialog", 100, 350) """)
  }

}

