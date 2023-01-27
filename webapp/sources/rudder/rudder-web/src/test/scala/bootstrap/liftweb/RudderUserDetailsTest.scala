/*
 *************************************************************************************
 * Copyright 2018 Normation SAS
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

package bootstrap.liftweb

import com.normation.rudder.AuthorizationType
import com.normation.rudder.api.ApiAclElement
import com.normation.rudder.rest.AuthorizationApiMapping
import com.normation.rudder.rest.RoleApiMapping
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

/*
 * Test hash algo for user password.
 */

@RunWith(classOf[JUnitRunner])
class RudderUserDetailsTest extends Specification {

  implicit class ForceEither[A, B](either: Either[A, B]) {
    def force: B = either match {
      case Left(a)  =>
        throw new IllegalArgumentException(s"Error in test: that either was expected to be right but is left with value: ${a}")
      case Right(b) => b
    }
  }

  val roleApiMapping = new RoleApiMapping(new AuthorizationApiMapping {
    override def mapAuthorization(authz: AuthorizationType): List[ApiAclElement] = Nil
  })

  val userXML_1 = <authentication hash="sha512" case-sensitivity="true">
    <user name="admin" role="administrator" password="c7ad44cbad762a5da0a452f9e854fdc1e0e7a52a38015f23f3eab1d80b931dd472634dfac71cd34ebc35d16ab7fb8a90c81f975113d6c7538dc69dd8de9077ec"/>
    <user name="ADMIN" role="administrator" password="c7ad44cbad762a5da0a452f9e854fdc1e0e7a52a38015f23f3eab1d80b931dd472634dfac71cd34ebc35d16ab7fb8a90c81f975113d6c7538dc69dd8de9077ec"/>
  </authentication>

  val userXML_2 = <authentication hash="sha512" case-sensitivity="false">
    <user name="admin" role="administrator" password="c7ad44cbad762a5da0a452f9e854fdc1e0e7a52a38015f23f3eab1d80b931dd472634dfac71cd34ebc35d16ab7fb8a90c81f975113d6c7538dc69dd8de9077ec"/>
    <user name="ADMIN" role="administrator" password="c7ad44cbad762a5da0a452f9e854fdc1e0e7a52a38015f23f3eab1d80b931dd472634dfac71cd34ebc35d16ab7fb8a90c81f975113d6c7538dc69dd8de9077ec"/>
  </authentication>

  "simple file with case sensitivity should discern users with similar username" >> {
    val userDetailList = UserFileProcessing.parseXml(roleApiMapping, userXML_1, "userXML_1", false).force

    (userDetailList.isCaseSensitive must beTrue) and
    (userDetailList.users.size must beEqualTo(2))
  }

  "simple file *without* case sensitivity should filter out ALL similar username" >> {
    val userDetailList = UserFileProcessing.parseXml(roleApiMapping, userXML_2, "userXML_2", false).force

    (userDetailList.isCaseSensitive must beFalse) and
    (userDetailList.users.size must beEqualTo(0))
  }
}
