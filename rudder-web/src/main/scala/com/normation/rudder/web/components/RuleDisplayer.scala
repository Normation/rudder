/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

import bootstrap.liftweb.RudderConfig
import net.liftweb.http.DispatchSnippet
import net.liftweb.common._
import com.normation.rudder.domain.policies.Directive
import net.liftweb.http.{SHtml,S}
import scala.xml._
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.js._
import JsCmds._
import com.normation.rudder.web.components.popup.CreateOrCloneRulePopup
import JE._
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers._
import com.normation.eventlog.ModificationId
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.rule.category._
import com.normation.rudder.domain.policies.Rule


class RuleDisplayer (
    directive : Option[DirectiveApplicationManagement]
  , htmlId_viewAll : String
  , detailsCallbackLink : (Rule, String) => JsCmd
  , showPopup : JsCmd
) extends DispatchSnippet with Loggable  {


  private[this] val ruleRepository       = RudderConfig.roRuleRepository
  private[this] val roCategoryRepository = RudderConfig.roRuleCategoryRepository
  private[this] val woCategoryRepository = RudderConfig.woRuleCategoryRepository
  private[this] val ruleCategoryService  = RudderConfig.ruleCategoryService
  private[this] val uuidGen              = RudderConfig.stringUuidGenerator

  def dispatch = {
    case "display" => { _ => NodeSeq.Empty }
  }

  def viewCategories : NodeSeq = {
    val ruleGrid = new RuleCategoryTree(
        "categoryTree"
    )
      def AddNewCategory() = {
      val index = randomInt(1000)
      val newCat = RuleCategory(
          RuleCategoryId(uuidGen.newUuid)
        , s"Category-$index"
        , s"this is category $index"
        , Nil
      )
      val root = roCategoryRepository.getRootCategory.get
      woCategoryRepository.create(newCat, root.id,ModificationId(uuidGen.newUuid),CurrentUser.getActor,None)
      SetHtml("categoryTreeParent",viewCategories)
    }
     <div>
                <lift:authz role="rule_write">
              <div id="actions_zone">
                { if (directive.isEmpty) {
                  SHtml.ajaxButton("New Category", () => AddNewCategory(), ("class" -> "newRule")) ++ Script(OnLoad(JsRaw("correctButtons();")))}
                } else {
                  NodeSeq.Empty
                }
              </div>
            </lift:authz>
             <div class="dataTables_wrapper" id="categoryTree">
             {ruleGrid.tree}
             </div>
     </div>
  }

  def viewRules : NodeSeq = {
    val ruleGrid = new RuleGrid(
        "rules_grid_zone",
        ruleRepository.getAll().openOr(Seq()),
        Some(detailsCallbackLink),
        showCheckboxColumn = directive.isDefined
    )
    def includeSubCategory = {
      SHtml.ajaxCheckbox(true, value => JsRaw(s"""include=${value};
          filterTableInclude('#grid_rules_grid_zone',filter,include); """), ("id","includeCheckbox"))

    }

            <div id={htmlId_viewAll}>
              <div id="actions_zone">
                <lift:authz role="rule_write">
                { if (directive.isDefined) {
                    SHtml.ajaxButton("Select All", () => showPopup, ("class" -> "newRule")) ++ Script(OnLoad(JsRaw("correctButtons();")))
                  } else {
                    SHtml.ajaxButton("New Rule", () => showPopup, ("class" -> "newRule")) ++ Script(OnLoad(JsRaw("correctButtons();")))
                  }
                }
                </lift:authz>
                <span style="margin-left:50px;">{includeSubCategory} <span style="margin-left:10px;"> Include Rules from subcategories</span></span>
              </div>
             {ruleGrid.rulesGridWithUpdatedInfo(directive.isDefined) }
           </div>

  }

  def display = {
    <div>
      <div class="inner-portlet" style="float:left; margin-top:40px; min-width:150px; width:30%; overflow: auto">
        <div class="inner-portlet-header" style="font-family:monospace; color: #999; border:0; letter-spacing:1px;'">Categories</div>
          <div class="inner-portlet-content" id="categoryTreeParent">
            {viewCategories}
          </div>
      </div>
      <div class="inner-portlet" style="float:left; min-width:300px; width:60%; border-left: 1px #656565 solid; margin-top:40px; padding-left:40px;">
        <div class="inner-portlet-header" style="font-family:monospace; color: #999; border:0; letter-spacing:1px;'" id="categoryDisplay">Rules</div>
        <div class="inner-portlet-content">
          {viewRules}
        </div>
      </div>
    </div> ++ Script(JsRaw("""
var include = true;
var filter = "Rules";
var column = 2;"""))
  }

}