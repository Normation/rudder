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

package com.normation.rudder.web.snippet.configuration

import com.normation.rudder.domain.policies._
import com.normation.rudder.services.policies._
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.services.TechniqueRepository
import net.liftweb.http.LocalSnippet
import net.liftweb.common._
import Box._
import net.liftweb.http.{SHtml,S}
import scala.xml._
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.js._
import JsCmds._
import com.normation.rudder.web.components.popup.CreateOrCloneRulePopup
import JE._
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers._
import com.normation.rudder.web.components.{
  RuleEditForm,
  ComponentInitializationException,
  RuleGrid
}
import com.normation.rudder.domain.policies.{GroupTarget,Rule}
import com.normation.rudder.repository._
import com.normation.utils.StringUuidGenerator
import com.normation.plugins.{SpringExtendableSnippet,SnippetExtensionKey}
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.web.components.RuleCategoryTree
import com.normation.rudder.rule.category._
import com.normation.eventlog.ModificationId
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.web.components.RuleDisplayer

/**
 * Snippet for managing Rules.
 * It allows to see what Rules are available,
 * remove or edit them,
 * and add new ones.
 */
class RuleManagement extends DispatchSnippet with SpringExtendableSnippet[RuleManagement] with Loggable {
  import RuleManagement._

  private[this] val ruleRepository       = RudderConfig.roRuleRepository
  private[this] val roCategoryRepository = RudderConfig.roRuleCategoryRepository
  private[this] val woCategoryRepository = RudderConfig.woRuleCategoryRepository
  private[this] val uuidGen              = RudderConfig.stringUuidGenerator

  //the popup component
  private[this] val creationPopup = new LocalSnippet[CreateOrCloneRulePopup]

  val extendsAt = SnippetExtensionKey(classOf[RuleManagement].getSimpleName)


  override def mainDispatch = {
    RudderConfig.configService.rudder_workflow_enabled match {
      case Full(workflowEnabled) =>
        RudderConfig.configService.rudder_ui_changeMessage_enabled match {
          case Full(changeMsgEnabled) =>
            Map(
                "head" -> { _:NodeSeq => head(workflowEnabled, changeMsgEnabled) }
              , "editRule" -> { _:NodeSeq => editRule(workflowEnabled, changeMsgEnabled) }
              , "viewRules" -> { _:NodeSeq => viewRules(workflowEnabled, changeMsgEnabled) }
            )
          case  eb: EmptyBox =>
            val e = eb ?~! "Error when getting Rudder application configuration for change message activation"
            logger.error(s"Error when displaying Rules : ${e.messageChain}")
            Map(
                "head" -> { _:NodeSeq => NodeSeq.Empty }
              , "editRule" -> { _:NodeSeq => NodeSeq.Empty }
              , "viewRules" -> { _: NodeSeq => <div class="error">{e.msg}</div> }
            )
        }
      case eb: EmptyBox =>
        val e = eb ?~! "Error when getting Rudder application configuration for workflow activation"
        logger.error(s"Error when displaying Rules : ${e.messageChain}")
        Map(
            "head" -> { _:NodeSeq => NodeSeq.Empty }
          , "editRule" -> { _:NodeSeq => NodeSeq.Empty }
          , "viewRules" -> { _: NodeSeq => <div class="error">{e.msg}</div> }
        )
    }
  }


  private def setCreationPopup(ruleToClone:Option[Rule], workflowEnabled: Boolean, changeMsgEnabled : Boolean) : Unit = {
         creationPopup.set(Full(new CreateOrCloneRulePopup(ruleToClone,
            onSuccessCallback = onCreateRule(workflowEnabled, changeMsgEnabled) )))
   }


   /**
    * Create the popup
    */
  def createPopup : NodeSeq = {
    creationPopup.is match {
      case Failure(m,_,_) =>  <span class="error">Error: {m}</span>
      case Empty => <div>The component is not set</div>
      case Full(popup) => popup.popupContent()
    }
  }

  private[this] val currentRuleForm = new LocalSnippet[RuleEditForm]


  def head(workflowEnabled: Boolean, changeMsgEnabled : Boolean) : NodeSeq = {
    RuleEditForm.staticInit ++
    RuleGrid.staticInit ++
    {<head>
      <script type="text/javascript" src="/javascript/jstree/jquery.jstree.js" id="jstree"></script>
      <script type="text/javascript" src="/javascript/rudder/tree.js" id="tree"></script>
      {Script(
        JsRaw("""
var include = true;
var filter = "Rules";
var column = 1;
$.fn.dataTableExt.oStdClasses.sPageButtonStaticDisabled="paginate_button_disabled";
        function updateTips( t ) {
          tips
            .text( t )
            .addClass( "ui-state-highlight" );
          setTimeout(function() {
            tips.removeClass( "ui-state-highlight", 1500 );
          }, 500 );
        }
        function checkLength( o, n, min, max ) {
          if ( o.val().length > max || o.val().length < min ) {
            o.addClass( "ui-state-error" );
            updateTips( "Length of " + n + " must be between " +
              min + " and " + max + "." );
            return false;
          } else {
            return true;
          }
        }
        """) &
        OnLoad(JsRaw("""correctButtons();""") & parseJsArg(workflowEnabled, changeMsgEnabled))
      ) }
    </head>
    }
  }

