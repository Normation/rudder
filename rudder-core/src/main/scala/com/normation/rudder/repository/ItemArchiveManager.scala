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

package com.normation.rudder.repository

import java.io.File
import com.normation.rudder.domain.policies.ConfigurationRule
import com.normation.rudder.domain.policies.ConfigurationRuleId
import net.liftweb.common.Box

trait GitConfigurationRuleArchiver {
  /**
   * Archive a configuration rule in a file system
   * managed by git. 
   * If gitCommitCr is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def archiveConfigurationRule(cr:ConfigurationRule, gitCommitCr:Boolean = true) : Box[File]
  
  /**
   * Commit modification done in the Git repository for any
   * configuration rules.
   * Return the git commit id. 
   */
  def commitConfigurationRules() : Box[String]
  
  /**
   * Delete an archived configuration rule. 
   * If gitCommitCr is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def deleteConfigurationRule(crId:ConfigurationRuleId, gitCommitCr:Boolean = true) : Box[File]
  
  /**
   * Get the root directory where configuration rules are saved
   */
  def getRootDirectory : File
}

case class ArchiveId(value:String)

/**
 * This trait allow to manage archives of Policy library, configuration rules
 * and groupes. 
 * 
 * Archive can be done in one shot, partially updated, or read back. 
 */
trait ItemArchiveManager {
  
  /**
   * Save all items handled by that archive manager 
   * and return an ID for the archive on success. 
   */
  def saveAll(includeSystem:Boolean = false) : Box[ArchiveId]
  
  /**
   * Import the last available archive in Rudder. 
   * If anything goes bad, implementation of that method
   * should take all the care to let Rudder in the state
   * where it was just before the (unsuccessful) import 
   * was required. 
   */
  def importLastArchive(includeSystem:Boolean = false) : Box[Unit]

}