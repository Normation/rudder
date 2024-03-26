/*
 *************************************************************************************
 * Copyright 2015 Normation SAS
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

package com.normation.rudder.inventory

import better.files.*
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*

@RunWith(classOf[JUnitRunner])
class TestRenameReceived extends Specification {

  "old naming format should not be renamed" >> {
    val f = "dev-www-2bb4e01b-be7e-4c9e-9c30-177aada44869.ocs"
    val s = "dev-www-2bb4e01b-be7e-4c9e-9c30-177aada44869.ocs.sign"

    (InventoryMover.normalizeReceivedName(File(f)) === f) and
    (InventoryMover.normalizeReceivedName(File(s)) === s)
  }

  "old naming format should not be renamed - even with _ in the hostname" >> {
    val f = "dev_www-2bb4e01b-be7e-4c9e-9c30-177aada44869.ocs"
    val s = "dev_www-2bb4e01b-be7e-4c9e-9c30-177aada44869.ocs.sign"

    (InventoryMover.normalizeReceivedName(File(f)) === f) and
    (InventoryMover.normalizeReceivedName(File(s)) === s)
  }

  "new format should be renamed" >> {
    val f          = "2bb4e01b-be7e-4c9e-9c30-177aada44869_2024-03-21T16:07:29+01:00.ocs"
    val s          = "2bb4e01b-be7e-4c9e-9c30-177aada44869_2024-03-21T16:07:29+01:00.ocs.sign"
    val normalized = "2bb4e01b-be7e-4c9e-9c30-177aada44869"

    (InventoryMover.normalizeReceivedName(File(f)) === normalized + ".ocs") and
    (InventoryMover.normalizeReceivedName(File(s)) === normalized + ".ocs.sign")
  }

  "actually, we should be able to handle _ in the first part too" in {
    val f          = "dev_www_2bb4e01b-be7e-4c9e-9c30-177aada44869_2024-03-21T16:07:29+01:00.ocs"
    val s          = "dev_www_2bb4e01b-be7e-4c9e-9c30-177aada44869_2024-03-21T16:07:29+01:00.ocs.sign"
    val normalized = "dev_www_2bb4e01b-be7e-4c9e-9c30-177aada44869"

    (InventoryMover.normalizeReceivedName(File(f)) === normalized + ".ocs") and
    (InventoryMover.normalizeReceivedName(File(s)) === normalized + ".ocs.sign")
  }

}
