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

package com.normation.cfclerk.services

import com.normation.cfclerk.domain._
import net.liftweb.common.Box

/**
 * Deals with templates : prepare the templates, fetch variables needed by a policy,
 * and write machine configuration
 * @author Nicolas CHARLES
 *
 */
trait Cf3PromisesFileWriterService {


  /**
   * For a set of technique, read all the template from the file system in
   * an efficient way
   */
  def readTemplateFromFileSystem(techniques:Set[TechniqueId]) : Box[Map[Cf3PromisesFileTemplateId, Cf3PromisesFileTemplateCopyInfo]]

  /**
   * Write the current seq of template file a the path location, replacing the variables found in variableSet
   * @param fileSet : the set of template to be written
   * @param variableSet : the set of variable
   * @param path : where to write the files
   * @param expectedReportsLines: the lines corresponding to the expected reports (csv file to write)
   */
  def writePromisesFiles(
        fileSet              : Set[Cf3PromisesFileTemplateCopyInfo]
      , variableSet          : Seq[STVariable]
      , outPath              : String
      , expectedReportsLines : Seq[String]): Unit

  /**
   * Move the promises from the new folder to their final folder, backuping previous promises in the way
   * Throw an exception if something fails during the move (all the data will be restored)
   * @param folder : (Container identifier, promisefolder, newfolder, backupfolder )
   */
  def movePromisesToFinalPosition(folders : Seq[PromisesFinalMoveInfo] ) : Seq[PromisesFinalMoveInfo]

  /**
   * Concatenate all the variables for each policy Instances.
   * @param policyContainer
   * @return
   */
  def prepareAllCf3PolicyDraftVariables(container: Cf3PolicyDraftContainer):  Map[TechniqueId, Map[String, Variable]]
}
