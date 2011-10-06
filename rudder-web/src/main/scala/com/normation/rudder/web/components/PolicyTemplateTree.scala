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
  PolicyPackageId,PolicyPackage,
  PolicyPackageCategoryId, PolicyPackageCategory
}
import com.normation.cfclerk.services.PolicyPackageService
import com.normation.rudder.web.model.JsTreeNode
import net.liftweb.common._
import net.liftweb.http.{SHtml,S}
import scala.xml._
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.js._
import JsCmds._ // For implicits
import JE._
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers._
import com.normation.rudder.repository._
import com.normation.rudder.services.policies._
import bootstrap.liftweb.LiftSpringApplicationContext.inject

/**
 * A component to display a tree based on a
 * policy template.
 * 
 * The tree show all parent of the policy template
 * and dependent configuration rules.
 * 
 */
class PolicyTemplateTree(
  htmlId_userTree:String, 
  policyTemplateId:UserPolicyTemplateId,
  switchStatusFilter : ModificationStatus 
) extends DispatchSnippet with Loggable {
  
  //find policy template
  val policyPackageService = inject[PolicyPackageService]
  //find & create user categories
  val userPolicyTemplateCategoryRepository = inject[UserPolicyTemplateCategoryRepository]
  //find & create user policy templates
  val userPolicyTemplateRepository = inject[UserPolicyTemplateRepository]
  
  val dependencyService = inject[DependencyAndDeletionService]
  
  val configurationRuleRepository = inject[ConfigurationRuleRepository]
  
  def dispatch = { 
    case "tree" => { _ => tree }
  }
  
  
  def tree() : NodeSeq = {

    (for {
      parents <- userPolicyTemplateRepository.userPolicyTemplateBreadCrump(policyTemplateId)
      (rootCat,subCats) <- parents.reverse match {
        case h::tail => Full((h, tail))
        case _ => Failure("No parent category found for template %s, abort".format(policyTemplateId))
      }
      upt <- userPolicyTemplateRepository.getUserPolicyTemplate(policyTemplateId)
      pt <- policyPackageService.getLastPolicyByName(upt.referencePolicyTemplateName)
      dep <- dependencyService.policyTemplateDependencies(policyTemplateId,switchStatusFilter)
    } yield {
      categoryNode(rootCat,subCats, dep, pt, upt)
    }) match {
      case Full(treeNode) => {<ul>{treeNode.toXml}</ul>} ++ Script(OnLoad(JsRaw(
          """buildPolicyTemplateDependencyTree('#%s'); createTooltip();""".format(htmlId_userTree)
      )))
      case e:EmptyBox => 
        val msg = "Can not build tree of dependencies for policy template %s".format(policyTemplateId)
        logger.error(msg,e)
        (new JsTreeNode {
          override def body = <span class="error">Can not find dependencies. <span class="errorDetails">{(e ?~! msg).messageChain}</span></span>
          override def children = Nil
        }).toXml
    }
  }
  
  
  private[this] def categoryNode(
      category:UserPolicyTemplateCategory,
      subCat: List[UserPolicyTemplateCategory],
      dep: PolicyTemplateDependencies,
      pt: PolicyPackage,
      upt:UserPolicyTemplate
  ) : JsTreeNode = new JsTreeNode {
    override val attrs = ( "rel" -> "category" ) :: Nil   
    override def body = { <a href="#"><span class="treeUptCategoryName tooltipable" tooltipid={category.id.value.replaceAll("/", "")}  title={category.description}>{category.name}</span></a><div class="tooltipContent" id={category.id.value.replaceAll("/", "")}><h3>{category.name}</h3><div>{category.description}</div></div> }
    override def children = subCat match { 
      case Nil => policyTemplateNode(dep, pt, upt) :: Nil
      case h::tail => categoryNode(h, tail, dep, pt,upt) :: Nil
    }
  }
  
  private[this] def policyTemplateNode(dep: PolicyTemplateDependencies, pt:PolicyPackage, upt:UserPolicyTemplate) : JsTreeNode = new JsTreeNode {
    override def body = {  <a href="#"><span class="treePolicyTemplateName tooltipable" tooltipid={upt.referencePolicyTemplateName.value} title={pt.description}>{pt.name}</span></a><div class="tooltipContent" id={upt.referencePolicyTemplateName.value}><h3>{pt.name}</h3><div>{pt.description}</div></div> }
    override def children = dep.policyInstances.keySet.toList.map {  piId => policyInstanceNode(dep,piId) }
    override val attrs = ( "rel" -> "template") :: Nil ::: (if(!upt.isActivated) ("class" -> "disableTreeNode") :: Nil else Nil )
  }
  
  private[this] def policyInstanceNode(dep: PolicyTemplateDependencies, id:PolicyInstanceId) : JsTreeNode = {
    val (pi, ruleIds) = dep.policyInstances(id)
    
    new JsTreeNode {
      override val attrs = ( "rel" -> "policy") :: Nil ::: (if(!pi.isActivated) ("class" -> "disableTreeNode") :: Nil else Nil )
      override def body = {  <a href="#"><span class="treePolicyInstance tooltipable" tooltipid={pi.id.value} title={pi.shortDescription}>{pi.name}</span></a><div class="tooltipContent" id={pi.id.value}><h3>{pi.name}</h3><div>{pi.shortDescription}</div></div> }
      override def children = ruleIds.map ( k => configurationRuleNode(k) ).toList
    }
  }
  
  private[this] def configurationRuleNode(id:ConfigurationRuleId) : JsTreeNode = {
    configurationRuleRepository.get(id) match {
      case Full(cr) => new JsTreeNode {
        override val attrs = ( "rel" -> "rule") :: Nil ::: (if(!cr.isActivated) ("class" -> "disableTreeNode") :: Nil else Nil )
        override def body = { <a href="#"><span class="treeRuleName tooltipable" tooltipid={id.value} title={cr.shortDescription}>{cr.name}</span></a><div class="tooltipContent" id={id.value}><h3>{cr.name}</h3><div>{cr.shortDescription}</div></div> }
        override def children = Nil
      }
      case e:EmptyBox => 
        val msg = "Can not build tree of dependencies for policy template %s".format(policyTemplateId)
        logger.error(msg, e)
        
        new JsTreeNode {
          override def body = <span class="error">Can not find configuration rule details for id {id}. <span class="errorDetails">{(e?~!msg)}</span></span>
          override def children = Nil
        }
    }
  }
  
}