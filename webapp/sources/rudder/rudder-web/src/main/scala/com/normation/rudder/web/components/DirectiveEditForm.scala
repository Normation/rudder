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

import bootstrap.liftweb.RudderConfig
import com.normation.box.*
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.rudder.domain.RudderLDAPConstants
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.policies.PolicyMode.*
import com.normation.rudder.domain.policies.PolicyModeOverrides.Always
import com.normation.rudder.domain.policies.PolicyModeOverrides.Unoverridable
import com.normation.rudder.domain.workflows.*
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.services.workflows.DGModAction
import com.normation.rudder.services.workflows.DirectiveChangeRequest
import com.normation.rudder.users.CurrentUser
import com.normation.rudder.web.ChooseTemplate
import com.normation.rudder.web.components.popup.CreateCloneDirectivePopup
import com.normation.rudder.web.components.popup.ModificationValidationPopup
import com.normation.rudder.web.model.*
import com.normation.zio.UnsafeRun
import net.liftweb.common.*
import net.liftweb.http.*
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.*
import net.liftweb.util.Helpers.*
import org.apache.commons.text.StringEscapeUtils
import scala.xml.*

object DirectiveEditForm {

  private def body = ChooseTemplate(
    "templates-hidden" :: "components" :: "ComponentDirectiveEditForm" :: Nil,
    "component-body"
  )

  private def crForm = ChooseTemplate(
    "templates-hidden" :: "components" :: "ComponentDirectiveEditForm" :: Nil,
    "component-form"
  )
}

/**
 * The form that handles Directive edition (not creation)
 * - update name, description, parameters
 *
 * Parameters can not be null.
 */
