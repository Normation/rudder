/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

package com.normation.rudder.facts.nodes

import com.normation.inventory.domain.AcceptedInventory
import com.normation.inventory.domain.Bios
import com.normation.inventory.domain.FullInventory
import com.normation.inventory.domain.MachineInventory
import com.normation.inventory.domain.MachineUuid
import com.normation.inventory.domain.Manufacturer
import com.normation.inventory.domain.VirtualMachineType
import com.normation.inventory.domain.VmType
import com.normation.rudder.MockNodes
import com.softwaremill.quicklens.*
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.Chunk

/*
 * Simple test on merge / mask / etc for SelcetFacts
 */
//@silent("a type was inferred to be `\\w+`; this may indicate a programming error.")
@RunWith(classOf[JUnitRunner])
class TestSelectFacts extends Specification {
  import MockNodes.*

  val machine1: MachineInventory = MachineInventory(
    MachineUuid("machine1"),
    AcceptedInventory,
    VirtualMachineType(VmType.VirtualBox),
    None,
    None,
    None,
    None,
    Some(Manufacturer("the manufactorurer")),
    None,
    List(Bios("bios"))
  )

  val nodeFact1: NodeFact = NodeFact.fromCompat(node1, Right(FullInventory(nodeInventory1, Some(machine1))), softwares, None)

  "masking 1" >> {
    (SelectFacts.mask(nodeFact1)(using SelectFacts.all) === nodeFact1) and
    (nodeFact1.software must not(beEmpty)) and
    (nodeFact1.environmentVariables must not(beEmpty)) and
    (nodeFact1.bios must not(beEmpty)) and
    (nodeFact1.softwareUpdate must not(beEmpty))
  }

  "masking 2" >> {
    SelectFacts.mask(nodeFact1)(using SelectFacts.noSoftware) === nodeFact1.modify(_.software).setTo(Chunk())
  }

  "masking 3" >> {
    SelectFacts.mask(nodeFact1)(using SelectFacts.none) === nodeFact1
      .modify(_.swap)
      .setTo(None)
      .modify(_.accounts)
      .setTo(Chunk())
      .modify(_.bios)
      .setTo(Chunk())
      .modify(_.controllers)
      .setTo(Chunk())
      .modify(_.environmentVariables)
      .setTo(Chunk())
      .modify(_.fileSystems)
      .setTo(Chunk())
      .modify(_.inputs)
      .setTo(Chunk())
      .modify(_.localGroups)
      .setTo(Chunk())
      .modify(_.localUsers)
      .setTo(Chunk())
      .modify(_.logicalVolumes)
      .setTo(Chunk())
      .modify(_.memories)
      .setTo(Chunk())
      .modify(_.networks)
      .setTo(Chunk())
      .modify(_.physicalVolumes)
      .setTo(Chunk())
      .modify(_.ports)
      .setTo(Chunk())
      .modify(_.processes)
      .setTo(Chunk())
      .modify(_.processors)
      .setTo(Chunk())
      .modify(_.slots)
      .setTo(Chunk())
      .modify(_.software)
      .setTo(Chunk()) // softwareUpdate are part of core and always present
      .modify(_.sounds)
      .setTo(Chunk())
      .modify(_.storages)
      .setTo(Chunk())
      .modify(_.videos)
      .setTo(Chunk())
      .modify(_.vms)
      .setTo(Chunk())
  }

  "masking 4" >> {
    SelectFacts.mask(nodeFact1)(using
      SelectFacts.none
        .modify(_.bios.mode)
        .setTo(SelectMode.Retrieve)
        .modify(_.software.mode)
        .setTo(SelectMode.Retrieve)
        .modify(_.environmentVariables.mode)
        .setTo(SelectMode.Retrieve)
    ) === nodeFact1
      .modify(_.swap)
      .setTo(None)
      .modify(_.accounts)
      .setTo(Chunk())
//      .modify(_.bios)
//      .setTo(Chunk())
      .modify(_.controllers)
      .setTo(Chunk())
//      .modify(_.environmentVariables)
//      .setTo(Chunk())
      .modify(_.fileSystems)
      .setTo(Chunk())
      .modify(_.inputs)
      .setTo(Chunk())
      .modify(_.localGroups)
      .setTo(Chunk())
      .modify(_.localUsers)
      .setTo(Chunk())
      .modify(_.logicalVolumes)
      .setTo(Chunk())
      .modify(_.memories)
      .setTo(Chunk())
      .modify(_.networks)
      .setTo(Chunk())
      .modify(_.physicalVolumes)
      .setTo(Chunk())
      .modify(_.ports)
      .setTo(Chunk())
      .modify(_.processes)
      .setTo(Chunk())
      .modify(_.processors)
      .setTo(Chunk())
      .modify(_.slots)
      .setTo(Chunk())
//      .modify(_.software)
//      .setTo(Chunk())
//      .modify(_.softwareUpdate)
//      .setTo(Chunk())
      .modify(_.sounds)
      .setTo(Chunk())
      .modify(_.storages)
      .setTo(Chunk())
      .modify(_.videos)
      .setTo(Chunk())
      .modify(_.vms)
      .setTo(Chunk())
  }
}
