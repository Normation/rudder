/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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

package com.normation.inventory.provisioning.fusion

import better.files.Resource
import com.normation.inventory.provisioning.fusion.PreInventoryParserCheckInventoryAge.*
import com.normation.zio.*
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import scala.xml.XML
import zio.*

@RunWith(classOf[JUnitRunner])
class TestPreParsingDate extends Specification {

  val now       = OffsetDateTime.of(2023, 11, 16, 11, 26, 18, 0, ZoneOffset.UTC)
  val centos8   = OffsetDateTime.of(2023, 11, 16, 11, 26, 18, 0, ZoneOffset.UTC)
  val maxBefore = 1.day
  val maxAfter  = 2.hours
  val okDate    = centos8
  val before    = now.minus(maxBefore).minusSeconds(1)
  val after     = now.plus(maxAfter).plusSeconds(1)

  "Pre inventory check age" should {
    "be able to extract a date without timezone info in inventory" in {
      val xml  = XML.load(Resource.getAsStream("fusion-inventories/8.0/centos8.ocs"))
      val v    = extracDateValue(xml)
      val date = parseInventoryDate(v)
      (v === "2023-11-16 11:26:18") and
      (date must beRight[OffsetDateTime](beEqualTo(centos8)))
    }
    "be able to extract a date  timezone info in inventory" in {
      val xml  = XML.load(Resource.getAsStream("fusion-inventories/8.0/sles15sp4.ocs"))
      val v    = extracDateValue(xml)
      val date = parseInventoryDate(v)
      (v === "2023-11-16 11:46:12+0100") and
      (date must beRight[OffsetDateTime](beEqualTo(OffsetDateTime.of(2023, 11, 16, 11, 46, 12, 0, ZoneOffset.ofHours(1)))))
    }
    "accept date in range" in {
      checkDate(okDate, now, maxBefore, maxAfter) === Right(())
    }
    "reject date before" in {
      checkDate(before, now, maxBefore, maxAfter).left.map(_.fullMsg) must beLeft(
        matching(s".*Inventory is too old.*")
      )
    }
    "reject date after" in {
      checkDate(after, now, maxBefore, maxAfter).left.map(_.fullMsg) must beLeft(
        matching(s".*Inventory is too far in the future.*")
      )
    }
    "reject centos8 inventory from 2023 (testing full process)" in {
      val xml     = XML.load(Resource.getAsStream("fusion-inventories/8.0/centos8.ocs"))
      val checker = new PreInventoryParserCheckInventoryAge(maxBefore, maxAfter)
      ZioRuntime.unsafeRun(checker(xml).either).left.map(_.fullMsg) must beLeft(
        matching(s".*Inventory is too old.*")
      )
    }
  }
}
