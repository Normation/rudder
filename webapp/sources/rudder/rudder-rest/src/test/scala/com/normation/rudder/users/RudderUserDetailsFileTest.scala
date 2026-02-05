/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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
package com.normation.rudder.users

import org.junit.runner.RunWith
import org.specs2.matcher.XmlMatchers
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class RudderUserDetailsFileTest extends Specification with XmlMatchers {

  // content of the XML file, with unsafe-hashes removed
  val userXml = <authentication  unsafe-hashes="true" hash="sha-1" case-sensitivity="true">
    <user name="user1" password="1234" permissions="user" tenants="zoneA" />
    <user name="user2" password="a94a8fe5ccb19ba61c4c0873d391e987982fbbd3" permissions="read_only" />
  </authentication>

  val expectedXml = <authentication hash="plop" case-sensitivity="true">
    <user name="user1" password="1234" permissions="user" tenants="zoneA" />
    <user name="user2" password="a94a8fe5ccb19ba61c4c0873d391e987982fbbd3" permissions="read_only" />
  </authentication>

  val exec = UserManagementIO.replaceXml(userXml, FileUserDetailListProvider.XmlMigrationRule("plop"), "testFile")

  "migration rule should work" >> {
    exec must beRight(beEqualToIgnoringSpace(expectedXml))
  }
}
