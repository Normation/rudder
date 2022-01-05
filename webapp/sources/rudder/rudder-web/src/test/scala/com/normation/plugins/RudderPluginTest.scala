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

package com.normation.plugins

import com.normation.utils.ParseVersion

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class RudderPluginTest extends Specification {

  implicit class ForceParse(s: String) {
    def toVersion = ParseVersion.parse(s) match {
      case Left(err) => throw new IllegalArgumentException(s"Can not parse '${s}' as a version in test: ${err}")
      case Right(v)  => v
    }
  }

  "Parsing a plugin version" should {
    "be able to read simple rudder version" in {
      PluginVersion.from("7.1.0-2.3.0") must_!= (PluginVersion("7.1.0".toVersion, "2.3.0".toVersion))
    }
    "automatically add a patch level (eq 0)" in {
      PluginVersion.from("7.1-2.3") must_!= (PluginVersion("7.1.0".toVersion, "2.3.0".toVersion))
    }
    "understand complicated format with rc" in {
      PluginVersion.from("7.0.0~rc2-SNAPSHOT-2.1-nightly") must_!= (PluginVersion("7.0.0~rc2-SNAPSHOT".toVersion, "2.1.0-nightly".toVersion))
    }
  }
}
