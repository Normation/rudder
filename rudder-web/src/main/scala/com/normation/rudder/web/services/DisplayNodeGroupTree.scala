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
import net.liftweb.http.js.JsCmd
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers.{ boolean2, strToSuperArrowAssoc }

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

      override def children = Nil

      override val attrs = (
          ( "rel" -> { if (targetInfo.isSystem) "system_target" else "group"} ) ::
          (targetInfo.target match {
            case g:FullGroupTarget =>
              ( "groupId" -> g.nodeGroup.id.value ) ::
              ( "id" -> ("jsTree-" + g.nodeGroup.id.value) ) ::
               Nil
            case o:FullOtherTarget =>
              ( "id" -> ("jsTree-" + o.target.target) ) ::
               Nil
          })
      )
      override def body = {
        val tooltipId = Helpers.nextFuncName

        val xml  = {
          <span class="treeGroupName tooltipable" tooltipid={tooltipId} title="">
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
          </div>
        }

        onClickNode match {
          case None                      => <a style="cursor:default">{xml}</a>
          case _ if(targetInfo.isSystem) => <a style="cursor:default">{xml}</a>
          case Some(f)                   => SHtml.a(() => f(targetInfo), xml)
        }
      }

    }

    displayCategory(groupLib).toXml
  }

  //build the tree category, filtering only category with groups
  def buildTreeKeepingGroupWithNode(
      groupLib       : FullNodeGroupCategory
    , nodeId         : NodeId
    , onClickCategory: Option[FullNodeGroupCategory => JsCmd] = None
    , onClickTarget  : Option[(FullNodeGroupCategory, FullRuleTargetInfo) => JsCmd] = None
  ) : NodeSeq = {

    displayTree(
        groupLib
      , onClickCategory
      , onClickTarget
      , keepCategory   = (cat => cat.allGroups.values.exists( _.nodeGroup.serverList.contains(nodeId)))
      , keepTargetInfo = (ti => ti match {
          case FullRuleTargetInfo(FullGroupTarget(_, g), _, _, _, _) => g.serverList.contains(nodeId)
          case _ => false
        })
    )
  }

}
