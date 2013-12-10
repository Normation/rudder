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

  private def details =
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentRuleEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "details", xml)
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
  , onSuccessCallback : () => JsCmd = { () => Noop } //JS to execute on form success (update UI parts)
  //there are call by name to have the context matching their execution when called,
  , onFailureCallback : () => JsCmd = { () => Noop }
  , onCloneCallback   : (Rule) => JsCmd = { (r:Rule) => Noop }
) extends DispatchSnippet with SpringExtendableSnippet[RuleEditForm] with Loggable {
  import RuleEditForm._

  private[this] val htmlId_save = htmlId_rule + "Save"
  private[this] val htmlId_EditZone = "editRuleZone"

  private[this] val roRuleRepository     = RudderConfig.roRuleRepository
  private[this] val roCategoryRepository = RudderConfig.roRuleCategoryRepository
  private[this] val reportingService     = RudderConfig.reportingService
  private[this] val userPropertyService  = RudderConfig.userPropertyService
  private[this] val categoryService      = RudderConfig.ruleCategoryService

  private[this] val roChangeRequestRepo  = RudderConfig.roChangeRequestRepository
  private[this] val categoryHierarchyDisplayer = RudderConfig.categoryHierarchyDisplayer

  private[this] var selectedTargets = rule.targets
  private[this] var selectedDirectiveIds = rule.directiveIds

  private[this] val getFullNodeGroupLib = RudderConfig.roNodeGroupRepository.getFullGroupLibrary _
  private[this] val getFullDirectiveLib = RudderConfig.roDirectiveRepository.getFullDirectiveLibrary _
  private[this] val getAllNodeInfos     = RudderConfig.nodeInfoService.getAll _


  //////////////////////////// public methods ////////////////////////////
  val extendsAt = SnippetExtensionKey(classOf[RuleEditForm].getSimpleName)

  def mainDispatch = Map(
    "showForm" -> { _:NodeSeq => showForm() },
    "showEditForm" -> { _:NodeSeq => showForm(1) }
  )

  private[this] def showForm(tab :Int = 0) : NodeSeq = {
    (getFullNodeGroupLib(), getFullDirectiveLib(), getAllNodeInfos()) match {
      case (Full(groupLib), Full(directiveLib), Full(nodeInfos)) =>
        val allNodeInfos = nodeInfos.map( x => (x.id -> x) ).toMap

        val form = {
          if(CurrentUser.checkRights(Read("rule"))) {
            val formContent = if (CurrentUser.checkRights(Edit("rule"))) {
              showCrForm(groupLib, directiveLib)
            } else {
              <div>You have no rights to see rules details, please contact your administrator</div>
            }

            (
              "#editForm" #> formContent &
              "#details"  #> showRuleDetails(directiveLib, allNodeInfos)
            ).apply(body)

          } else {
            <div>You have no rights to see rules details, please contact your administrator</div>
          }
        }

        val ruleComplianceTabAjax = SHtml.ajaxCall(JsRaw("'"+rule.id.value+"'"), (v:String) => Replace("details",showRuleDetails(directiveLib, allNodeInfos)))._2.toJsCmd

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

  private[this] def  showRuleDetails(directiveLib: FullActiveTechniqueCategory, allNodeInfos: Map[NodeId, NodeInfo]) : NodeSeq = {
    val updatedrule = roRuleRepository.get(rule.id)
    (
      "#details *" #> { (n:NodeSeq) => SHtml.ajaxForm(n) } andThen
      "#nameField" #>    <div>{crName.displayNameHtml.getOrElse("Could not fetch rule name")} {updatedrule.map(_.name).openOr("could not fetch rule name")} </div> &
      "#categoryField" #> <div> {category.displayNameHtml.getOrElse("Could not fetch rule category")} {updatedrule.flatMap(c => categoryService.shortFqdn(c.categoryId)).openOr("could not fetch rule category")}</div> &
      "#rudderID" #> {rule.id.value.toUpperCase} &
      "#shortDescriptionField" #>  <div>{crShortDescription.displayNameHtml.getOrElse("Could not fetch short description")} {updatedrule.map(_.shortDescription).openOr("could not fetch rule short descritption")}</div> &
      "#longDescriptionField" #>  <div>{crLongDescription.displayNameHtml.getOrElse("Could not fetch description")} {updatedrule.map(_.longDescription).openOr("could not fetch rule long description")}</div> &
      "#compliancedetails" #> { updatedrule match {
        case Full(rule) => showCompliance(rule, directiveLib, allNodeInfos)
        case _ => logger.debug("Could not display rule details for rule %s".format(rule.id))
          <div>Could not fetch rule</div>
      }}
    )(details)
  }


  private[this] def showCrForm(groupLib: FullNodeGroupCategory, directiveLib: FullActiveTechniqueCategory) : NodeSeq = {
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
              , onClickCategory = None
              , onClickTechnique = None
              , onClickDirective = None
            )
          }</ul>
        }</div> } &
      "#selectGroupField" #> {
        <div id={htmlId_groupTree}>
          <ul>{DisplayNodeGroupTree.displayTree(
              groupLib
            , None
            , Some(onClickRuleTarget)
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
        JsCrVar("updateSelectedTargets", AnonFunc(JsRaw("""
          $('#selectedTargets').val(JSON.stringify(
            $.jstree._reference('#%s').get_selected().map(function(){
              return this.id;
            }).get()));""".format(htmlId_groupTree)
        ))) &
      OnLoad(
        //build jstree and
        //init bind callback to move
        JsRaw("buildGroupTree('#%1$s','%3$s', %2$s, 'on');".format(
            htmlId_groupTree,
            serializeTargets(selectedTargets.toSeq),
            S.contextPath
        )) &
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

  /*
   * from a JSON array: [ "id1", "id2", ...], get the list of
   * Directive Ids.
   * Never fails, but returned an empty list.
   */
  private[this] def unserializedirectiveIds(ids:String) : Seq[DirectiveId] = {
    implicit val formats = DefaultFormats
    parse(ids).extract[List[String]].map( x => DirectiveId(x.replace("jsTree-","")) )
  }

  private[this] def unserializeTargets(ids:String) : Seq[RuleTarget] = {
    implicit val formats = DefaultFormats
    parse(ids).extract[List[String]].map{x =>
      val id = x.replace("jsTree-","")
      RuleTarget.unser(id).getOrElse(GroupTarget(NodeGroupId(id)))
    }
  }


  ////////////// Callbacks //////////////

  private[this] def updateFormClientSide() : JsCmd = {
    Replace(htmlId_EditZone, this.showForm(1) )
  }

  private[this] def onSuccess() : JsCmd = {
    //MUST BE THIS WAY, because the parent may change some reference to JsNode
    //and so, our AJAX could be broken
    onSuccessCallback() & updateFormClientSide() &
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

    val newOnclick = "updateSelectedPis(); updateSelectedTargets(); " +
      save.attributes.asAttrMap("onclick")

    SHtml.hidden( { ids =>
        selectedDirectiveIds = unserializedirectiveIds(ids).toSet
      }, serializedirectiveIds(selectedDirectiveIds.toSeq)
    ) % ( "id" -> "selectedPis") ++
    SHtml.hidden( { targets =>
        selectedTargets = unserializeTargets(targets).toSet
      }, serializeTargets(selectedTargets.toSeq)
    ) % ( "id" -> "selectedTargets") ++
    save % ( "onclick" -> newOnclick)
  }

  private[this] def onClickRuleTarget(parentCategory: FullNodeGroupCategory, targetInfo: FullRuleTargetInfo) : JsCmd = {
      selectedTargets = selectedTargets + targetInfo.target.target
      Noop
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

  private[this] val category =
    new WBSelectField(
        "Rule category"
      , categoryHierarchyDisplayer.getRuleCategoryHierarchy(roCategoryRepository.getRootCategory.get, None).map { case (id, name) => (id.value -> name)}
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
        , targets          = selectedTargets
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
      JsRaw("$.modal.close();") & onSuccessCallback() & SetHtml("editRuleZone",
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
      if ( selectedTargets.size == 0 ) {
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

 /********************************************
  * Utilitary methods for JS
  ********************************************/


  //////////////// Compliance ////////////////

  /*
   * For each table : the subtable is contained in td : details
   * when + is clicked: it gets the content of td details then process it has a datatable
   */
  private[this] def showCompliance(rule: Rule, directiveLib: FullActiveTechniqueCategory, allNodeInfos: Map[NodeId, NodeInfo]) : NodeSeq = {

    /*
     * That javascript function gather all the data needed to display the details
     * They are stocked in the details row of the line (which is not displayed)
     */
    val FormatDetailsJSFunction = """
      function fnFormatDetails( oTable, nTr ) {
        var fnData = oTable.fnGetData( nTr );
        var oTable2 = fnData[fnData.length-1];
        var sOut ='<div class="innerDetails">'+oTable2+'</div>';
        return sOut;
      }"""

      /*
       * That Javascript function describe the behavior of the inner dataTable
       * On click it open or close its details
       * the content is dynamically computed
       */
    val innerClickJSFunction = """
      var componentPlusTd = $(this.fnGetNodes());
      componentPlusTd.each( function () {
        $(this).unbind();
        $(this).click( function (e) {
          if ($(e.target).hasClass('noexpand'))
            return false;
            var nTr = this;
            var i = $.inArray( nTr, anOpen );
            if ( i === -1 ) {
             $(this).find("td.listopen").removeClass("listopen").addClass("listclose");
              var nDetailsRow = Otable2.fnOpen( nTr, fnFormatDetails(Otable2, nTr), 'details' );
              $('div.innerDetails table', nDetailsRow).dataTable({
                "asStripeClasses": [ 'color1', 'color2' ],
                "bAutoWidth": false,
                "bFilter" : false,
                "bPaginate" : false,
                "bLengthChange": false,
                "bInfo" : false,
                "sPaginationType": "full_numbers",
                "bJQueryUI": true,
                "aaSorting": [[ 1, "asc" ]],
                "aoColumns": [
                  { "sWidth": "40px", "bSortable": false },
                  { "sWidth": "355px" },
                  { "sWidth": "50px" },
                  { "sWidth": "120px" },
                  { "sWidth": "10px" , "bSortable": false  , "bVisible":false}
                ]
              });
              $('div.dataTables_wrapper:has(table.noMarginGrid)').addClass('noMarginGrid');
              $('td.details', nDetailsRow).attr("colspan",5);
              $('div.innerDetails table', nDetailsRow).attr("style","");
              $('div.innerDetails', nDetailsRow).slideDown(300);
              anOpen.push( nTr );
            }
            else {
              $(this).find("td.listclose").removeClass("listclose").addClass("listopen");
              $('div.innerDetails', $(nTr).next()[0]).slideUp( 300,function () {
                oTable.fnClose( nTr );
                anOpen.splice( i, 1 );
              } );
            }
      } ); } );""".format(S.contextPath)
      /*
       * This is the main Javascript function to have cascaded DataTables
       */
    val ReportsGridClickFunction = """
     createTooltip();
     var plusTd = $($('#reportsGrid').dataTable().fnGetNodes());

     plusTd.each(function(i) {
       var nTr = this.parentNode;
       var i = $.inArray( nTr, anOpen );
         if ( i != -1 ) {
           $(nTr).next().find("table").dataTable().fnDraw();
         }
     } );

     plusTd.each( function () {
       $(this).unbind();
       $(this).click( function (e) {
         if ($(e.target).hasClass('noexpand'))
           return false;
         var nTr = this;
         var i = $.inArray( nTr, anOpen );
         if ( i === -1 ) {
               $(this).find("td.listopen").removeClass("listopen").addClass("listclose");
           var nDetailsRow = oTable.fnOpen( nTr, fnFormatDetails(oTable, nTr), 'details' );
           var Otable2 =  $('div.innerDetails table:first', nDetailsRow).dataTable({
             "asStripeClasses": [ 'color1', 'color2' ],
             "bAutoWidth": false,
             "bFilter" : false,
             "bPaginate" : false,
             "bLengthChange": false,
             "bInfo" : false,
             "sPaginationType": "full_numbers",
             "bJQueryUI": true,
             "aaSorting": [[ 2, "asc" ]],
             "aoColumns": [
               { "sWidth": "20px", "bSortable": false },
               { "sWidth": "375px" },
               { "sWidth": "50px" },
               { "sWidth": "120px" },
               { "sWidth": "10px", "bSortable": false  , "bVisible":false }
             ],
              "fnDrawCallback" : function( oSettings ) {%2$s}
           } );
           $('div.dataTables_wrapper:has(table.noMarginGrid)').addClass('noMarginGrid');
           $('div.innerDetails table:first', nDetailsRow).attr("style","");
           $('div.innerDetails', nDetailsRow).slideDown(300);
           anOpen.push( nTr );
         }
         else {
           $(this).find("td.listclose").removeClass("listclose").addClass("listopen");
           $('div.innerDetails', $(nTr).next()[0]).slideUp(300, function () {
             oTable.fnClose( nTr );
             anOpen.splice( i, 1 );
           } );
         }
    } ) } );""".format(S.contextPath,innerClickJSFunction)

    /*
     * It displays the report Detail of a Rule
     * It displays each Directive and prepare its components detail
     */
    def showReportDetail(batch : Box[Option[ExecutionBatch]], directiveLib: FullActiveTechniqueCategory) : NodeSeq = {
      batch match {
        case e: EmptyBox => <div class="error">Error while fetching report information</div>
        case Full(None) => NodeSeq.Empty
        case Full(Some(reports)) =>
          val directivesreport=reports.getRuleStatus().filter(dir => rule.directiveIds.contains(dir.directiveId))
          val tooltipid = Helpers.nextFuncName
          ( "#reportsGrid [class+]" #> "tablewidth" &
            "#reportLine" #> {
              directivesreport.flatMap { directiveStatus =>
                directiveLib.allDirectives.get(directiveStatus.directiveId) match {
                  case Some((fullActiveTechnique, directive))  => {

                    val tech = fullActiveTechnique.techniques.get(directive.techniqueVersion).map(_.name).getOrElse("Unknown technique")
                    val techversion = directive.techniqueVersion;
                    val tooltipid = Helpers.nextFuncName
                    val components= showComponentsReports(directiveStatus.components)
                    val severity = ReportType.getSeverityFromStatus(directiveStatus.directiveReportType)
                    ( "#status [class+]" #> severity.replaceAll(" ", "") &
                      "#status *" #> <center>{severity}</center> &
                      "#details *" #> components &
                      "#directive [class+]" #> "listopen" &
                      "#directive *" #>{
                        <span>
                          <b>{directive.name}</b>
                          <span class="tooltipable" tooltipid={tooltipid} title="">
                            <img   src="/images/icInfo.png" style="padding-left:4px"/>
                          </span>
                          { val xml = <img   src="/images/icTools.png" style="padding-left:4px" class="noexpand"/>
                            SHtml.a( {()=> RedirectTo("""/secure/configurationManager/directiveManagement#{"directiveId":"%s"}""".format(directive.id.value))},xml,("style","padding-left:4px"),("class","noexpand"))
                          }
                          <div class="tooltipContent" id={tooltipid}>
                            Directive <b>{directive.name}</b> is based on technique
                            <b>{tech}</b> (version {techversion})
                          </div>
                        </span> }&
                      "#severity *" #> buildComplianceChart(directiveStatus)
                    ) (reportsLineXml)
                  }
                  case None =>
                    logger.error(s"An error occured when trying to load directive ${directiveStatus.directiveId.value}.")
                    <div class="error">Node with ID "{directiveStatus.directiveId.value}" is invalid</div>
                }
              }
            }
          ) (reportsGridXml) ++ Script( JsRaw("""
                  %s
                  var anOpen = [];
                  var oTable = $('#reportsGrid').dataTable( {
                    "asStripeClasses": [ 'color1', 'color2' ],
                    "bAutoWidth": false,
                    "bFilter" : true,
                    "bPaginate" : true,
                    "bLengthChange": true,
                    "sPaginationType": "full_numbers",
                    "bJQueryUI": true,
                    "oLanguage": {
                      "sSearch": ""
                    },
                    "sDom": '<"dataTables_wrapper_top"fl>rt<"dataTables_wrapper_bottom"ip>',
                    "aaSorting": [[ 1, "asc" ]],
                    "aoColumns": [
                      { "sWidth": "403px" },
                      { "sWidth": "50px" },
                      { "sWidth": "120px" },
                      { "sWidth": "10px", "bSortable": false  , "bVisible":false }
                    ],
                    "fnDrawCallback" : function( oSettings ) {%s}
                  } );
                  $('.dataTables_filter input').attr("placeholder", "Search");
                  """.format(FormatDetailsJSFunction,ReportsGridClickFunction) ) )
      }
    }

    /*
     * Display component details of a directive and add its compoenent value details if needed
     */
    def showComponentsReports(components : Seq[ComponentRuleStatusReport]) : NodeSeq = {
    val worstseverity= ReportType.getSeverityFromStatus(ReportType.getWorseType(components.map(_.componentReportType))).replaceAll(" ", "")
    <table id={Helpers.nextFuncName} cellspacing="0" style="display:none" class="noMarginGrid tablewidth">
     <thead>
       <tr class="head tablewidth">
       <th class="emptyTd"><span/></th>
       <th >Component<span/></th>
       <th >Status<span/></th>
       <th >Compliance<span/></th>
       <th style="border-left:0;" ></th>
     </tr>
    </thead>
    <tbody>{
      components.flatMap { component =>
        val severity = ReportType.getSeverityFromStatus(component.componentReportType)
        ( "#status [class+]" #> severity.replaceAll(" ", "") &
          "#status *" #> <center>{severity}</center> &
          "#component *" #>  <b>{component.component}</b> &
          "#severity *" #>  buildComplianceChart(component)
          ) (
            if (component.componentValues.forall( x => x.componentValue =="None")) {
              // only None, we won't show the details, we don't need the plus and that td should not be clickable
              ("* [class+]" #> "noexpand").apply(componentDetails)
            } else {
              // standard  display that can be expanded
              val tooltipid = Helpers.nextFuncName
              val value = showComponentValueReport(component.componentValues,worstseverity)
              ( "#details *" #>  value &
                "tr [class+]" #> "cursor" &
                "#component [class+]" #>  "listopen"
              ) (componentDetails )
            }
      )  }  }
      </tbody>
    </table>
    }

    /*
     * Display component value details
     */
    def showComponentValueReport(values : Seq[ComponentValueRuleStatusReport],directiveSeverity:String) : NodeSeq = {
    val worstseverity= ReportType.getSeverityFromStatus(ReportType.getWorseType(values.map(_.cptValueReportType))).replaceAll(" ", "")
    <table id={Helpers.nextFuncName} cellspacing="0" style="display:none" class="noMarginGrid tablewidth ">
      <thead>
        <tr class="head tablewidth">
          <th class="emptyTd"><span/></th>
          <th >Value<span/></th>
          <th >Status<span/></th>
          <th >Compliance<span/></th>
          <th style="border-left:0;" ></th>
        </tr>
      </thead>
      <tbody>{
        // we need to group all the ComponentValueRuleStatusReports by unexpandedComponentValue if any, or by component value
        // and agregate them together
        val reportsByComponents = values.groupBy { entry => entry.unexpandedComponentValue.getOrElse(entry.componentValue)}
        reportsByComponents.map { case (key, entries) =>
          val severity = ReportType.getWorseType(entries.map(_.cptValueReportType))
          ComponentValueRuleStatusReport(
             entries.head.directiveid // can't fail because we are in a groupBy
           , entries.head.component  // can't fail because we are in a groupBy
           , key
           , None // TODO : is it what we want ??
           , severity
           , entries.flatMap(_.reports)
          )
        }.flatMap { value =>
          val severity = ReportType.getSeverityFromStatus(value.cptValueReportType)
          ( "#valueStatus [class+]" #> severity.replaceAll(" ", "") &
            "#valueStatus *" #> <center>{severity}</center> &
            "#componentValue *" #>  <b>{value.componentValue}</b> &
            "#componentValue [class+]" #>  "firstTd" &
            "#keySeverity *" #> buildComplianceChartForComponent(value, reportsByComponents.get(value.componentValue))
         ) (componentValueDetails) } }
      </tbody>
    </table>
    }

    def reportsGridXml : NodeSeq = {
    <table id="reportsGrid" cellspacing="0">
      <thead>
        <tr class="head tablewidth">
          <th >Directive<span/></th>
          <th >Status<span/></th>
          <th >Compliance<span/></th>
          <th style="border-left:0;" ></th>
        </tr>
      </thead>
      <tbody>
        <div id="reportLine"/>
      </tbody>
    </table>
    }

    def reportsLineXml : NodeSeq = {
    <tr class="cursor">
      <td id="directive" class="nestedImg"></td>
      <td id="status" class="firstTd"></td>
      <td name="severity" class="firstTd"><div id="severity" style="text-align:right;"/></td>
      <td id="details" ></td>
    </tr>
    }

    def componentDetails : NodeSeq = {
    <tr id="componentLine" class="detailedReportLine severity" >
      <td id="first" class="emptyTd"/>
      <td id="component" ></td>
      <td id="status" class="firstTd"></td>
      <td name="severity" class="firstTd"><div id="severity" style="text-align:right;"/></td>
      <td id="details"/>
    </tr>
    }

    def componentValueDetails : NodeSeq = {
    <tr id="valueLine"  class="detailedReportLine severityClass severity ">
      <td id="first" class="emptyTd"/>
      <td id="componentValue" class="firstTd"></td>
      <td id="valueStatus" class="firstTd"></td>
      <td name="keySeverity" class="firstTd"><div id="keySeverity" style="text-align:right;"/></td>
      <td/>
    </tr>
    }

    def buildComplianceChart(rulestatusreport:RuleStatusReport) : NodeSeq = {
    rulestatusreport.computeCompliance match {
      case Some(percent) =>  {
        val text = Text(percent.toString + "%")
        val attr = BasicElemAttr("class","noexpand")
        SHtml.a({() => showPopup(rulestatusreport, directiveLib, allNodeInfos)}, text,attr)
      }
      case None => Text("Not Applied")
    }
    }

    def buildComplianceChartForComponent(
      ruleStatusReport: ComponentValueRuleStatusReport
    , values          : Option[Seq[ComponentValueRuleStatusReport]]
    ) : NodeSeq = {
    ruleStatusReport.computeCompliance match {
      case Some(percent) =>  {
        val text = Text(percent.toString + "%")
        val attr = BasicElemAttr("class","noexpand")
        values match {
          case None => SHtml.a({() => showPopup(ruleStatusReport, directiveLib, allNodeInfos)}, text,attr)
          case Some(reports) => SHtml.a({() => showPopup(ruleStatusReport, reports, directiveLib, allNodeInfos)}, text,attr)
        }
      }
      case None => Text("Not Applied")
    }
    }

    val batch = reportingService.findImmediateReportsByRule(rule.id)

    <div>
    <hr class="spacer" />
        {showReportDetail(batch, directiveLib)}
    </div>++ Script( OnLoad( After( TimeSpan(100), JsRaw("""createTooltip();"""))))
  }

  ///////////////// Compliance detail popup/////////////////////////

  /*
   * Node summary, treat all top level reports
   */
  private[this] def nodeGridXml: NodeSeq = {
    <table id={ Helpers.nextFuncName } cellspacing="0">
      <thead>
        <tr class="head">
          <th>Node<span/></th>
          <th>Status<span/></th>
        </tr>
      </thead>
      <tbody>
        <div id="reportLine"/>
      </tbody>
    </table>
    <div class="nodeReportGrid_pagination paginatescala">
      <div id="nodeReportGrid_paginate_area"/>
    </div>
    <hr class="spacer"/>
    <br/> ++ Script(OnLoad(JsRaw("""
          $('#nodeReportGrid').dataTable({
                "asStripeClasses": [ 'color1', 'color2' ],
                "bAutoWidth": false,
                "bFilter" : true,
                "bPaginate" : true,
                "bLengthChange": true,
                "sPaginationType": "full_numbers",
                "bJQueryUI": true,
                "oLanguage": {
                 "sSearch": ""
                },
                "sDom": '<"dataTables_wrapper_top"fl>rt<"dataTables_wrapper_bottom"ip>',
                "aoColumns": [
                  { "sWidth": "200px" },
                  { "sWidth": "100px" }
                ]
          } );""")))
  }

  private[this] def nodeLineXml: NodeSeq = {
    <tr class="noexpand">
      <td id="node"></td>
      <td id="severity"></td>
    </tr>
  }

  private[this] def missingGridXml(id: String = "reports", message: String = ""): NodeSeq = {

    <h3>Missing reports</h3>
    <div>The following reports are what Rudder expected to receive, but did not. This usually indicates a bug in the Technique being used.</div>
    <table id={ id + "Grid" } cellspacing="0" style="clear:both">
      <thead>
        <tr class="head">
          <th>Technique<span/></th>
          <th>Component<span/></th>
          <th>Value<span/></th>
        </tr>
      </thead>
      <tbody>
        <div id="reportLine"/>
      </tbody>
    </table>
    <br/>
  }

  private[this] def missingLineXml: NodeSeq = {
    <tr>
      <td id="technique"></td>
      <td id="component"></td>
      <td id="value"></td>
    </tr>
  }

  def unexpectedGridXml(id: String = "reports", message: String = ""): NodeSeq = {

    <h3>Unexpected reports</h3>
    <div>The following reports were received by Rudder, but did not match the reports declared by the Technique. This usually indicates a bug in the Technique being used.</div>
    <table id={ id + "Grid" } cellspacing="0" style="clear:both">
      <thead>
        <tr class="head">
          <th>Node<span/></th>
          <th>Technique<span/></th>
          <th>Component<span/></th>
          <th>Value<span/></th>
          <th>Message<span/></th>
        </tr>
      </thead>
      <tbody>
        <div id="reportLine"/>
      </tbody>
    </table>
    <br/>
  }

  def unexpectedLineXml: NodeSeq = {
    <tr>
      <td id="node"></td>
      <td id="technique"></td>
      <td id="component"></td>
      <td id="value"></td>
      <td id="message"></td>
    </tr>
  }
  /*
     * Detailled reporting, each line is a report message
     */
  def reportsGridXml(id: String = "reports", message: String = ""): NodeSeq = {
    <center><b>{ message }</b></center>
    <table id={ id + "Grid" } cellspacing="0" style="clear:both">
      <thead>
        <tr class="head">
          <th>Node<span/></th>
          <th>Status<span/></th>
          <th style="border-left:0;"></th>
        </tr>
      </thead>
      <tbody>
        <div id="reportLine"/>
      </tbody>
    </table>
    <br/>
  }

  def reportLineXml: NodeSeq = {
    <tr class="cursor">
      <td id="node" class="listopen"></td>
      <td id="status"></td>
      <td id="details"/>
    </tr>
  }
  def messageLineXml: NodeSeq = {
    <tr class="cursor">
      <td class="emptyTd"/>
      <td id="component" class="listopen"></td>
      <td id="status"></td>
      <td id="details"></td>
    </tr>
  }
  def messageValueLineXml: NodeSeq = {
    <tr>
      <td class="emptyTd"/>
      <td id="value"></td>
      <td id="message"></td>
      <td id="status"></td>
    </tr>
  }


  /*
     * Display a summary of every node
     * We only have node hostname, and it status
     */
  def showSummary(nodeReports: Seq[NodeReport], allNodeInfos: Map[NodeId, NodeInfo]): NodeSeq = {
    val nodes = nodeReports.map(_.node).distinct
    val nodeStatuses = nodes.map(node => NodeReport(node, ReportType.getWorseType(nodeReports.filter(_.node == node).map(stat => stat.reportType)), nodeReports.filter(_.node == node).flatMap(stat => stat.message).toList))
    nodeStatuses.toList match {
      case Nil => NodeSeq.Empty
      case nodeStatus :: rest =>
        val nodeReport = allNodeInfos.get(nodeStatus.node) match {
          case Some(nodeInfo) => {
            val tooltipid = Helpers.nextFuncName
            ("#node *" #>
              <a class="noexpand" href={ """/secure/nodeManager/searchNodes#{"nodeId":"%s"}""".format(nodeStatus.node.value) }>
                <span class="curspoint">
                  { nodeInfo.hostname }
                </span>
              </a> &
              "#severity *" #> <center>{ ReportType.getSeverityFromStatus(nodeStatus.reportType) }</center> &
              "#severity [class+]" #> ReportType.getSeverityFromStatus(nodeStatus.reportType).replaceAll(" ", ""))(nodeLineXml)
          }
          case None =>
            logger.error("An error occured when trying to load node %s".format(nodeStatus.node.value))
            <div class="error">Node with ID "{ nodeStatus.node.value }" is invalid</div>
        }
        nodeReport ++ showSummary(rest, allNodeInfos)
    }
  }

  def showMissingReports(reports: Seq[MessageReport], gridId: String, tabid: Int, techniqueName: String, techniqueVersion: String): NodeSeq = {
    def showMissingReport(report: (String, String)): NodeSeq = {
      ("#technique *" #> "%s (%s)".format(techniqueName, techniqueVersion) &
        "#component *" #> report._1 &
        "#value *" #> report._2)(missingLineXml)
    }

    if (reports.size > 0) {
      val components: Seq[String] = reports.map(_.component).distinct
      val missingreports = components.flatMap(component => reports.filter(_.component == component).map(report => (component, report.value))).distinct
      ("#reportLine" #> missingreports.flatMap(showMissingReport(_))).apply(missingGridXml(gridId)) ++
        Script(JsRaw("""
             var oTable%1$s = $('#%2$s').dataTable({
               "asStripeClasses": [ 'color1', 'color2' ],
               "bAutoWidth": false,
               "bFilter" : true,
               "bPaginate" : true,
               "bLengthChange": true,
               "sPaginationType": "full_numbers",
               "bJQueryUI": true,
               "oLanguage": {
                 "sSearch": ""
               },
               "sDom": '<"dataTables_wrapper_top"fl>rt<"dataTables_wrapper_bottom"ip>',
               "aaSorting": [[ 0, "asc" ]],
               "aoColumns": [
                 { "sWidth": "150px" },
                 { "sWidth": "150px" },
                 { "sWidth": "150px" }
               ]
             } );
         """.format(tabid, gridId + "Grid")))
    } else
      NodeSeq.Empty
  }

  /*
       * reports are displayed in cascaded dataTables
       * Parameters:
       *  reports : the reports we need to show
       *  GridId  : the dataTable name
       *  tabid   : an identifier to ease javascript
       *  message : Message to display on top of the dataTable
       *
       */
  private[this] def showReports(reports: Seq[MessageReport], gridId: String, tabid: Int, allNodeInfos: Map[NodeId, NodeInfo], message: String = ""): NodeSeq = {
    /*
         * Show report about a node
         * Parameters :
         * nodeId, Seq[componentName,value, unexpandedValue,reportsMessage, reportType)
         */
    def showNodeReport(nodeReport: (NodeId, Seq[(String, String, Option[String], List[String], ReportType)])): NodeSeq = {
      allNodeInfos.get(nodeReport._1) match {
        case Some(nodeInfo) => {
          val status = ReportType.getSeverityFromStatus(ReportType.getWorseType(nodeReport._2.map(_._5)))
          ("#node *" #>
            <span class="unfoldable">
              { nodeInfo.hostname }
              {
                val xml = <img src="/images/icDetails.png" style="margin-bottom:-3px" class="noexpand"/>
                SHtml.a({ () => RedirectTo("""/secure/nodeManager/searchNodes#{"nodeId":"%s"}""".format(nodeReport._1.value)) }, xml, ("style", "padding-left:4px"), ("class", "noexpand"))
              }
            </span> &
            "#status *" #> <center>{ status }</center> &
            "#status [class+]" #> status.replaceAll(" ", "") &
            "#details *" #> showComponentReport(nodeReport._2))(reportLineXml)
        }
        case None =>
          logger.error("An error occured when trying to load node %s".format(nodeReport._1.value))
          <div class="error">Node with ID "{ nodeReport._1.value }" is invalid</div>
      }
    }
    /*
         * Seq[componentName,value, unexpandedValue,reportsMessage, reportType)
         */
    def showComponentReport(componentReports: (Seq[(String, String, Option[String], List[String], ReportType)])): NodeSeq = {
      <table id={ Helpers.nextFuncName } cellspacing="0" style="display:none" class=" noMarginGrid tablewidth ">
        <thead>
          <tr class="head tablewidth">
            <th class="emptyTd"><span/></th>
            <th>Component<span/></th>
            <th>Status<span/></th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {
            val components = componentReports.map(_._1).distinct

            val valueReports = components.map(tr => (tr, componentReports.filter(_._1 == tr).map(rep => (rep._2, rep._3, rep._4, rep._5))))
            valueReports.flatMap { report =>
              val status = ReportType.getSeverityFromStatus(ReportType.getWorseType(report._2.map(_._4)))
              ("#component *" #> report._1 &
                "#status *" #> <center>{ status }</center> &
                "#status [class+]" #> status.replaceAll(" ", "") &
                "#details *" #> showValueReport(report._2))(messageLineXml)
            }
          }
        </tbody>
      </table>
    }
    def showValueReport(valueReport: (Seq[(String, Option[String], List[String], ReportType)])): NodeSeq = {
      <table id={ Helpers.nextFuncName } cellspacing="0" style="display:none" class="noMarginGrid tablewidth ">
        <thead>
          <tr class="head tablewidth">
            <th class="emptyTd"><span/></th>
            <th>Value<span/></th>
            <th>Message<span/></th>
            <th>Status<span/></th>
          </tr>
        </thead>
        <tbody>
          {
            valueReport.flatMap { report =>
              val status = ReportType.getSeverityFromStatus(report._4)
              ("#value *" #> {
                report._2 match {
                  case None => Text(report._1)
                  case Some(unexpanded) if unexpanded == report._1 => Text(report._1)
                  case Some(unexpanded) =>
                    val tooltipid = Helpers.nextFuncName
                    <div>
                      <span>{ report._1 }</span>
                      <span class="tooltipable" tooltipid={ tooltipid } title="">
                        <img src="/images/icInfo.png" style="padding-left:4px"/>
                      </span>
                      <div class="tooltipContent" id={ tooltipid }>
                        Value <b>{ report._1 }</b> was expanded from the entry <b>{ unexpanded }</b>
                      </div>
                    </div>
                }
              } &
                "#status *" #> <center>{ status }</center> &
                "#status [class+]" #> status.replaceAll(" ", "") &
                "#message *" #> <ul>{ report._3.map(msg => <li>{ msg }</li>) }</ul>)(messageValueLineXml)
            }
          }
        </tbody>
      </table>
    }


    ///// actual code for showReports methods /////

    if (reports.size > 0) {
      val nodes = reports.map(_.report.node).distinct
      val datas = nodes.map(node => {
        val report = reports.filter(_.report.node == node).map(report => (report.component, report.value, report.unexpandedValue, report.report.message, report.report.reportType))
        (node, report)
      })
      val innerJsFun = """
              var componentTab = $(this.fnGetNodes());
              componentTab.each( function () {
                $(this).unbind();
                $(this).click( function (e) {
                  if ($(e.target).hasClass('noexpand'))
                    return false;
                  var nTr = this;
                  var i = $.inArray( nTr, anOpen%1$s );
                    if ( i === -1 ) {
                    $(this).find("td.listopen").removeClass("listopen").addClass("listclose");
                    var nDetailsRow = Otable2.fnOpen( nTr, fnFormatDetails(Otable2, nTr), 'details' );
                    $('div.innerDetails table', nDetailsRow).dataTable({
                      "asStripeClasses": [ 'color1', 'color2' ],
                      "bAutoWidth": false,
                      "bFilter" : false,
                      "bPaginate" : false,
                      "bLengthChange": false,
                      "bInfo" : false,
                      "sPaginationType": "full_numbers",
                      "bJQueryUI": true,
                      "aaSorting": [[ 1, "asc" ]],
                      "aoColumns": [
                        { "sWidth": "40px", "bSortable": false },
                        { "sWidth": "110px" },
                        { "sWidth": "210px" },
                        { "sWidth": "50px" , "bSortable": false},
                      ]
                    } );
                    $('div.dataTables_wrapper:has(table.noMarginGrid)').addClass('noMarginGrid');
                    $('td.details', nDetailsRow).attr("colspan",4);
                    $('div.innerDetails table', nDetailsRow).attr("style","");
                    $('div.innerDetails', nDetailsRow).slideDown(300);
                    anOpen%1$s.push( nTr );
                    createTooltip();
                    }
                    else {
                    $(this).find("td.listclose").removeClass("listclose").addClass("listopen");
                    $('div.innerDetails', $(nTr).next()[0]).slideUp( 300,function () {
                      oTable%1$s.fnClose( nTr );
                      anOpen%1$s.splice( i, 1 );
                    } );
                  }
                } ); } )""".format(tabid, S.contextPath)

      val jsFun = """
            var tab = $($('#%2$s').dataTable().fnGetNodes());
            tab.each( function () {
              $(this).unbind();
              $(this).click( function (e) {
                if ($(e.target).hasClass('noexpand'))
                  return false;
                var nTr = this;
                var i = $.inArray( nTr, anOpen%1$s );
                if ( i === -1 ) {
                  $(this).find("td.listopen").removeClass("listopen").addClass("listclose");
                  var fnData = oTable%1$s.fnGetData( nTr );
                  var nDetailsRow = oTable%1$s.fnOpen( nTr, fnFormatDetails%1$s(oTable%1$s, nTr), 'details' );
                  var Otable2 = $('div.innerDetails table:first', nDetailsRow).dataTable({
                    "asStripeClasses": [ 'color1', 'color2' ],
                    "bAutoWidth": false,
                    "bFilter" : false,
                    "bPaginate" : false,
                    "bLengthChange": false,
                    "bInfo" : false,
                    "sPaginationType": "full_numbers",
                    "bJQueryUI": true,
                    "aaSorting": [[ 1, "asc" ]],
                    "aoColumns": [
                      { "sWidth": "20px", "bSortable": false },
                      { "sWidth": "350px" },
                      { "sWidth": "50px" },
                      { "sWidth": "10px", "bSortable": false  , "bVisible":false }
                    ],
                 "fnDrawCallback" : function( oSettings ) {%4$s}
                  } );
                  $('div.innerDetails table:first', nDetailsRow).attr("style","");
                  $('div.innerDetails', nDetailsRow).slideDown(300);
                  $('div.dataTables_wrapper:has(table.noMarginGrid)').addClass('noMarginGrid');
                  anOpen%1$s.push( nTr );
                }
                else {
                   $(this).find("td.listclose").removeClass("listclose").addClass("listopen");
                    $('div.innerDetails', $(nTr).next()[0]).slideUp(300, function () {
                    oTable%1$s.fnClose( nTr );
                    anOpen%1$s.splice( i, 1 );
                  } );
                }
          } );} );""".format(tabid, gridId + "Grid", S.contextPath, innerJsFun)
      ("#reportLine" #> datas.flatMap(showNodeReport(_))).apply(reportsGridXml(gridId, message)) ++
        /*Sorry about the Javascript
             * but we need to have dynamic definition of those datatables
             * As we need to have several dynamic datables, we have to add a specific identifier, the tabid
             * Everything is based what has been done for the previous dataTable
             */
        Script(JsRaw("""
            function fnFormatDetails%1$s( oTable, nTr ) {
              var fnData = oTable.fnGetData( nTr );
              var oTable2 = fnData[fnData.length-1]
              var sOut ='<div class="innerDetails">'+oTable2+'</div>';
              return sOut;
            }
            var anOpen%1$s = [];
             var oTable%1$s = $('#%2$s').dataTable({
               "asStripeClasses": [ 'color1', 'color2' ],
               "bAutoWidth": false,
               "bFilter" : true,
               "bPaginate" : true,
               "bLengthChange": true,
               "sPaginationType": "full_numbers",
               "bJQueryUI": true,
               "oLanguage": {
                 "sSearch": ""
               },
               "sDom": '<"dataTables_wrapper_top"fl>rt<"dataTables_wrapper_bottom"ip>',
               "aaSorting": [[ 1, "asc" ]],
               "aoColumns": [
                 { "sWidth": "378px" },
                 { "sWidth": "50px" },
                 { "sWidth": "10px","bSortable": false  , "bVisible":false}
               ],
               "fnDrawCallback" : function( oSettings ) {%3$s}
             } );
                """.format(tabid, gridId + "Grid", jsFun)))
    } else
      NodeSeq.Empty
  }

  def buildDisabled(toDisable: List[Int]): String = {
    toDisable match {
      case Nil => ""
      case value :: Nil => value.toString()
      case value :: rest => "%s, %s".format(value, buildDisabled(rest))
    }
  }

  /**
   * Unfold the report to (Seq[ComponentValueRuleStatusReport], DirectiveId)
   */
  def getComponentValueRule(report: RuleStatusReport): (Seq[ComponentValueRuleStatusReport], DirectiveId) = {
    report match {
      case DirectiveRuleStatusReport(directiveId, components, _) =>
        (components.flatMap(_.componentValues), directiveId)
      case ComponentRuleStatusReport(directiveId, component, values, _) =>
        (values, directiveId)
      case value: ComponentValueRuleStatusReport =>
        (Seq(value), value.directiveid)
    }
  }

  /**
   * convert the componentvaluesrules to componentValueRule and Directive
   * Checks as well that all componentvaluesrules belong to the same directive
   */
  def getComponentValueRule(entries: Seq[ComponentValueRuleStatusReport]): Box[(Seq[ComponentValueRuleStatusReport], DirectiveId)] = {
    entries.map(x => x.directiveid).distinct match {
      case seq if seq.size == 0 => Failure("Not enough ComponentValueRuleStatusReport")
      case seq if seq.size == 1 => Full((entries, seq.head))
      case seq => logger.error("Too many DirectiveIds fot a specific componentValueRule %s".format(seq)); Failure("Too many DirectiveIds fot a specific componentValueRule %s".format(seq))
    }
  }

  def showReportsByType(reports: Seq[ComponentValueRuleStatusReport], directiveId : DirectiveId, directiveLib: FullActiveTechniqueCategory, allNodeInfos: Map[NodeId, NodeInfo]): NodeSeq = {
    val optDirective = directiveLib.allDirectives.get(directiveId)
    val (techName, techVersion) = optDirective.map { case (fat, d) => (fat.techniqueName.value, d.techniqueVersion.toString) }.getOrElse(("Unknown Technique", "N/A"))
    val error = reports.flatMap(report => report.processMessageReport(_.reportType == ErrorReportType))
    val missing = reports.flatMap(report => report.processMessageReport(nreport => nreport.reportType == UnknownReportType & nreport.message.size == 0))
    val unexpected = reports.flatMap(report => report.processMessageReport(nreport => nreport.reportType == UnknownReportType & nreport.message.size != 0))
    val repaired = reports.flatMap(report => report.processMessageReport(_.reportType == RepairedReportType))
    val success = reports.flatMap(report => report.processMessageReport(_.reportType == SuccessReportType))
    val all = reports.flatMap(report => report.processMessageReport(report => true))

    val xml = (
         showReports(all, "report", 0, allNodeInfos) ++ showMissingReports(missing, "missing", 1, techName, techVersion)
      ++ showUnexpectedReports(unexpected, "unexpected", 2, techName, techVersion, allNodeInfos)
    )
    xml
  }

  def showUnexpectedReports(reports: Seq[MessageReport], gridId: String, tabid: Int, techniqueName: String, techniqueVersion: String, allNodeInfos: Map[NodeId, NodeInfo]): NodeSeq = {
    def showUnexpectedReport(report: MessageReport): NodeSeq = {
      allNodeInfos.get(report.report.node) match {
        case Some(nodeInfo) => {
          ("#node *" #>
            <a class="unfoldable" href={ """/secure/nodeManager/searchNodes#{"nodeId":"%s"}""".format(report.report.node.value) }>
              <span class="curspoint noexpand">
                { nodeInfo.hostname }
              </span>
            </a> &
            "#technique *" #> "%s (%s)".format(techniqueName, techniqueVersion) &
            "#component *" #> report.component &
            "#value *" #> report.value &
            "#message *" #> <ul>{ report.report.message.map(msg => <li>{ msg }</li>) }</ul>)(unexpectedLineXml)
        }
        case None =>
          logger.error("An error occured when trying to load node %s".format(report.report.node.value))
          <div class="error">Node with ID "{ report.report.node.value }" is invalid</div>
      }
    }

    if (reports.size > 0) {
      ("#reportLine" #> reports.flatMap(showUnexpectedReport(_))).apply(unexpectedGridXml(gridId)) ++
        Script(JsRaw("""
             var oTable%1$s = $('#%2$s').dataTable({
               "asStripeClasses": [ 'color1', 'color2' ],
               "bAutoWidth": false,
               "bFilter" : true,
               "bPaginate" : true,
               "bLengthChange": true,
               "sPaginationType": "full_numbers",
               "bJQueryUI": true,
               "oLanguage": {
                 "sSearch": ""
               },
               "sDom": '<"dataTables_wrapper_top"fl>rt<"dataTables_wrapper_bottom"ip>',
               "aaSorting": [[ 0, "asc" ]],
               "aoColumns": [
                 { "sWidth": "100px" },
                 { "sWidth": "100px" },
                 { "sWidth": "100px" },
                 { "sWidth": "100px" },
                 { "sWidth": "200px" }
               ]
             } );
         """.format(tabid, gridId + "Grid")))
    } else
      NodeSeq.Empty
  }

  private[this] def directiveName(id:DirectiveId, directiveLib: FullActiveTechniqueCategory) = directiveLib.allDirectives.get(id).map( _._2.name ).getOrElse("can't find directive name")

  private[this] def createPopup(directiveByNode: RuleStatusReport, directiveLib: FullActiveTechniqueCategory, allNodeInfos: Map[NodeId, NodeInfo]) : NodeSeq = {

    (
      "#innerContent" #> {
        val xml = directiveByNode match {
          case d:DirectiveRuleStatusReport =>
            <div>
              <ul>
                <li> <b>Rule:</b> {rule.name}</li>
                <li><b>Directive:</b> {directiveName(d.directiveId, directiveLib)}</li>
              </ul>
            </div>
          case c:ComponentRuleStatusReport =>
            <div>
              <ul>
                <li> <b>Rule:</b> {rule.name}</li>
                <li><b>Directive:</b> {directiveName(c.directiveid, directiveLib)}</li>
                <li><b>Component:</b> {c.component}</li>
              </ul>
            </div>
          case ComponentValueRuleStatusReport(directiveId,component,value,unexpanded,_,_) =>
            <div>
              <ul>
                <li> <b>Rule:</b> {rule.name}</li>
                <li><b>Directive:</b> {directiveName(directiveId, directiveLib)}</li>
                <li><b>Component:</b> {component}</li>
                <li><b>Value:</b> {value}</li>
              </ul>
            </div>
          }
        val (reports, directiveId) = getComponentValueRule(directiveByNode)
        val tab = showReportsByType(reports, directiveId, directiveLib, allNodeInfos)

        xml++tab
      }
    ).apply(popupXml)
  }

  private[this] def createPopup(
      componentStatus : ComponentValueRuleStatusReport
    , cptStatusReports: Seq[ComponentValueRuleStatusReport]
    , directiveLib    : FullActiveTechniqueCategory
    , allNodeInfos    : Map[NodeId, NodeInfo]
  ): NodeSeq = {
    (
      "#innerContent" #> {
        val xml = <div>
                    <ul>
                      <li><b>Rule:</b> { rule.name }</li>
                      <li><b>Directive:</b> { directiveName(componentStatus.directiveid, directiveLib) }</li>
                      <li><b>Component:</b> { componentStatus.component }</li>
                      <li><b>Value:</b> { componentStatus.unexpandedComponentValue.getOrElse(componentStatus.componentValue) }</li>
                    </ul>
                  </div>
        val tab = getComponentValueRule(cptStatusReports) match {
          case e: EmptyBox => <div class="error">Could not retrieve information from reporting</div>
          case Full((reports, directiveId)) => showReportsByType(reports, directiveId, directiveLib, allNodeInfos)
        }
        xml ++ tab
      }).apply(popupXml)
  }
  val popupXml: NodeSeq = {
    <div class="simplemodal-title">
      <h1>Node compliance detail</h1>
      <hr/>
    </div>
    <div class="simplemodal-content" style="max-height:500px;overflow-y:auto;">
      <div id="innerContent"/>
    </div>
    <div class="simplemodal-bottom">
      <hr/>
      <div class="popupButton">
        <span>
          <button class="simplemodal-close" onClick="return false;">
            Close
          </button>
        </span>
      </div>
    </div>
  }

  val htmlId_rulesGridZone = "rules_grid_zone"
  val htmlId_reportsPopup = "popup_" + htmlId_rulesGridZone
  val htmlId_modalReportsPopup = "modal_" + htmlId_rulesGridZone


  /**
   * Create the popup
   */
  private[this] def showPopup(directiveStatus: RuleStatusReport, directiveLib: FullActiveTechniqueCategory, allNodeInfos: Map[NodeId, NodeInfo]) : JsCmd = {
    val popupHtml = createPopup(directiveStatus, directiveLib, allNodeInfos)
    SetHtml(htmlId_reportsPopup, popupHtml) & OnLoad(
        JsRaw("""
            $('.dataTables_filter input').attr("placeholder", "Search");
            """
        ) //&  initJsCallBack(tableId)
    ) &
    JsRaw( s""" createPopup("${htmlId_modalReportsPopup}")""")
  }

  /**
   * We need to have a Popup with all the ComponentValueRuleStatusReports for
   * cases when differents nodes have differents values
   */
  private[this] def showPopup(
      componentStatus : ComponentValueRuleStatusReport
    , cptStatusReports: Seq[ComponentValueRuleStatusReport]
    , directiveLib    : FullActiveTechniqueCategory
    , allNodeInfos    : Map[NodeId, NodeInfo]
  ) : JsCmd = {
    val popupHtml = createPopup(componentStatus, cptStatusReports, directiveLib, allNodeInfos)
    SetHtml(htmlId_reportsPopup, popupHtml) & OnLoad(
        JsRaw("""
            $('.dataTables_filter input').attr("placeholder", "Search");
            """
        ) //&  initJsCallBack(tableId)
    ) &
    JsRaw( s""" createPopup("${htmlId_modalReportsPopup}")""")
  }

}
