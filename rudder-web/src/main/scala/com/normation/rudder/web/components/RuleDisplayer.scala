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
import net.liftweb.http.LocalSnippet
import com.normation.rudder.web.components.popup.RuleCategoryPopup


class RuleDisplayer (
    directive           : Option[DirectiveApplicationManagement]
  , gridId              : String
  , detailsCallbackLink : (Rule, String) => JsCmd
  , onCreateRule        : (Rule) => JsCmd
  , showRulePopup        : (Option[Rule]) => JsCmd
) extends DispatchSnippet with Loggable  {


  private[this] val ruleRepository       = RudderConfig.roRuleRepository
  private[this] val roCategoryRepository = RudderConfig.roRuleCategoryRepository
  private[this] val woCategoryRepository = RudderConfig.woRuleCategoryRepository
  private[this] val ruleCategoryService  = RudderConfig.ruleCategoryService
  private[this] val uuidGen              = RudderConfig.stringUuidGenerator

  private[this] val htmlId_popup = "createRuleCategoryPopup"

  private[this] var root : Box[RuleCategory]= {
    directive match  {
      case Some(appManagement) =>
        Full(appManagement.rootCategory)
      case None =>
        roCategoryRepository.getRootCategory
    }
  }

  def dispatch = {
    case "display" => { _ => NodeSeq.Empty }
  }

  private[this] def refreshGrid = {
    SetHtml(gridId, viewRules)
  }

  private[this] val ruleCategoryTree = {
    root.map(
      new RuleCategoryTree(
          "categoryTree"
        , _
        , directive
        , (() =>  check)
        , ((c:RuleCategory) => showCategoryPopup(Some(c)))
        , ((c:RuleCategory) => showDeleteCategoryPopup(c))
        , () => refreshGrid
    ) )
  }
  def viewCategories(ruleCategoryTree : RuleCategoryTree) : NodeSeq = {


    val actionButton =
                 if (directive.isEmpty) {
                  SHtml.ajaxButton("New Category", () => showCategoryPopup(None), ("class" -> "newRule")) ++ Script(OnLoad(JsRaw("correctButtons();")))
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
           {ruleCategoryTree.tree}
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


  private[this] def rules = directive.map(_.rules).getOrElse(ruleRepository.getAll().openOr(Seq()))

  def viewRules : NodeSeq = {

    val callbackLink = {
      if (directive.isDefined) {
        None
      } else {
        Some(detailsCallbackLink)
      }
    }
    val ruleGrid = {
      new RuleGrid(
          "rules_grid_zone"
        , rules
        , callbackLink
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
        NodeSeq.Empty
      } else {
        SHtml.ajaxButton("New Rule", () => showRulePopup(None), ("class" -> "newRule"),("style","float:left")) ++ Script(OnLoad(JsRaw("correctButtons();")))
      }
    }

    <div id={gridId}>
      <div>
        <lift:authz role="rule_write">
          {actionButton}
        </lift:authz>
        <div style={s"margin:10px 0px 0px ${if (directive.isDefined) 0 else 50}px; float:left"}>{includeSubCategory} <span style="margin-left:10px;"> Display Rules from subcategories</span></div>
        <hr class="spacer"/>
      </div>
      {ruleGrid.rulesGridWithUpdatedInfo(directive.isDefined) }
    </div>

  }

  def display = {
   val columnToFilter = {
     if (directive.isDefined) 2 else 1
   }

   ruleCategoryTree match {
     case Full(ruleCategoryTree) =>
       <div style="padding:10px;">
         <div style="float:left; width: 20%; overflow:auto">
           <div class="inner-portlet-header" style="letter-spacing:1px; padding-top:0;">CATEGORIES</div>
           <div class="inner-portlet-content" id="categoryTreeParent">
               {viewCategories(ruleCategoryTree)}
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
                  var column = ${columnToFilter};"""
                ) )
     case eb:EmptyBox =>
       val fail = eb ?~! "Could not get root category"
       val msg = s"An error occured while fetching Rule categories , cause is ${fail.messageChain}"
       logger.error(msg)
       <div style="padding:10px;">
         <div class="error">{msg}</div>
       </div>
   }
  }


  def ruleCreationPopup (ruleToClone:Option[Rule]) = {
    ruleCategoryTree match {
      case Full(ruleCategoryTree) =>
        val root = ruleCategoryTree.getRoot
        new CreateOrCloneRulePopup(
            root
          , ruleToClone
          , ruleCategoryTree.getSelected
          , onSuccessCallback = onCreateRule
        ).popupContent
     case eb:EmptyBox =>
       val fail = eb ?~! "Could not get root category"
       val msg = s"An error occured while fetching Rule categories , cause is ${fail.messageChain}"
       logger.error(msg)
       <div style="padding:10px;">
         <div class="error">{msg}</div>
       </div>
    }
  }


  // Popup

    private[this] def creationPopup(category : Option[RuleCategory], ruleCategoryTree : RuleCategoryTree) = {
      val rootCategory = ruleCategoryTree.getRoot
      new  RuleCategoryPopup(
          rootCategory
        , category
        , ruleCategoryTree.getSelected
        , {(r : RuleCategory) =>
            root = roCategoryRepository.getRootCategory
            ruleCategoryTree.refreshTree(root) & refreshGrid
          }
      )
    }

   /**
    * Create the popup
    */
    private[this] def showCategoryPopup(category : Option[RuleCategory]) : JsCmd = {
    val popupHtml =
      ruleCategoryTree match {
        case Full(ruleCategoryTree) =>
          creationPopup(category,ruleCategoryTree).popupContent
        case eb:EmptyBox =>
          // Should not happen, the function will be called only if the rootCategory is Set
          val fail = eb ?~! "Could not get root category"
          val msg = s"An error occured while fetching Rule categories , cause is ${fail.messageChain}"
          logger.error(msg)
          <div style="padding:10px;">
            <div class="error">{msg}</div>
          </div>
     }
      SetHtml(htmlId_popup, popupHtml) &
      JsRaw( s""" createPopup("${htmlId_popup}") """)
    }

  /**
    * Create the delete popup
    */
    private[this] def showDeleteCategoryPopup(category : RuleCategory) : JsCmd = {
    val popupHtml =
      ruleCategoryTree match {
        case Full(ruleCategoryTree) =>
          creationPopup(Some(category), ruleCategoryTree).deletePopupContent(category.canBeDeleted(rules.toList))
        case eb:EmptyBox =>
          // Should not happen, the function will be called only if the rootCategory is Set
          val fail = eb ?~! "Could not get root category"
          val msg = s"An error occured while fetching Rule categories , cause is ${fail.messageChain}"
          logger.error(msg)
          <div style="padding:10px;">
            <div class="error">{msg}</div>
          </div>
     }
    SetHtml(htmlId_popup, popupHtml) &
    JsRaw( s""" createPopup("${htmlId_popup}") """)
    }

}