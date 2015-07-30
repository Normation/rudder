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

package com.normation.cfclerk.domain

import org.junit._
import org.junit.Assert._
import org.junit.runner._
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner._




@RunWith(classOf[JUnitRunner])
class BundleOrderTest extends Specification {

  implicit def s2b(s: String): BundleOrder = BundleOrder(s)

  implicit def l2b(l:List[String]): List[BundleOrder] = l.map(BundleOrder(_))


  /**
   * Reminder for my self-me that keep forgeting what compare means:
   * compare(a,b) < 0  => sorted
   * compare(a,b) > 0  => reverse order
   */

  "Simple BundleOrder" should {

    "be equal with emtpy srting" in {
      BundleOrder.compare("", "") === 0
    }

    "let emtpy string be the smallest" in {
      (BundleOrder.compare("plop", "") > 0) must beTrue
    }

    "use alphaNum" in {
      (BundleOrder.compare("plop", "foo") > 0) must beTrue
    }

    "be sortable with numbers" in {
      (BundleOrder.compare("050 a", "010 b") > 0) must beTrue
    }

    "be becarefull, it's NOT BY NUMBER" in {
      (BundleOrder.compare("50", "100") > 0) must beTrue
    }
  }

  "List compare" should {

    "be equals with empty list" in {
      BundleOrder.compareList(List(), List()) === 0
    }

    "ok for equals" in {
      BundleOrder.compareList(List("10", "20"), List("10", "20")) === 0
    }
    "ok for equals, different size" in {
      BundleOrder.compareList(List("10", ""), List("10")) === 0
    }
    "handle less than" in {
      (BundleOrder.compareList(List("10", "100"), List("20")) < 0) must beTrue
    }

    "handle more than" in {
      (BundleOrder.compareList(List("101", "100"), List("020", "200", "300")) > 0) must beTrue
    }
  }

}
