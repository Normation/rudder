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

package com.normation.rudder.domain.policies

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner._
import net.liftweb.common._
import com.normation.rudder.domain.policies.PolicyMode.Enforce
import com.normation.rudder.domain.policies.PolicyMode.Verify

@RunWith(classOf[JUnitRunner])
class PolicyModeTest extends Specification with Loggable {


  "Chekin rules on policy mode" should {
    "show that global policy mode wins when not overridable" in {
      PolicyMode.computeMode(
          GlobalPolicyMode(Enforce, PolicyModeOverrides.Unoverridable)
        , Some(Verify), Some(Verify) :: Nil
      ) must equalTo(Enforce)
    }
    "show that global policy mode wins when not overridable" in {
      PolicyMode.computeMode(
          GlobalPolicyMode(Enforce, PolicyModeOverrides.Unoverridable)
        , Some(Enforce), Some(Verify) :: Nil
      ) must equalTo(Enforce)
    }
    "and loose when overridable" in {
      PolicyMode.computeMode(
          GlobalPolicyMode(Enforce, PolicyModeOverrides.Always)
        , Some(Enforce), Some(Verify) :: Nil
      ) must equalTo(Verify)
    }
    "and loose when overridable" in {
      PolicyMode.computeMode(
          GlobalPolicyMode(Enforce, PolicyModeOverrides.Always)
        , Some(Verify), Some(Enforce) :: Nil
      ) must equalTo(Verify)
    }
    "EVEN if global is on verify" in {
      PolicyMode.computeMode(
          GlobalPolicyMode(Verify, PolicyModeOverrides.Always)
        , Some(Enforce), Some(Enforce) :: Nil
      ) must equalTo(Enforce)
    }
    "But is chosen if nothign else defined" in {
      PolicyMode.computeMode(
          GlobalPolicyMode(Enforce, PolicyModeOverrides.Always)
        , None, None :: Nil
      ) must equalTo(Enforce)
    }
  }
}
