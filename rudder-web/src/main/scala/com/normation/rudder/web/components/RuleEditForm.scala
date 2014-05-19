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

import scala.xml.NodeSeq
import scala.xml.Text
import com.normation.inventory.domain.NodeId
import com.normation.plugins.SnippetExtensionKey
import com.normation.plugins.SpringExtendableSnippet
import com.normation.rudder.authorization.Edit
import com.normation.rudder.authorization.Read
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.FullRuleTargetInfo
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.domain.policies.TargetUnion
import com.normation.rudder.domain.policies.TargetExclusion
import com.normation.rudder.domain.reports.bean._
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
import net.liftweb.http.SHtml.BasicElemAttr
import net.liftweb.http.SHtml.ElemAttr.pairToBasic
import net.liftweb.http.Templates
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmd
import net.liftweb.http.js.JsCmds._
import net.liftweb.json._
import net.liftweb.util.ClearClearable
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers._
import com.normation.rudder.domain.nodes.NodeInfo
import org.joda.time.DateTime
import com.normation.rudder.web.model.WBSelectField
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.domain.policies.TargetExclusion
import com.normation.rudder.domain.policies.TargetComposition
import com.normation.rudder.web.services.CategoryHierarchyDisplayer
import com.normation.rudder.rule.category.RoRuleCategoryRepository
import com.normation.rudder.rule.category.RuleCategoryId

object RuleEditForm {

