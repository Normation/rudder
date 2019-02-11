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

package com.normation.rudder.repository

import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategory
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.domain.policies.ActiveTechniqueCategory
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.rudder.rule.category.RuleCategory

import com.normation.errors._

/**
 * A category of the technique library.
 *
 */
case class ActiveTechniqueCategoryContent(
    category  : ActiveTechniqueCategory
  , categories: Set[ActiveTechniqueCategoryContent]
  , templates : Set[ActiveTechniqueContent]
)

case class ActiveTechniqueContent(
    activeTechnique : ActiveTechnique
  , directives      : Set[Directive]
)

/**
 * Identifier for user library archive
 */
case class ActiveTechniqueLibraryArchiveId(value:String)

/**
 * That trait allows to manage the import of active techniques library
 * (categories, templates, directives) from the File System into
 * the LDAP.
 */

trait ParseActiveTechniqueLibrary {

  /**
   * That method parse rules from the
   * file system for an archive with the given ID.
   */
  def getArchive(archiveId:GitCommitId) : IOResult[ActiveTechniqueCategoryContent]
}

/**
 * That trait allows to manage the import of rules
 * from the File System into the LDAP.
 * That part read the last CR archive.
 */
trait ParseRules {

  /**
   * That method parse rules from the
   * file system for an archive with the given ID.
   */
  def getArchive(archiveId:GitCommitId) : IOResult[Seq[Rule]]
}


/**
 * That trait allows to manage the import of Rule categories
 * from the File System into the LDAP.
 * That part read the last Rule category archive.
 */
trait ParseRuleCategories {

  /**
   * That method parse Rule category from the
   * file system for an archive with the given ID.
   */
  def getArchive(archiveId:GitCommitId) : IOResult[RuleCategory]
}


/**
 * That trait allows to manage the import of Global Parameters
 * from the File System into the LDAP.
 * That part read the last Parameters archive.
 */
trait ParseGlobalParameters {

  /**
   * That method parse global parameters from the
   * file system for an archive with the given ID.
   */
  def getArchive(archiveId:GitCommitId) : IOResult[Seq[GlobalParameter]]
}

/**
 * A category of the group library.
 */
case class NodeGroupCategoryContent(
    category  : NodeGroupCategory
  , categories: Set[NodeGroupCategoryContent]
  , groups    : Set[NodeGroup]
)

/**
 * Identifier for user library archive
 */
case class NodeGroupLibraryArchiveId(value:String)

trait ParseGroupLibrary {

  /**
   * That method parse a group library from the
   * file system for an archive with the given ID.
   */
  def getArchive(archiveId:GitCommitId) : IOResult[NodeGroupCategoryContent]
}



trait ImportTechniqueLibrary {
  /**
   * That method swap an existing active technique library in LDAP
   * to a new one.
   *
   * In case of error, we try to restore the old technique library.
   */
  def swapActiveTechniqueLibrary(rootCategory: ActiveTechniqueCategoryContent, includeSystem: Boolean = false) : IOResult[Unit]
}



trait ImportGroupLibrary {
  /**
   * That method swap an existing active technique library in LDAP
   * to a new one.
   *
   * In case of error, we try to restore the old technique library.
   */
  def swapGroupLibrary(rootCategory: NodeGroupCategoryContent, includeSystem: Boolean = false) : IOResult[Unit]
}
