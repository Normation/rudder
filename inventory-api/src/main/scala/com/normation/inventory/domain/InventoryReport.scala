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

package com.normation.inventory.domain

import scala.collection.mutable.Buffer
import scala.xml.NodeSeq
import com.normation.utils.HashcodeCaching


/**
 * Define what an inventory report can contain
 * A report is done for one operating system (the server),
 * on at most one container (the underlying machine, physical or virtual).
 * These object may be link too some software and other virtual machine
 * (hosted on the server)
 *
 * The first returned value (the String) is the Device_id.
 * It is a sequel of OCSi/Fusion reports, and perhaps should be removed.
 *
 * The OperatingSystem is an {@code OperatingSystem} object with as much information
 * that we gathered
 *
 * The third returned value holds the (perhaps multiple) containers of that server.
 * At this point, we don't really now if they are bare machine or Virtual ones.
 *
 * The last returned values holds the VMs that are running atop this OS. They may
 * (of course) be empty.
 *
 */
case class InventoryReport(
  val name:String, //an id for that report (ex: name of the file received)
  val inventoryAgentDevideId:String,
  val node:NodeInventory,
  val machine:MachineInventory,
  val version:Option[Version],
  //maybe a list of hosted vms
  val vms : Seq[MachineInventory],

  //a list of software
  val applications : Seq[Software],
  //other not specified managed element
  val sourceReport : NodeSeq
) extends HashcodeCaching
