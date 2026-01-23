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

package com.normation.cfclerk.services

import com.normation.cfclerk.domain.TechniqueCategory
import com.normation.cfclerk.domain.TechniqueCategoryId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.rudder.tenants.ChangeContext
import net.liftweb.common.Box

sealed trait TechniqueCategoryModType
object TechniqueCategoryModType {
  final case class Updated(id: TechniqueCategory)                                extends TechniqueCategoryModType // change in name, description
  final case class Added(cat: TechniqueCategory, parentId: TechniqueCategoryId)  extends TechniqueCategoryModType
  final case class Deleted(cat: TechniqueCategory)                               extends TechniqueCategoryModType
  // be careful - the newId is NOT the parent ID, but the full new ID so that renamed are alse taken into account
  final case class Moved(oldId: TechniqueCategoryId, newId: TechniqueCategoryId) extends TechniqueCategoryModType
}

sealed trait TechniqueVersionModType { def name: String }
case object VersionDeleted extends TechniqueVersionModType { val name = "deleted" }
case object VersionAdded   extends TechniqueVersionModType { val name = "added"   }
case object VersionUpdated extends TechniqueVersionModType { val name = "updated" }

sealed trait TechniquesLibraryUpdateType {
  val techniqueName: TechniqueName
}

/**
 * All version of a technique were deleted, so the technique is
 * full deleted
 */
final case class TechniqueDeleted(techniqueName: TechniqueName, deletedVersion: Set[TechniqueVersion])
    extends TechniquesLibraryUpdateType

/**
 * Some version of a technique were modified, either:
 * - added,
 * - deleted,
 * - modified.
 *
 * One version can have only ONE state update.
 */
final case class TechniqueUpdated(techniqueName: TechniqueName, diff: Map[TechniqueVersion, TechniqueVersionModType])
    extends TechniquesLibraryUpdateType

/**
 * A trait that allows its implementation to get notification
 * about Reference Policy Template Library update.
 *
 * The implementation must be registered to a TechniqueRepository
 * that allows such notification to be shared.
 *
 */
trait TechniquesLibraryUpdateNotification {

  /**
   * A name to identify that callback
   */
  def name: String

  /**
   * Order:
   * higher value for order mean the callback will happen latter
   */
  def order: Int

  /**
   * That method will be called when techniques are updated.
   * TODO: perhaps we want something more useful as a return type.
   *
   * Description is a log description to explain why techniques should be updated
   * (user action, commit, etc).
   */
  def updatedTechniques(
      gitRev:            String,
      techniqueIds:      Map[TechniqueName, TechniquesLibraryUpdateType],
      updatedCategories: Set[TechniqueCategoryModType]
  )(implicit cc: ChangeContext): Box[Unit]

}
