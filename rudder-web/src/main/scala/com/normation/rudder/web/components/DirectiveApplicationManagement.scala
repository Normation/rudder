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

import com.normation.rudder.domain.policies._
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategoryId
import net.liftweb.common._




// TOTO : Move to core
case class DirectiveApplicationResult (
    rules              : List[RuleId]
  , completeCategory   : List[RuleCategoryId]
  , incompleteCategory : List[RuleCategoryId]
) {
  def addCategory(isComplete : Boolean, category: RuleCategoryId) = {
    if (isComplete) {
      copy(completeCategory = category :: completeCategory)
    } else {
      copy(incompleteCategory = category :: incompleteCategory)
    }
  }
}

object DirectiveApplicationResult {
  def apply(rule:RuleId) : DirectiveApplicationResult= {
    DirectiveApplicationResult(List(rule),Nil,Nil)
  }

  def mergeOne(that : DirectiveApplicationResult, into : DirectiveApplicationResult) = {
    DirectiveApplicationResult (
        (that.rules ++ into.rules).distinct
      , (that.completeCategory ++ into.completeCategory).distinct
      , (that.incompleteCategory ++ into.incompleteCategory).distinct
    )
  }

  // Must not be empty :(
  def merge( results : List[DirectiveApplicationResult]) = {
    (results.tail :\ results.head) (mergeOne)
  }
}


case class DirectiveApplicationManagement (
    directive : Directive
) extends Loggable {

  type Category = RuleCategory
  type CategoryId = RuleCategoryId
  private[this] val roRuleRepo = RudderConfig.roRuleRepository
  private[this] val roRuleCategoryRepo = RudderConfig.roRuleCategoryRepository
  //private[this] val roRuleRepo = RudderConfig.roRuleRepository

  // Static utils

  val rules = roRuleRepo.getAll(false).get.toList

  val applyingRules = rules.filter(_.directiveIds.contains(directive.id))

  val applyingRulesId = applyingRules.map(_.id)

  val rootCategory = roRuleCategoryRepo.getRootCategory.get

  val categories = {
    def mapCategories (cat : Category, map : Map[CategoryId,List[CategoryId]] = Map.empty) : Map[CategoryId,List[CategoryId]] = {
      (cat.childs :\ map) (mapCategories) + (cat.id -> cat.childs.map(_.id))
    }

    mapCategories(rootCategory)
  }

  val parentCategories = {
    categories.flatMap{case (parent,childs) => childs.map( (_ -> parent) )}
  }

  def completeMapping (baseMap : Map[CategoryId,List[RuleId]]) : Map[CategoryId,List[RuleId]] = {
    def toApply(category:CategoryId,rules:List[RuleId], map: Map[CategoryId,List[RuleId]])  : Map[CategoryId,List[RuleId]] = {
      val updatedMap = map.updated(category, (map.get(category).getOrElse(Nil) ++ rules).distinct)
      parentCategories.get(category) match {
        case None => updatedMap
        case Some(parent) => toApply(parent,rules,updatedMap)
      }
    }
    logger.warn(baseMap)
    (baseMap :\ baseMap) {case ((cat,rules),map) => toApply(cat, rules, map)}

  }
  val rulesByCategory = completeMapping(rules.groupBy(_.category).mapValues(_.map(_.id)))

  logger.error(rulesByCategory)
  val applyingRulesbyCategory = completeMapping(applyingRules.groupBy(_.category).mapValues(_.map(_.id)))
  logger.info(applyingRulesbyCategory)
  val rulesMap= rules.groupBy(_.id).mapValues(_.head)

  private[this] var currentApplyingRules = applyingRulesbyCategory

  def checkRulesToUpdate = {
    val current = currentApplyingRules.values.toList.flatten

    val nowApplying = current.diff(applyingRulesId)

    val notApplyingAnymore = applyingRulesId.diff(current)

    (nowApplying.map(rulesMap),notApplyingAnymore.map(rulesMap))

  }

  def checkRule(id : RuleId, status: Boolean) = {
    def checkRule(id : RuleId, status: Boolean /*maybe need a class to declare status ? */, category : CategoryId) : DirectiveApplicationResult = {
      logger.info(s"check for $id, in $category")
      val rules = currentApplyingRules.get(category).getOrElse(Nil)
      val (newApplication,isComplete) =  if (status) {
        val result = (id :: rules).sortBy(_.value)
        val completeRules = rulesByCategory(category).sortBy(_.value)
        (result,result == completeRules)
      } else {
        val result = rules.filter(_ != id)

        (result,result.isEmpty)
      }
      currentApplyingRules = currentApplyingRules.updated(category, newApplication)


      parentCategories.get(category) match {
        case None => DirectiveApplicationResult(id)
        case Some(parent) =>
          val result = checkRule(id, status, parent)
          result.addCategory(isComplete, category)
      }

    }

    val res = checkRule(id, status, rulesMap(id).category)
    logger.info(res)
    logger.error(checkRulesToUpdate)
    res

  }

  def checkCategory (id : CategoryId, status:Boolean) = {
    val currentApplication = currentApplyingRules(id)
    val completeApplication  = rulesByCategory(id)

    val rulesToCheck = if (status) {
      completeApplication.diff(currentApplication)
    } else {
      completeApplication.intersect(currentApplication)
    }

    val applications = rulesToCheck.map(checkRule(_, status))

    DirectiveApplicationResult.merge(applications)
  }

  logger.info(applyingRulesbyCategory)

  /* CE que je veux :
   *   Pouvoir partager entre plusieurs component (Rule grid, Rule category tree, Directive Edit form)
   *
   *
   * - Une list de toute les RULE appliquant la directive AVANT => applyingRules
   * - Une map Rule category -> sous categories Directes: pour déterminer les dépendances
   * - Une map Rule category -> categorie parentes ? pas nécessaire mais car on peut toujorus sé débrouiller pour faire le lookup dans l'autre table
   * - Une map Rule category -> Rule filles (stricte, pas les rules des sous categories) : base
   * - Une map mutable Rule Category -> Rule => ce qui est applique actuellement dans l'UI => fille strictes
   *
   * A chaque modif dans l'UI, appel en ajax pour modifier cet objet -> retourne trois listes : ce qui est a coché, ce qui est a décoché, ce qui est a mettre en intermédiaire, javascript pour modifier chaque checkbox (id = id de la regle)
   * Lors de la validation par l'utilisateur, Directive EditForm récupérer le diff entre applying Rules, et tout les valeurs dans la map mutable
   *
   */
}