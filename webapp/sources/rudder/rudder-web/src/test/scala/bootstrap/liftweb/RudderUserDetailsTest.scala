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

import com.github.ghik.silencer.silent
import com.normation.errors.IOResult
import com.normation.rudder.AuthorizationType
import com.normation.rudder.CustomRoleResolverResult
import com.normation.rudder.Role
import com.normation.rudder.Role.NamedCustom
import com.normation.rudder.RudderRoles
import com.normation.rudder.UncheckedCustomRole
import com.normation.rudder.api.ApiAclElement
import com.normation.rudder.rest.AuthorizationApiMapping
import com.normation.rudder.rest.RoleApiMapping
import com.normation.zio._
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import org.specs2.specification.core.Fragments
import scala.xml.Elem

/*
 * Test hash algo for user password.
 */

@silent("a type was inferred to be `AnyVal`")
@RunWith(classOf[JUnitRunner])
class RudderUserDetailsTest extends Specification {

  implicit class ForceEither[A](iores: IOResult[A]) {
    def force: A = iores.either.runNow match {
      case Left(e)  =>
        throw new IllegalArgumentException(s"Error in test: that either was expected to be right but is left with value: ${e}")
      case Right(a) => a
    }
  }

  val roleApiMapping = new RoleApiMapping(new AuthorizationApiMapping {
    override def mapAuthorization(authz: AuthorizationType): List[ApiAclElement] = Nil
  })

  def getUserDetailList(xml: Elem, debugName: String, extendedAuthz: Boolean = true) =
    UserFileProcessing.parseXml(roleApiMapping, xml, debugName, extendedAuthz, false).force

  // also check that we accept both `role` and `roles` tags
  val userXML_1 = <authentication hash="sha512" case-sensitivity="true">
    <user name="admin" role="administrator" password="c7ad44cbad762a5da0a452f9e854fdc1e0e7a52a38015f23f3eab1d80b931dd472634dfac71cd34ebc35d16ab7fb8a90c81f975113d6c7538dc69dd8de9077ec"/>
    <user name="ADMIN" permissions="administrator" password="c7ad44cbad762a5da0a452f9e854fdc1e0e7a52a38015f23f3eab1d80b931dd472634dfac71cd34ebc35d16ab7fb8a90c81f975113d6c7538dc69dd8de9077ec"/>
  </authentication>

  val userXML_2 = <authentication hash="sha512" case-sensitivity="false">
    <user name="admin" permissions="administrator" password="c7ad44cbad762a5da0a452f9e854fdc1e0e7a52a38015f23f3eab1d80b931dd472634dfac71cd34ebc35d16ab7fb8a90c81f975113d6c7538dc69dd8de9077ec"/>
    <user name="ADMIN" role="administrator" password="c7ad44cbad762a5da0a452f9e854fdc1e0e7a52a38015f23f3eab1d80b931dd472634dfac71cd34ebc35d16ab7fb8a90c81f975113d6c7538dc69dd8de9077ec"/>
  </authentication>

  val userXML_empty = <authentication>
    <user name="admin" roles="administrator"/>
  </authentication>

  "simple file with case sensitivity should discern users with similar username" >> {
    val userDetailList = getUserDetailList(userXML_1, "userXML_1")

    (userDetailList.isCaseSensitive must beTrue) and
    (userDetailList.users.size must beEqualTo(2))
  }

  "simple file *without* case sensitivity should filter out ALL similar username" >> {
    val userDetailList = getUserDetailList(userXML_2, "userXML_2")

    (userDetailList.isCaseSensitive must beFalse) and
    (userDetailList.users.size must beEqualTo(0))
  }

  "an account without password field get a random 20 char pass" >> {
    val userDetailList = getUserDetailList(userXML_empty, "userXML_empty")

    (userDetailList.users.size must beEqualTo(1)) and (userDetailList.users("admin").getPassword.size must beEqualTo(20))
  }

  val userXML_3 = <authentication>
    <custom-roles>
      <role name="role_a1" permissions="ROLE_a0,roLE_A0"/>                    <!-- node_read,node_write,config_*,parameter_*,technique_*,directive_*,rule_* -->
      <role name="role_a0" permissions="node_read,node_write,configuration"/> <!-- node_read,node_write,config_*,parameter_*,technique_*,directive_*,rule_* -->

