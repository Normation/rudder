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
    val newRules      = (that.rules ++ into.rules).distinct
    val newComplete   = (that.completeCategory ++ into.completeCategory).distinct
    // Filter complete from incomplete so there is no conflict (actions are building to end up with complete status, so if complete stauts is achieved in some there is no icomplete possibility)
    val newIncomplete = (that.incompleteCategory ++ into.incompleteCategory).distinct.diff(newComplete)
    DirectiveApplicationResult (newRules, newComplete, newIncomplete)
  }

  def merge( results : List[DirectiveApplicationResult]) = {
    results match {
      case Nil => DirectiveApplicationResult(Nil,Nil,Nil)
      case head :: Nil => head
      case _ => (results.tail :\ results.head) (mergeOne)
    }
  }
}


case class DirectiveApplicationManagement (
    directive    : Directive
  , rules        : List[Rule]
  , rootCategory : RuleCategory
) extends Loggable {

  // Utility Types
  private[this] type Category = RuleCategory
  private[this] type CategoryId = RuleCategoryId

  /*
   * Initial Values
   */

  // Rules

  // Applying Rules At the beginning
  private[this] val applyingRules = rules.filter(_.directiveIds.contains(directive.id))

  // Applying Rules Id
  private[this] val applyingRulesId = applyingRules.map(_.id)

  // Map to get a Rule form it id
  private[this] val rulesMap= rules.groupBy(_.id).mapValues(_.head)


  // Categories

  // Map to get children categories from a category
  private[this] val categories = {
    def mapCategories (cat : Category, map : Map[CategoryId,List[CategoryId]] = Map.empty) : Map[CategoryId,List[CategoryId]] = {
      (cat.childs :\ map) (mapCategories) + (cat.id -> cat.childs.map(_.id))
    }

    mapCategories(rootCategory).withDefaultValue(Nil)
  }

  // Map ti get parent category from a category
  private[this] val parentCategories = {
    categories.flatMap{case (parent,childs) => childs.map( (_ -> parent) )}
  }


  // Category -> Rule

  // Utility method to complete the map (add Rules to parents)
  private[this] def completeMapping (baseMap : Map[CategoryId,List[RuleId]]) : Map[CategoryId,List[RuleId]] = {
    def toApply(category:CategoryId,rules:List[RuleId], map: Map[CategoryId,List[RuleId]])  : Map[CategoryId,List[RuleId]] = {
      val updatedMap = map.updated(category, (map.get(category).getOrElse(Nil) ++ rules).distinct)
      parentCategories.get(category) match {
        case None => updatedMap
        case Some(parent) => toApply(parent,rules,updatedMap)
      }
    }
    (baseMap :\ baseMap) {case ((cat,rules),map) => toApply(cat, rules, map)}

  }


  // Get Rules from a category
  private[this] val rulesByCategory = completeMapping(rules.groupBy(_.categoryId).mapValues(_.map(_.id))).withDefaultValue(Nil)

  // Get applying Rules fror each category at the beginning
  private[this] val applyingRulesbyCategory = completeMapping(applyingRules.groupBy(_.categoryId).mapValues(_.map(_.id))).withDefaultValue(Nil)

  // Current State, this variable will contains the application state of all Rules and categories
  private[this] var currentApplyingRules = applyingRulesbyCategory.withDefaultValue(Nil)


  // Get rules that needs to be updated , and divide them by those who are new application and those who don't apply the Directive annymore
  def checkRulesToUpdate = {

    // Need to get all Current application Rule, need to have them disctinct (we got them from a Map)
    val current = currentApplyingRules.values.toList.flatten.distinct

    // Compute Rules that are now applying the Directive, We need the Rules that are now applying and were not appying at the beginning
    val nowApplying = current.diff(applyingRulesId)
    logger.debug(s"current is $current")
    logger.debug(s"applyingRules are $applyingRulesId")
    logger.debug(s"now applying are $nowApplying")

    // Compute that are not applyting the Directive anymore, We need the rules that were applying and that don't apply anymore
    val notApplyingAnymore = applyingRulesId.diff(current)

    (nowApplying.map(rulesMap),notApplyingAnymore.map(rulesMap))

  }


  /*
   *  Check Functions, They will change the state inside the service
   */
  def checkRule(id : RuleId, status: Boolean) = {
    def checkRule(id : RuleId, status: Boolean, category : CategoryId) : DirectiveApplicationResult = {
      logger.debug(s"check for $id, in $category")
      // Get current state
      val currentAppliedRules = currentApplyingRules.get(category).getOrElse(Nil)
      // Get the new application status, and if the category completed is Full
      val (newApplication,isComplete) = {
        if (status) {
          val result = (id :: currentAppliedRules).sortBy(_.value).distinct
          val completeRules = rulesByCategory(category).sortBy(_.value)
          (result,result == completeRules)
        } else {
          val result = currentAppliedRules.filter(_ != id)
          (result,result.isEmpty)
        }
      }

      // Update current state
      currentApplyingRules = currentApplyingRules.updated(category, newApplication)

      // Look for parent and add the last result
      (parentCategories.get(category) match {
        case None         => DirectiveApplicationResult(id)
        case Some(parent) => checkRule(id, status, parent)
      }).addCategory(isComplete, category)
    }
    // get category of Rule then start : TODO : pass a pattern matching, result to Box
    checkRule(id, status, rulesMap(id).categoryId)

  }

   // Check a Lists of Rules, used when you try to apply several rules together
   def checkRules (rules : List[RuleId], status:Boolean) = {
     DirectiveApplicationResult.merge(rules.map(checkRule(_, status)))
  }

  // Check a Category
  def checkCategory (id : CategoryId, status:Boolean) = {
    // Current application of that category
    val currentApplication = currentApplyingRules(id)
    // All Rules contained in that category
    val completeApplication  = rulesByCategory(id)
    logger.debug(s"category $id is currently applying ${currentApplication.size} rules and completeApplication contains ${completeApplication.size} ")

    // Get Rules that needs modifications
    val rulesToCheck = if (status) {
      // Here we add the Category: get those missing from complete state
      completeApplication.diff(currentApplication)
    } else {
      // Here we remove the category: Get so the actual application
      completeApplication
    }
    logger.debug(s"there is ${rulesToCheck.size} to ${if (status) "check" else "uncheck" }")

    //Check Rules from that category
    val applications = rulesToCheck.map(checkRule(_, status))
    logger.debug(s"final applications for category $id:")
    // Final merge
    DirectiveApplicationResult.merge(applications)
  }


  /*
   * Check Status methods, here are functions to check whether a rule is considered apply the Directive or not
   */
  // Check status of rule
  def ruleStatus(rule: Rule) = {
    currentApplyingRules.get(rule.categoryId).getOrElse(Nil).contains(rule.id)
  }
  // Check status of Rule, based on its Id
  def ruleStatus(ruleId : RuleId) : Box[Boolean] = {
    rulesMap.get(ruleId) match {
      case Some(rule) => Full(ruleStatus(rule))
      case None       => Failure(s"Could not get Rule with id ${ruleId.value} from directive application.")
    }
  }

  // Check a list of Rules
  def checkRules = {
    rules.partition(ruleStatus)
  }

  // Check if a category is complete or not
  def isCompletecategory (id : CategoryId) = {
    val currentApplication = currentApplyingRules.get(id).getOrElse(Nil)
    val completeApplication  = rulesByCategory(id)
    currentApplication.size > 0 && currentApplication == completeApplication
  }

  // Check if the category is in an indeterminate state
  def isIndeterminateCategory (id : CategoryId) = {
    val currentApplication = currentApplyingRules.get(id).getOrElse(Nil)
    val completeApplication  = rulesByCategory(id)
    currentApplication.size > 0 && currentApplication != completeApplication
  }

  // Check if the category is in empty
  def isEmptyCategory (id : CategoryId) = {
    val currentApplication = rulesByCategory.get(id).getOrElse(Nil)
    currentApplication.size > 0
  }

}