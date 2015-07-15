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

package com.normation.utils

import org.junit.Test;
import org.junit._
import org.junit.Assert._

import junit.framework.TestSuite
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import org.joda.time.DateTime
import org.joda.time.format._

import com.normation.cfclerk.domain._
import org.joda.time.{Days => _, _ }
import org.joda.time.format._

@RunWith(classOf[BlockJUnit4ClassRunner])
class RepetitionComputerTest {

  // Set a given date to a day, and see what's happening
  @Test
  def testDaysManipulation() {

    // this is a wednesday
    val localDate =  ISODateTimeFormat.localDateParser.parseDateTime("2010-05-12").toLocalDate

    assert(localDate.dayOfWeek.get == DateTimeConstants.WEDNESDAY)

    val monday = localDate.dayOfWeek().setCopy(DateTimeConstants.MONDAY);

    assert(monday.dayOfWeek.get == DateTimeConstants.MONDAY)
    assert(monday.dayOfMonth.get == 10)

    val wednesday = localDate.dayOfWeek().setCopy(DateTimeConstants.WEDNESDAY);

    assert(wednesday.dayOfWeek.get == DateTimeConstants.WEDNESDAY)
    assert(wednesday.dayOfMonth.get == 12)


    val sunday =  ISODateTimeFormat.localDateParser.parseDateTime("2010-05-16").toLocalDate

    assert(sunday.dayOfWeek.get == DateTimeConstants.SUNDAY)

    val prevMonday = sunday.dayOfWeek().setCopy(DateTimeConstants.MONDAY);

    assert(prevMonday.dayOfWeek.get == DateTimeConstants.MONDAY)
    assert(prevMonday.dayOfMonth.get == 10)
  }

}