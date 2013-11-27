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
import com.normation.rudder.repository._
import com.normation.rudder.services.policies._
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.rule.category._
import net.liftweb.json._
import com.normation.eventlog.ModificationId
import com.normation.rudder.web.model.CurrentUser

/**
 * A component to display a tree based on a
 * Technique.
 *
 * The tree show all parent of the Technique
 * and dependent Rules.
 *
 */
class RuleCategoryTree(
  htmlId_RuleCategoryTree:String
) extends DispatchSnippet with Loggable {

  private[this] val roRuleCategoryRepository   = RudderConfig.roRuleCategoryRepository
  private[this] val woRuleCategoryRepository = RudderConfig.woRuleCategoryRepository
  private[this] val ruleCategoryService      = RudderConfig.ruleCategoryService
  private[this] val uuidGen              = RudderConfig.stringUuidGenerator
  def dispatch = {
    case "tree" => { _ => tree }
  }

  private[this] def refreshTree() : JsCmd =  {
    SetHtml(htmlId_RuleCategoryTree, tree()) &
    OnLoad(After(TimeSpan(50), JsRaw("""createTooltip();correctButtons();""")))
  }

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
            category <- roRuleCategoryRepository.get(sourceCatId)//Box(lib.allCategories.get(NodeGroupCategoryId(sourceCatId))) ?~! "Error while trying to find category with requested id %s".format(sourceCatId)

            result <- woRuleCategoryRepository.updateAndMove(
                          category
                        , destCatId
                        , ModificationId(uuidGen.newUuid)
                        , CurrentUser.getActor
                        , reason = None
                      ) ?~! s"Error while trying to move category with requested id '${sourceCatId}' to category id '${destCatId}'"
          } yield {
            (category.id.value, result)
          }) match {
            case Full((id,res)) =>
              refreshTree
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

    (for {
      root <-  roRuleCategoryRepository.getRootCategory
    } yield {
      categoryNode(root)
    }) match {

      case Full(treeNode) =>
        {<ul>{treeNode.toXml}</ul>} ++ Script(OnLoad(JsRaw(
          s"""buildRuleCategoryTree('#${htmlId_RuleCategoryTree}','${roRuleCategoryRepository.getRootCategory.map(_.id.value).getOrElse("")}','${S.contextPath}');
        $$('#${htmlId_RuleCategoryTree}').bind("move_node.jstree", function (e,data) {
          var sourceCatId = $$(data.rslt.o).attr("id");
          var destCatId = $$(data.rslt.np).attr("id");
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
      )))
      case e:EmptyBox =>
        val msg = "Can not build tree of Rule categories"
        logger.error(msg,e)
        (new JsTreeNode {
          override def body = <span class="error">Can not find dependencies. <span class="errorDetails">{(e ?~! msg).messageChain}</span></span>
          override def children = Nil
        }).toXml
    }
  }


  private[this] def categoryNode(category : RuleCategory) : JsTreeNode = new JsTreeNode {
    override val attrs = ( "rel" -> "category" ) :: ("id", category.id.value) :: Nil
    override def body = {
      def img(source:String,alt:String) = <img src={"/images/"+source} alt={alt} height="14" width="14" class="iconscala" style=" margin: 0 5px 0 0;float:none;" />
      val tooltipId = Helpers.nextFuncName
      val xml = {
           <span class="treeRuleCategoryName tooltipable" tooltipid={tooltipId} title="">
             {category.name}

             <span id={"actions"+category.id.value} class="categoryAction">
               {if(category.id.value!="rootRuleCategory")  SHtml.span(img("icPen.png","Edit"),Noop, ("style","margin:0 5px;max-height:20px; max-width:20px;")) ++
                 SHtml.span(img("icfail.png","Delete"),Noop, ("style","margin:0 5px;max-height:20px; max-width:20px;"))
                 else NodeSeq.Empty}
             </span>
           </span>
         <div class="tooltipContent" id={tooltipId}>
           <h3>{category.name}</h3>
           <div>{category.description}</div>
         </div> ++ Script{JsRaw {s"""
           $$('#${"actions"+category.id.value}').hide();

           $$('#${category.id.value}').mouseover( function(e) {
           e.stopPropagation();

           $$('#${"actions"+category.id.value}').show();
           $$('#${category.id.value} a:first').addClass("treeOver jstree-hovered");
         });


           $$('#${category.id.value}').hover( function(e) {
           $$('.categoryAction').hide();
           $$('.treeOver').removeClass("treeOver jstree-hovered");
           $$('#${"actions"+category.id.value}').show();
           $$('#${category.id.value} a:first').addClass("treeOver jstree-hovered");
           }, function(e) {
           $$('.treeOver').removeClass("treeOver jstree-hovered");
           $$('#${"actions"+category.id.value}').hide();
           } );
           """
         }}
      }
       SHtml.a(() => ruleCategoryService.shortFqdn(category.id) match {
         case Full(fqdn) =>
           val escaped = Utility.escape(fqdn)
           JsRaw(s"""
               filter='${escaped}';
               filterTableInclude('#grid_rules_grid_zone',filter,include);
           """) & SetHtml("categoryDisplay",Text(fqdn))
         case e: EmptyBox => //Display an error, for now, nothing
           Noop
       }, xml)
    }
    override def children = category.childs.map(categoryNode(_))


  }


}