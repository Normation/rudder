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

package com.normation.rudder.services.log


import com.normation.rudder.domain.log.InventoryEventLog
import com.normation.rudder.repository.EventLogRepository
import net.liftweb.common._

class InventoryEventLogServiceImpl(
    repository : EventLogRepository
) extends InventoryEventLogService {
  
  
  /**
   * Returns all the inventory related event log
   * @return
   */
  def getInventoryEventLogs() : Box[Seq[InventoryEventLog]] = {
    repository.getEventLogByCriteria(Some(" eventType in ('AcceptNode', 'RefuseNode')")) match {
      case Full(seq) => 
        val result = scala.collection.mutable.Buffer[InventoryEventLog]()
        for (log <- seq) {
          log match {
            case inventoryLog : InventoryEventLog =>result += inventoryLog
            case _ => return Failure("Wrong event log type, not an inventory")
          }
        }
        Full(result)
      case Empty => Empty
      case _ => Failure("Could not retrieve eventLogs")
    }
  }

}

