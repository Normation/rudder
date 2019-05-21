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

import com.normation.rudder.web.model.JsTreeNode
import net.liftweb.common._
import net.liftweb.http.{SHtml,S}
import scala.xml._
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.js._
import JsCmds._
import JE._
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers._
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.rule.category._
import net.liftweb.json._
import com.normation.eventlog.ModificationId
import com.normation.rudder.web.model.CurrentUser

import com.normation.box._

/**
 * A component to display a tree based on a
 * Technique.
 *
 * The tree show all parent of the Technique
 * and dependent Rules.
 *
 */
class RuleCategoryTree(
    htmlId_RuleCategoryTree : String
  , rootCategory            : RuleCategory
  , directive               : Option[DirectiveApplicationManagement]
  , check                   : () => JsCmd
  , editPopup               : RuleCategory => JsCmd
  , deletePopup             : RuleCategory => JsCmd
  , updateComponent         : () => JsCmd
) extends DispatchSnippet with Loggable {

  private[this] val roRuleCategoryRepository   = RudderConfig.roRuleCategoryRepository
  private[this] val woRuleCategoryRepository = RudderConfig.woRuleCategoryRepository
  private[this] val ruleCategoryService      = RudderConfig.ruleCategoryService
  private[this] val uuidGen              = RudderConfig.stringUuidGenerator
  private[this] var root = rootCategory

  private[this] var selectedCategoryId = rootCategory.id

  def getSelected = {
    if (root.contains(selectedCategoryId)) {
      selectedCategoryId
    } else {
      resetSelected
      rootCategory.id
    }
  }

  def getRoot = {
    root
  }
  def resetSelected = selectedCategoryId = rootCategory.id

  def dispatch = {
    case "tree" => { _ => tree }
  }

  def refreshTree(newRoot : Box[RuleCategory]) : JsCmd =  {
    val html = newRoot match {
      case Full(newRoot) =>
        root = newRoot
        <div id={htmlId_RuleCategoryTree}> {tree()}</div>
      case eb : EmptyBox =>
        val fail = eb ?~! "Could not get root category"
        val msg = s"An error occured while refreshing Rule categoriy tree , cause is ${fail.messageChain}"
        logger.error(msg)
        <div style="padding:10px;">
          <div class="error">{msg}</div>
        </div>
    }
    Replace(htmlId_RuleCategoryTree, html)&
    selectCategory() &
    OnLoad(After(TimeSpan(50), JsRaw("""createTooltip();""")))
  }

  // Update selected category and select it in the tree (trigger its function)
  def updateSelectedCategory(newSelection : RuleCategoryId) = {
    selectedCategoryId = newSelection
    // Select the new node, boolean flag to true to respect select limitation
    JsRaw(s"""
        $$("#${htmlId_RuleCategoryTree}").jstree("select_node", "#${newSelection.value}", true);
    """) &
    selectCategory()
  }

  // Perform category selection, filter in the dataTable and display the name of the category
  def selectCategory() = {
    (for {
      rootCategory <- roRuleCategoryRepository.getRootCategory.toBox
      fqdn         <- ruleCategoryService.bothFqdn(rootCategory, selectedCategoryId, true)
    } yield {
      fqdn
    }) match {
      case Full((long,short)) =>
        val escaped = Utility.escape(short)
        OnLoad(JsRaw(s"""
            filter='${escaped}';
            filterTableInclude('#grid_rules_grid_zone',filter,include);
        """)) &
        SetHtml("categoryDisplay",Text(long)) &
        check()
      case e: EmptyBox => //Display an error, for now, nothing
        Noop
    }
  }
  private[this] val isDirectiveApplication = directive.isDefined

  private[this] def moveCategory(arg: String) : JsCmd = {
    //parse arg, which have to  be json object with sourceGroupId, destCatId
    try {
      (for {
         JObject(child) <- JsonParser.parse(arg)
         JField("sourceCatId", JString(sourceCatId)) <- child
         JField("destCatId", JString(destCatId)) <- child
       } yield {
         (RuleCategoryId(sourceCatId), RuleCategoryId(destCatId))
       }) match {
        case (sourceCatId, destCatId) :: Nil =>
          (for {
            category <- roRuleCategoryRepository.get(sourceCatId)
            result <- woRuleCategoryRepository.updateAndMove(
                          category
                        , destCatId
                        , ModificationId(uuidGen.newUuid)
                        , CurrentUser.actor
                        , reason = None
                      ).chainError(s"Error while trying to move category with requested id '${sourceCatId}' to category id '${destCatId}'")
            newRoot <- roRuleCategoryRepository.getRootCategory
          } yield {
            (category.id.value, newRoot)
          }).toBox match {
            case Full((id,newRoot)) =>
              refreshTree(Full(newRoot)) & updateComponent() & selectCategory()
            case f:Failure => Alert(f.messageChain + "\nPlease reload the page")
            case Empty => Alert("Error while trying to move category with requested id '%s' to category id '%s'\nPlease reload the page.".format(sourceCatId,destCatId))
          }
        case _ => Alert("Error while trying to move group: bad client parameters")
      }
    } catch {
      case e:Exception => Alert("Error while trying to move group")
    }
  }

  def tree() : NodeSeq = {

    val treeFun = if (isDirectiveApplication) "buildRuleCategoryTreeNoDnD" else "buildRuleCategoryTree"
    <ul>{
      categoryNode(root).toXml}
  </ul> ++
    Script(
      After(TimeSpan(50),
        JsRaw(s"""
          ${treeFun}('#${htmlId_RuleCategoryTree}','${getSelected.value}','${S.contextPath}');
          $$('#${htmlId_RuleCategoryTree}').bind("move_node.jstree", function (e,data) {
            var sourceCatId = data.node.id;
            var destCatId = data.parent;
            if( destCatId ) {
              if( sourceCatId ) {
                var arg = JSON.stringify({ 'sourceCatId' : sourceCatId, 'destCatId' : destCatId });
                ${SHtml.ajaxCall(JsVar("arg"), moveCategory _ )._2.toJsCmd};
              } else {
                alert("Can not move that kind of object");
                $$.jstree.rollback(data.rlbk);
              }
            } else {
              alert("Can not move to something else than a category");
              $$.jstree.rollback(data.rlbk);
            }
          });
          createTooltip();"""
    ) ) )
  }

  private[this] def categoryNode(category : RuleCategory) : JsTreeNode = new JsTreeNode {
    val tooltipId = Helpers.nextFuncName

    override val attrs = ("data-jstree" -> """{ "type" : "category" }""") :: ("id", category.id.value) :: Nil

    override def body = {

      val applyCheckBox = {
        directive match {
          case Some(directiveApplication) =>
            def check(status:Boolean) : JsCmd = {
             directiveApplication.checkCategory(category.id, status) match {
                case DirectiveApplicationResult(rules,completeCategories,indeterminate) =>
                  After(
                      TimeSpan(50)
                    , JsRaw(s"""
                        $$('#${category.id.value + "Checkbox"}').prop("indeterminate",false);
                        ${rules.map(c => s"""$$('#${c.value}Checkbox').prop("checked",${status}); """).mkString("\n")}
                        ${completeCategories.map(c => s"""$$('#${c.value}Checkbox').prop("indeterminate",false); """).mkString("\n")}
                        ${completeCategories.map(c => s"""$$('#${c.value}Checkbox').prop("checked",${status}); """).mkString("\n")}
                        ${indeterminate.map(c => s"""$$('#${c.value}Checkbox').prop("indeterminate",true); """).mkString("\n")}
                      """)
                  )
              } }

            // Create the checkbox with their values, then set the "indeterminate" content
            SHtml.ajaxCheckbox(directiveApplication.isCompletecategory(category.id), check _, ("id",category.id.value + "Checkbox"), ("style","margin : 2px 5px 0px 2px;"))++
            Script(OnLoad(After(
                TimeSpan(400)
              , JsRaw(s"""
                  $$('#${category.id.value + "Checkbox"}').click(function (e) { e.stopPropagation(); })
                  $$('#${category.id.value + "Checkbox"}').prop("indeterminate",${directiveApplication.isIndeterminateCategory(category.id)});"""))))
          case None => NodeSeq.Empty
        }
      }

      val actionButtons = {
        if(!isDirectiveApplication && category.id.value!="rootRuleCategory") {

        <span id={"actions"+category.id.value} class="categoryAction" style="padding-top:1px;padding-left:10px">{
          SHtml.span(NodeSeq.Empty,editPopup(category),("id","edit"+category.id.value),("class","fa fa-pencil"), ("style", "margin-right:10px")) ++
          SHtml.span(NodeSeq.Empty,deletePopup(category), ("id","delete"+category.id.value),("class","fa fa-trash-o"))
        } </span>
        } else {
          NodeSeq.Empty
        }
      }

      val tooltip = {
        if (category.description.size > 0) {
          <div class="tooltipContent" id={tooltipId}>
            <h3>{category.name}</h3>
            <div>{category.description}</div>
          </div>
        } else {
          NodeSeq.Empty
        }
      }
      val xml = {
        <span id={category.id.value+"Name"} tooltipid={tooltipId} title="" class="treeRuleCategoryName tooltipable" >
          {applyCheckBox}{category.name}
        </span> ++
        {actionButtons} ++
        {tooltip}
      }
       SHtml.a(() => {
         selectedCategoryId = category.id
         selectCategory()
       }, xml)
    }

    def childs = category.childs.
                   // Filter empty categories, only if we are on a Directive management page (so you can't apply to empty categories)
                   filter(c => directive.map(_.isEmptyCategory(c.id)).getOrElse(true)).
                   // Sort by name
                   sortBy(_.name.toLowerCase())

    override def children = childs.map(categoryNode(_))

  }
}
