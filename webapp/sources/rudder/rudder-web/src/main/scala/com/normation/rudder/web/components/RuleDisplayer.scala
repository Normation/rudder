/*
 *************************************************************************************
 * Copyright 2013 Normation SAS
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
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.rule.category.*
import com.normation.rudder.users.CurrentUser
import com.normation.rudder.web.components.popup.CreateOrCloneRulePopup
import com.normation.rudder.web.components.popup.RuleCategoryPopup
import net.liftweb.common.*
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.SHtml
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import org.apache.commons.text.StringEscapeUtils
import scala.xml.*

/**
 * the component in charge of displaying the rule grid, with category tree
 * and one line by rule.
 */
class RuleDisplayer(
    directive:           Option[DirectiveApplicationManagement],
    gridId:              String,
    detailsCallbackLink: (Rule, String) => JsCmd,
    onCreateRule:        (Rule) => JsCmd,
    showRulePopup:       (Option[Rule]) => JsCmd,
    columnCompliance:    DisplayColumn,
    graphRecentChanges:  DisplayColumn
) extends DispatchSnippet with Loggable {

  implicit private val qc: QueryContext = CurrentUser.queryContext // bug https://issues.rudder.io/issues/26605
  private val ruleRepository       = RudderConfig.roRuleRepository
  private val roCategoryRepository = RudderConfig.roRuleCategoryRepository

  private val htmlId_popup = "createRuleCategoryPopup"

  def getRootCategory(): Box[RuleCategory] = {
    directive match {
      case Some(appManagement) =>
        Full(appManagement.rootCategory)
      case None                =>
        roCategoryRepository.getRootCategory().toBox
    }
  }

  private var root: Box[RuleCategory] = {
    getRootCategory()
  }

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = { case "display" => { _ => NodeSeq.Empty } }

  // refresh the rule grid
  private def refreshGrid(implicit qc: QueryContext) = {
    SetHtml(gridId, viewRules)
  }

  private val ruleCategoryTree = {
    root.map(
      new RuleCategoryTree(
        "categoryTree",
        _,
        directive,
        (() => check()),
        ((c: RuleCategory) => showCategoryPopup(Some(c))),
        ((c: RuleCategory) => showDeleteCategoryPopup(c)),
        () => refreshGrid
      )
    )
  }

  def includeSubCategory:   Elem    = {
    SHtml.ajaxCheckbox(
      true,
      value => OnLoad(JsRaw(s"""
        include=${value};
        filterTableInclude('#grid_rules_grid_zone',filter,include); """)) & check(),
      ("id", "includeCheckbox")
    ) // JsRaw ok, no user inputs
  }
  def actionButtonCategory: NodeSeq = {
    if (directive.isEmpty) {
      SHtml.ajaxButton(
        "Add category",
        () => showCategoryPopup(None),
        ("class" -> "new-icon category btn btn-success btn-outline btn-sm")
      )
    } else {
      NodeSeq.Empty
    }
  }

  def displaySubcategories:                               NodeSeq = {
    <ul class="form-group list-sm">
      <li class="rudder-form">
        <div class="input-group">
          <label for="includeCheckbox" class="input-group-text" id="includeSubCategory">
            {includeSubCategory}
            <label class="label-radio" for="includeCheckbox">
              <span class="ion ion-checkmark-round"></span>
            </label>
            <span class="ion ion-checkmark-round check-icon"></span>
          </label>
          <label for="includeCheckbox" class="form-control">
            Display Rules from subcategories
          </label>
        </div>
      </li>
    </ul>
  }
  def viewCategories(ruleCategoryTree: RuleCategoryTree): NodeSeq = {
    <div id="treeParent">
      {displaySubcategories}
      <div id="categoryTree">
        {ruleCategoryTree.tree()}
      </div>
    </div>
  }

  def check(): JsCmd = {
    def action(ruleId: RuleId, status: Boolean) = {
      JsRaw(s"""$$('#${StringEscapeUtils.escapeEcmaScript(ruleId.serialize)}Checkbox').prop("checked",${status}); """)
    } // JsRaw ok, escaped

    directive match {
      case Some(d) =>
        d.checkRules match {
          case (toCheck, toUncheck) =>
            (toCheck.map(r => action(r.id, status = true)) ++ toUncheck.map(r => action(r.id, status = false)))
              .foldLeft(Noop)(_ & _)
        }
      case None    => Noop
    }
  }

  def actionButtonRule: NodeSeq = {
    if (directive.isDefined) {
      NodeSeq.Empty
    } else {
      SHtml.ajaxButton("Create Rule", () => showRulePopup(None), ("class" -> "new-icon btn btn-sm btn-success"))
    }
  }

  private def viewRules(implicit qc: QueryContext): NodeSeq = {

    val callbackLink = {
      if (directive.isDefined) {
        None
      } else {
        Some(detailsCallbackLink)
      }
    }
    val ruleGrid     = {
      new RuleGrid(
        "rules_grid_zone",
        callbackLink,
        directive.isDefined,
        directive,
        columnCompliance,
        graphRecentChanges
      )
    }

    <div>
      {
      ruleGrid.rulesGridWithUpdatedInfo(None, showActionsColumn = !directive.isDefined, isPopup = false) ++
      Script(OnLoad(ruleGrid.asyncDisplayAllRules(None).applied))
    }
    </div>

  }

  def display: NodeSeq = {
    val columnToFilter = {
      if (directive.isDefined) 3 else 2
    }

    ruleCategoryTree match {
      case Full(ruleCategoryTree) =>
        <div>
          <div class="row col-small-padding">
            <div class="col-sm-12 col-xl-3 col-lg-4">
              <div class="box">
                <div class="box-header with-border">
                  <h3 class="box-title"><i class="fa fa-filter" aria-hidden="true"></i>Filters</h3>
                </div>
                <div class="box-body">
                  <div class="row">
                    <div class="col-sm-12">
                      <div id="showFiltersRules" class="filters">
                        <div>
                          <div class="filterTag">
                            <div class="input-group search-addon">
                              <label for="searchStr" class="input-group-text search-addon"><span class="ion ion-search"></span></label>
                              <input type="text" id="searchStr" class="input-sm form-control" placeholder="Filter" onkeyup="searchTargetRules(this)"/>
                            </div>
                            <!--
                            <div class="form-group">
                              <label>Tags</label>
                              <div class="input-group">
                                <div id="ruleFilterKeyInput" angucomplete-alt="" placeholder="key" minlength="1" maxlength="100"
                                     pause="500" selected-object="selectTag" remote-url="{{contextPath}}/secure/api/completion/tags/rule/key/"
                                     remote-url-data-field="data" title-field="value" input-class="form-control input-sm input-key"
                                     match-class="highlight" input-changed="updateTag" override-suggestions="true">
                                </div>
                                <span class="input-group-text addon-json">=</span>
                                <div id="ruleFilterValueInput" angucomplete-alt="" placeholder="value" minlength="1" maxlength="100"
                                     pause="500" selected-object="selectValue" remote-url="{{contextPath}}/secure/api/completion/tags/rule/value/{{newTag.key}}/"
                                     remote-url-data-field="data" title-field="value" input-class="form-control input-sm input-value" match-class="highlight"
                                     input-changed="updateValue" override-suggestions="true">
                                </div>
                                <button type="button" ng-click="addTag(newTag)" class="btn btn-default btn-sm" ng-disabled=" (isEmptyOrBlank(newTag.key) &amp;&amp; isEmptyOrBlank(newTag.value)); ">
                                  <span class="fa fa-plus"></span>
                                </button>
                              </div>
                            </div>
                            <div class="only-tags">
                              <button class="btn btn-default btn-xs pull-right" ng-click="clearAllTags()" ng-hide="tags.length==0">
                                Clear all tags
                                <i class="fa fa-trash" aria-hidden="true"></i>
                              </button>
                            </div>
                            <div class="tags-container">
                              <div class="btn-group btn-group-xs" role="group"  ng-repeat="tag in tags track by $index">
                                <button class="btn btn-default tags-label" ng-class="{'onlyKey':only.key, 'onlyValue':only.value, 'already-exist':tag.alreadyExist}" ng-click="modifyTag(tag,'ruleFilterKeyInput','ruleFilterValueInput')" >
                                  <i class="fa fa-tag"></i>
                                  <span class="tag-key">
                                    <span ng-show="tag.key!=''">{{{{tag.key}}}}</span>
                                    <i class='fa fa-asterisk' aria-hidden='true' ng-show="tag.key==''"></i>
                                  </span>
                                  <span class="tag-separator">=</span>
                                  <span class="tag-value">
                                    <span ng-show="tag.value!=''">{{{{tag.value}}}}</span>
                                    <i class='fa fa-asterisk' aria-hidden='true' ng-show="tag.value==''"></i>
                                  </span>
                                </button>
                                <button type="button" class="btn btn-default" ng-click="removeTag($index)">
                                  <span class="fa fa-times"></span>
                                </button>
                              </div>
                            </div>
                            -->
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
              <div class="box">
                <div class="box-header with-border">
                  <h3 class="box-title"><i class="fa fa-list" aria-hidden="true"></i>Categories</h3>
                  <div class="box-tools pull-right">
                    <lift:authz role="rule_write">
                      {actionButtonCategory}
                    </lift:authz>
                  </div>
                </div>
                <div class="box-body" id="boxTreeRules">
                  <div class="row">
                    <div class="col-sm-12" id="categoryTreeParent">
                      {viewCategories(ruleCategoryTree)}
                    </div>
                  </div>
                </div>
              </div>
            </div>
            <div class="col-xl-9 col-sm-12 col-lg-8">
              <div class="box">
                <div class="box-header with-border">
                  <h3 class="box-title"><i class="fa fa-gears" aria-hidden="true"></i>Rules</h3>
                  <div class="box-tools pull-right">
                    <button class="btn btn-box-tool btn-default toggleTabFilter updateTable btn-sm" id="updateRuleTable">Refresh<span class="fa fa-refresh"></span></button>
                    <lift:authz role="rule_write">
                      {actionButtonRule}
                    </lift:authz>
                  </div>
                </div>
                <div class="box-body">
                  <div class="row">
                    <div class="col-sm-12" id={gridId}>
                      {viewRules}
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div> ++ Script(JsRaw(s"""
                  var include = true;
                  var filter = "";
                  var column = ${columnToFilter};""")) // JsRaw ok, no user inputs
      case eb: EmptyBox =>
        val fail = eb ?~! "Could not get root category"
        val msg  = s"An error occured while fetching Rule categories , cause is ${fail.messageChain}"
        logger.error(msg)
        <div style="padding:10px;">
         <div class="error">{msg}</div>
       </div>
    }
  }

  def ruleCreationPopup(ruleToClone: Option[Rule]): NodeSeq = {
    ruleCategoryTree match {
      case Full(ruleCategoryTree) =>
        val root = ruleCategoryTree.getRoot
        new CreateOrCloneRulePopup(
          root,
          ruleToClone,
          ruleCategoryTree.getSelected,
          onSuccessCallback = onCreateRule
        ).popupContent()
      case eb: EmptyBox =>
        val fail = eb ?~! "Could not get root category"
        val msg  = s"An error occured while fetching Rule categories , cause is ${fail.messageChain}"
        logger.error(msg)
        <div style="padding:10px;">
         <div class="error">{msg}</div>
       </div>
    }
  }

  // Popup
  private def creationPopup(category: Option[RuleCategory], ruleCategoryTree: RuleCategoryTree) = {
    val rootCategory = ruleCategoryTree.getRoot
    new RuleCategoryPopup(
      rootCategory,
      category,
      ruleCategoryTree.getSelected,
      { (r: RuleCategory) =>
        root = roCategoryRepository.getRootCategory().toBox
        ruleCategoryTree.refreshTree(root) & refreshGrid
      }
    )
  }

  /**
    * Create the popup
    */
  private def showCategoryPopup(category: Option[RuleCategory]): JsCmd = {
    val popupHtml = {
      ruleCategoryTree match {
        case Full(ruleCategoryTree) =>
          creationPopup(category, ruleCategoryTree).popupContent()
        case eb: EmptyBox =>
          // Should not happen, the function will be called only if the rootCategory is Set
          val fail = eb ?~! "Could not get root category"
          val msg  = s"An error occured while fetching Rule categories , cause is ${fail.messageChain}"
          logger.error(msg)
          <div style="padding:10px;">
            <div class="error">{msg}</div>
          </div>
      }
    }
    SetHtml(htmlId_popup, popupHtml) &
    JsRaw(s""" initBsModal("${htmlId_popup}") """) // JsRaw ok, const
  }

  /**
    * Create the delete popup
    */
  private def showDeleteCategoryPopup(category: RuleCategory): JsCmd = {
    val popupHtml = {
      ruleCategoryTree match {
        case Full(ruleCategoryTree) =>
          val rules = directive.map(_.rules).getOrElse(ruleRepository.getAll().toBox.openOr(Seq())).toList
          creationPopup(Some(category), ruleCategoryTree).deletePopupContent(category.canBeDeleted(rules))
        case eb: EmptyBox =>
          // Should not happen, the function will be called only if the rootCategory is Set
          val fail = eb ?~! "Could not get root category"
          val msg  = s"An error occured while fetching Rule categories , cause is ${fail.messageChain}"
          logger.error(msg)
          <div style="padding:10px;">
            <div class="error">{msg}</div>
          </div>
      }
    }
    SetHtml(htmlId_popup, popupHtml) &
    JsRaw(s"""initBsModal("${htmlId_popup}");""") // JsRaw ok, const
  }
}
