/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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

package com.normation.rudder.rest.lift

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/*
 * Check that path traversal are not possible in inventory API
 */
@RunWith(classOf[JUnitRunner])
class InventoryApiTest extends Specification {

  "InventoryApi.signatureFileName" should {

    "reduce a path-traversal name to its basename" >> {
      InventoryApi.getInventoryAndSignatureFileName("inventory", "pwn/../../../../../../etc/cron.d/pwn") must beEqualTo(
        ("inventory", "inventory.sign")
      )
    }

    "reduce an absolute path to its basename" >> {
      InventoryApi.getInventoryAndSignatureFileName("evil", "/etc/cron.d/evil.sign") must beEqualTo(("evil", "evil.sign"))
    }

    "never return a name containing a path separator" >> {
      InventoryApi.getInventoryAndSignatureFileName("a/b/c/node.ocs.gz", "a/b/c/node.ocs.sign.gz") must beEqualTo(
        ("node.ocs.gz", "node.ocs.sign.gz")
      )
    }

    "keep a plain signature name unchanged" >> {
      InventoryApi.getInventoryAndSignatureFileName("node-uuid.ocs.gz", "node-uuid.ocs.sign") must beEqualTo(
        ("node-uuid.ocs.gz", "node-uuid.ocs.sign")
      )
    }
  }
}
