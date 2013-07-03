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

package com.normation.rudder.web.components

import com.normation.rudder.domain.policies._
import com.normation.cfclerk.domain.{
  TechniqueId,Technique,
  TechniqueCategoryId, TechniqueCategory
}
import com.normation.cfclerk.services.TechniqueRepository
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

/**
 * A component to display a tree based on a
 * Technique.
 *
 * The tree show all parent of the Technique
 * and dependent Rules.
 *
 */
class TechniqueTree(
  htmlId_activeTechniquesTree:String,
  techniqueId:ActiveTechniqueId,
  switchStatusFilter : ModificationStatus
) extends DispatchSnippet with Loggable {

  //find Technique
  val techniqueRepository       = RudderConfig.techniqueRepository
  val activeTechniqueRepository = RudderConfig.roDirectiveRepository
  val dependencyService         = RudderConfig.dependencyAndDeletionService
  val ruleRepository            = RudderConfig.roRuleRepository
  val getGrouLib                = RudderConfig.roNodeGroupRepository.getFullGroupLibrary _

  def dispatch = {
    case "tree" => { _ => tree }
  }


  def tree() : NodeSeq = {

    (for {
      parents <- activeTechniqueRepository.activeTechniqueBreadCrump(techniqueId)
      (rootCat,subCats) <- parents.reverse match {
        case h::tail => Full((h, tail))
        case _ => Failure("No parent category found for template %s, abort".format(techniqueId))
      }
      activeTechnique <- activeTechniqueRepository.getActiveTechnique(techniqueId)
      technique <- techniqueRepository.getLastTechniqueByName(activeTechnique.techniqueName)
      dep <- dependencyService.techniqueDependencies(techniqueId,getGrouLib(),switchStatusFilter)
    } yield {
      categoryNode(rootCat,subCats, dep, technique, activeTechnique)
    }) match {
      case Full(treeNode) => {<ul>{treeNode.toXml}</ul>} ++ Script(OnLoad(JsRaw(
          """buildTechniqueDependencyTree('#%s'); createTooltip();""".format(htmlId_activeTechniquesTree)
      )))
      case e:EmptyBox =>
        val msg = "Can not build tree of dependencies for Technique %s".format(techniqueId)
        logger.error(msg,e)
        (new JsTreeNode {
          override def body = <span class="error">Can not find dependencies. <span class="errorDetails">{(e ?~! msg).messageChain}</span></span>
          override def children = Nil
        }).toXml
    }
  }


  private[this] def categoryNode(
      category:ActiveTechniqueCategory,
      subCat: List[ActiveTechniqueCategory],
      dep: TechniqueDependencies,
      technique: Technique,
      activeTechnique:ActiveTechnique
  ) : JsTreeNode = new JsTreeNode {
    override val attrs = ( "rel" -> "category" ) :: Nil
    override def body = { <a href="#"><span class="treeActiveTechniqueCategoryName tooltipable" tooltipid={category.id.value.replaceAll("/", "")}  title={category.description}>{category.name}</span></a><div class="tooltipContent" id={category.id.value.replaceAll("/", "")}><h3>{category.name}</h3><div>{category.description}</div></div> }
    override def children = subCat match {
      case Nil => techniqueNode(dep, technique, activeTechnique) :: Nil
      case h::tail => categoryNode(h, tail, dep, technique,activeTechnique) :: Nil
    }
  }

  private[this] def techniqueNode(dep: TechniqueDependencies, technique:Technique, activeTechnique:ActiveTechnique) : JsTreeNode = new JsTreeNode {
    override def body = {  <a href="#"><span class="treeTechniqueName tooltipable" tooltipid={activeTechnique.techniqueName.value} title={technique.description}>{technique.name}</span></a><div class="tooltipContent" id={activeTechnique.techniqueName.value}><h3>{technique.name}</h3><div>{technique.description}</div></div> }
    override def children = dep.directives.keySet.toList.map {  directiveId => directiveNode(dep,directiveId) }
    override val attrs = ( "rel" -> "template") :: Nil ::: (if(!activeTechnique.isEnabled) ("class" -> "disableTreeNode") :: Nil else Nil )
  }

  private[this] def directiveNode(dep: TechniqueDependencies, id:DirectiveId) : JsTreeNode = {
    val (directive, ruleIds) = dep.directives(id)

    new JsTreeNode {
      override val attrs = ( "rel" -> "directive") :: Nil ::: (if(!directive.isEnabled) ("class" -> "disableTreeNode") :: Nil else Nil )
      override def body = {  <a href="#"><span class="treeDirective tooltipable" tooltipid={directive.id.value} title={directive.shortDescription}>{directive.name}</span></a><div class="tooltipContent" id={directive.id.value}><h3>{directive.name}</h3><div>{directive.shortDescription}</div></div> }
      override def children = ruleIds.map ( k => ruleNode(k) ).toList
    }
  }

  private[this] def ruleNode(id:RuleId) : JsTreeNode = {
    ruleRepository.get(id) match {
      case Full(rule) => new JsTreeNode {
        override val attrs = ( "rel" -> "rule") :: Nil ::: (if(!rule.isEnabled) ("class" -> "disableTreeNode") :: Nil else Nil )
        override def body = { <a href="#"><span class="treeRuleName tooltipable" tooltipid={id.value} title={rule.shortDescription}>{rule.name}</span></a><div class="tooltipContent" id={id.value}><h3>{rule.name}</h3><div>{rule.shortDescription}</div></div> }
        override def children = Nil
      }
      case e:EmptyBox =>
        val msg = "Can not build tree of dependencies for Technique %s".format(techniqueId)
        logger.error(msg, e)

        new JsTreeNode {
          override def body = <span class="error">Can not find Rule details for id {id}. <span class="errorDetails">{(e?~!msg)}</span></span>
          override def children = Nil
        }
    }
  }

}