/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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
import com.normation.box._
import com.normation.cfclerk.domain.Technique
import com.normation.errors
import com.normation.rudder.domain.policies._
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.services.policies._
import com.normation.rudder.web.model.JsTreeNode
import net.liftweb.common._
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmds._
import scala.xml._

/**
 * A component to display a tree based on a
 * Technique.
 *
 * The tree show all parent of the Technique
 * and dependent Rules.
 *
 */
class TechniqueTree(
    htmlId_activeTechniquesTree: String,
    techniqueId:                 ActiveTechniqueId,
    switchStatusFilter:          ModificationStatus
) extends DispatchSnippet with Loggable {

  // find Technique
  val techniqueRepository       = RudderConfig.techniqueRepository
  val activeTechniqueRepository = RudderConfig.roDirectiveRepository
  val dependencyService         = RudderConfig.dependencyAndDeletionService
  val ruleRepository            = RudderConfig.roRuleRepository
  val getGrouLib: () => errors.IOResult[FullNodeGroupCategory] = RudderConfig.roNodeGroupRepository.getFullGroupLibrary _

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = { case "tree" => { _ => tree() } }

  def tree(): NodeSeq = {

    (for {
      parents            <- activeTechniqueRepository.activeTechniqueBreadCrump(techniqueId).toBox
      (rootCat, subCats) <- parents.reverse match {
                              case h :: tail => Full((h, tail))
                              case _         => Failure("No parent category found for template %s, abort".format(techniqueId))
                            }
      activeTechnique    <- activeTechniqueRepository.getActiveTechniqueByActiveTechnique(techniqueId).toBox.flatMap(Box(_))
      technique          <- techniqueRepository.getLastTechniqueByName(activeTechnique.techniqueName)
      dep                <- dependencyService.techniqueDependencies(techniqueId, getGrouLib().toBox, switchStatusFilter)
    } yield {
      categoryNode(rootCat, subCats, dep, technique, activeTechnique)
    }) match {
      case Full(treeNode) =>
        { <ul>{treeNode.toXml}</ul> } ++ Script(
          OnLoad(
            JsRaw(
              """buildTechniqueDependencyTree('#%s'); initBsTooltips();""".format(htmlId_activeTechniquesTree)
            )
          )
        )
      case e: EmptyBox =>
        val msg = "Can not build tree of dependencies for Technique %s".format(techniqueId)
        logger.error(msg, e)
        (new JsTreeNode {
          override def body     = <span class="error">Can not find dependencies. <span class="errorDetails">{
            (e ?~! msg).messageChain
          }</span></span>
          override def children = Nil
        }).toXml
    }
  }

  private[this] def categoryNode(
      category:        ActiveTechniqueCategory,
      subCat:          List[ActiveTechniqueCategory],
      dep:             TechniqueDependencies,
      technique:       Technique,
      activeTechnique: ActiveTechnique
  ): JsTreeNode = new JsTreeNode {
    override val attrs    = ("data-jstree" -> """{ "type" : "category" }""") :: Nil
    val tooltipContent    = s"<h3>${category.name}</h3>\n<div>${category.description}</div>"
    override def body     = {
      <a href="#"><span class="treeActiveTechniqueCategoryName" data-bs-toggle="tooltip" title={tooltipContent}>{
        category.name
      }</span></a>
    }
    override def children = subCat match {
      case Nil       => techniqueNode(dep, technique, activeTechnique) :: Nil
      case h :: tail => categoryNode(h, tail, dep, technique, activeTechnique) :: Nil
    }
  }

  private[this] def techniqueNode(
      dep:             TechniqueDependencies,
      technique:       Technique,
      activeTechnique: ActiveTechnique
  ): JsTreeNode = new JsTreeNode {
    val tooltipContent    = s"<h3>${technique.name}</h3>\n<div>${technique.description}</div>"
    override def body     = {
      <a href="#"><span class="treeTechniqueName"  data-bs-toggle="tooltip" title={tooltipContent}>{technique.name}</span></a>
    }
    override def children = dep.directives.keySet.toList.map(directiveId => directiveNode(dep, directiveId))
    override val attrs    = ("data-jstree" -> """{ "type" : "template" }""") :: Nil ::: (if (!activeTechnique.isEnabled)
                                                                                        ("class" -> "disableTreeNode") :: Nil
                                                                                      else Nil)
  }

  private[this] def directiveNode(dep: TechniqueDependencies, id: DirectiveUid): JsTreeNode = {
    val (directive, ruleIds) = dep.directives(id)

    new JsTreeNode {
      override val attrs    = ("data-jstree" -> """{ "type" : "directive" }""") :: Nil ::: (if (!directive.isEnabled)
                                                                                           ("class" -> "disableTreeNode") :: Nil
                                                                                         else Nil)
      override def body     = {
        val tooltipContent = s"<h3>${directive.name}</h3>\n<div>${directive.shortDescription}</div>"
        <a href="#"><span class="treeDirective" data-bs-toggle="tooltip" title={tooltipContent}>{directive.name}</span></a>
      }
      override def children = ruleIds.map(k => ruleNode(k)).toList
    }
  }

  private[this] def ruleNode(id: RuleId): JsTreeNode = {
    ruleRepository.get(id).toBox match {
      case Full(rule) =>
        new JsTreeNode {
          override val attrs    = {
            ("data-jstree" -> """{ "type" : "rule" }""") :: Nil ::: (if (!rule.isEnabled) ("class" -> "disableTreeNode") :: Nil
                                                                     else Nil)
          }
          override def body     = {
            val tooltipContent = s"<h3>${rule.name}</h3>\n<div>${rule.shortDescription}</div>"
            <a href="#"><span class="treeRuleName" data-bs-toggle="tooltip" title={tooltipContent}>{
              rule.name
            }</span></a>
          }
          override def children = Nil
        }
      case e: EmptyBox =>
        val msg = "Can not build tree of dependencies for Technique %s".format(techniqueId)
        logger.error(msg, e)

        new JsTreeNode {
          override def body     = <span class="error">Can not find Rule details for id {id}. <span class="errorDetails">{
            (e ?~! msg)
          }</span></span>
          override def children = Nil
        }
    }
  }

}
