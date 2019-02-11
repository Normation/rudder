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

import com.normation.rudder.domain.policies._
import com.normation.cfclerk.domain.Technique
import net.liftweb.http.js._
import JsCmds._
import net.liftweb.util._
import net.liftweb.http._
import JE._
import net.liftweb.common._

import scala.xml._
import net.liftweb.util.Helpers._
import com.normation.rudder.web.model._
import com.normation.rudder.repository._
import com.normation.rudder.domain.RudderLDAPConstants
import com.normation.rudder.web.components.popup.CreateCloneDirectivePopup
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.domain.workflows._
import com.normation.rudder.web.components.popup.ModificationValidationPopup
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.rudder.domain.policies.PolicyMode._
import com.normation.rudder.domain.policies.PolicyModeOverrides.Always
import com.normation.rudder.domain.policies.PolicyModeOverrides.Unoverridable
import com.normation.rudder.services.workflows.DGModAction
import com.normation.rudder.services.workflows.DirectiveChangeRequest
import com.normation.rudder.web.ChooseTemplate

import com.normation.box._

object DirectiveEditForm {

  /**
   * This is part of component static initialization.
   * Any page which contains (or may contains after an ajax request)
   * that component have to add the result of that method in it.
   */
  def staticInit: NodeSeq = RuleGrid.staticInit

  private def body = ChooseTemplate(
      "templates-hidden" :: "components" :: "ComponentDirectiveEditForm" :: Nil
    , "component-body"
  )

  private def crForm = ChooseTemplate(
      "templates-hidden" :: "components" :: "ComponentDirectiveEditForm" :: Nil
     , "component-form"
  )
}

/**
 * The form that handles Directive edition (not creation)
 * - update name, description, parameters
 *
 * Parameters can not be null.
 */
