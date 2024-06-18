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

package com.normation.rudder.web.snippet

import org.junit.runner.RunWith
import zio.Scope
import zio.test.*
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class HomePageTest extends ZIOSpecDefault {

  val mapping = Map(
    "6.0.8-xenial0"                  -> "6.0.8",
    "6.0.4.release-1.EL.7"           -> "6.0.4",
    "6.1.10"                         -> "6.1.10",
    "cfe-3.7.4"                      -> "3.7.4",
    "6.1.6-0"                        -> "6.1.6",
    "7.0.6-bionic0"                  -> "7.0.6",
    "7.1.14-jessie0"                 -> "7.1.14",
    "6.2-0.3"                        -> "6.2-0.3",
    "6.2.3-stretch0"                 -> "6.2.3",
    "cfe-3.6.0.65534"                -> "3.6.0.65534",
    "4.1.8.release-1.SLES.12"        -> "4.1.8",
    "3.1.15.release-1.EL.5"          -> "3.1.15",
    "7.2.0~rc1-ubuntu22.04"          -> "7.2.0.rc1",
    "6.1.17.release-1.EL.7"          -> "6.1.17",
    "6.1.0-precise0"                 -> "6.1.0",
    "1543543:6.0.1.beta4.git-1.EL.6" -> "6.0.1.beta4.git", // epoch is removed

    "6.1.0~alpha1~git201609090917-jessie0" -> "6.1.0.alpha1.git201609090917",
    "6.2.0.beta1~git-1"                    -> "6.2.0.beta1.git",
    "2.3.0~beta3~git201109200504-lenny0"   -> "2.3.0.beta3.git201109200504",
    "5.0.9.rc1_git201903300127"            -> "5.0.9.rc1_git201903300127",
    "7.1.19.rc1.git201901220440-1.EL.7"    -> "7.1.19.rc1.git201901220440",
    "3.1.15.release-1.AIX.5.3"             -> "3.1.15",
    "6.0.1~rc1~git201502100126-lenny0"     -> "6.0.1.rc1.git201502100126",
    "not-init"                             -> "not" // well, not very interesting, but we had that at some point in rudder
  )

  val allTests: Seq[Spec[Any, Nothing]] = mapping.toList.map {
    case (source, res) =>
      test(res)(assertTrue(HomePageUtils.formatAgentVersion(source) == res))
  }

  override def spec: Spec[TestEnvironment with Scope, Any] = {
    suite("Agent version mapping must matches")(allTests)
  }

}
