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
import com.normation.eventlog.ModificationId
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.rule.category.*
import com.normation.rudder.web.model.JsTreeNode
import net.liftweb.common.*
import net.liftweb.http.S
import net.liftweb.http.SecureDispatchSnippet
import net.liftweb.http.SHtml
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.json.*
import net.liftweb.util.Helpers.*
import org.apache.commons.text.StringEscapeUtils
import scala.xml.*

/**
 * A component to display a tree based on a
 * Technique.
 *
 * The tree show all parent of the Technique
 * and dependent Rules.
 *
 */
class RuleCategoryTree(
    htmlId_RuleCategoryTree: String,
    rootCategory:            RuleCategory,
    directive:               Option[DirectiveApplicationManagement],
    check:                   () => JsCmd,
    editPopup:               RuleCategory => JsCmd,
    deletePopup:             RuleCategory => JsCmd,
    updateComponent:         () => JsCmd
) extends SecureDispatchSnippet with Loggable {

  private val roRuleCategoryRepository = RudderConfig.roRuleCategoryRepository
  private val woRuleCategoryRepository = RudderConfig.woRuleCategoryRepository
  private val ruleCategoryService      = RudderConfig.ruleCategoryService
  private val uuidGen                  = RudderConfig.stringUuidGenerator
  private var root                     = rootCategory

  private var selectedCategoryId = rootCategory.id

  def getSelected: RuleCategoryId = {
    if (root.contains(selectedCategoryId)) {
      selectedCategoryId
    } else {
      resetSelected
      rootCategory.id
    }
  }

  def getRoot:       RuleCategory = {
    root
  }
  def resetSelected: Unit         = selectedCategoryId = rootCategory.id

  def secureDispatch: QueryContext ?=> PartialFunction[String, NodeSeq => NodeSeq] = { case "tree" => { _ => tree() } }

  def refreshTree(newRoot: Box[RuleCategory])(using qc: QueryContext): JsCmd = {
    val html = newRoot match {
      case Full(newRoot) =>
        root = newRoot
        <div id={htmlId_RuleCategoryTree}> {tree()}</div>
      case eb: EmptyBox =>
        val fail = eb ?~! "Could not get root category"
        val msg  = s"An error occured while refreshing Rule categoriy tree , cause is ${fail.messageChain}"
        logger.error(msg)
        <div style="padding:10px;">
          <div class="error">{msg}</div>
        </div>
    }
    Replace(htmlId_RuleCategoryTree, html) &
    selectCategory() &
    OnLoad(After(TimeSpan(50), JsRaw("""initBsTooltips();"""))) // JsRaw ok, const
  }

  // Update selected category and select it in the tree (trigger its function)
  def updateSelectedCategory(newSelection: RuleCategoryId): JsCmd = {
    selectedCategoryId = newSelection
    // Select the new node, boolean flag to true to respect select limitation
    JsRaw(s"""
        $$("#${htmlId_RuleCategoryTree}").jstree("select_node", "#${StringEscapeUtils.escapeEcmaScript(
        newSelection.value
      )}", true);
    """) &
    selectCategory()
  }

  // Perform category selection, filter in the dataTable and display the name of the category
  def selectCategory(): JsCmd = {
    (for {
      rootCategory <- roRuleCategoryRepository.getRootCategory().toBox
      fqdn         <- ruleCategoryService.bothFqdn(rootCategory, selectedCategoryId, rootInCaps = true)
    } yield {
      fqdn
    }) match {
      case Full((long, short)) =>
        val escaped = StringEscapeUtils.escapeEcmaScript(short)
        OnLoad(JsRaw(s"""
            filter='${escaped}';
            filterTableInclude('#grid_rules_grid_zone',filter,include);
        """)) & // JsRaw ok, escaped
        SetHtml("categoryDisplay", Text(long)) &
        check()
      case e: EmptyBox => // Display an error, for now, nothing
        Noop
    }
  }
  private val isDirectiveApplication = directive.isDefined

  private def moveCategory(arg: String)(using qc: QueryContext): JsCmd = {
    // parse arg, which have to  be json object with sourceGroupId, destCatId
    try {
      (for {
        case JObject(child) <- JsonParser.parse(arg)
        case JField("sourceCatId", JString(sourceCatId)) <- child
        case JField("destCatId", JString(destCatId)) <- child
      } yield {
        (RuleCategoryId(sourceCatId), RuleCategoryId(destCatId))
      }) match {
        case (sourceCatId, destCatId) :: Nil =>
          (for {
            category <- roRuleCategoryRepository.get(sourceCatId)
            result   <-
              woRuleCategoryRepository
                .updateAndMove(
                  category,
                  destCatId,
                  ModificationId(uuidGen.newUuid),
                  qc.actor,
                  reason = None
                )
                .chainError(
                  s"Error while trying to move category with requested id '${sourceCatId.value}' to category id '${destCatId.value}'"
                )
            newRoot  <- roRuleCategoryRepository.getRootCategory()
          } yield {
            (category.id.value, newRoot)
          }).toBox match {
            case Full((id, newRoot)) =>
              refreshTree(Full(newRoot)) & updateComponent() & selectCategory()
            case f: Failure => Alert(f.messageChain + "\nPlease reload the page")
            case Empty =>
              Alert(
                "Error while trying to move category with requested id '%s' to category id '%s'\nPlease reload the page.".format(
                  sourceCatId,
                  destCatId
                )
              )
          }
        case _                               => Alert("Error while trying to move group: bad client parameters")
      }
    } catch {
      case e: Exception => Alert("Error while trying to move group")
    }
  }

  def tree()(using qc: QueryContext): NodeSeq = {

    val treeFun = if (isDirectiveApplication) "buildRuleCategoryTreeNoDnD" else "buildRuleCategoryTree"
    <ul>{
      categoryNode(root).toXml
    }
  </ul> ++
    Script(
      After(
        TimeSpan(50),
        JsRaw(s"""
          ${treeFun}('#${htmlId_RuleCategoryTree}','${StringEscapeUtils.escapeEcmaScript(getSelected.value)}','${S.contextPath}');
          $$('#${htmlId_RuleCategoryTree}').bind("move_node.jstree", function (e,data) {
            var sourceCatId = data.node.id;
            var destCatId = data.parent;
            if( destCatId ) {
              if( sourceCatId ) {
                var arg = JSON.stringify({ 'sourceCatId' : sourceCatId, 'destCatId' : destCatId });
                ${SHtml.ajaxCall(JsVar("arg"), moveCategory)._2.toJsCmd};
              } else {
                alert("Can not move that kind of object");
                $$.jstree.rollback(data.rlbk);
              }
            } else {
              alert("Can not move to something else than a category");
              $$.jstree.rollback(data.rlbk);
            }
          });
          initBsTooltips();""")
      )
    )
  }

  // escape Rule id to make it a checkbox id
  def toCheckboxId(id: RuleCategoryId): String = {
    StringEscapeUtils.escapeEcmaScript(id.value) + "Checkbox"
  }

  private def categoryNode(category: RuleCategory): JsTreeNode = new JsTreeNode {

    override val attrs = ("data-jstree" -> """{ "type" : "category" }""") :: ("id", category.id.value) :: Nil

    override def body: NodeSeq = {

      val applyCheckBox = {
        directive match {
          case Some(directiveApplication) =>
            def check(status: Boolean): JsCmd = {
              directiveApplication.checkCategory(category.id, status) match {
                case DirectiveApplicationResult(rules, completeCategories, indeterminate) =>
                  After(
                    TimeSpan(50),
                    JsRaw(s"""
                        $$('#${toCheckboxId(category.id)}').prop("indeterminate",false);
                        ${rules
                        .map(c =>
                          s"""$$('#${StringEscapeUtils.escapeEcmaScript(c.serialize)}Checkbox').prop("checked",${status}); """
                        )
                        .mkString("\n")}
                        ${completeCategories
                        .map(c => s"""$$('#${toCheckboxId(c)}').prop("indeterminate",false); """)
                        .mkString("\n")}
                        ${completeCategories
                        .map(c => s"""$$('#${toCheckboxId(c)}').prop("checked",${status}); """)
                        .mkString("\n")}
                        ${indeterminate.map(c => s"""$$('#${toCheckboxId(c)}').prop("indeterminate",true); """).mkString("\n")}
                      """)
                  )
              }
            }

            // Create the checkbox with their values, then set the "indeterminate" content
            SHtml.ajaxCheckbox(
              directiveApplication.isCompletecategory(category.id),
              check,
              ("id", toCheckboxId(category.id)),
              ("style", "margin : 2px 5px 0px 2px;")
            ) ++
            Script(
              OnLoad(
                After(
                  TimeSpan(400),
                  JsRaw(s"""
                  $$('#${toCheckboxId(category.id)}').click(function (e) { e.stopPropagation(); })
                  $$('#${toCheckboxId(category.id)}').prop("indeterminate",${directiveApplication.isIndeterminateCategory(
                      category.id
                    )});""")
                )
              )
            )
          case None                       => NodeSeq.Empty
        }
      }

      val actionButtons = {
        if (!isDirectiveApplication && category.id.value != "rootRuleCategory") {

          <span id={"actions" + category.id.value} class="categoryAction" style="padding-top:1px;padding-left:10px">{
            SHtml.span(
              NodeSeq.Empty,
              editPopup(category),
              ("id", "edit" + category.id.value),
              ("class", "fa fa-pencil"),
              ("style", "margin-right:10px")
            ) ++
            SHtml.span(NodeSeq.Empty, deletePopup(category), ("id", "delete" + category.id.value), ("class", "fa fa-trash-o"))
          } </span>
        } else {
          NodeSeq.Empty
        }
      }

      val xml = {
        if (category.description.size > 0) {
          val tooltipContent = s"<div>\n<h3>${category.name}</h3>\n<div>${category.description}</div>\n</div>"
          <span id={category.id.value + "Name"} title={tooltipContent} class="treeRuleCategoryName">
            {applyCheckBox}{category.name}
          </span> ++ { actionButtons }
        } else {
          <span id={category.id.value + "Name"} class="treeRuleCategoryName" >
            {applyCheckBox}{category.name}
          </span> ++ { actionButtons }
        }
      }
      SHtml.a(
        () => {
          selectedCategoryId = category.id
          selectCategory()
        },
        xml
      )
    }

    def childs = category.childs
      .
        // Filter empty categories, only if we are on a Directive management page (so you can't apply to empty categories)
      filter(c => directive.map(_.isEmptyCategory(c.id)).getOrElse(true))
      .
      // Sort by name
      sortBy(_.name.toLowerCase())

    override def children = childs.map(categoryNode(_))

  }
}
