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

package com.normation.rudder.web.services

import scala.xml.NodeSeq
import scala.xml.NodeSeq.seqToNodeSeq
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.FullGroupTarget
import com.normation.rudder.domain.policies.FullOtherTarget
import com.normation.rudder.domain.policies.FullRuleTargetInfo
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.web.model.JsTreeNode
import net.liftweb.common.Loggable
import net.liftweb.http.SHtml
import net.liftweb.http.js._
import net.liftweb.http.js.JsCmds._
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers.{ boolean2, strToSuperArrowAssoc }
import net.liftweb.http.js.JE.JsRaw
import com.normation.rudder.domain.policies.FullRuleTarget
import com.normation.rudder.domain.policies.RuleTarget

/**
 *
 * A service that is able to render the node group tree.
 *
 */
object DisplayNodeGroupTree extends Loggable {


  /**
   * Display the group tree, optionnaly filtering out
   * some category or group by defining which one to
   * keep. By default, display everything.
   */
  def displayTree(
      groupLib       : FullNodeGroupCategory
    , onClickCategory: Option[FullNodeGroupCategory => JsCmd]
    , onClickTarget  : Option[(FullNodeGroupCategory, FullRuleTargetInfo) => JsCmd]
    , targetActions  : Map[String,(FullRuleTargetInfo) => JsCmd]
    , included       : Set[RuleTarget] = Set()
    , excluded       : Set[RuleTarget] = Set()
    , keepCategory   : FullNodeGroupCategory => Boolean = _ => true
    , keepTargetInfo : FullRuleTargetInfo => Boolean = _ => true
  ) : NodeSeq =  {


    def displayCategory(
        category: FullNodeGroupCategory
    ) : JsTreeNode = new JsTreeNode {

      private[this] val localOnClickTarget = onClickTarget.map( _.curried(category) )

      private[this] val tooltipId = Helpers.nextFuncName

      private[this] val xml = (
        <span class="treeGroupCategoryName tooltipable" tooltipid={tooltipId} title="">{category.name}</span>
        <div class="tooltipContent" id={tooltipId}>
          <h3>{category.name}</h3>
          <div>{category.description}</div>
        </div>
      )

      override def body = onClickCategory match {
        case None    => <a href="#">{xml}</a>
        case Some(f) => SHtml.a({() => f(category)}, xml)
      }

      override def children = (
           category.subCategories.collect { case x if(keepCategory(x)) => displayCategory(x) }
        ++ category.targetInfos.collect { case x if(keepTargetInfo(x)) => displayFullRuleTargetInfo(x, localOnClickTarget) }
      )


      val rel = {
        if(category.id == groupLib.id) "root-category"
        else if (category.isSystem)    "system_category"
        else                           "category"
      }

      override val attrs =
        ( "rel" -> rel ) ::
        ( "catId" -> category.id.value ) ::
        ( "class" -> "" ) ::
        Nil
    }


   /////////////////////////////////////////////////////////////////////////////////////////////

    def displayFullRuleTargetInfo(
        targetInfo    : FullRuleTargetInfo
      , onClickNode   : Option[FullRuleTargetInfo => JsCmd]
    ) : JsTreeNode = new JsTreeNode {


      val groupId = {
        targetInfo.target match {
          case g:FullGroupTarget =>
            g.nodeGroup.id.value
          case o:FullRuleTarget =>
            o.target.target
        }
      }

      val htmlId = s"jstree-${targetInfo.target.target.target}"
      val jsId = htmlId.replace(":", "\\\\:")

        val jsInitFunction = Script(JsRaw (s"""
           $$('#${jsId}').mouseover( function(e) {
             e.stopPropagation();
             $$('#${jsId} .targetActions').show();
             $$('#${jsId} a:first').addClass("treeOver jstree-hovered");
           } );


           $$('#${jsId}').hover( function(e) {
             $$('.targetActions').hide();
             $$('.treeOver').removeClass("treeOver jstree-hovered");
             $$('#${jsId} .targetActions').show();
             $$('#${jsId} a:first').addClass("treeOver jstree-hovered");
           }, function(e) {
             $$('.treeOver').removeClass("treeOver jstree-hovered");
             $$('#${jsId} .targetActions').hide();
           } );

           $$('#${jsId} .targetActions').click( function(e) {
             e.stopPropagation();
           } );
           """))

      override def children = Nil

      val classes = {
        val includedClass = if (included.contains(targetInfo.target.target)) {"targetIncluded"} else ""
        val excludedClass = if (excluded.contains(targetInfo.target.target)) {"targetExcluded"} else {""}
        s"$includedClass $excludedClass"
      }

      override val attrs = {
        ( "rel"     -> { if (targetInfo.isSystem) "system_target" else "group"} ) ::
        ( "groupId" -> groupId ) ::
        ( "id"      -> htmlId ) ::
        ( "class"   -> classes) ::
        Nil
      }

      override def body = {
        val tooltipId = Helpers.nextFuncName
        val actionButtons = {
          if (!targetActions.isEmpty) {
            <span class="targetActions">
              { (targetActions get ("include") match {
                case Some (include) =>

                  val tooltipId = Helpers.nextFuncName
                  <img src="/images/ic_add.png" class="tooltipable targetAction" tooltipid={tooltipId} title="" onclick={include(targetInfo).toJsCmd}/>
                  <div class="tooltipContent" id={tooltipId}><div>Include Nodes from this group.</div></div>
                case None => NodeSeq.Empty
                }) ++
                (targetActions get ("exclude") match {
                  case Some (exclude) =>

                  val tooltipId = Helpers.nextFuncName
                  <img src="/images/ic_remove.png" class="tooltipable targetAction" tooltipid={tooltipId} title="" onclick={exclude(targetInfo).toJsCmd}/>
                  <div class="tooltipContent" id={tooltipId}><div>Exclude Nodes from this group.</div></div>
                  case None => NodeSeq.Empty
                })
              }
            </span>
          } else {
              NodeSeq.Empty
          }
        }
        val xml  = {
          <span class="treeGroupName tooltipable" tooltipid={tooltipId} title="" style="float:left">
            {targetInfo.name}
            { targetInfo.target match {
                case g:FullGroupTarget => s": ${g.nodeGroup.isDynamic ? "dynamic" | "static" }"
                case _ => ""
            } }

            { if (targetInfo.isSystem) <span class="greyscala">(System)</span>}
          </span>

          <div class="tooltipContent" id={tooltipId}>
            <h3>{targetInfo.name}</h3>
            <div>{targetInfo.description}</div>
          </div> ++
        actionButtons ++ jsInitFunction
        }

        onClickNode match {
          case None                      => <a style="cursor:default">{xml}</a>
          // only disable click when actions are empty so we can act on them
          case _ if(targetInfo.isSystem && targetActions.isEmpty) => <a style="cursor:default">{xml}</a>
          case Some(f)                   => <a onclick={f(targetInfo).toJsCmd}>{xml}</a>
        }
      }

    }

    displayCategory(groupLib).toXml ++ Script(JsRaw("$('.targetActions').hide();"))
  }

  //build the tree category, filtering only category with groups
  def buildTreeKeepingGroupWithNode(
      groupLib       : FullNodeGroupCategory
    , nodeId         : NodeId
    , onClickCategory: Option[FullNodeGroupCategory => JsCmd] = None
    , onClickTarget  : Option[(FullNodeGroupCategory, FullRuleTargetInfo) => JsCmd] = None
    , targetActions  : Map[String,(FullRuleTargetInfo) => JsCmd] = Map()
  ) : NodeSeq = {

    displayTree(
        groupLib
      , onClickCategory
      , onClickTarget
      , targetActions
      , keepCategory   = (cat => cat.allGroups.values.exists( _.nodeGroup.serverList.contains(nodeId)))
      , keepTargetInfo = (ti => ti match {
          case FullRuleTargetInfo(FullGroupTarget(_, g), _, _, _, _) => g.serverList.contains(nodeId)
          case _ => false
        })
    )
  }

}
