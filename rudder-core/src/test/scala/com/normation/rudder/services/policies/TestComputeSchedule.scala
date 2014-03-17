/*
*************************************************************************************
* Copyright 2014 Normation SAS
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

package com.normation.rudder.services.policies

import org.junit.runner._
import org.specs2.runner._
import org.specs2.mutable._
import net.liftweb.common._
import org.specs2.specification.Example
import org.specs2.execute.Pending
import org.specs2.matcher.Matchers._
/**
 * Test Node scheudle computation
 */


@RunWith(classOf[JUnitRunner])
class TestComputeSchedule extends Specification {

  "A squedule with 5 minutes interval" should {
    "be the default one when starting at 0:00" in {
      ComputeSchedule.computeSchedule(0,0,5) must beEqualTo (
          Full(""""Min00", "Min05", "Min10", "Min15", "Min20", "Min25", "Min30", "Min35", "Min40", "Min45", "Min50", "Min55"""")
      )
    }

   "be the default one when starting at 4:00" in {
      ComputeSchedule.computeSchedule(4,0,5) must beEqualTo (
          Full(""""Min00", "Min05", "Min10", "Min15", "Min20", "Min25", "Min30", "Min35", "Min40", "Min45", "Min50", "Min55"""")
      )
    }

   "be the default one of by 2 minutes when starting at 4:02" in {
      ComputeSchedule.computeSchedule(4,2,5) must beEqualTo (
          Full(""""Min02", "Min07", "Min12", "Min17", "Min22", "Min27", "Min32", "Min37", "Min42", "Min47", "Min52", "Min57"""")
      )
    }
  }

  "A squedule with non trivial interval" should {
    "fail if a mix of hours and minutes" in {
      ComputeSchedule.computeSchedule(0,0, 63) must beEqualTo (
        Failure("Agent execution interval can only be defined as minutes (less than 60) or complete hours, (1 hours 3 minutes is not supported)")
      )
    }
    
    "be every hours if defined with an interval of 1 hour" in {
      ComputeSchedule.computeSchedule(0,0,60) must beEqualTo (
          Full(""""Hr00.Min00", "Hr01.Min00", "Hr02.Min00", "Hr03.Min00", "Hr04.Min00", "Hr05.Min00", "Hr06.Min00", "Hr07.Min00", "Hr08.Min00", "Hr09.Min00", "Hr10.Min00", "Hr11.Min00", "Hr12.Min00", "Hr13.Min00", "Hr14.Min00", "Hr15.Min00", "Hr16.Min00", "Hr17.Min00", "Hr18.Min00", "Hr19.Min00", "Hr20.Min00", "Hr21.Min00", "Hr22.Min00", "Hr23.Min00"""")
      )
    }
    "be every two hours if defined with an interval of 2 hours, and off by some minutes" in {
      ComputeSchedule.computeSchedule(3,12,120) must beEqualTo (
          Full(""""Hr01.Min12", "Hr03.Min12", "Hr05.Min12", "Hr07.Min12", "Hr09.Min12", "Hr11.Min12", "Hr13.Min12", "Hr15.Min12", "Hr17.Min12", "Hr19.Min12", "Hr21.Min12", "Hr23.Min12"""")
      )
    }
  }
}