class DirectiveEditForm(
    htmlId_policyConf       : String
  , technique               : Technique
  , activeTechnique         : ActiveTechnique
  , fullActiveTechnique     : FullActiveTechnique
  , val directive           : Directive
  , oldDirective            : Option[Directive]
  , globalMode              : GlobalPolicyMode
  , isADirectiveCreation    : Boolean = false
  , onSuccessCallback       : (Either[Directive,ChangeRequestId]) => JsCmd
  , onMigrationCallback     : (Directive, Option[Directive]) => JsCmd
  , onFailureCallback       : () => JsCmd = { () => Noop }
  , onRemoveSuccessCallBack : () => JsCmd = { () => Noop }
) extends DispatchSnippet with Loggable {

  import DirectiveEditForm._

  val currentDirectiveSettingForm = new LocalSnippet[DirectiveEditForm]

  private[this] val directiveEditorService = RudderConfig.directiveEditorService
  private[this] val techniqueRepo          = RudderConfig.techniqueRepository
  private[this] val roRuleRepo             = RudderConfig.roRuleRepository
  private[this] val roRuleCategoryRepo     = RudderConfig.roRuleCategoryRepository
  private[this] val workflowLevelService   = RudderConfig.workflowLevelService

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

  val rules = roRuleRepo.getAll(false).toBox.getOrElse(Seq()).toList
  val rootCategory = roRuleCategoryRepo.getRootCategory.toBox.getOrElse(throw new RuntimeException("Error when retrieving the rule root category - it is most likelly a bug. Pleae report."))
  val directiveApp = new DirectiveApplicationManagement(directive,rules,rootCategory)
  def dispatch = {
    case "showForm" => { _ => showForm }
  }

  def showForm(): NodeSeq = {
    staticInit ++
    (
      "#container [id]" #> htmlId_policyConf &
      "#editForm" #> showDirectiveForm()
    )(body)
  }

  def migrateButton(version : => TechniqueVersion, text: String, id:String = "migrationButton") = {
    SHtml.ajaxSubmit(
        text
      , () => {
          val newDirective = directive.copy(techniqueVersion = version)
          onMigrationCallback(newDirective,Some(directive))
        }
      , ("id" -> id)
      , ("class" -> "btn btn-default")
    )
  }

  val displayDeprecationWarning = technique.deprecrationInfo match {
    case Some(info) =>
      ( "#deprecation-message *" #> info.message &
        "#migrate-button *" #> {
            (for {
              lastTechniqueVersion <- fullActiveTechnique.newestAvailableTechnique
              if( lastTechniqueVersion.id.version != directive.techniqueVersion )
            } yield {
              Text("Please upgrade to a new version: ") ++
              migrateButton(lastTechniqueVersion.id.version,"Migrate now!","deprecation-migration")
            }).getOrElse(NodeSeq.Empty)
        }
      )
    case None =>
      ("#deprecation-warning [class+]" #> "hidden" )
  }

  def showDirectiveForm(): NodeSeq = {

    val ruleDisplayer = {
      new RuleDisplayer(
          Some(directiveApp)
        , "view"
        , (_ :Rule,_ : String)  => Noop
        , (_ : Rule)         => Noop
        , (_ : Option[Rule]) => Noop
        , DisplayColumn.FromConfig
        , DisplayColumn.FromConfig
        ).display
    }

    val versionSelect = directiveVersion
    val currentVersion = showDeprecatedVersion(directive.techniqueVersion)
    // It is always a Full, but in case add a warning
    val versionSelectId = versionSelect.uniqueFieldId match {
      case Full(id) => id
      case _ =>
        logger.warn("could not find id for migration select version")
        "id_not_found"
    }
    val (osCompatibility,agentCompatibility) : (NodeSeq,NodeSeq) = {
      val osCompEmpty : NodeSeq =
        <span>
          Can be used on any system.
        </span>
      val agentCompEmpty : NodeSeq =
        <span>
          Can be used on any agent.
        </span>
      technique.compatible match {
        case None =>
          (osCompEmpty,agentCompEmpty)
        case Some(comp) =>
          val osComp =  comp.os match {
            case Seq() =>
              osCompEmpty
            case oses =>
                <span>
                  {oses.mkString(", ")}
                </span>
          }
          val agentComp = comp.agents match {
            case Seq() =>
              agentCompEmpty
            case agent =>
                <span>
                  {agent.mkString(", ")}
                </span>
          }
          (osComp,agentComp)
      }
    }
    val (disableMessage, enableBtn) = (fullActiveTechnique.isEnabled, directive._isEnabled) match{
      case(false, false) =>
        ( "This Directive and its Technique are disabled."
        , <span>
            {SHtml.ajaxSubmit("Enable Directive", () => onSubmitDisable(DGModAction.Enable), ("class" ,"btn btn-sm btn-default"))}
            <a class="btn btn-sm btn-default" href={s"/secure/administration/techniqueLibraryManagement/#${fullActiveTechnique.techniqueName}"}>Edit Technique</a>
          </span>
        )
      case(false, true) =>
        ( "The Technique of this Directive is disabled."
        , <a class="btn btn-sm btn-default" href={s"/secure/administration/techniqueLibraryManagement/#${fullActiveTechnique.techniqueName}"}>Edit Technique</a>
        )
      case(true, false) =>
        ( "This Directive is disabled."
          , SHtml.ajaxSubmit("Enable", () => onSubmitDisable(DGModAction.Enable), ("class" ,"btn btn-sm btn-default"))
        )
      case(true, true) =>
        ( "" , NodeSeq.Empty )
    }
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
      "#directiveTitle *" #> <span class={ if(fullActiveTechnique.isEnabled) "" else "is-disabled" }>{directive.name}</span> &
      "#disactivateButtonLabel" #> {
        if (directive.isEnabled) "Disable" else "Enable"
       } &
       "#removeAction *" #> {
         SHtml.ajaxSubmit("Delete", () => onSubmitDelete(),("class" ,"btn btn-danger"))
       } &
       "#desactivateAction *" #> {
         val status = directive.isEnabled ? DGModAction.Disable | DGModAction.Enable
         SHtml.ajaxSubmit(status.name, () => onSubmitDisable(status), ("class" ,"btn btn-default"))
       } &
       "#clone" #> SHtml.ajaxButton(
            { Text("Clone") },
            { () =>  clone() },
            {("class", "btn btn-default")},
            {("type", "button")}
       ) &
       //form and form fields
      "#techniqueName *" #>
        <a href={ "/secure/administration/techniqueLibraryManagement/" +
          technique.id.name.value }>
          { technique.name } version {technique.id.version}
        </a> &
      "#techniqueDescription *" #> technique.description &
      "#isDisabled" #> {
        if (!fullActiveTechnique.isEnabled || !directive.isEnabled)
          <div class="alert alert-warning">
            <i class="fa fa-exclamation-triangle" aria-hidden="true"></i>
            {disableMessage}
            {enableBtn}
          </div>
        else NodeSeq.Empty
      } &
      "#nameField" #> {directiveName.toForm_!} &
      "#tagField *" #> tagsEditForm.tagsForm("directiveTags", "directiveEditTagsApp", updateTag, false) &
      "#rudderID *" #> {directive.id.value} &
      "#shortDescriptionField" #> directiveShortDescription.toForm_! &
      "#longDescriptionField" #> directiveLongDescription.toForm_! &
      "#priority" #> directivePriority.toForm_! &
      "#policyModes" #> policyModes.toForm_! &
      "#version" #> versionSelect.toForm_! &
      "#version *+" #> migrateButton(directiveVersion.get,"Migrate") &
      "#parameters" #> parameterEditor.toFormNodeSeq &
      "#directiveRulesTab *" #> ruleDisplayer &
      "#save" #> { SHtml.ajaxSubmit("Save", onSubmitSave _) % ("id" -> htmlId_save) % ("class" -> "btn btn-success") } &
      "#notifications *" #> updateAndDisplayNotifications() &
      "#showTechnical *" #> SHtml.a(() => JsRaw("$('#technicalDetails').show(400);") & showDetailsStatus(true), Text("Show technical details"), ("class","listopen")) &
      "#isSingle *" #> showIsSingle &
      "#compatibilityOs" #> osCompatibility &
      "#compatibilityAgent" #> agentCompatibility &
      displayDeprecationWarning
    )(crForm) ++
    Script(OnLoad(
      JsRaw("""activateButtonOnFormChange("%s", "%s");  """
        .format(htmlId_policyConf, htmlId_save)) &
      JsRaw(s"""$$('#technicalDetails').hide();""") &
      JsRaw(s"""
          $$("input").not("#treeSearch").keydown( function(event) {
            processKey(event , '${htmlId_save}');
          } );
          checkMigrationButton("${currentVersion}","${versionSelectId}");
          $$('#${versionSelect.uniqueFieldId.getOrElse("id_not_found")}').change(
            function () {
              checkMigrationButton("${currentVersion}","${versionSelectId}")
            }
          );
          adjustHeight("#edit-box","#directiveToolbar")
          """)

    )
    )
  }

  private[this] def showDetailsStatus(hide:Boolean) : JsCmd = {
    val name = if (hide) "Hide" else "Show"
    val classAttribute = ("class", if (hide) "listclose" else "listopen")
    SetHtml("showTechnical",SHtml.a(() => JsRaw("$('#technicalDetails').toggle(400);") & showDetailsStatus(!hide), Text(s"$name technical details"), classAttribute))
  }

  private[this] def clone(): JsCmd = {
    SetHtml(CreateCloneDirectivePopup.htmlId_popup,
        newCreationPopup(technique, activeTechnique)) &
    JsRaw(s""" createPopup("${CreateCloneDirectivePopup.htmlId_popup}"); """)
  }

  ////////////// Callbacks //////////////

  def addFormMsg(msg: NodeSeq) = formTracker.addFormError(msg)

  private[this] def onFailure(): JsCmd = {
    formTracker.addFormError(error("There was problem with your request."))
    showErrorNotifications()
  }

  private[this] def onNothingToDo() : JsCmd = {
    formTracker.addFormError(error("There are no modifications to save."))
    showErrorNotifications()
  }

  private[this] def showErrorNotifications() : JsCmd = {
    onFailureCallback() & Replace("editForm", showDirectiveForm) &
    //restore user to the update parameter tab
    JsRaw("""scrollToElement("notifications", "#directiveDetails");""")
  }

  private[this] def showIsSingle(): NodeSeq = {
    <span>
      {
        if (technique.isMultiInstance) {
          Text("Multi instance: Several Directives based on this Technique can be applied on any given node")
        } else {
          Text("Unique: Only ONE Directive based on this Technique can be applied on any given node")
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

  private[this] val directiveName = new WBTextField("Name", directive.name) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def className = "form-control"
    override def labelClassName = "col-xs-12"
    override def subContainerClassName = "col-xs-12"
    override def validations =
      valMinLen(1, "Name must not be empty") _ :: Nil
  }

  private[this] val directiveShortDescription = {
    new WBTextField("Short description", directive.shortDescription) {
      override def className = "form-control"
      override def labelClassName = "col-xs-12"
      override def subContainerClassName = "col-xs-12"
      override def setFilter = notNull _ :: trim _ :: Nil
      override val maxLen = 255
      override def validations = Nil
    }
  }

  private[this] val directiveLongDescription = {
    new WBTextAreaField("Description", directive.longDescription.toString) {
      override def setFilter = notNull _ :: trim _ :: Nil
      override def className = "form-control"
      override def labelClassName = "col-xs-12"
      override def subContainerClassName = "col-xs-12"
    }
  }

  private[this] val directivePriority = {
    val priorities = List(
        ( 0, "Highest")
      , ( 1, "+4")
      , ( 2, "+3")
      , ( 3, "+2")
      , ( 4, "+1")
      , ( 5, "Default")
      , ( 6, "-1")
      , ( 7, "-2")
      , ( 8, "-3")
      , ( 9, "-4")
      , (10, "Lowest")
    )
    new WBSelectObjField(
        "Priority"
      , priorities
      , defaultValue = directive.priority
    ) {
      override val displayHtml =
        <div>
          <b>Priority</b>
          <span>
            <span tooltipid="priorityId" class="ruddericon tooltipable glyphicon glyphicon-question-sign" title=""></span>
            <div class="tooltipContent" id="priorityId">
              <h4> Priority </h4>
              <p>Priority has two uses depending if the technique from which that directive is derived is <b>Unique</b> or not.</p>
              <p>Unique directives can be applied only once (for example Time Settings), so only the highest priority will be applied.</p>
              <p>For <b>non-unique</b> directives, priority is used to choose the order of the directive application when applicable. The
                highest priority directive comes first.</p>
              <p>Note that overriding variable definitions are the ones coming last and so, the used value will be the one with the lowest priority.</p>
              <p>More information is available in <a href="https://docs.rudder.io/reference/current/usage/advanced_configuration_management.html#_special_use_case_overriding_generic_variable_definition">documentation about ordering directive application</a>.</p>
            </div>
          </span>
        </div>
      override def className = "form-control"
      override def labelClassName = "col-xs-12"
      override def subContainerClassName = "col-xs-12"
    }
  }

  private[this] var newTags = directive.tags

  def updateTag (boxTag : Box[Tags]) = {
    boxTag match {
      case Full(tags) => newTags = tags
      case eb : EmptyBox =>
        val failure = eb ?~! s"Error when updating directive ${directive.id.value} tag"
        formTracker.addFormError(error(failure.messageChain))
    }
  }
  def tagsEditForm = new TagsEditForm(directive.tags)

  def showDeprecatedVersion (version : TechniqueVersion) = {
    val deprecationInfo = fullActiveTechnique.techniques(version).deprecrationInfo match {
      case Some(_) => "(deprecated)"
      case None => ""
    }
    s"${version} ${deprecationInfo}"
  }
  private[this] val globalOverrideText = globalMode.overridable match {
    case Always  =>
      <div>
        You may override the agent policy mode on this directive.
        If set to <b>Audit</b> this directive will never be enforced.
        If set to <b>Enforce</b> this directive will appply necessary changes except on Nodes with a <b>Verify</b> override setting.
      </div>
    case Unoverridable =>
      <p>
        Currrent global settings do not allow this mode to be overriden on a per-directive bases. You may change this in <b>Settings</b>,
        or contact your Rudder administrator about this.
      </p>
  }
    private[this] val policyModes = {
      val l = Seq(
          None -> s"Use global default mode (currently ${globalMode.mode.name})"
        , Some(Enforce) -> "Override to Enforce"
        , Some(Audit) -> "Override to Audit"
      )
      new WBSelectObjField(
        "Policy mode"
      , l
      , defaultValue = directive.policyMode
    ) {
      override val displayHtml =
        <div>
          <b>Policy mode</b>
          <span>
            <span tooltipid="policyModeId" class="ruddericon tooltipable glyphicon glyphicon-question-sign" title=""></span>
            <div class="tooltipContent" id="policyModeId">
              <h4>Policy mode</h4>
              <p>Configuration rules in Rudder can operate in one of two modes:</p>
              <ol>
                <li><b>Audit</b>: the agent will examine configurations and report any differences, but will not make any changes</li>
                <li><b>Enforce</b>: the agent will make changes to fix any configurations that differ from your directives</li>
              </ol>
              <p>
                By default all nodes and all directives operate in the global default mode defined in
                <b> Settings</b>, which is currently <b>{globalMode.mode.name}</b>.
              </p>
              {globalOverrideText}
            </div>
          </span>
        </div>
      override def className = "form-control"
      override def labelClassName = "col-xs-12"
      override def subContainerClassName = "col-xs-12"
    }
  }

  val versions = fullActiveTechnique.techniques.keys.map(v => (v,showDeprecatedVersion(v))).toSeq.sortBy(_._1)

  private[this] val directiveVersion =
    new WBSelectObjField(
        "Technique version"
      , versions
      , directive.techniqueVersion
      , Seq(("id" -> "selectVersion"))
    ) {

      override def className = "form-control"
      override def labelClassName = "col-xs-12 text-bold"
      override def subContainerClassName = "version-group"
    }

  private[this] val formTracker = {
    val l = List(directiveName, directiveShortDescription, directiveLongDescription) //++ crReasons
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
    checkVariables()

    if (formTracker.hasErrors) {
      onFailure
    } else {
      val (addRules,removeRules)= directiveApp.checkRulesToUpdate
      val baseRules = (addRules ++ removeRules).sortBy(_.id.value)

      val finalAdd = addRules.map(r => r.copy(directiveIds =  r.directiveIds + directive.id ))
      val finalRem = removeRules.map(r => r.copy(directiveIds =  r.directiveIds - directive.id ))
      val updatedRules = (finalAdd ++ finalRem).sortBy(_.id.value)

      if (isADirectiveCreation) {

        // On creation, don't create workflow
        //does some rules are assigned to that new directive ?
        val action = if(baseRules.toSet == updatedRules.toSet) {
          DGModAction.CreateSolo
        } else {
          DGModAction.CreateAndModRules
        }

        val newDirective = directive.copy(
            parameters       = parameterEditor.mapValueSeq
          , name             = directiveName.get
          , shortDescription = directiveShortDescription.get
          , priority         = directivePriority.get
          , longDescription  = directiveLongDescription.get
          , _isEnabled       = directive.isEnabled
          , policyMode       = policyModes.get
          , tags             = newTags
        )

        displayConfirmationPopup(
            action
          , newDirective
          , baseRules
          , updatedRules
        )
      } else {
        //check if it's a migration - old directive present with a different technique version
        val isMigration = oldDirective.map( _.techniqueVersion != directive.techniqueVersion).getOrElse(false)

        val updatedDirective = directive.copy(
            parameters       = parameterEditor.mapValueSeq
          , name             = directiveName.get
          , shortDescription = directiveShortDescription.get
          , priority         = directivePriority.get
          , longDescription  = directiveLongDescription.get
          , policyMode       = policyModes.get
          , tags             = newTags
        )

        if ((!isMigration && directive == updatedDirective && updatedRules.isEmpty)) {
          onNothingToDo()
        } else {
          displayConfirmationPopup(
              DGModAction.Update
            , updatedDirective
            , baseRules
            , updatedRules
          )
        }
      }

      //display confirmation pop-up that also manage workflows

    }
  }

  //action must be 'enable' or 'disable'
  private[this] def onSubmitDisable(action: DGModAction): JsCmd = {
    displayConfirmationPopup(
        action
      , directive.copy(_isEnabled = !directive._isEnabled)
      , Nil
      , Nil
    )
  }

  private[this] def onSubmitDelete(): JsCmd = {
    displayConfirmationPopup(
        DGModAction.Delete
      , directive
      , Nil
      , Nil
    )
  }

  /*
   * Create the confirmation pop-up
   */
  private[this] def displayConfirmationPopup(
      action       : DGModAction
    , newDirective : Directive
    , baseRules    : List[Rule]
    , updatedRules : List[Rule]
  ) : JsCmd = {
    val optOriginal = { if(isADirectiveCreation) None else if(oldDirective.isEmpty) Some(directive) else oldDirective }
    // Find old root section if there is an initial State
    val rootSection = optOriginal.flatMap(old => techniqueRepo.get(TechniqueId(activeTechnique.techniqueName,old.techniqueVersion)).map(_.rootSection)).getOrElse(technique.rootSection)
    val change = DirectiveChangeRequest(action, technique.id.name, activeTechnique.id, rootSection, newDirective, optOriginal, baseRules, updatedRules)

    workflowLevelService.getForDirective(CurrentUser.actor, change) match {
      case eb: EmptyBox =>
        val msg = s"Error when getting the validation workflow for changes in directive '${change.newDirective.name}'"
        logger.warn(msg, eb)
        JsRaw(s"alert('${msg}')")
      case Full(workflowService) =>
        val popup = {
          // if it's not a creation and we have workflow, then we redirect to the CR
          val (successCallback, failureCallback) = {
            if(workflowService.needExternalValidation()) {
              (
                  (crId: ChangeRequestId) => onSuccessCallback(Right(crId))
                , (xml: NodeSeq) => JsRaw("$('#confirmUpdateActionDialog').bsModal('hide');") & onFailure
              )
            } else {
              val success = {
                if (action == DGModAction.Delete) {
                  val nSeq = <div id={ htmlId_policyConf }>Directive successfully deleted</div>
                  (_: ChangeRequestId) => JsRaw("$('#confirmUpdateActionDialog').bsModal('hide');") & onRemoveSuccessCallBack() & SetHtml(htmlId_policyConf, nSeq) &
                    successNotification()
                } else {
                  (_: ChangeRequestId)  => JsRaw("$('#confirmUpdateActionDialog').bsModal('hide');") & successNotification() & onSuccessCallback(Left(newDirective))
                }
              }

              (
                  success
                , (xml: NodeSeq) => JsRaw("$('#confirmUpdateActionDialog').bsModal('hide');") & onFailure
              )
            }
          }

          new ModificationValidationPopup(
              Left(change)
            , workflowService
            , onSuccessCallback = successCallback
            , onFailureCallback = failureCallback
            , onCreateSuccessCallBack = ( result => onSuccessCallback(result) & successNotification())
            , onCreateFailureCallBack = onFailure _
            , parentFormTracker = formTracker
          )
        }

        popup.popupWarningMessages match {
          case None =>
            popup.onSubmit
          case Some(_) =>
            SetHtml("confirmUpdateActionDialog", popup.popupContent) &
            JsRaw("""createPopup("confirmUpdateActionDialog")""")
        }
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

  private[this] def newCreationPopup(technique:Technique, activeTechnique:ActiveTechnique) : NodeSeq = {

    val popup = new CreateCloneDirectivePopup(
      technique.name, technique.description,
      technique.id.version, directive,
      onSuccessCallback =  dir => onSuccessCallback(Left(dir)))

    popup.popupContent
  }

  ///////////// success pop-up ///////////////

  private[this] def successNotification() : JsCmd = {
    JsRaw("""createSuccessNotification()""")
  }
}