class DirectiveEditForm(
    htmlId_policyConf:       String,
    technique:               Technique,
    activeTechnique:         ActiveTechnique,
    techniques:              Map[TechniqueVersion, Technique],
    val directive:           Directive,
    oldDirective:            Option[Directive],
    globalMode:              GlobalPolicyMode,
    isADirectiveCreation:    Boolean = false,
    onSuccessCallback:       (Either[Directive, ChangeRequestId]) => JsCmd,
    onMigrationCallback:     (Directive, Option[Directive]) => JsCmd,
    onFailureCallback:       () => JsCmd = { () => Noop },
    onRemoveSuccessCallBack: () => JsCmd = { () => Noop },
    displayTechniqueDetails: ActiveTechniqueId => JsCmd = { _ => Noop }
) extends SecureDispatchSnippet with Loggable {

  import DirectiveEditForm.*

  val currentDirectiveSettingForm = new LocalSnippet[DirectiveEditForm]

  private val directiveEditorService = RudderConfig.directiveEditorService
  private val techniqueRepo          = RudderConfig.techniqueRepository
  private val roRuleRepo             = RudderConfig.roRuleRepository
  private val roRuleCategoryRepo     = RudderConfig.roRuleCategoryRepository
  private val workflowLevelService   = RudderConfig.workflowLevelService
  private val ncfTechniqueService    = RudderConfig.ncfTechniqueReader

  private val htmlId_save     = htmlId_policyConf + "Save"
  private val parameterEditor = {
    directiveEditorService.get(technique.id, directive.id.uid, directive.parameters) match {
      case Full(pe)         => pe
      case Empty            => {
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

  private val checkRights = CurrentUser.checkRights

  val rules:        List[Rule]   = roRuleRepo.getAll(false).toBox.getOrElse(Seq()).toList
  val rootCategory: RuleCategory = roRuleCategoryRepo
    .getRootCategory()
    .toBox
    .getOrElse(
      throw new RuntimeException("Error when retrieving the rule root category - it is most likelly a bug. Pleae report.")
    )
  val directiveApp = new DirectiveApplicationManagement(directive, rules, rootCategory)

  def secureDispatch: QueryContext ?=> PartialFunction[String, NodeSeq => NodeSeq] = { case "showForm" => { _ => showForm() } }

  def isNcfTechnique(id: TechniqueId): Boolean = {
    val test = for {
      res                           <- ncfTechniqueService.readTechniquesMetadataFile
      (techniquesEditor, methods, _) = res
      ids                            = techniquesEditor.map(_.id.value)
    } yield {
      ids.contains(id.name.value)
    }

    test.toBox match {
      case Full(res) => res
      case eb: EmptyBox => false
    }
  }

  def showForm()(implicit qc: QueryContext): NodeSeq = {
    (
      "#container [id]" #> htmlId_policyConf &
      "#editForm" #> showDirectiveForm()
    )(body)
  }

  def migrateButton(version: => TechniqueVersion, text: String, id: String = "migrationButton"): Elem = {
    <lift:authz role="directive_write">
      {
      SHtml.ajaxSubmit(
        text,
        () => {
          val newDirective = directive.copy(techniqueVersion = version)
          onMigrationCallback(newDirective, Some(directive))
        },
        ("id"    -> id),
        ("class" -> "btn btn-default")
      )
    }
    </lift:authz>
  }

  val displayDeprecationWarning: CssSel = technique.deprecrationInfo match {
    case Some(info) =>
      ("#deprecation-message *" #> info.message &
      "#migrate-button *" #> {
        (for {
          lastTechniqueVersion <- techniques.toSeq.sortBy(_._1).reverse.map(_._2).headOption
          if (lastTechniqueVersion.id.version != directive.techniqueVersion)
        } yield {
          Text("Please upgrade to a new version: ") ++
          migrateButton(lastTechniqueVersion.id.version, "Migrate now!", "deprecation-migration")
        }).getOrElse(NodeSeq.Empty)
      })
    case None       =>
      ("#deprecation-warning [class+]" #> "d-none")
  }

  def updateRuleDisplayer()(using qc: QueryContext) = {
    val ruleDisplayer = {
      new RuleDisplayer(
        Some(directiveApp),
        "view",
        (_: Rule, _: String) => Noop,
        (_: Rule) => Noop,
        (_: Option[Rule]) => Noop,
        DisplayColumn.FromConfig,
        DisplayColumn.FromConfig
      ).display
    }
    SHtml.ajaxButton(
      "Target rules",
      () => SetHtml("directiveRulesTab", ruleDisplayer),
      ("class", "nav-link"),
      ("data-bs-toggle", "tab"),
      ("data-bs-target", "#rulesTab"),
      ("type", "button"),
      ("role", "tab"),
      ("aria-controls", "rulesTab"),
      ("aria-selected", "false")
    )
  }

  def showDirectiveForm()(implicit qc: QueryContext): NodeSeq = {

    val versionSelect   = if (isADirectiveCreation) {
      <div id="version" class="row wbBaseField form-group">
        <label for="version" class="col-sm-12 wbBaseFieldLabel">Technique version</label>
        <div  class="col-sm-12"><input  name="version" class="form-control" readonly="" value={
        directive.techniqueVersion.serialize
      }/></div>
      </div>
    } else { directiveVersion.toForm_! }
    val currentVersion  = showDeprecatedVersion(directive.techniqueVersion)
    // It is always a Full, but in case add a warning
    val versionSelectId = directiveVersion.uniqueFieldId match {
      case Full(id) => id
      case _        =>
        logger.warn("could not find id for migration select version")
        "id_not_found"
    }

    val (disableMessage, enableBtn) = (activeTechnique.isEnabled, directive._isEnabled) match {
      case (false, false) =>
        (
          "This Directive and its Technique are disabled.",
          <span>
            {SHtml.ajaxSubmit("Enable Directive", () => onSubmitDisable(DGModAction.Enable), ("class", "btn btn-sm btn-default"))}
            {
            SHtml.ajaxSubmit(
              "Enable Technique",
              () => displayTechniqueDetails(activeTechnique.id),
              ("class", "btn btn-sm btn-default")
            )
          }
          </span>
        )
      case (false, true)  =>
        (
          "The Technique of this Directive is disabled.",
          SHtml.ajaxSubmit(
            "Enable Technique",
            () => displayTechniqueDetails(activeTechnique.id),
            ("class", "btn btn-sm btn-default")
          )
        )
      case (true, false)  =>
        (
          "This Directive is disabled.",
          SHtml.ajaxSubmit("Enable", () => onSubmitDisable(DGModAction.Enable), ("class", "btn btn-sm btn-default"))
        )
      case (true, true)   =>
        ("", NodeSeq.Empty)
    }
    (
      "#editForm" #> { (n: NodeSeq) => SHtml.ajaxForm(n) } andThen
      // don't show the action button when we are creating a popup
      "#pendingChangeRequestNotification" #> { (xml: NodeSeq) =>
        PendingChangeRequestDisplayer.checkByDirective(xml, directive.id.uid, checkRights)
      } &
      "#existingPrivateDrafts" #> displayPrivateDrafts &
      "#existingChangeRequests" #> displayChangeRequests &
      ".topLevelAction" #> ((xml: NodeSeq) => {
        if (isADirectiveCreation) NodeSeq.Empty
        else xml
      }) andThen
      ClearClearable &
      // activation button: show disactivate if activated
      "#directiveTitle" #> <span>{directive.name} {
        if (activeTechnique.isEnabled) NodeSeq.Empty else <span class="badge-disabled"></span>
      }</span> &
      "#shortDescription" #> (if (directive.shortDescription.isEmpty) NodeSeq.Empty
                              else <div class="header-description"><p>{directive.shortDescription}</p></div>) &
      "#disactivateButtonLabel" #> {
        if (directive.isEnabled) "Disable" else "Enable"
      } &
      "#removeAction" #> {
        SHtml.ajaxSubmit("Delete", () => onSubmitDelete(), ("class", "btn btn-danger"))
      } &
      "#desactivateAction" #> {
        val status = directive.isEnabled ? DGModAction.Disable | DGModAction.Enable
        SHtml.ajaxSubmit(status.name.capitalize, () => onSubmitDisable(status), ("class", "btn btn-default"))
      } &
      "#clone" #> SHtml.ajaxButton(
        Text("Clone"),
        () => clonePopup(),
        ("class", "btn btn-default"),
        ("type", "button")
      ) &
      // form and form fields
      "#techniqueName *" #> {
        if (isNcfTechnique(technique.id)) {
          <a href={
            "/secure/configurationManager/techniqueEditor/technique/" +
            technique.id.name.value
          }>
              {technique.name}
              version
              {technique.id.version}
            </a>
        } else {
          <a href={
            "/secure/administration/maintenance#techniqueTree"
          }>
              {technique.name}
              version
              {technique.id.version}
            </a>
        }
      } &
      "#techniqueID *" #> technique.id.name.value &
      "#showTechniqueDescription *" #> <button type="button" class="btn btn-technical-details btn-default" onclick="$('#techniqueDescriptionPanel').toggle(400);$(this).toggleClass('opened');">technique description</button> &
      "#techniqueDescription *" #> technique.description &
      "#isDisabled" #> {
        if (!activeTechnique.isEnabled || !directive.isEnabled) {
          <div class="main-alert alert alert-warning">
            <i class="fa fa-exclamation-triangle" aria-hidden="true"></i>
            {disableMessage}
            {enableBtn}
          </div>
        } else NodeSeq.Empty
      } &
      "#nameField" #> { directiveName.toForm_! } &
      "#tagField *" #> tagsEditForm.tagsForm("directiveTags", "directiveEditTagsApp", updateTag, isRule = false) &
      "#directiveID *" #> { directive.id.uid.value } &
      "#shortDescriptionField" #> directiveShortDescription.toForm_! &
      "#longDescriptionField" #> directiveLongDescription.toForm_! &
      "#priority" #> directivePriority.toForm_! &
      "#policyModesLabel" #> policyModesLabel &
      "#policyModes" #> policyModes.toForm_! &
      "#version" #> versionSelect &
      "#version *+" #> (if (isADirectiveCreation) NodeSeq.Empty else migrateButton(directiveVersion.get, "Migrate")) &
      "#parameters" #> (if (!parameterEditor.isEditable) {
                          <div class="alert alert-info">This Technique has no configurable parameters.</div>
                        } else {
                          NodeSeq.Empty ++
                          parameterEditor.toFormNodeSeq
                        }) &
      "#rulesNav *" #> updateRuleDisplayer() &
      "#save" #> { SHtml.ajaxSubmit("Save", onSubmitSave) % ("id" -> htmlId_save) % ("class" -> "btn btn-success") } &
      "#notifications" #> displayNotifications() &
      "#showTechnical *" #> <button type="button" class="btn btn-technical-details btn-default" onclick="$('#technicalDetails').toggle(400);$(this).toggleClass('opened');">Technical details</button> &
      "#isSingle *" #> showIsSingle() &
      "#paramInfo *" #> showParametersLink() &
      displayDeprecationWarning
    )(crForm) ++
    Script(
      OnLoad(
        JsRaw(s"""
                 |activateButtonOnFormChange("${htmlId_policyConf}", "${htmlId_save}");
                 |setupMarkdown(${Str(directive.longDescription).toJsCmd}, "longDescriptionField")
                 |generateMarkdown(${Str(technique.longDescription).toJsCmd}, "#techniqueDescription")
                 |$$('#technicalDetails').hide();
                 |$$("input").not("#treeSearch").keydown( function(event) {
                 |  processKey(event , '${htmlId_save}');
                 |} );
                 |checkMigrationButton("${currentVersion}","${versionSelectId}");
                 |$$('#${directiveVersion.uniqueFieldId.getOrElse("id_not_found")}').change( function () {
                 |  checkMigrationButton("${currentVersion}","${versionSelectId}")
                 |} );
                 |var main = document.getElementById("directiveComplianceApp")
                 |var initValues = {
                 |  directiveId : "${StringEscapeUtils.escapeEcmaScript(directive.id.uid.value)}",
                 |  contextPath : contextPath
                 |};
                 |var app = Elm.Directivecompliance.init({node: main, flags: initValues});
                 |app.ports.errorNotification.subscribe(function(str) {
                 |  createErrorNotification(str)
                 |});
                 |// Initialize tooltips
                 |app.ports.initTooltips.subscribe(function(msg) {
                 |  setTimeout(function(){
                 |    initBsTooltips();
                 |  }, 400);
                 |});
                 |$$("#complianceLinkTab").on("click", function (){
                 |  app.ports.loadCompliance.send("");
                 |});
                 |if(${isADirectiveCreation}){
                 |$$("#complianceNav").hide();
                 |}else{
                 |$$("#complianceNav").show();
                 |}""".stripMargin) // JsRaw OK, input are encoded via encJs
      )
    )

  }

  private def clonePopup()(using qc: QueryContext): JsCmd = {
    SetHtml("basePopup", newCreationPopup(technique, activeTechnique)) &
    JsRaw(s""" initBsModal("basePopup"); """)
  }

  ////////////// Callbacks //////////////

  def addFormMsg(msg: NodeSeq): Unit = formTracker.addFormError(msg)

  private def onFailure(hasVariableErrors: Boolean = false)(implicit qc: QueryContext): JsCmd = {
    formTracker.addFormError(error("There was a problem with your request."))
    val cmd = if (hasVariableErrors) {
      showVariablesErrorNotifications()
    } else {
      showErrorNotifications()
    }
    formTracker.cleanErrors
    cmd
  }

  private def onNothingToDo()(implicit qc: QueryContext): JsCmd = {
    formTracker.addFormError(error("There are no modifications to save."))
    showErrorNotifications()
  }

  private def showVariablesErrorNotifications(): JsCmd = {
    // only replace notification container to avoid resetting the tab state
    onFailureCallback() & Replace("notification", displayNotifications())
  }

  private def showErrorNotifications()(implicit qc: QueryContext): JsCmd = {
    onFailureCallback() & Replace("editForm", showDirectiveForm())
  }

  private def showIsSingle(): NodeSeq = {
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

  private def showParametersLink(): NodeSeq = {
    if (isADirectiveCreation) {
      <div class="callout-fade callout-info">
        <div class="marker">
          <span class="fa fa-info-circle"></span>
        </div>
        <div class="">
          <p>You are creating a new Directive. You must set its parameters before saving.</p>
          <p>To do so, please go to the corresponding tab, or use the shortcut below:
        </p>
          <div class="action-btn">
            <button type="button" class="btn btn-primary btn-icon" onclick="document.querySelector('[data-bs-target=\'#parametersTab\']').click()">
              Set parameters
              <i class="fa fa-arrow-right"></i>
            </button>
          </div>
        </div>
      </div>
    } else {
      NodeSeq.Empty
    }
  }

  private def displayPrivateDrafts: Option[NodeSeq] = {
//TODO
//    for {
//      drafts <- roDraftChangeRequestRepository.getAll(actor, directive.id)
//    }
    None
  }

  private def displayChangeRequests: Option[NodeSeq] = {
    None
  }

  ///////////// fields for Directive settings ///////////////////

  private val directiveName = new WBTextField("Name", directive.name) {
    override def setFilter             = notNull :: trim :: Nil
    override def className             = "form-control"
    override def labelClassName        = "col-sm-12"
    override def subContainerClassName = "col-sm-12"
    override def errorClassName        = ""
    override def validations           =
      valMinLen(1, "Name must not be empty") :: Nil
  }

  private val directiveShortDescription = {
    new WBTextField("Short description", directive.shortDescription) {
      override def className             = "form-control"
      override def labelClassName        = "col-sm-12"
      override def subContainerClassName = "col-sm-12"
      override def setFilter             = notNull :: trim :: Nil
      override val maxLen                = 255
      override def validations: List[String => List[FieldError]] = Nil
    }
  }

  private val directiveLongDescription = {
    new WBTextAreaField("Description", directive.longDescription) {
      override def setFilter             = notNull :: trim :: Nil
      override def className             = "form-control"
      override def labelClassName        = ""
      override def subContainerClassName = ""
      override def containerClassName    = "col-6 pe-2"
      override def inputAttributes: Seq[(String, String)] = Seq(("rows", "15"))
      override def labelExtensions: NodeSeq               = {
        <i class="fa fa-check text-success cursorPointer half-opacity"     onmouseenter="toggleOpacity(this)" title="Valid description" onmouseout="toggleOpacity(this)" onclick="toggleMarkdownEditor('longDescriptionField')"></i> ++ Text(
          " "
        ) ++
        <i class="fa fa-eye-slash text-primary cursorPointer half-opacity" onmouseenter="toggleOpacity(this)" title="Show/hide preview" onmouseout="toggleOpacity(this)" onclick="togglePreview(this, 'longDescriptionField')"></i>
      }

    }
  }

  private val directivePriority = {
    val priorities = List(
      (0, "Highest"),
      (1, "+4"),
      (2, "+3"),
      (3, "+2"),
      (4, "+1"),
      (5, "Default"),
      (6, "-1"),
      (7, "-2"),
      (8, "-3"),
      (9, "-4"),
      (10, "Lowest")
    )
    new WBSelectObjField(
      "Priority",
      priorities,
      defaultValue = directive.priority
    ) {
      val tooltipContent = {
        s"""
           |<div>
           |              <h4> Priority </h4>
           |              <p>Priority has two uses depending if the technique from which that directive is derived is <b>Unique</b> or not.</p>
           |              <p>Unique directives can be applied only once (for example Time Settings), so only the highest priority will be applied.</p>
           |              <p>For <b>non-unique</b> directives, priority is used to choose the order of the directive application when applicable. The
           |                highest priority directive comes first.</p>
           |              <p>Note that overriding variable definitions are the ones coming last and so, the used value will be the one with the lowest priority.</p>
           |              <p>More information is available in <a href="https://docs.rudder.io/reference/current/usage/advanced_configuration_management.html#_special_use_case_overriding_generic_variable_definition">documentation about ordering directive application</a>.</p>
           |            </div>
           |""".stripMargin
      }
      override val displayHtml: NodeSeq = {
        <div>
          Priority
          <span>
            <span class="ruddericon fa fa-question-circle" data-bs-toggle="tooltip" title={tooltipContent}></span>
          </span>
        </div>
      }
      override def className             = "form-select"
      override def labelClassName        = "col-sm-12"
      override def subContainerClassName = "col-sm-12"
    }
  }

  private var newTags = directive.tags

  def updateTag(boxTag: Box[Tags]): Unit = {
    boxTag match {
      case Full(tags) => newTags = tags
      case eb: EmptyBox =>
        val failure = eb ?~! s"Error when updating directive ${directive.id.uid.value} tag"
        formTracker.addFormError(error(failure.messageChain))
    }
  }
  def tagsEditForm = new TagsEditForm(directive.tags, directive.id.uid.value)

  def showDeprecatedVersion(version: TechniqueVersion): String = {
    // here, we use default revision to get deprecation info, but we should likely have a per revision
    // deprecation message possible
    val deprecationInfo = techniques(version.withDefaultRev).deprecrationInfo match {
      case Some(_) => "(deprecated)"
      case None    => ""
    }
    s"${version.serialize} ${deprecationInfo}"
  }
  private val globalOverrideText = globalMode.overridable match {
    case Always        =>
      <div>
        You may override the agent policy mode on this directive.
        If set to <b>Audit</b> this directive will never be enforced.
        If set to <b>Enforce</b> this directive will appply necessary changes except on nodes with a <b>Verify</b> override setting.
      </div>
    case Unoverridable =>
      <p>
        Currrent global settings do not allow this mode to be overridden on a per-directive bases. You may change this in <b>Settings</b>,
        or contact your Rudder administrator about this.
      </p>
  }

  private val policyModesLabel = {
    val tooltipContent = {
      s"""
         |<div>
         |          <h4>Policy mode</h4>
         |          <p>Configuration rules in Rudder can operate in one of two modes:</p>
         |          <ol>
         |            <li><b>Audit</b>: the agent will examine configurations and report any differences, but will not make any changes</li>
         |            <li><b>Enforce</b>: the agent will make changes to fix any configurations that differ from your directives</li>
         |          </ol>
         |          <p>
         |            By default all nodes and all directives operate in the global default mode defined in
         |            <b> Settings</b>, which is currently <b>${globalMode.mode.name}</b>.
         |          </p>
         |          ${globalOverrideText}
         |        </div>
         |""".stripMargin
    }
    <label class="wbBaseFieldLabel">
      Policy mode
      <span>
        <span class="ruddericon fa fa-question-circle" data-bs-toggle="tooltip" title={tooltipContent}></span>
      </span>
    </label>
  }

  private val policyModes = {
    val l           = Seq(
      "global",
      "audit",
      "enforce"
    )
    val defaultMode = directive.policyMode match {
      case Some(Enforce) => "enforce"
      case Some(Audit)   => "audit"
      case _             => "global"
    }
    new WBRadioField(
      "Policy Mode",
      l,
      defaultMode,
      {
        case "global"  =>
          <span class="global-btn">Global mode (<span class={s"global-mode " ++ globalMode.mode.name}></span>)</span>
        case "audit"   => <span class="audit-btn">Audit</span>
        case "enforce" => <span class="enforce-btn">Enforce</span>
        case _         => NodeSeq.Empty
      }
    ) {
      override def setFilter             = notNull :: trim :: Nil
      override def className             = "checkbox-group policymode-group"
      override def labelClassName        = "d-none"
      override def subContainerClassName = "col-sm-12"
    }
  }

  val versions: Seq[(TechniqueVersion, String)] = techniques.keys.map(v => (v, showDeprecatedVersion(v))).toSeq.sortBy(_._1)

  private val directiveVersion = {
    val attributes = ("id" -> "selectVersion") ::
      (if (isADirectiveCreation) {
         ("disabled" -> "true") :: Nil
       } else {
         Nil
       })

    new WBSelectObjField(
      "Technique version",
      versions,
      directive.techniqueVersion,
      attributes
    ) {

      override def className = "form-select"

      override def labelClassName = "col-sm-12"

      override def subContainerClassName = "version-group w-auto"
    }
  }

  private val formTracker = {
    val l = List(directiveName, directiveShortDescription, directiveLongDescription) // ++ crReasons
    new FormTracker(l)
  }

  private def error(msg: String) = <span class="error">{msg}</span>

  // Returns true if there is any error
  private def checkVariables(): Boolean = {
    !parameterEditor.mapValueSeq.forall { vars =>
      try {
        val s = Seq(parameterEditor.variableSpecs(vars._1).toVariable(vars._2))
        RudderLDAPConstants.variableToSeq(s)
        true
      } catch {
        case e: Exception =>
          formTracker.addFormError(error(e.getMessage))
          false
      }
    }
  }

  private def onSubmitSave()(implicit qc: QueryContext): JsCmd = {
    val hasVariableErrors = checkVariables()

    if (formTracker.hasErrors) {
      onFailure(hasVariableErrors)
    } else {
      val (addRules, removeRules) = directiveApp.checkRulesToUpdate
      val baseRules               = (addRules ++ removeRules).sortBy(_.id.serialize)

      val finalAdd     = addRules.map(r => r.copy(directiveIds = r.directiveIds + directive.id))
      val finalRem     = removeRules.map(r => r.copy(directiveIds = r.directiveIds - directive.id))
      val updatedRules = (finalAdd ++ finalRem).sortBy(_.id.serialize)

      val newPolicyMode = policyModes.get match {
        case "global"  => None
        case "enforce" => Some(Enforce)
        case "audit"   => Some(Audit)
      }

      if (isADirectiveCreation) {

        // On creation, don't create workflow
        // does some rules are assigned to that new directive ?
        val action = if (baseRules.toSet == updatedRules.toSet) {
          DGModAction.CreateSolo
        } else {
          DGModAction.CreateAndModRules
        }

        val newDirective = directive.copy(
          parameters = parameterEditor.mapValueSeq,
          name = directiveName.get,
          shortDescription = directiveShortDescription.get,
          priority = directivePriority.get,
          longDescription = directiveLongDescription.get,
          _isEnabled = directive.isEnabled,
          policyMode = newPolicyMode,
          tags = newTags
        )

        displayConfirmationPopup(
          action,
          newDirective,
          baseRules,
          updatedRules
        )
      } else {
        // check if it's a migration - old directive present with a different technique version
        val isMigration = oldDirective.map(_.techniqueVersion != directive.techniqueVersion).getOrElse(false)

        val updatedDirective = directive.copy(
          parameters = parameterEditor.mapValueSeq,
          name = directiveName.get,
          shortDescription = directiveShortDescription.get,
          priority = directivePriority.get,
          longDescription = directiveLongDescription.get,
          policyMode = newPolicyMode,
          tags = newTags
        )

        if ((!isMigration && directive == updatedDirective && updatedRules.isEmpty)) {
          onNothingToDo()
        } else {
          displayConfirmationPopup(
            DGModAction.Update,
            updatedDirective,
            baseRules,
            updatedRules
          )
        }
      }

      // display confirmation pop-up that also manage workflows

    }
  }

  // action must be 'enable' or 'disable'
  private def onSubmitDisable(action: DGModAction)(implicit qc: QueryContext): JsCmd = {
    displayConfirmationPopup(
      action,
      directive.copy(_isEnabled = !directive._isEnabled),
      Nil,
      Nil
    )
  }

  private def onSubmitDelete()(implicit qc: QueryContext): JsCmd = {
    displayConfirmationPopup(
      DGModAction.Delete,
      directive,
      Nil,
      Nil
    )
  }

  /*
   * Create the confirmation pop-up
   */
  private def displayConfirmationPopup(
      action:       DGModAction,
      newDirective: Directive,
      baseRules:    List[Rule],
      updatedRules: List[Rule]
  )(implicit qc: QueryContext): JsCmd = {
    val optOriginal = { if (isADirectiveCreation) None else if (oldDirective.isEmpty) Some(directive) else oldDirective }
    // Find old root section if there is an initial State
    val rootSection = optOriginal
      .flatMap(old => techniqueRepo.get(TechniqueId(activeTechnique.techniqueName, old.techniqueVersion)).map(_.rootSection))
      .getOrElse(technique.rootSection)
    val change      = DirectiveChangeRequest(
      action,
      technique.id.name,
      activeTechnique.id,
      rootSection,
      newDirective,
      optOriginal,
      baseRules,
      updatedRules
    )

    workflowLevelService
      .getForDirective(qc.actor, change)
      .chainError(s"Error when getting the validation workflow for changes in directive '${change.newDirective.name}'")
      .either
      .runNow match {
      case Left(err)              =>
        logger.warn(err.fullMsg)
      case Right(workflowService) =>
        val popup = {
          // if it's not a creation and we have workflow, then we redirect to the CR
          val (successCallback, failureCallback) = {
            if (workflowService.needExternalValidation()) {
              (
                (crId: ChangeRequestId) => onSuccessCallback(Right(crId)),
                (xml: NodeSeq) => JsRaw("hideBsModal('basePopup');") & onFailure()
              )
            } else {
              val success = {
                if (action == DGModAction.Delete) {
                  val nSeq = <style>#policyConfiguration{{height: initial !important;}}</style>
                  (_: ChangeRequestId) =>
                    JsRaw("hideBsModal('basePopup');") & onRemoveSuccessCallBack() & SetHtml(htmlId_policyConf, nSeq) &
                    successNotification("Directive successfully deleted")
                } else { (_: ChangeRequestId) =>
                  JsRaw("hideBsModal('basePopup');") & successNotification("") & onSuccessCallback(Left(newDirective))
                }
              }

              (
                success,
                (xml: NodeSeq) => JsRaw("hideBsModal('basePopup');") & onFailure()
              )
            }
          }

          new ModificationValidationPopup(
            Left(change),
            workflowService,
            onSuccessCallback = successCallback,
            onFailureCallback = failureCallback,
            onCreateSuccessCallBack = (result => onSuccessCallback(result) & successNotification("")),
            onCreateFailureCallBack = () => onFailure(),
            parentFormTracker = formTracker
          )
        }

        popup.popupWarningMessages match {
          case None    =>
            popup.onSubmit()
          case Some(_) =>
            SetHtml("basePopup", popup.popupContent()) &
            JsRaw("""initBsModal("basePopup")""")
        }
    }
  }

  private def displayNotifications(): NodeSeq = {
    val notifications = formTracker.formErrors

    if (notifications.isEmpty) {
      <div id="notification"></div>
    } else {
      <div id="notification" class="main-alert alert alert-danger">
        <ul>{notifications.map(n => <li>{n}</li>)}</ul>
      </div>
    }
  }

  private def newCreationPopup(technique: Technique, activeTechnique: ActiveTechnique)(using qc: QueryContext): NodeSeq = {

    val popup = new CreateCloneDirectivePopup(
      technique.name,
      technique.description,
      technique.id.version,
      directive,
      onSuccessCallback = dir => onSuccessCallback(Left(dir))
    )

    popup.popupContent()
  }

  ///////////// success pop-up ///////////////

  private def successNotification(msg: String): JsCmd = {
    JsRaw(s"""createSuccessNotification("${msg}")""")
  }
}