  /**
   * This is part of component static initialization.
   * Any page which contains (or may contains after an ajax request)
   * that component have to add the result of that method in it.
   */
  def staticInit:NodeSeq =
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentRuleEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "staticInit", xml)
    }) openOr Nil

  private def body =
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentRuleEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "body", xml)
    }) openOr Nil

  private def crForm =
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentRuleEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "form", xml)
    }) openOr Nil


  val htmlId_groupTree = "groupTree"
  val htmlId_activeTechniquesTree = "userPiTree"
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
  , workflowEnabled   : Boolean
  , changeMsgEnabled  : Boolean
  , onSuccessCallback : (Rule) => JsCmd = { (r : Rule) => Noop } //JS to execute on form success (update UI parts)
  //there are call by name to have the context matching their execution when called,
  , onFailureCallback : () => JsCmd = { () => Noop }
  , onCloneCallback   : (Rule) => JsCmd = { (r:Rule) => Noop }
) extends DispatchSnippet with SpringExtendableSnippet[RuleEditForm] with Loggable {
  import RuleEditForm._

  private[this] val htmlId_save = htmlId_rule + "Save"
  private[this] val htmlId_EditZone = "editRuleZone"

  private[this] val roRuleRepository     = RudderConfig.roRuleRepository
  private[this] val roCategoryRepository = RudderConfig.roRuleCategoryRepository
  private[this] val userPropertyService  = RudderConfig.userPropertyService
  private[this] val categoryService      = RudderConfig.ruleCategoryService

  private[this] val roChangeRequestRepo  = RudderConfig.roChangeRequestRepository
  private[this] val categoryHierarchyDisplayer = RudderConfig.categoryHierarchyDisplayer

  private[this] var ruleTarget = RuleTarget.merge(rule.targets)
  private[this] var selectedDirectiveIds = rule.directiveIds

  private[this] val getFullNodeGroupLib = RudderConfig.roNodeGroupRepository.getFullGroupLibrary _
  private[this] val getFullDirectiveLib = RudderConfig.roDirectiveRepository.getFullDirectiveLibrary _
  private[this] val getAllNodeInfos     = RudderConfig.nodeInfoService.getAll _

  private[this] val usedDirectiveIds = roRuleRepository.getAll().getOrElse(Seq()).flatMap { case r =>
    r.directiveIds.map( id => (id -> r.id))
  }.groupBy( _._1 ).mapValues( _.size).toSeq

  //////////////////////////// public methods ////////////////////////////
  val extendsAt = SnippetExtensionKey(classOf[RuleEditForm].getSimpleName)

  def mainDispatch = Map(
    "showForm" -> { _:NodeSeq =>
      showForm() },
    "showEditForm" -> { _:NodeSeq =>
      showForm(1)}
  )

  private[this] def showForm(tab :Int = 0) : NodeSeq = {
    (getFullNodeGroupLib(), getFullDirectiveLib(), getAllNodeInfos()) match {
      case (Full(groupLib), Full(directiveLib), Full(nodeInfos)) =>

        val form = {
          if(CurrentUser.checkRights(Read("rule"))) {
            val formContent = if (CurrentUser.checkRights(Edit("rule"))) {
              showCrForm(groupLib, directiveLib)
            } else {
              <div>You have no rights to see rules details, please contact your administrator</div>
            }

            (
              "#editForm" #> formContent &
              "#details"  #> new RuleCompliance(rule).display(directiveLib, nodeInfos)
            ).apply(body)

          } else {
            <div>You have no rights to see rules details, please contact your administrator</div>
          }
        }

        def updateCompliance() = {
           roRuleRepository.get(rule.id) match {
             case Full(updatedrule) =>
               new RuleCompliance(updatedrule).display(directiveLib, nodeInfos)
             case eb:EmptyBox =>
               logger.error("could not get updated version of the Rule")
               <div>Could not get updated version of the Rule, please </div>
           }

        }
        val ruleComplianceTabAjax = SHtml.ajaxCall(JsRaw("'"+rule.id.value+"'"), (v:String) => Replace("details",updateCompliance()))._2.toJsCmd

        form ++
        Script(
          OnLoad(JsRaw(
            s"""$$( "#editRuleZone" ).tabs(); $$( "#editRuleZone" ).tabs('select', ${tab});"""
          )) &
          JsRaw(s"""
            | $$("#editRuleZone").bind( "show", function(event, ui) {
            | if(ui.panel.id== 'ruleComplianceTab') { ${ruleComplianceTabAjax}; }
            | });
            """.stripMargin('|')
          )
        )

      case (a, b, c) =>
        List(a,b,c).collect{ case eb: EmptyBox =>
          val e = eb ?~! "An error happens when trying to get the node group library"
          logger.error(e.messageChain)
          <div class="error">{e.msg}</div>
        }
    }
  }



  private[this] def showCrForm(groupLib: FullNodeGroupCategory, directiveLib: FullActiveTechniqueCategory) : NodeSeq = {

    val maptarget = groupLib.allTargets.map{
      case (gt,fg) => s" '${gt.target}' : '${fg.name}'"
    }.toList.mkString("{",",","}")


    val included = ruleTarget.includedTarget.targets
    val excluded = ruleTarget.excludedTarget.targets

    (
      "#editForm *" #> { (n:NodeSeq) => SHtml.ajaxForm(n) } andThen
      ClearClearable &
      "#pendingChangeRequestNotification" #> { xml:NodeSeq =>
          PendingChangeRequestDisplayer.checkByRule(xml, rule.id, workflowEnabled)
        } &
      //activation button: show disactivate if activated
      "#disactivateButtonLabel" #> { if(rule.isEnabledStatus) "Disable" else "Enable" } &
      "#removeAction *" #> {
         SHtml.ajaxButton("Delete", () => onSubmitDelete(),("class","dangerButton"))
       } &
       "#desactivateAction *" #> {
         val status = rule.isEnabledStatus ? "disable" | "enable"
         SHtml.ajaxButton(status.capitalize, () => onSubmitDisable(status))
       } &
      "#clone" #> SHtml.ajaxButton(
                      { Text("Clone") }
                    , { () =>  onCloneCallback(rule) }
                    , ("type", "button")
      ) &
      "#nameField" #> crName.toForm_! &
      "#categoryField" #>   category.toForm_! &
      "#shortDescriptionField" #> crShortDescription.toForm_! &
      "#longDescriptionField" #> crLongDescription.toForm_! &
      "#selectPiField" #> {
        <div id={htmlId_activeTechniquesTree}>{
          <ul>{
            DisplayDirectiveTree.displayTree(
                directiveLib = directiveLib
              , usedDirectiveIds = usedDirectiveIds
              , onClickCategory = None
              , onClickTechnique = None
              , onClickDirective = None
                //filter techniques without directives, and categories without technique
              , keepCategory    = category => category.allDirectives.nonEmpty
              , keepTechnique   = technique => technique.directives.nonEmpty
              , addEditLink = true
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
            , included
            , excluded
          )}</ul>
        </div> } &
      "#save" #> saveButton &
      "#notifications" #>  updateAndDisplayNotifications &
      "#editForm [id]" #> htmlId_rule
    )(crForm) ++
    Script(OnLoad(JsRaw("""
      correctButtons();
    """)))++ Script(
        //a function to update the list of currently selected Directives in the tree
        //and put the json string of ids in the hidden field.
        JsCrVar("updateSelectedPis", AnonFunc(JsRaw("""
          $('#selectedPis').val(JSON.stringify(
            $.jstree._reference('#%s').get_selected().map(function(){
              return this.id;
            }).get()));""".format(htmlId_activeTechniquesTree)
        ))) &
      OnLoad(
        // Initialize angular part of page and group tree
        JsRaw(s"""
          angular.bootstrap('#groupManagement', ['groupManagement']);
          var scope = angular.element($$("#GroupCtrl")).scope();
          scope.$$apply(function(){
            scope.init(${ruleTarget.toString()},${maptarget});
          } );
          buildGroupTree('#${htmlId_groupTree}','${S.contextPath}', [], 'on');"""
        ) &
        //function to update list of PIs before submiting form
        JsRaw("buildRulePIdepTree('#%1$s', %2$s,'%3$s');".format(
            htmlId_activeTechniquesTree,
            serializedirectiveIds(selectedDirectiveIds.toSeq),
            S.contextPath
        )) &
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

  private[this] def serializeTargets(targets:Seq[RuleTarget]) : String = {
    implicit val formats = Serialization.formats(NoTypeHints)
    Serialization.write(
        targets.map { target =>
          target match {
            case GroupTarget(g) => "jsTree-" + g.value
            case _ => "jsTree-" + target.target
          }
        }
    )
  }
  private[this] def serializeTarget(target:RuleTarget) : String = {
    target.toString()
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
    Replace(htmlId_EditZone, this.showForm(1) )
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
    JsRaw("""scrollToElement("notifications");""")
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
    val save = SHtml.ajaxSubmit("Save", onSubmit _) % ("id" -> htmlId_save)
    // update onclick to get the list of directives and groups in the hidden
    // fields before submitting

    val newOnclick = "updateSelectedPis(); " +
      save.attributes.asAttrMap("onclick")

    SHtml.hidden( { ids =>
        selectedDirectiveIds = unserializedirectiveIds(ids).toSet
      }, serializedirectiveIds(selectedDirectiveIds.toSeq)
    ) % ( "id" -> "selectedPis") ++
    SHtml.hidden( { target =>
        ruleTarget = unserializeTarget(target)
      }, ruleTarget.target
    ) % ( "id" -> "selectedTargets") ++
    save % ( "onclick" -> newOnclick)
  }

  private[this] def targetClick(targetInfo: FullRuleTargetInfo) : JsCmd = {
    val target = targetInfo.target.target.target
    JsRaw(s"""onClickTarget("${target}");""")
  }

  private[this] def includeRuleTarget(targetInfo: FullRuleTargetInfo) : JsCmd = {
    val target = targetInfo.target.target.target
    JsRaw(s"""includeTarget("${target}");""")
  }

  private[this] def excludeRuleTarget(targetInfo: FullRuleTargetInfo) : JsCmd = {
    val target = targetInfo.target.target.target
    JsRaw(s"""excludeTarget("${target}");""")
  }

  /////////////////////////////////////////////////////////////////////////
  /////////////////////////////// Edit form ///////////////////////////////
  /////////////////////////////////////////////////////////////////////////

  ///////////// fields for Rule settings ///////////////////

  private[this] val crName = new WBTextField("Name", rule.name) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def className = "twoCol"
    override def validations =
      valMinLen(3, "The name must have at least 3 characters") _ :: Nil
  }

  private[this] val crShortDescription = {
    new WBTextField("Short description", rule.shortDescription) {
      override def className = "twoCol"
      override def setFilter = notNull _ :: trim _ :: Nil
      override val maxLen = 255
      override def validations =  Nil
    }
  }

  private[this] val crLongDescription = {
    new WBTextAreaField("Description", rule.longDescription.toString) {
      override def setFilter = notNull _ :: trim _ :: Nil
      override def className = "twoCol"
    }
  }

  private[this] val categories = categoryHierarchyDisplayer.getRuleCategoryHierarchy(roCategoryRepository.getRootCategory.get, None)
  private[this] val category =
    new WBSelectField(
        "Rule category"
      , categories.map { case (id, name) => (id.value -> name)}
      , rule.categoryId.value
    ) {
    override def className = "twoCol"
  }

  private[this] val formTracker = new FormTracker(List(crName, crShortDescription, crLongDescription))

  private[this] def error(msg:String) = <span class="error">{msg}</span>

  private[this] def onSubmit() : JsCmd = {
    if(formTracker.hasErrors) {
      onFailure
    } else { //try to save the rule
      val newCr = rule.copy(
          name             = crName.is
        , shortDescription = crShortDescription.is
        , longDescription  = crLongDescription.is
        , targets          = Set(ruleTarget)
        , directiveIds     = selectedDirectiveIds
        , isEnabledStatus  = rule.isEnabledStatus
        , categoryId       = RuleCategoryId(category.is)
      )
       if (newCr == rule) {
          onNothingToDo()
        } else {
          displayConfirmationPopup("save", newCr)
        }
    }
  }

   //action must be 'enable' or 'disable'
  private[this] def onSubmitDisable(action:String): JsCmd = {
    displayConfirmationPopup(
        action
      , rule.copy(isEnabledStatus = action == "enable")
    )
  }

  private[this] def onSubmitDelete(): JsCmd = {
    displayConfirmationPopup(
        "delete"
      , rule
    )
  }


  // Create the popup for workflow
  private[this] def displayConfirmationPopup(
      action  : String
    , newRule : Rule
  ) : JsCmd = {
    // for the moment, we don't have creation from here
    val optOriginal = Some(rule)

    val popup = new RuleModificationValidationPopup(
          newRule
        , optOriginal
        , action
        , workflowEnabled
        , cr => workflowCallBack(action)(cr)
        , () => JsRaw("$.modal.close();") & onFailure
        , parentFormTracker = Some(formTracker)
      )

    if((!changeMsgEnabled) && (!workflowEnabled)) {
      popup.onSubmit
    } else {
      SetHtml("confirmUpdateActionDialog", popup.popupContent) &
      JsRaw("""createPopup("confirmUpdateActionDialog")""")
    }
  }

  private[this] def workflowCallBack(action:String)(returns : Either[Rule,ChangeRequestId]) : JsCmd = {
    if ((!workflowEnabled) & (action == "delete")) {
      JsRaw("$.modal.close();") & onSuccessCallback(rule) & SetHtml("editRuleZone",
          <div id={htmlId_rule}>Rule '{rule.name}' successfully deleted</div>
      )
    } else {
      returns match {
        case Left(rule) => // ok, we've received a rule, do as before
          this.rule = rule
          JsRaw("$.modal.close();") &  onSuccess
        case Right(changeRequest) => // oh, we have a change request, go to it
          RedirectTo(s"""/secure/utilities/changeRequest/${changeRequest.value}""")
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
    def warning(warn : String) : NodeSeq = {
      <div style="padding-top: 15px; clear:both">
        <img src="/images/icWarn.png" alt="Warning!" height="25" width="25" class="warnicon"/>
        <h4 style="float:left">{warn} No configuration policy will be deployed.</h4>
      </div>
    }

    val content =
      if ( ruleTarget.excludedTarget.targets.size + ruleTarget.includedTarget.targets.size== 0 ) {
        if ( selectedDirectiveIds.size == 0 ) {
          warning("This Rule is not applied to any Groups and does not have any Directives to apply.")
        } else {
          warning("This Rule is not applied to any Groups.")
        }
      } else {
        if ( selectedDirectiveIds.size == 0 ) {
          warning("This Rule does not have any Directives to apply.")
        } else {
          NodeSeq.Empty
      } }

    SetHtml("successDialogContent",content) &
    JsRaw(""" callPopupWithTimeout(200, "successConfirmationDialog")""")
  }

}
