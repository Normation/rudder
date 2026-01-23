/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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
package com.normation.rudder.services.workflows

import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestCheckMergeDivergence extends Specification {

  implicit def toTechniqueVersion(s: String): TechniqueVersion = {
    TechniqueVersion.parse(s) match {
      case Left(err) => throw new RuntimeException(s"Error in test when parsing a technique version: ${err}")
      case Right(v)  => v
    }
  }

  implicit def toDirectiveId(s: String): DirectiveId = DirectiveId(DirectiveUid(s))

  "We should avoid merge divergence with same directive" >> {
    val d1_orig                  = Directive("d1", "1.0", Map("foo1" -> Seq("bar1")), "d1", "d1", None, security = None)
    val d1_div                   = Directive("d1", "1.0", Map("foo1" -> Seq("bar1")), "d1", "d1", None, security = None)
    implicit val changeRequestId = 1

    CheckDivergenceForMerge.compareDirectives(d1_orig, d1_div) must beTrue
  }
  "We should have a merge divergence with a difference in parameter" >> {
    val d1_orig                  = Directive("d1", "1.0", Map("foo1" -> Seq("bar1")), "d1", "d1", None, security = None)
    val d1_div                   = Directive("d1", "1.0", Map("foo1" -> Seq("foo2")), "d1", "d1", None, security = None)
    implicit val changeRequestId = 1

    CheckDivergenceForMerge.compareDirectives(d1_orig, d1_div) must beFalse
  }
  "We should have avoid divergence with space differences in parameters" >> {
    val d1_orig                  = Directive("d1", "1.0", Map("foo1" -> Seq("bar1 bar2")), "d1", "d1", None, security = None)
    val d1_div                   = Directive("d1", "1.0", Map("foo1" -> Seq("bar1   bar2")), "d1", "d1", None, security = None)
    implicit val changeRequestId = 1

    CheckDivergenceForMerge.compareDirectives(d1_orig, d1_div) must beTrue
  }
  "We should have avoid divergence with line feed differences in parameters" >> {
    val d1_orig                  = Directive("d1", "1.0", Map("foo1" -> Seq("bar1\n\rbar2")), "d1", "d1", None, security = None)
    val d1_div                   = Directive("d1", "1.0", Map("foo1" -> Seq("bar1\nbar2")), "d1", "d1", None, security = None)
    implicit val changeRequestId = 1

    CheckDivergenceForMerge.compareDirectives(d1_orig, d1_div) must beTrue
  }
}
