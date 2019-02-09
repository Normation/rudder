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

import scala.xml.NodeSeq
import scala.xml.Text
import com.normation.plugins.DefaultExtendableSnippet
import com.normation.rudder.AuthorizationType
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.FullRuleTargetInfo
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.web.components.popup.RuleModificationValidationPopup
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.web.model.FormTracker
import com.normation.rudder.web.model.WBTextAreaField
import com.normation.rudder.web.model.WBTextField
import com.normation.rudder.web.services.DisplayDirectiveTree
import com.normation.rudder.web.services.DisplayNodeGroupTree
import bootstrap.liftweb.RudderConfig
import net.liftweb.common._
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.S
import net.liftweb.http.SHtml
import net.liftweb.http.SHtml.ElemAttr.pairToBasic
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmd
import net.liftweb.http.js.JsCmds._
import net.liftweb.json._
import net.liftweb.util.ClearClearable
import net.liftweb.util.Helpers._
import com.normation.rudder.web.model.WBSelectField
import com.normation.rudder.domain.policies.TargetExclusion
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.Tags
import com.normation.rudder.services.workflows.RuleChangeRequest
import com.normation.rudder.services.workflows.RuleModAction
import com.normation.rudder.web.ChooseTemplate

object RuleEditForm {

  /**
   * This is part of component static initialization.
   * Any page which contains (or may contains after an ajax request)
   * that component have to add the result of that method in it.
   */
  def staticInit:NodeSeq = ChooseTemplate(
      "templates-hidden" :: "components" :: "ComponentRuleEditForm" :: Nil
    , "component-staticinit"
  )

  private def body = ChooseTemplate(
      "templates-hidden" :: "components" :: "ComponentRuleEditForm" :: Nil
    , "component-body"
  )

  private def crForm = ChooseTemplate(
      "templates-hidden" :: "components" :: "ComponentRuleEditForm" :: Nil
    , "component-form"
  )

  val htmlId_groupTree = "groupTree"
  val htmlId_activeTechniquesTree = "directiveTree"
}