      <role name="ROLE_B0" permissions="inventory"/> <!-- node_read -->
      <role name="role_c0" permissions="rule_only"/> <!-- node_* -->

      <role name="role_d0" permissions="role_a1,ROLE_B0,role_c0"/>  <!-- node_*,config_*,parameter_*,technique_*,directive_*,rule_* -->

      <role name="role_e0" permissions="inventory,role_e0"/> <!-- error + role removed - self reference leads to nothing -->
      <role name="role_e1" permissions="role_e2"/>           <!-- error + role removed - mutual reference leads to nothing -->
      <role name="role_e2" permissions="role_e1"/>           <!-- error + role removed - mutual reference leads to nothing -->
      <role name="role_e3" permissions="role_e4,role_c0"/>   <!-- error + role removed - mutual reference leads to nothing -->
      <role name="role_e4" permissions="role_e3"/>           <!-- error + role removed - mutual reference leads to nothing -->
      <role name="role_e6" permissions="role_e5"/>           <!-- warn - non existing reference is ignored -->
      <role name="inventory" permissions="administrator"/>   <!-- error + role removed - already defined -->
    </custom-roles>

    <user name="admin" role="administrator" password="..."/>
    <user name="user_a0" password="..." permissions="inventory"/> <!-- node read -->
    <user name="user_a1" password="..." permissions="role_A1"/>   <!-- node_read,node_write,config_*,parameter_*,technique_*,directive_*,rule_* -->
  </authentication>

  "general rules around custom roles definition and error should be parsed correctly" >> {
    import AuthorizationType._
    val userDetailList = getUserDetailList(userXML_3, "userXML_3")

    val roleA0 = NamedCustom("role_a0", List(Role.forRight(Node.Read), Role.forRight(Node.Write), Role.Configuration))
    val roleA1 = NamedCustom("role_a1", List(roleA0))
    val roleB0 = NamedCustom("ROLE_B0", List(Role.Inventory))
    val roleC0 = NamedCustom("role_c0", List(Role.RuleOnly))

    val parsedRoles = {
      roleA0 ::
      roleA1 ::
      roleB0 ::
      roleC0 ::
      NamedCustom("role_d0", List(roleA1, roleB0, roleC0)) ::
      NamedCustom("role_e6", Nil) ::
      Nil
    }

    (userDetailList.isCaseSensitive must beTrue) and
    (userDetailList.users.size must beEqualTo(3)) and
    (userDetailList.customRoles must containTheSameElementsAs(parsedRoles)) and
    (userDetailList.users("user_a1").roles must containTheSameElementsAs(List(roleA1)))
  }

  /// role specific  unit tests

  "Unknown roles in user list are ignored but don't lead to no_right" >> {
    RudderRoles.parseRoles(List("configuration", "non-existing-role", "inventory")).runNow must beEqualTo(
      List(Role.Configuration, Role.Inventory)
    )
  }

  "if one role leads to NoRights, parseRoles is only one NoRights" >> {
    RudderRoles.parseRoles(List("configuration", "no_rights", "inventory")).runNow must beEqualTo(List(Role.NoRights))
  }

  "Administrator does not gibe additional roles but it give the special right 'any-rights'" >> {
    (RudderRoles.parseRoles(List("administrator")).runNow must beEqualTo(List(Role.Administrator))) and
    (Role.Administrator.rights.authorizationTypes.collect { case a if (a == AuthorizationType.AnyRights) => a } must beEqualTo(
      Set(AuthorizationType.AnyRights)
    ))
  }

  "We have reserved word for named custom role: authorization-like named are forbidden" >> {
    val crs        = List("any", "foo_all", "foo_read", "foo_write", "foo_edit").map(n => UncheckedCustomRole(n, Nil))
    val knownRoles = RudderRoles.getAllRoles.runNow
    Fragments.foreach(crs) { cr =>
      s"'${cr.name}' can not be part of a named custom role" >> {
        RudderRoles.resolveCustomRoles(List(cr), knownRoles).runNow must beEqualTo(
          CustomRoleResolverResult(
            Nil,
            List((cr, "'any' and patterns 'kind_[read,edit,write,all] are reserved that can't be used for a custom role"))
          )
        )
      }
    }
  }

}
