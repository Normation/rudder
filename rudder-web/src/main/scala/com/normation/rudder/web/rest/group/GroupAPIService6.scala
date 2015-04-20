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

package com.normation.rudder.web.rest.group

import com.normation.rudder.web.rest.RestUtils._
import net.liftweb.common._
import net.liftweb.json._
import net.liftweb.json.JsonDSL._
import com.normation.rudder.repository._
import com.normation.eventlog._
import com.normation.rudder.web.rest._
import com.normation.rudder.domain.nodes._
import com.normation.rudder.domain.RudderDit

case class GroupApiService6 (
    readGroup     : RoNodeGroupRepository
  , writeGroup    : WoNodeGroupRepository
  , restDataSerializer   : RestDataSerializer
) extends Loggable {

  def getCategoryTree = {
    for {
        root <- readGroup.getFullGroupLibrary
    } yield {
      restDataSerializer.serializeGroupCategory(root, root.id, FullDetails)
    }
  }

  def getCategoryDetails(id : NodeGroupCategoryId) = {
    for {
      root <- readGroup.getFullGroupLibrary
      category <- Box(root.allCategories.get(id)) ?~! s"Cannot find Group category '${id.value}'"
      parent <- Box(root.parentCategories.get(id)) ?~! s"Cannot find Group category '${id.value}' parent"
    } yield {
      restDataSerializer.serializeGroupCategory(category, parent.id, MinimalDetails)
    }
  }

  def deleteCategory(id : NodeGroupCategoryId)(actor : EventActor, modId : ModificationId, reason : Option[String]) = {
    for {
      root <- readGroup.getFullGroupLibrary
      category <- Box(root.allCategories.get(id)) ?~! s"Cannot find Group category '${id.value}'"
      parent <- Box(root.parentCategories.get(id)) ?~! s"Cannot find Groupl category '${id.value}' parent"
      _ <- writeGroup.delete(id, modId, actor, reason)
    } yield {
      restDataSerializer.serializeGroupCategory(category, parent.id, MinimalDetails)
    }
  }

  def updateCategory(id : NodeGroupCategoryId, restData: Box[RestGroupCategory])(actor : EventActor, modId : ModificationId, reason : Option[String]) = {
    for {
      data <- restData
      root <- readGroup.getFullGroupLibrary
      category <- Box(root.allCategories.get(id)) ?~! s"Cannot find Group category '${id.value}'"
      oldParent <- Box(root.parentCategories.get(id)) ?~! s"Cannot find Group category '${id.value}' parent"
      parent = data.parent.getOrElse(oldParent.id)
      update = data.update(category)
      _ <-writeGroup.saveGroupCategory(update.toNodeGroupCategory,parent, modId, actor, reason)
    } yield {
      restDataSerializer.serializeGroupCategory(update, parent, MinimalDetails)
    }
  }

  def createCategory(id : NodeGroupCategoryId, restData: Box[RestGroupCategory])(actor : EventActor, modId : ModificationId, reason : Option[String]) = {
    for {
      data <- restData
      update <- data.create(id)
      category = update.toNodeGroupCategory
      parent = data.parent.getOrElse(NodeGroupCategoryId("GroupRoot"))
      _ <-writeGroup.addGroupCategorytoCategory(category,parent, modId, actor, reason)
    } yield {
      restDataSerializer.serializeGroupCategory(update, parent, MinimalDetails)
    }
  }

}