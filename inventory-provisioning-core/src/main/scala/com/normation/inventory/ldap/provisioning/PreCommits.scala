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

package com.normation.inventory.ldap.provisioning

import com.normation.inventory.services.provisioning._

import com.normation.inventory.domain.InventoryReport
import net.liftweb.common.{Box,Full,Empty,EmptyBox,Failure}
import com.normation.inventory.domain._
import com.normation.inventory.ldap.core.InventoryMapper

/**
 * Check OS Type. 
 * We can not handle "UnknownOsType", we just don't know what
 * to do with them
 */
object CheckOsType extends PreCommit {
  
  override val name = "pre_commit_inventory:check_os_type_is_known"
    
  override def apply(report:InventoryReport) : Box[InventoryReport] = {
    
    report.node.main.osDetails.os match {
      case UnknownOSType =>
        val xml = report.sourceReport\\"OPERATINGSYSTEM"
        Failure("Os Type is not suported (OS Type: %s; OS Name: %s)".format(
          (xml\\"KERNEL_NAME").text
        , (xml\\"NAME").text
      ))
      case _ => Full(report)
    }
    
  }
}


/**
 * Normalize machine Name.
 * They are mandatory, but not always provided
 */
object CheckMachineName extends PreCommit {
  
  private[this] def checkName(machine:MachineInventory) : MachineInventory = {
    //machine cn is mandatory, if not set we use the uuid
    if(!machine.name.isDefined) {
      machine.copy(name = Some(machine.id.value))
    } else {
      machine
    }
  }
  
  override val name = "pre_commit_inventory:check_machine_cn"
    
  override def apply(report:InventoryReport) : Box[InventoryReport] = {
    //machine are in FullMachine and VMs
    Full(report.copy(
      machine = checkName(report.machine),
      vms = report.vms.map { m =>   checkName(m) }
    ) )
  }
}


/**
 * Log the report to save 
 */
class LogReportPreCommit(
  mapper:InventoryMapper,
  ldifLogger:LDIFReportLogger
) extends PreCommit {
  private[this] def reportToLdif( report:InventoryReport ) = {
    mapper.treeFromNode( report.node ).toLDIFRecords ++
    mapper.treeFromMachine( report.machine ).toLDIFRecords ++
    report.vms.flatMap( vm => mapper.treeFromMachine( vm ).toLDIFRecords ) ++
    report.applications.map( s => mapper.entryFromSoftware( s ).toLDIFRecord )
  }
  
  override val name = "pre_commit_inventory:log_inventory"
  
  override def apply(report:InventoryReport) : Box[InventoryReport] = {
    ldifLogger.log(
        report.name,
        Some("LDIF describing the state of inventory to reach after save. What will be actually saved may be modified by pre/post processing"),
        Some("REPORT"),
        reportToLdif(report))
    Full(report)
  }
  
}


/**
 * Update last inventory date for Server and machine
 */
class LastInventoryDate() extends PreCommit {
  import org.joda.time.DateTime
  
  override val name = "pre_commit_inventory:set_last_inventory_date"
    
  override def apply(report:InventoryReport) : Box[InventoryReport] = {
    val now = DateTime.now()
    
    Full(report.copy (
      node = report.node.copy( receiveDate = Some(now) ),
      machine = report.machine.copy( receiveDate = Some(now) )
    ) )
  }
}


/**
 * Set the ip values in the server object, from the networks data
 */
object AddIpValues extends PreCommit {
  
  override val name = "pre_commit_inventory:add_ip_values"
  
  override def apply(report:InventoryReport) : Box[InventoryReport] = {

    val ips = report.node.networks.flatMap(x => x.ifAddresses).map(x => x.getHostAddress() )
    
    Full(report.copy( node = report.node.copy( serverIps = ips ) ) )

    
  }
}