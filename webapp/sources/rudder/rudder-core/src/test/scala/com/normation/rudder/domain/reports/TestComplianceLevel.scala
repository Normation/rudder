/*
*************************************************************************************
* Copyright 2022 Normation SAS
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

package com.normation.rudder.domain.reports

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner


/**
 * Test properties about Compliance Level,
 * especially pourcent related (sum to 100, rounding error, etc)
 */


@RunWith(classOf[JUnitRunner])
class TestComplianceLevel extends Specification {

  sequential

  "we can sort levels" should {

    "sort smallest first" in {

      val c = ComplianceLevel(12,1,3,4,9,11,0,8,5,2,6,13,10,7)

      CompliancePercent.sortLevels(c).map(_._1) === List(0,1,2,3,4,5,6,7,8,9,10,11,12,13)

    }

    "sort equals in order of worse last" in {
      val c = ComplianceLevel(4,4,4,4,9,11,1,4,5,2,6,13,10,7)
      // interresting part is the 5 '4', where:
      // (4, 7 = not applicable) < (4, 1 = success) < (4, 0 = pending) < (4, 2 = repaired) < (4, 3 = error)
      CompliancePercent.sortLevels(c) ===
      List((1,6), (2,9), (4,7), (4,1), (4,0), (4,2), (4,3), (5,8), (6,10), (7,13), (9,4), (10,12), (11,5), (13,11))
    }
  }


  "a compliance must never be rounded below its precision" >> {

    "which is 0.01 by default" >> {
      val c = ComplianceLevel(success = 1, error = 100000)
      (c.pc.repaired === 0) and (c.pc.success === 0.01) and (c.pc.error === 99.99)
    }

    "and can be 0" >> {
      val c = ComplianceLevel(success = 1, error = 100000)
      val pc = CompliancePercent.fromLevels(c, 0)
      (pc.repaired === 0) and (pc.success === 1) and (pc.error === 99)
    }

    "or a lot" >> {
      val c = ComplianceLevel(success = 1, error = 1000000000)
      val pc = CompliancePercent.fromLevels(c, 5)
      (pc.repaired === 0) and (pc.success === 0.00001) and (pc.error === 99.99999)
    }

  }

  "compliance when there is no report is 0" >> {

    ComplianceLevel().compliance === 0
  }

  "Compliance must sum to 100 percent" >> {

    "when using default precision" >> {
      val c = ComplianceLevel(0,1,1,1)
      (c.pc.success === 33.33) and (c.pc.repaired === 33.33) and (c.pc.error === 33.34)
    }

    "when using 0 digits" >> {
      val c = ComplianceLevel(0,1,1,1)
      val pc = CompliancePercent.fromLevels(c, 0)
      (pc.success === 33) and (pc.repaired === 33) and (pc.error === 34)
    }
  }
}
