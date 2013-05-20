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
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.web.model.JsTreeNode
import net.liftweb.common.Loggable
import net.liftweb.http.SHtml
import net.liftweb.http.js.JsCmd
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers.{ boolean2, strToSuperArrowAssoc }
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.rudder.domain.policies.Directive
import scala.xml.Text
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.FullActiveTechnique

/**
 *
 * A service that is able to render the node group tree.
 *
 */
object DisplayDirectiveTree extends Loggable {


  /**
   * Display the directive tree, optionaly filtering out
   * some category or group by defining which one to
   * keep. By default, display everything.
   */
  def displayTree(
      directiveLib    : FullActiveTechniqueCategory
    , onClickCategory : Option[FullActiveTechniqueCategory => JsCmd]
    , onClickTechnique: Option[(FullActiveTechniqueCategory, FullActiveTechnique) => JsCmd]
    , onClickDirective: Option[(FullActiveTechniqueCategory, FullActiveTechnique, Directive) => JsCmd]
    , keepCategory    : FullActiveTechniqueCategory => Boolean = _ => true
    , keepTechnique   : FullActiveTechnique => Boolean = _ => true
    , keepDirective   : Directive => Boolean = _ => true
  ) : NodeSeq =  {


    def displayCategory(
        category: FullActiveTechniqueCategory
      , nodeId  : String
    ) : JsTreeNode = new JsTreeNode {

      private[this] val localOnClickTechnique = onClickTechnique.map( _.curried(category) )

      private[this] val localOnClickDirective = onClickDirective.map( _.curried(category) )

      private[this] val tooltipId = Helpers.nextFuncName
      private[this] val xml = (
        <span class="treeActiveTechniqueCategoryName tooltipable" tooltipid={tooltipId} title="">
          {Text(category.name)}
        </span>
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
        category.subCategories
          .sortBy( _.name )
          .zipWithIndex
          .collect{ case (node, i) if(keepCategory(node)) =>
              displayCategory(node, nodeId + "_" + i)
          }
        ++
        category.activeTechniques
          .sortBy( _.techniqueName.value )
          .collect { case at if(keepTechnique(at)) =>
            displayActiveTechnique(at, localOnClickTechnique, localOnClickDirective)
          }
      )


      override val attrs = ( "rel" -> "category") :: ( "id" -> nodeId) :: Nil
    }


   /////////////////////////////////////////////////////////////////////////////////////////////

    def displayActiveTechnique(
        activeTechnique: FullActiveTechnique
      , onClickTechnique: Option[FullActiveTechnique => JsCmd]
      , onClickDirective: Option[FullActiveTechnique => Directive => JsCmd]
    ) : JsTreeNode = new JsTreeNode {

      private[this] val localOnClickDirective = onClickDirective.map( f => f(activeTechnique) )

      override val attrs = (
        ( "rel" -> "template") :: Nil :::
        ( if(!activeTechnique.isEnabled)
            ("class" -> "disableTreeNode") :: Nil
          else Nil
        )
      )

      override def children = {
        activeTechnique.directives
          .sortBy( _.name )
          .collect { case x if(keepDirective(x)) => displayDirective(x, localOnClickDirective) }
      }

      override def body = {
        val tooltipId = Helpers.nextFuncName

        //display information (name, etc) relative to last technique version

        val xml = activeTechnique.newestAvailableTechnique match {
          case Some(technique) =>
            <span class="treeActiveTechniqueName tooltipable" tooltipid={tooltipId} title="">
              {technique.name}
            </span>
            <div class="tooltipContent" id={tooltipId}>
              <h3>{technique.name}</h3>
              <div>{technique.description}</div>
            </div>
          case None => <span class="error">No technique available for that active technique</span>
        }

        onClickTechnique match {
          case None                           => <a style="cursor:default">{xml}</a>
          case _ if(activeTechnique.isSystem) => <a style="cursor:default">{xml}</a>
          case Some(f)                        => SHtml.a(() => f(activeTechnique), xml)
        }
      }
    }

    def displayDirective(
        directive  : Directive
      , onClickDirective: Option[Directive => JsCmd]
    ) : JsTreeNode = new JsTreeNode {

      override def children = Nil

      override val attrs = (
                  ( "rel" -> "directive") ::
                  ( "id" -> ("jsTree-" + directive.id.value)) ::
                  ( if(!directive.isEnabled)
                      ("class" -> "disableTreeNode") :: Nil
                    else Nil
                  )

      )
      override def body = {
        val tooltipId = Helpers.nextFuncName

        val xml  = {
                    <span class="treeDirective tooltipable" tooltipid={tooltipId} title="">
                      {directive.name}
                    </span>
                    <div class="tooltipContent" id={tooltipId}>
                      <h3>{directive.name}</h3>
                      <div>{directive.shortDescription}</div>
                    </div>
        }

        onClickDirective match {
          case None                     => <a style="cursor:default">{xml}</a>
          case _ if(directive.isSystem) => <a style="cursor:default">{xml}</a>
          case Some(f)                  => SHtml.a(() => f(directive), xml)
        }
      }
    }

    displayCategory(directiveLib, "jstn_0").toXml

  }

}
