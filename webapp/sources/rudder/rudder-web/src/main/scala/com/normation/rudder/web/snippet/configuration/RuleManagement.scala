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

package com.normation.rudder.web.snippet.configuration

import com.normation.rudder.domain.policies._
import net.liftweb.http.LocalSnippet
import net.liftweb.common._
import net.liftweb.http.SHtml
import scala.xml._
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.js._
import JsCmds._
import com.normation.rudder.web.components.popup.CreateOrCloneRulePopup
import JE._
import com.normation.rudder.web.components.{
  RuleEditForm,

  RuleGrid
}
import com.normation.rudder.domain.policies.Rule
import com.normation.plugins.{SpringExtendableSnippet,SnippetExtensionKey}
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.web.components.RuleDisplayer
import com.normation.rudder.web.components.DisplayColumn

/**
 * Snippet for managing Rules.
 * It allows to see what Rules are available,
 * remove or edit them,
 * and add new ones.
 */
class RuleManagement extends DispatchSnippet with SpringExtendableSnippet[RuleManagement] with Loggable {
  import RuleManagement._

  private[this] val ruleRepository       = RudderConfig.roRuleRepository

  //the popup component
  private[this] val currentRuleForm = new LocalSnippet[RuleEditForm]
  private[this] val currentRuleDisplayer = new LocalSnippet[RuleDisplayer]

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
            val e = eb ?~! "Error when getting Rudder application configuration for change audit message activation"
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

  def head(workflowEnabled: Boolean, changeMsgEnabled : Boolean) : NodeSeq = {
    RuleEditForm.staticInit ++
    RuleGrid.staticInit ++
    {<head>
      {Script(
        JsRaw("""
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
        OnLoad(parseJsArg(workflowEnabled, changeMsgEnabled))
      ) }
    </head>
    }
  }

  def viewRules(workflowEnabled: Boolean, changeMsgEnabled : Boolean) : NodeSeq = {
    currentRuleDisplayer.set(Full(new RuleDisplayer(
        None
      , "rules_grid_zone"
      , detailsCallbackLink(workflowEnabled, changeMsgEnabled)
      , (rule : Rule ) => onCreateRule(workflowEnabled, changeMsgEnabled)(rule,"showForm")
      , showPopup
      , DisplayColumn.Force(true)
      , DisplayColumn.FromConfig
    )))

    currentRuleDisplayer.get match {
      case Full(ruleDisplayer) => ruleDisplayer.display
      case eb: EmptyBox =>
        val fail = eb ?~! ("Error when displaying Rules")
        <div class="error">Error in the form: {fail.messageChain}</div>
    }
  }

  // When a rule is changed, the Rule displayer should be updated (Rule grid, selected category, ...)
  def onRuleChange(workflowEnabled: Boolean, changeMsgEnabled : Boolean)(rule:Rule) = {
    currentRuleDisplayer.get match {
      case Full(ruleDisplayer) =>
        ruleDisplayer.onRuleChange(rule.categoryId)
      case eb: EmptyBox =>
        SetHtml(htmlId_viewAll,viewRules(workflowEnabled, changeMsgEnabled))
    }
  }
  def editRule(workflowEnabled: Boolean, changeMsgEnabled : Boolean, dispatch:String="showForm") : NodeSeq = {
    def errorDiv(f:Failure) = <div id={htmlId_editRuleDiv} class="error">Error in the form: {f.messageChain}</div>

    currentRuleForm.get match {
      case f:Failure => errorDiv(f)
      case Empty => <div id={htmlId_editRuleDiv}></div>
      case Full(form) => form.dispatch(dispatch)(NodeSeq.Empty)
    }

  }

  def onCreateRule(workflowEnabled: Boolean, changeMsgEnabled : Boolean)(rule : Rule, action : String) : JsCmd = {
    updateEditComponent(rule, workflowEnabled, changeMsgEnabled)

    //update UI
    onRuleChange(workflowEnabled, changeMsgEnabled)(rule) &
    Replace(htmlId_editRuleDiv, editRule(workflowEnabled, changeMsgEnabled, action))
  }

  /**
   * If a query is passed as argument, try to dejoniffy-it, in a best effort
   * way - just don't take of errors.
   *
   * We want to look for #{ "ruleId":"XXXXXXXXXXXX" }
   */
  private[this] def parseJsArg(workflowEnabled: Boolean, changeMsgEnabled : Boolean)(): JsCmd = {
    def displayDetails(ruleData:String) = {
      import net.liftweb.json._
      val json = parse(ruleData)
      json \ "ruleId" match {
        case JString(ruleId) =>
          ruleRepository.get(RuleId(ruleId)) match {
            case Full(rule) =>
              json \ "action" match {
                case JString(action) =>
                  onCreateRule(workflowEnabled, changeMsgEnabled)(rule,action)
                case _ =>
                  onCreateRule(workflowEnabled, changeMsgEnabled)(rule,"showForm")
              }

            case _ => Noop
          }
        case _ => Noop
      }
    }

    JsRaw(s"""
        var ruleData = null;
        try {
          ruleData = decodeURI(window.location.hash.substring(1)) ;
        } catch(e) {
          ruleData = null
        }
        if( ruleData != null && ruleData.length > 0) {
          ${SHtml.ajaxCall(JsVar("ruleData"), displayDetails _ )._2.toJsCmd}
        }
    """
    )
  }

     /**
    * Create the popup
    */
  def createPopup(ruleToClone : Option[Rule]) : NodeSeq = {
    currentRuleDisplayer.get match {
      case Failure(m,_,_) =>  <span class="error">Error: {m}</span>
      case Empty => <div>The component is not set</div>
      case Full(popup) => popup.ruleCreationPopup(ruleToClone)
    }
  }

  private[this] def showPopup(clonedRule:Option[Rule]) : JsCmd = {

    val popupHtml = createPopup(clonedRule:Option[Rule])
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
                     , onCloneCallback = { (updatedRule:Rule) => showPopup(Some(updatedRule)) }
                     , onSuccessCallback = { (updatedRule:Rule) => onRuleChange(workflowEnabled,changeMsgEnabled)(updatedRule)  }
                   )
    currentRuleForm.set(Full(form))
  }

  private[this] def detailsCallbackLink(workflowEnabled: Boolean, changeMsgEnabled : Boolean)(rule:Rule, action:String) : JsCmd = {
    updateEditComponent(rule, workflowEnabled, changeMsgEnabled)
    //update UI
    Replace(htmlId_editRuleDiv, editRule(workflowEnabled, changeMsgEnabled, action )) &
    JsRaw("""this.window.location.hash = "#" + JSON.stringify({'ruleId':'%s'})""".format(rule.id.value))
  }

}

object RuleManagement {
  val htmlId_editRuleDiv = "editRuleZone"
  val htmlId_viewAll = "viewAllRulesZone"
  val htmlId_addPopup = "add-rule-popup"
  val htmlId_addCrButton = "add-rule-button"
}
