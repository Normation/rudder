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
import com.normation.rudder.domain.policies.RuleId


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
    val root = directive.map(_.rootCategory).getOrElse(roCategoryRepository.getRootCategory.get)
    val ruleGrid = new RuleCategoryTree(
        "categoryTree"
      , root
      , directive
      , (() =>  check)
    )
      def AddNewCategory() = {
      val index = randomInt(1000)
      val newCat = RuleCategory(
          RuleCategoryId(uuidGen.newUuid)
        , s"Category-$index"
        , s"this is category $index"
        , Nil
      )

      woCategoryRepository.create(newCat, root.id,ModificationId(uuidGen.newUuid),CurrentUser.getActor,None)
      SetHtml("categoryTreeParent",viewCategories)
    }
    val actionButton =
                 if (directive.isEmpty) {
                  SHtml.ajaxButton("New Category", () => AddNewCategory(), ("class" -> "newRule")) ++ Script(OnLoad(JsRaw("correctButtons();")))
                } else {
                  NodeSeq.Empty
                }

     <div>
       <lift:authz role="rule_write">
         <div>
           {actionButton}
         </div>
       </lift:authz>
       <div id="treeParent" style="overflow:auto; margin-top:10px; max-height:300px;border: 1px #999 ridge; padding-right:15px;">
         <div id="categoryTree">
           {ruleGrid.tree}
         </div>
       </div>
     </div>
  }

  def check() = {
    def action(ruleId : RuleId, status:Boolean) = {
      JsRaw(s"""$$('#${ruleId.value}Checkbox').prop("checked",${status}); """)
    }

    directive match {
      case Some(d) => d.checkRules match {case (toCheck,toUncheck) => (toCheck.map(r => action(r.id,true)) ++ toUncheck.map(r => action(r.id,false)) :\ Noop){ _ & _}}
      case None    => Noop
    }
  }


  def viewRules : NodeSeq = {

    val rules = directive.map(_.rules).getOrElse(ruleRepository.getAll().openOr(Seq()))
    val ruleGrid = {
      new RuleGrid(
          "rules_grid_zone"
        , rules
        , Some(detailsCallbackLink)
        , directive.isDefined
        , directive
      )
    }
    def includeSubCategory = {
      SHtml.ajaxCheckbox(
          true
        , value =>  JsRaw(s"""
          include=${value};
          filterTableInclude('#grid_rules_grid_zone',filter,include); """) & check()
        , ("id","includeCheckbox")
      )
    }



    def actionButton = {
      if (directive.isDefined) {
          SHtml.ajaxButton("Select All", () => ruleGrid.selectAllVisibleRules(true)) ++ Script(OnLoad(JsRaw("correctButtons();")))
      } else {
        SHtml.ajaxButton("New Rule", () => showPopup, ("class" -> "newRule")) ++ Script(OnLoad(JsRaw("correctButtons();")))
      }
    }

    <div id={htmlId_viewAll}>
      <div>
        <lift:authz role="rule_write">
          {actionButton}
        </lift:authz>
        <span style="margin:10px 50px;">{includeSubCategory} <span style="margin-left:10px;"> Display Rules from subcategories</span></span>
      </div>
      {ruleGrid.rulesGridWithUpdatedInfo(directive.isDefined) }
    </div>

  }

  def display = {
   val columnToFilter = {
     if (directive.isDefined) 2 else 1
   }
   <div style="padding:10px;">
      <div style="float:left; width: 20%; overflow:auto">
        <div class="inner-portlet-header" style="letter-spacing:1px; padding-top:0;">CATEGORIES</div>
          <div class="inner-portlet-content" id="categoryTreeParent">
            {viewCategories}
          </div>
      </div>
      <div style="float:left; width:78%;padding-left:2%;">
        <div class="inner-portlet-header" style="letter-spacing:1px; padding-top:0;" id="categoryDisplay">RULES</div>
        <div class="inner-portlet-content">
          {viewRules}
        </div>
      </div>
    </div> ++ Script(JsRaw(s"""
var include = true;
var filter = "";
var column = ${columnToFilter};"""))
  }

}