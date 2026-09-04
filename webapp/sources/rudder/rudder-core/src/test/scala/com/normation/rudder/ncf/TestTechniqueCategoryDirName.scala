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
package com.normation.rudder.ncf

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestTechniqueCategoryDirName extends Specification {

  private def dirName(displayName: String) = TechniqueCategoryDirName.fromDisplayName(displayName).map(_.value)

  "The directory name of a technique category" should {

    "keep a name that is already safe" in {
      dirName("my-category_1") must beRight("my-category_1")
    }

    "replace spaces and non-ASCII characters" in {
      dirName("Mon Câtégorie") must beRight("mon_c_t_gorie")
    }

    "collapse the runs of underscores it creates" in {
      dirName("Mon Câtégorie / test") must beRight("mon_c_t_gorie_test")
    }

    "not let a name escape its parent directory" in {
      (dirName("../../etc") must beRight("etc")) and
      (dirName("a/../b") must beRight("a_b"))
    }

    "drop control characters" in {
      dirName("a b\tc\nd") must beRight("a_b_c_d")
    }

    "refuse a dot, so that no refactoring can turn a name into a traversal" in {
      (dirName("  .hidden.  ") must beRight("hidden")) and
      (dirName("a.b") must beRight("a_b")) and
      (dirName(".") must beLeft) and
      (dirName("..") must beLeft)
    }

    "refuse a name with no usable character" in {
      (dirName("") must beLeft) and
      (dirName("   ") must beLeft) and
      (dirName("é") must beLeft)
    }

    "fold the case, since LDAP compares category ids without it" in {
      // two names differing only by case can not be two categories
      (dirName("Foo") must beRight("foo")) and
      (dirName("SYSTEM settings") must beRight("system_settings"))
    }

    "truncate to what a file system accepts for one path segment" in {
      dirName("a" * 300).map(_.length) must beRight(255)
    }
  }
}