/**
 * The form that handles Rule edition
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
class RuleEditForm(
    htmlId_rule       : String //HTML id for the div around the form
  , var rule          : Rule //the Rule to edit
  , changeMsgEnabled  : Boolean
  , onSuccessCallback : (Rule) => JsCmd = { (r : Rule) => Noop } //JS to execute on form success (update UI parts)
  //there are call by name to have the context matching their execution when called,
  , onFailureCallback : () => JsCmd = { () => Noop }
  , onCloneCallback   : (Rule) => JsCmd = { (r:Rule) => Noop }
) extends DispatchSnippet with DefaultExtendableSnippet[RuleEditForm] with Loggable {
  import RuleEditForm._

  private[this] val htmlId_save     = htmlId_rule + "Save"
  private[this] val htmlId_EditZone = "editRuleZone"

  private[this] val roRuleRepository           = RudderConfig.roRuleRepository
  private[this] val categoryHierarchyDisplayer = RudderConfig.categoryHierarchyDisplayer

  private[this] var ruleTarget           = RuleTarget.merge(rule.targets)
  private[this] var selectedDirectiveIds = rule.directiveIds

  private[this] val getFullNodeGroupLib = RudderConfig.roNodeGroupRepository.getFullGroupLibrary _
  private[this] val getFullDirectiveLib = RudderConfig.roDirectiveRepository.getFullDirectiveLibrary _
  private[this] val getAllNodeInfos     = RudderConfig.nodeInfoService.getAll _
  private[this] val getRootRuleCategory = RudderConfig.roRuleCategoryRepository.getRootCategory _
  private[this] val configService       = RudderConfig.configService
  private[this] val linkUtil            = RudderConfig.linkUtil

  private[this] val workflow            = RudderConfig.workflowLevelService

  //////////////////////////// public methods ////////////////////////////

  def mainDispatch = Map(
      "showForm"          -> { _:NodeSeq => showForm() }
    , "showRecentChanges" -> { _:NodeSeq => showForm("changesGrid") }
  )

  private[this] val boxRootRuleCategory = getRootRuleCategory()

  private[this] def showForm(idToScroll  : String = "editRuleZonePortlet") : NodeSeq = {
    (getFullNodeGroupLib(), getFullDirectiveLib(), getAllNodeInfos(), boxRootRuleCategory, configService.rudder_global_policy_mode()) match {
      case (Full(groupLib), Full(directiveLib), Full(nodeInfos), Full(rootRuleCategory), Full(globalMode)) =>

        val form = {
          if(CurrentUser.checkRights(AuthorizationType.Rule.Read)) {
            val formContent = if (CurrentUser.checkRights(AuthorizationType.Rule.Read)) {
              showCrForm(groupLib, directiveLib, globalMode)
            } else {
              <div>You have no rights to see rules details, please contact your administrator</div>
            }
            (
                s"#${htmlId_EditZone}" #> { (n:NodeSeq) => SHtml.ajaxForm(n) } andThen
                ClearClearable &
              "#ruleForm" #> formContent &
              actionButtons()
            ).apply(body)

          } else {
            <div>You have no rights to see rules details, please contact your administrator</div>
          }
        }

        form ++
        Script(
          OnLoad(JsRaw( s"""
            var tab_stored = JSON.parse(localStorage.getItem('Active_Rule_Tab'));
            var active_tab = isNaN(tab_stored) ? 1 : tab_stored;
            $$($$('.rules-nav-tab > li > a').get(active_tab)).bsTab('show');
          """.stripMargin)) &
          JsRaw(s"""
            var target = $$( ".nav-tabs .active a" ).attr('href');
            if(target=="#ruleComplianceTab"){
              $$('#changeIconRule').addClass('fa-area-chart');
            }else{
              $$('#changeIconRule').removeClass('fa-area-chart');
            }
            $$("#editRuleZonePortlet").removeClass("nodisplay");
            ${Replace("details", new RuleCompliance(rule, rootRuleCategory).display).toJsCmd};
            scrollToElement("${idToScroll}", "body,html");
            $$('a[data-toggle="tab"]').on('shown.bs.tab', function (e) {
              var target = $$(e.target).attr("href") // activated tab
              if(target=="#ruleComplianceTab"){
                $$('#changeIconRule').addClass('fa-area-chart');
                localStorage.setItem('Active_Rule_Tab', 1);
              }else{
                $$('#changeIconRule').removeClass('fa-area-chart');
                localStorage.setItem('Active_Rule_Tab', 0);
              }
            });
            """
        ))

      case (groupLib, directiveLib, allNodes, rootRuleCategory, globalMode) =>
        List(groupLib, directiveLib, allNodes, rootRuleCategory, globalMode).collect{ case eb: EmptyBox =>
          val e = eb ?~! "An error happens when trying to get rule datas"
          logger.error(e.messageChain)
          <div class="error">{e.msg}</div>
        }
    }
  }

  private[this] def actionButtons () = {
    "#removeAction *" #> {
         SHtml.ajaxButton("Delete", () => onSubmitDelete(),("class","btn btn-danger"))
       } &
       "#desactivateAction *" #> {
         val status = if(rule.isEnabledStatus) { ("Disable", RuleModAction.Disable) } else { ("Enable", RuleModAction.Enable) }
         SHtml.ajaxButton(status._1, () => onSubmitDisable(status._2) ,("class","btn btn-default"))
       } &
      "#clone" #> SHtml.ajaxButton(
                      { Text("Clone") }
                    , { () =>  onCloneCallback(rule) }
                    , ("type", "button")
                    ,("class","btn btn-default")
      ) &
      "#save" #> saveButton

  }

  private[this] def showCrForm(groupLib: FullNodeGroupCategory, directiveLib: FullActiveTechniqueCategory, globalMode : GlobalPolicyMode) : NodeSeq = {

    val usedDirectiveIds = roRuleRepository.getAll().getOrElse(Seq()).flatMap { case r =>
      r.directiveIds.map( id => (id -> r.id))
    }.groupBy( _._1 ).mapValues( _.size).toSeq

    //is't there an other way to do that? We already have the target/name
    //in the tree, so there's just the existing id to find back
    val maptarget = groupLib.allTargets.map{
      case (gt,fg) => s" ${encJs(gt.target)} : ${encJs(fg.name)}"
    }.toList.mkString("{",",","}")

    val selectedDirectives =
      (for {
        id <- selectedDirectiveIds
        (_,directive) <-  directiveLib.allDirectives.get(id)
      } yield {
         s" ${encJs(id.value)} : ${encJs(directive.name)}"
      }).mkString("{",",","}")

    val includedTarget = ruleTarget.includedTarget.targets
    val excludedTarget = ruleTarget.excludedTarget.targets

    (
      "#pendingChangeRequestNotification" #> { xml:NodeSeq =>
          PendingChangeRequestDisplayer.checkByRule(xml, rule.id)
        } &
      //activation button: show disactivate if activated
      "#disactivateButtonLabel" #> { if(rule.isEnabledStatus) "Disable" else "Enable" } &
      "#nameField" #> crName.toForm_! &
      "#categoryField" #> category.toForm_! &
      "#shortDescriptionField" #> crShortDescription.toForm_! &
      "#longDescriptionField" #> crLongDescription.toForm_! &
      "#tagField" #> tagsEditForm.tagsForm("ruleTags", "ruleEditTagsApp", updateTag, true) &
      "#selectPiField" #> {
        <div id={htmlId_activeTechniquesTree}>{
          <ul>{
            DisplayDirectiveTree.displayTree(
                directiveLib
              , globalMode
              , usedDirectiveIds = usedDirectiveIds
              , onClickCategory = None
              , onClickTechnique = None
              , onClickDirective = Some((_,_,d) => directiveClick(d))
              , addEditLink = true
              , included = selectedDirectiveIds
                //filter techniques without directives, and categories without technique
              , keepCategory    = category => category.allDirectives.nonEmpty
              , keepTechnique   = technique => technique.directives.nonEmpty
            )
          }</ul>
        }</div> } &
      "#selectGroupField" #> {
        <div id={htmlId_groupTree}>
          <ul>{DisplayNodeGroupTree.displayTree(
              groupLib
            , None
            , Some( ( (_,target) => targetClick (target)))
            , Map(
                  "include" -> includeRuleTarget _
                , "exclude" -> excludeRuleTarget _
              )
            , includedTarget
            , excludedTarget
          )}</ul>
        </div> } &
      "#notifications" #>  updateAndDisplayNotifications
    )(crForm) ++ Script(
      OnLoad(
        // Initialize angular part of page and group tree
        JsRaw(s"""
          if(!angular.element('#groupManagement').scope()){
            angular.bootstrap('#groupManagement', ['groupManagement']);
          }
          var scope = angular.element($$("#GroupCtrl")).scope();
          scope.$$apply(function(){
            scope.init(${ruleTarget.toString()},${maptarget});
          } );
          buildGroupTree('#${htmlId_groupTree}','${S.contextPath}', [], 'on', undefined, false);"""
        ) &
        //function to update list of PIs before submiting form
        JsRaw(s"""
          if(!angular.element('#ruleDirectives').scope()){
            angular.bootstrap('#ruleDirectives', ['ruleDirectives']);
          }
          var ruleDirectiveScope = angular.element($$("#DirectiveCtrl")).scope();
          ruleDirectiveScope.$$apply(function(){
            ruleDirectiveScope.init(${selectedDirectives});
          } );
          buildDirectiveTree('#${htmlId_activeTechniquesTree}', ${serializedirectiveIds(selectedDirectiveIds.toSeq)},'${S.contextPath}', 0);
        """) &
        After(TimeSpan(50), JsRaw("""createTooltip();"""))
      )
    )
  }

  /*
   * from a list of PI ids, get a string.
   * the format is a JSON array: [ "id1", "id2", ...]
   */
  private[this] def serializedirectiveIds(ids:Seq[DirectiveId]) : String = {
    implicit val formats = Serialization.formats(NoTypeHints)
    Serialization.write(ids.map( "jsTree-" + _.value ))
  }

  /*
   * from a JSON array: [ "id1", "id2", ...], get the list of
   * Directive Ids.
   * Never fails, but returned an empty list.
   */
  private[this] def unserializedirectiveIds(ids:String) : Seq[DirectiveId] = {
    implicit val formats = DefaultFormats
    parse(ids).extract[List[String]].map( x => DirectiveId(x.replace("jsTree-","")) )
  }

  private[this] def unserializeTarget(target:String)  = {
      RuleTarget.unser(target).map {
        case exclusionTarget : TargetExclusion => exclusionTarget
        case t => RuleTarget.merge(Set(t))}.getOrElse(RuleTarget.merge(Set()))
  }

  ////////////// Callbacks //////////////

  private[this] def updateFormClientSide() : JsCmd = {
    Replace(htmlId_EditZone, this.showForm() )
  }

  private[this] def onSuccess() : JsCmd = {
    //MUST BE THIS WAY, because the parent may change some reference to JsNode
    //and so, our AJAX could be broken
    onSuccessCallback(rule) & updateFormClientSide() &
    //show success popup
    successPopup
  }

  private[this] def onFailure() : JsCmd = {
    onFailureCallback() &
    updateFormClientSide() &
    JsRaw("""scrollToElement("notifications", ".rudder_col");""")
  }

  private[this] def onNothingToDo() : JsCmd = {
    formTracker.addFormError(error("There are no modifications to save."))
    onFailure()
  }

  /*
   * Create the ajax save button
   */
  private[this] def saveButton : NodeSeq = {
    // add an hidden field to hold the list of selected directives
    val save = SHtml.ajaxSubmit("Save", onSubmit _) % ("id" -> htmlId_save) % ("class" -> "btn btn-success")
    // update onclick to get the list of directives and groups in the hidden
    // fields before submitting

    SHtml.hidden( { ids =>
        selectedDirectiveIds = unserializedirectiveIds(ids).toSet
      }, serializedirectiveIds(selectedDirectiveIds.toSeq)
    ) % ( "id" -> "selectedDirectives") ++
    SHtml.hidden( { target =>
        ruleTarget = unserializeTarget(target)
      }, ruleTarget.target
    ) % ( "id" -> "selectedTargets") ++
    save
  }

  private[this] def targetClick(targetInfo: FullRuleTargetInfo) : JsCmd = {
    val target = targetInfo.target.target.target
    JsRaw(s"""onClickTarget("${target}");""")
  }

  private[this] def directiveClick(directive: Directive) : JsCmd = {
    JsRaw(s"""onClickDirective("${directive.id.value}", ${directive.name.encJs});""")
  }

  private[this] def includeRuleTarget(targetInfo: FullRuleTargetInfo) : JsCmd = {
    val target = targetInfo.target.target.target
    JsRaw(s"""includeTarget(event, "${target}");""")
  }

  private[this] def excludeRuleTarget(targetInfo: FullRuleTargetInfo) : JsCmd = {
    val target = targetInfo.target.target.target
    JsRaw(s"""excludeTarget(event, "${target}");""")
  }

  /////////////////////////////////////////////////////////////////////////
  /////////////////////////////// Edit form ///////////////////////////////
  /////////////////////////////////////////////////////////////////////////

  ///////////// fields for Rule settings ///////////////////

  private[this] var newTags = rule.tags

  def updateTag (boxTag : Box[Tags]) = {
    boxTag match {
      case Full(tags) => newTags = tags
      case eb : EmptyBox =>
        val failure = eb ?~! s"Error when updating Rule ${rule.id.value} tag"
        formTracker.addFormError(error(failure.messageChain))
    }
  }
  def tagsEditForm = new TagsEditForm(rule.tags)

  private[this] val crName = new WBTextField("Name", rule.name) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def className = "form-control"
    override def labelClassName = "col-xs-12"
    override def subContainerClassName = "col-xs-12"
    override def validations =
      valMinLen(1, "Name must not be empty") _ :: Nil
  }

  private[this] val crShortDescription = {
    new WBTextField("Short description", rule.shortDescription) {
      override def className = "form-control"
      override def labelClassName = "col-xs-12"
      override def subContainerClassName = "col-xs-12"
      override def setFilter = notNull _ :: trim _ :: Nil
      override val maxLen = 255
      override def validations =  Nil
    }
  }

  private[this] val crLongDescription = {
    new WBTextAreaField("Description", rule.longDescription.toString) {
      override def setFilter = notNull _ :: trim _ :: Nil
      override def className = "form-control"
      override def labelClassName = "col-xs-12"
      override def subContainerClassName = "col-xs-12"
    }
  }

  private[this] val category = {
    //if root is not defined, the error message is managed on showForm
    val values = boxRootRuleCategory.map { r =>
      categoryHierarchyDisplayer.getRuleCategoryHierarchy(r, None).map { case (id, name) => (id.value -> name)}
    }.getOrElse(Nil)

    new WBSelectField("Rule category", values, rule.categoryId.value) {
      override def className = "form-control"
      override def labelClassName = "col-xs-12 text-bold"
      override def subContainerClassName = "col-xs-12"
    }
  }

  private[this] val formTracker = new FormTracker(List(crName, crShortDescription, crLongDescription))

  private[this] def error(msg:String) = <span class="error">{msg}</span>

  private[this] def onSubmit() : JsCmd = {
    if(formTracker.hasErrors) {
      onFailure
    } else { //try to save the rule
      val newCr = rule.copy(
          name             = crName.get
        , shortDescription = crShortDescription.get
        , longDescription  = crLongDescription.get
        , targets          = Set(ruleTarget)
        , directiveIds     = selectedDirectiveIds
        , isEnabledStatus  = rule.isEnabledStatus
        , categoryId       = RuleCategoryId(category.get)
        , tags             = newTags
      )

      if (newCr == rule) {
        onNothingToDo()
      } else {
        displayConfirmationPopup(RuleModAction.Update, newCr)
      }
    }
  }

   //action must be 'enable' or 'disable'
  private[this] def onSubmitDisable(action: RuleModAction): JsCmd = {
    displayConfirmationPopup(
        action
      , rule.copy(isEnabledStatus = action == RuleModAction.Enable)
    )
  }

  private[this] def onSubmitDelete(): JsCmd = {
    displayConfirmationPopup(
        RuleModAction.Delete
      , rule
    )
  }

  // Create the popup for workflow
  private[this] def displayConfirmationPopup(
      action  : RuleModAction
    , newRule : Rule
  ) : JsCmd = {

    val change = RuleChangeRequest(action, newRule, Some(rule))
    workflow.getForRule(CurrentUser.actor, change) match {
      case eb: EmptyBox =>
        val msg = "An error occured when trying to find the validation workflow to use for that change."
        logger.error(msg, eb)
        JsRaw(s"alert('${msg}')")

      case Full(workflowService) =>

        val popup = new RuleModificationValidationPopup(
            change
          , workflowService
          , cr => workflowCallBack(workflowService.needExternalValidation(), action)(cr)
          , () => JsRaw("$('#confirmUpdateActionDialog').bsModal('hide');") & onFailure
          , parentFormTracker = Some(formTracker)
        )

        if((!changeMsgEnabled) && (!workflowService.needExternalValidation())) {
          popup.onSubmit
        } else {
          SetHtml("confirmUpdateActionDialog", popup.popupContent) &
          JsRaw("""createPopup("confirmUpdateActionDialog")""")
        }
    }
  }

  private[this] def workflowCallBack(workflowEnabled: Boolean, action: RuleModAction)(returns : Either[Rule,ChangeRequestId]) : JsCmd = {
    if ((!workflowEnabled) & (action == RuleModAction.Delete)) {
      JsRaw("$('#confirmUpdateActionDialog').bsModal('hide');") & onSuccessCallback(rule) & SetHtml("editRuleZone",
          <div id={htmlId_rule}> Rule '{rule.name}' successfully deleted</div>
      )
    } else {
      returns match {
        case Left(rule) => // ok, we've received a rule, do as before
          this.rule = rule
          JsRaw("$('#confirmUpdateActionDialog').bsModal('hide');") &  onSuccess
        case Right(changeRequestId) => // oh, we have a change request, go to it
          linkUtil.redirectToChangeRequestLink(changeRequestId)
      }
    }
  }

  private[this] def updateAndDisplayNotifications : NodeSeq = {
    val notifications = formTracker.formErrors
    formTracker.cleanErrors

    if(notifications.isEmpty) {
      <div id="notifications" />
    }
    else {
      val html =
        <div id="notifications" class="notify">
          <ul class="field_errors">{notifications.map( n => <li>{n}</li>) }</ul>
        </div>
      html
    }
  }

  ///////////// success pop-up ///////////////
  private[this] def successPopup : JsCmd = {
    val warningNotification =
      ((ruleTarget.excludedTarget.targets.size + ruleTarget.includedTarget.targets.size == 0), (selectedDirectiveIds.size == 0)) match{
        case (true, true)  => JsRaw("""createWarningNotification("This Rule is not applied to any Groups and does not have any Directives to apply.")""")
        case (true, false) => JsRaw("""createWarningNotification("This Rule is not applied to any Groups.")""")
        case (false, true) => JsRaw("""createWarningNotification("This Rule does not have any Directives to apply.")""")
        case (_)           => JsRaw("")
      }

    JsRaw("""createSuccessNotification()""") & warningNotification
  }

}
