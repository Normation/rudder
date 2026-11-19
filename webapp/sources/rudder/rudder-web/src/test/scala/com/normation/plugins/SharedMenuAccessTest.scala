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

package com.normation.plugins

import com.normation.zio.*
import net.liftweb.common.*
import net.liftweb.http.LiftResponse
import net.liftweb.http.RedirectResponse
import net.liftweb.sitemap.Loc.TestAccess
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SharedMenuAccessTest extends Specification {

  private val granted: TestAccess = TestAccess(() => Empty)
  private def denied(to: String): TestAccess = TestAccess(() => Full(RedirectResponse(to)))

  private def eval(menu: String): Box[LiftResponse] = SharedMenuAccess.testAccess(menu).func()

  "A menu shared between plugins" should {
    "be accessible if only the last registered plugin grants access" in {
      SharedMenuAccess.register("granted-last", denied("/cve")).runNow
      SharedMenuAccess.register("granted-last", granted).runNow
      eval("granted-last") must beEqualTo(Empty)
    }
    "be accessible if only the first registered plugin grants access" in {
      SharedMenuAccess.register("granted-first", granted).runNow
      SharedMenuAccess.register("granted-first", denied("/su")).runNow
      eval("granted-first") must beEqualTo(Empty)
    }
    "redirect when no plugin grants access" in {
      SharedMenuAccess.register("denied", denied("/cve")).runNow
      SharedMenuAccess.register("denied", denied("/su")).runNow
      eval("denied") must beAnInstanceOf[Full[?]]
    }
    "redirect when no plugin registered the menu" in {
      eval("never-registered") must beAnInstanceOf[Full[?]]
    }
    "be accessible if a check fails, as in Lift semantics" in {
      SharedMenuAccess.register("failure", denied("/cve")).runNow
      SharedMenuAccess.register("failure", TestAccess(() => Failure("check error"))).runNow
      eval("failure") must beEqualTo(Empty)
    }
    "be evaluated at request time" in {
      var enabled = false
      SharedMenuAccess.register("dynamic", TestAccess(() => if (enabled) Empty else Full(RedirectResponse("/cve")))).runNow
      val access  = SharedMenuAccess.testAccess("dynamic")
      val before  = access.func()
      enabled = true
      (before must beAnInstanceOf[Full[?]]) and (access.func() must beEqualTo(Empty))
    }
  }
}
