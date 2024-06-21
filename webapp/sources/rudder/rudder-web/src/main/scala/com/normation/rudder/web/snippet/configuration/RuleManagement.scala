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

import bootstrap.liftweb.RudderConfig
import com.normation.box.*
import com.normation.plugins.DefaultExtendableSnippet
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.web.components.DisplayColumn
import com.normation.rudder.web.components.RuleDisplayer
import com.normation.rudder.web.components.RuleEditForm
import com.normation.rudder.web.components.popup.CreateOrCloneRulePopup
import net.liftweb.common.*
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.LocalSnippet
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import org.apache.commons.text.StringEscapeUtils
import scala.xml.*

/**
 * Snippet for managing Rules.
 * It allows to see what Rules are available,
 * remove or edit them,
 * and add new ones.
 */
class RuleManagement extends DispatchSnippet with DefaultExtendableSnippet[RuleManagement] with Loggable {
  import RuleManagement.*

  // the popup component
  private[this] val currentRuleForm      = new LocalSnippet[RuleEditForm]
  private[this] val currentRuleDisplayer = new LocalSnippet[RuleDisplayer]

  override def mainDispatch: Map[String, NodeSeq => NodeSeq] = {
    RudderConfig.configService.rudder_ui_changeMessage_enabled().toBox match {
      case Full(changeMsgEnabled) =>
        Map(
          "viewRules" -> { (_: NodeSeq) => viewRules(changeMsgEnabled) }
        )
      case eb: EmptyBox =>
        val e = eb ?~! "Error when getting Rudder application configuration for change audit message activation"
        logger.error(s"Error when displaying Rules : ${e.messageChain}")
        Map(
          "viewRules" -> { (_: NodeSeq) => <div class="error">{e.msg}</div> }
        )
    }
  }

  def viewRules(changeMsgEnabled: Boolean): NodeSeq = {
    currentRuleDisplayer.set(
      Full(
        new RuleDisplayer(
          None,
          "rules_grid_zone",
          detailsCallbackLink(changeMsgEnabled),
          (rule: Rule) => onCreateRule(changeMsgEnabled)(rule, "showForm"),
          showPopup,
          DisplayColumn.Force(true),
          DisplayColumn.FromConfig
        )
      )
    )

    currentRuleDisplayer.get match {
      case Full(ruleDisplayer) => ruleDisplayer.display
      case eb: EmptyBox =>
        val fail = eb ?~! ("Error when displaying Rules")
        <div class="error">Error in the form: {fail.messageChain}</div>
    }
  }

  // When a rule is changed, the Rule displayer should be updated (Rule grid, selected category, ...)
  def onRuleChange(changeMsgEnabled: Boolean)(rule: Rule):                JsCmd   = {
    currentRuleDisplayer.get match {
      case Full(ruleDisplayer) =>
        ruleDisplayer.onRuleChange(rule.categoryId)
      case eb: EmptyBox =>
        SetHtml(htmlId_viewAll, viewRules(changeMsgEnabled))
    }
  }
  def editRule(changeMsgEnabled: Boolean, dispatch: String = "showForm"): NodeSeq = {
    def errorDiv(f: Failure) = <div id={htmlId_editRuleDiv} class="error">Error in the form: {f.messageChain}</div>
    currentRuleForm.get match {
      case f: Failure => errorDiv(f)
      case Empty      => <div id={htmlId_editRuleDiv}></div>
      case Full(form) => form.dispatch(dispatch)(NodeSeq.Empty)
    }

  }

  def onCreateRule(changeMsgEnabled: Boolean)(rule: Rule, action: String): JsCmd = {
    updateEditComponent(rule, changeMsgEnabled)

    // update UI
    onRuleChange(changeMsgEnabled)(rule) &
    JsRaw(s"sessionStorage.removeItem('tags-${StringEscapeUtils.escapeEcmaScript(rule.id.serialize)}');") & // JsRaw ok, escaped
    Replace(htmlId_editRuleDiv, editRule(changeMsgEnabled, action))
  }

  /**
    * Create the popup
    */
  def createPopup(ruleToClone: Option[Rule]): NodeSeq = {
    currentRuleDisplayer.get match {
      case Failure(m, _, _) => <span class="error">Error: {m}</span>
      case Empty            => <div>The component is not set</div>
      case Full(popup)      => popup.ruleCreationPopup(ruleToClone)
    }
  }

  private[this] def showPopup(clonedRule: Option[Rule]): JsCmd = {
    val popupHtml = createPopup(clonedRule: Option[Rule])
    SetHtml(CreateOrCloneRulePopup.htmlId_popupContainer, popupHtml) &
    JsRaw(s""" createPopup("${CreateOrCloneRulePopup.htmlId_popup}") """) // JsRaw ok, const
  }

  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private[this] def updateEditComponent(rule: Rule, changeMsgEnabled: Boolean): Unit = {
    val form = new RuleEditForm(
      htmlId_editRuleDiv + "Form",
      rule,
      changeMsgEnabled,
      onCloneCallback = { (updatedRule: Rule) => showPopup(Some(updatedRule)) },
      onSuccessCallback = { (updatedRule: Rule) => onRuleChange(changeMsgEnabled)(updatedRule) }
    )
    currentRuleForm.set(Full(form))
  }

  private[this] def detailsCallbackLink(changeMsgEnabled: Boolean)(rule: Rule, action: String): JsCmd = {
    updateEditComponent(rule, changeMsgEnabled)
    // existing rule id may be unsafe and needs escaping when building raw JS
    val ruleId = StringEscapeUtils.escapeEcmaScript(rule.id.serialize)
    // update UI
    Replace(htmlId_editRuleDiv, editRule(changeMsgEnabled, action)) &
    JsRaw(s"""
      sessionStorage.removeItem('tags-${ruleId}');
      this.window.location.hash = "#" + JSON.stringify({'ruleId':'${ruleId}'});
    """.stripMargin) // JsRaw ok, escaped
  }

}

object RuleManagement {
  val htmlId_editRuleDiv = "editRuleZone"
  val htmlId_viewAll     = "viewAllRulesZone"
  val htmlId_addPopup    = "add-rule-popup"
  val htmlId_addCrButton = "add-rule-button"
}