  def viewRules(workflowEnabled: Boolean, changeMsgEnabled : Boolean) : NodeSeq = {
    new RuleDisplayer(None,"rules_grid_zone",detailsCallbackLink(workflowEnabled, changeMsgEnabled),showPopup(None, workflowEnabled, changeMsgEnabled)).display
  }

  def editRule(workflowEnabled: Boolean, changeMsgEnabled : Boolean, dispatch:String="showForm") : NodeSeq = {
    def errorDiv(f:Failure) = <div id={htmlId_editRuleDiv} class="error">Error in the form: {f.messageChain}</div>

    currentRuleForm.is match {
      case f:Failure => errorDiv(f)
      case Empty => <div id={htmlId_editRuleDiv}/>
      case Full(form) =>
        form.dispatch(dispatch)(NodeSeq.Empty) ++
        Script(JsRaw("""$("#editRuleZonePortlet").removeClass("nodisplay");
                           scrollToElement("editRuleZonePortlet");"""
        ) )
    }
  }

  def onCreateRule(workflowEnabled: Boolean, changeMsgEnabled : Boolean)(rule : Rule) : JsCmd = {
    updateEditComponent(rule, workflowEnabled, changeMsgEnabled)

    //update UI
    Replace(htmlId_viewAll,  viewRules(workflowEnabled, changeMsgEnabled)) &
    Replace(htmlId_editRuleDiv, editRule(workflowEnabled, changeMsgEnabled, "showEditForm"))
  }

  /**
   * If a query is passed as argument, try to dejoniffy-it, in a best effort
   * way - just don't take of errors.
   *
   * We want to look for #{ "ruleId":"XXXXXXXXXXXX" }
   */
  private[this] def parseJsArg(workflowEnabled: Boolean, changeMsgEnabled : Boolean)(): JsCmd = {
    def displayDetails(ruleId:String) = {
      ruleRepository.get(RuleId(ruleId)) match {
        case Full(rule) =>
          onCreateRule(workflowEnabled, changeMsgEnabled)(rule)
        case _ => Noop
      }
    }

    JsRaw("""
        var ruleId = null;
        try {
          ruleId = JSON.parse(window.location.hash.substring(1)).ruleId ;
        } catch(e) {
          ruleId = null
        }
        if( ruleId != null && ruleId.length > 0) {
          %s;
        }
    """.format(SHtml.ajaxCall(JsVar("ruleId"), displayDetails _ )._2.toJsCmd)
    )
  }

  private[this] def showPopup(clonedRule:Option[Rule], workflowEnabled: Boolean, changeMsgEnabled : Boolean) : JsCmd = {
    setCreationPopup(clonedRule, workflowEnabled, changeMsgEnabled)
    val popupHtml = createPopup
    SetHtml(CreateOrCloneRulePopup.htmlId_popupContainer, popupHtml) &
    JsRaw( s""" createPopup("${CreateOrCloneRulePopup.htmlId_popup}") """)

  }

  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private[this] def updateEditComponent(rule:Rule, workflowEnabled: Boolean, changeMsgEnabled : Boolean) : Unit = {
    val form = new RuleEditForm(
                       htmlId_editRuleDiv+"Form"
                     , rule
                     , workflowEnabled
                     , changeMsgEnabled
                     , onCloneCallback = { (updatedRule:Rule) => showPopup(Some(updatedRule), workflowEnabled, changeMsgEnabled) }
                     , onSuccessCallback = { () => //update UI
                         Replace(htmlId_viewAll,  viewRules(workflowEnabled, changeMsgEnabled) )
                       }
                   )
    currentRuleForm.set(Full(form))
  }

  private[this] def detailsCallbackLink(workflowEnabled: Boolean, changeMsgEnabled : Boolean)(rule:Rule, id:String="showForm") : JsCmd = {
    updateEditComponent(rule, workflowEnabled, changeMsgEnabled)
    //update UI
    Replace(htmlId_editRuleDiv, editRule(workflowEnabled, changeMsgEnabled, id)) &
    JsRaw("""this.window.location.hash = "#" + JSON.stringify({'ruleId':'%s'})""".format(rule.id.value))
  }

}


object RuleManagement {
  val htmlId_editRuleDiv = "editRuleZone"
  val htmlId_viewAll = "viewAllRulesZone"
  val htmlId_addPopup = "add-rule-popup"
  val htmlId_addCrButton = "add-rule-button"
}

