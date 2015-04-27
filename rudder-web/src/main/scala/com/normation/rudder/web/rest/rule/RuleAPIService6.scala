/*
*************************************************************************************
* Copyright 2015 Normation SAS
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

package com.normation.rudder.web.rest.rule

import com.normation.rudder.domain.policies._
import com.normation.rudder.web.rest.RestUtils._
import net.liftweb.common._
import net.liftweb.json._
import net.liftweb.json.JsonDSL._
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.rule.category._
import com.normation.eventlog._
import com.normation.rudder.web.rest._

case class RuleApiService6 (
    readRuleCategory     : RoRuleCategoryRepository
  , readRule             : RoRuleRepository
  , writeRuleCategory    : WoRuleCategoryRepository
  , categoryService      : RuleCategoryService
  , restDataSerializer   : RestDataSerializer
) extends Loggable {

  def getCategoryInformations(category: RuleCategory, parent: RuleCategoryId, detail : DetailLevel) = {
    for {
      rules <- readRule.getAll()
    } yield {
      restDataSerializer.serializeRuleCategory(category, parent, rules.groupBy(_.categoryId), detail)
    }
  }

  def getCategoryTree = {
    for {
        root <- readRuleCategory.getRootCategory()
        categories <- getCategoryInformations(root,root.id,FullDetails)
    } yield {
      categories
    }
  }

  def getCategoryDetails(id : RuleCategoryId) = {
    for {
      root <- readRuleCategory.getRootCategory()
      (category,parent) <- root.find(id)
      categories <- getCategoryInformations(category,parent,MinimalDetails)
    } yield {
      categories
    }
  }

  def deleteCategory(id : RuleCategoryId)(actor : EventActor, modId : ModificationId, reason : Option[String]) = {
    for {
      root <- readRuleCategory.getRootCategory()
      (category,parent) <- root.find(id)
      rules <- readRule.getAll()
      ok <- if (category.canBeDeleted(rules.toList)) {
              Full("ok")
            } else {
              Failure(s"Cannot delete category '${category.name}' since that category is not empty")
            }
      _ <- writeRuleCategory.delete(id, modId, actor, reason)
      category <- getCategoryInformations(category,parent,MinimalDetails)
    } yield {
      category
    }
  }

  def updateCategory(id : RuleCategoryId, restData: Box[RestRuleCategory])(actor : EventActor, modId : ModificationId, reason : Option[String]) = {
    logger.info(restData)
    for {
      data <- restData
      root <- readRuleCategory.getRootCategory()
      (category,parent) <- root.find(id)
      rules <- readRule.getAll()
      update = data.update(category)
      updatedParent = data.parent.getOrElse(parent)

      _ <- data.parent match {
        case Some(parent) =>
          writeRuleCategory.updateAndMove(update, parent, modId, actor, reason)
        case None =>
          writeRuleCategory.update(update, modId, actor, reason)
      }
      category <- getCategoryInformations(update,updatedParent,MinimalDetails)
    } yield {
      category
    }
  }



  def createCategory(id : RuleCategoryId, restData: Box[RestRuleCategory])(actor : EventActor, modId : ModificationId, reason : Option[String]) = {
    for {
      data <- restData
      update <- data.create(id)
      parent = data.parent.getOrElse(RuleCategoryId("rootRuleCategory"))
      _ <-writeRuleCategory.create(update,parent, modId, actor, reason)
      category <- getCategoryInformations(update,parent,MinimalDetails)
    } yield {
      category
    }
  }

}