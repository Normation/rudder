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

package com.normation.inventory.services.provisioning

import com.normation.inventory.domain.InventoryReport
import net.liftweb.common.Box


/**
 * Define an action that happens after than the report 
 * was committed in the Directory.
 * The "records" are a list of modification which actually
 * happened when the commit was done (and so, 
 * report "-" records = what was already in the directory).
 * 
 * By convention, a PostCommit which return:
 * - Full : continue the pipeline processing. AT LEAST input 
 *          records have to be returned (kind of forward to 
 *          the next postCommit)
 * - Empty or Failure : interrupt pipeline processing (following
 *                      PostCommits won't happened)
 * 
 * 
 * The R parameter is the return type of the back-end. 
 * Ideally, it should be only diff actually applied to the back-end, 
 * but it could be the new entity is the store can not provide
 * better information (LDAP can). 
 * 
 */
trait PostCommit[R] {
  
  def name : String
  
  def apply(report:InventoryReport,records:R) : Box[R]
}