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

package com.normation.inventory.domain

import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._

/**
 * Test properties about Memory serialization and deserialization
 */
@RunWith(classOf[JUnitRunner])
class MemoryTest extends Specification {

  val validMemoryValue: List[(String, Long)] = ("1234" -> 1234L) :: ("1234k" -> 1234*1024L) :: ("1MB" -> 1024*1024L) :: ("1mB" -> 1024*1024L) :: ("114 mB" -> 114*1024*1024L) :: Nil

  val invalidMemoryValue: List[String] = "1234ko"  :: " 1234k" :: "_1234k" :: "afddf" :: Nil


  "Parsing memory type" should {

    "work for all valid value" in {
      validMemoryValue.map { case ( value, result ) =>  MemorySize.parse(value) mustEqual(Some(result))}
    }

    "fail for all invalid value" in {
      invalidMemoryValue.map ( MemorySize.parse(_) mustEqual(None))
    }

  }
}
