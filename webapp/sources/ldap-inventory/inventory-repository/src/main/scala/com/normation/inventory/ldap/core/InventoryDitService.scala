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

package com.normation.inventory.ldap.core

import com.normation.inventory.domain.InventoryStatus
import com.unboundid.ldap.sdk.DN

/**
 * A service that finds on which DIT
 * an object belongs from its ID.
 * If no DIT are configured to keep object
 * with such ID, None is returned.
 */
trait InventoryDitService {
  /**
   * It is mandatory that all inventory status
   * leads to a dit
   */
  def getDit(status:InventoryStatus) : InventoryDit

  /**
   * It is mandatory that all inventory dit
   * leads to an inventory status
   */
  def getInventoryStatus(dit : InventoryDit) : InventoryStatus

  def getDit(dn:DN) : Option[InventoryDit]

  def getSoftwareBaseDN : DN
}
