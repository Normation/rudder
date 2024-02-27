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

import com.normation.errors.IOResult
import com.normation.rudder.ActionType
import com.normation.rudder.AuthorizationType
import com.normation.rudder.CustomRoleResolverResult
import com.normation.rudder.Rights
import com.normation.rudder.Role
import com.normation.rudder.Role.Builtin
import com.normation.rudder.Role.BuiltinName
import com.normation.rudder.Role.NamedCustom
import com.normation.rudder.RudderRoles
import com.normation.rudder.UncheckedCustomRole
import com.normation.rudder.api.ApiAclElement
import com.normation.rudder.facts.nodes.NodeSecurityContext
import com.normation.rudder.rest.AuthorizationApiMapping
import com.normation.rudder.rest.RoleApiMapping
import com.normation.rudder.tenants.TenantId
import com.normation.zio._
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import org.specs2.specification.core.Fragments
import scala.annotation.nowarn
import scala.xml.Elem
import zio.Chunk

/*
 * Test hash algo for user password.
 */

@nowarn("msg=a type was inferred to be `AnyVal`")
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

  // org.slf4j.LoggerFactory.getLogger("application.authorization").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)

  def getUserDetailList(xml: Elem, debugName: String, extendedAuthz: Boolean = true): ValidatedUserList =
    UserFileProcessing.parseXml(roleApiMapping, xml, debugName, extendedAuthz, false).force

  // also check that we accept both `role` and `roles` tags
  val userXML_1: Elem = <authentication hash="sha512" case-sensitivity="true">
    <user name="admin" role="administrator" password="c7ad44cbad762a5da0a452f9e854fdc1e0e7a52a38015f23f3eab1d80b931dd472634dfac71cd34ebc35d16ab7fb8a90c81f975113d6c7538dc69dd8de9077ec"/>
    <user name="ADMIN" permissions="administrator" password="c7ad44cbad762a5da0a452f9e854fdc1e0e7a52a38015f23f3eab1d80b931dd472634dfac71cd34ebc35d16ab7fb8a90c81f975113d6c7538dc69dd8de9077ec"/>
  </authentication>

  val userXML_2: Elem = <authentication hash="sha512" case-sensitivity="false">
    <user name="admin" permissions="administrator" password="c7ad44cbad762a5da0a452f9e854fdc1e0e7a52a38015f23f3eab1d80b931dd472634dfac71cd34ebc35d16ab7fb8a90c81f975113d6c7538dc69dd8de9077ec"/>
    <user name="ADMIN" role="administrator" password="c7ad44cbad762a5da0a452f9e854fdc1e0e7a52a38015f23f3eab1d80b931dd472634dfac71cd34ebc35d16ab7fb8a90c81f975113d6c7538dc69dd8de9077ec"/>
  </authentication>

  val userXML_empty: Elem = <authentication>
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

  "an account without password field get a random 32 chars pass" >> {
    val userDetailList = getUserDetailList(userXML_empty, "userXML_empty")

    (userDetailList.users.size must beEqualTo(1)) and (userDetailList.users("admin").getPassword.size must beEqualTo(32))
  }

  val userXML_3: Elem = <authentication>
    <custom-roles>
      <role name="role-a1" permissions="ROLE-a0,roLE-A0"/>                    <!-- node_read,node_write,config_*,parameter_*,technique_*,directive_*,rule_* -->
      <role name="role-a0" permissions="node_read,node_write,configuration"/> <!-- node_read,node_write,config_*,parameter_*,technique_*,directive_*,rule_* -->
      <role name="role-a2" permissions="plugin"/> <!-- node_read,node_write,config_*,parameter_*,technique_*,directive_*,rule_* -->

      <role name="ROLE-B0" permissions="inventory"/> <!-- node_read -->
      <role name="role-c0" permissions="rule_only"/> <!-- node_* -->
      <role name="role-c1" permissions="rule_only,cve_read"/> <!--valid permissions but not loaded: should be ignored -->
      <role name="role-c2" permissions="cve_read"/> <!--valid perm not loaded: role doesn't have any right -->

      <role name="role-d0" permissions="role-a1,ROLE-B0,role-c0"/>  <!-- node_*,config_*,parameter_*,technique_*,directive_*,rule_* -->

      <role name="role-e0" permissions="inventory,role-e0"/> <!-- error + role removed - self reference leads to nothing -->
      <role name="role-e1" permissions="role-e2"/>           <!-- error + role removed - mutual reference leads to nothing -->
      <role name="role-e2" permissions="role-e1"/>           <!-- error + role removed - mutual reference leads to nothing -->
      <role name="role-e3" permissions="role-e4,role-c0"/>   <!-- error + role removed - mutual reference leads to nothing -->
      <role name="role-e4" permissions="role-e3"/>           <!-- error + role removed - mutual reference leads to nothing -->
      <role name="role-e6" permissions="role-e5"/>           <!-- warn - non existing reference is ignored -->
      <role name="inventory" permissions="administrator"/>   <!-- error + role removed - already defined -->
    </custom-roles>

    <user name="admin" role="administrator" password="..."/>
    <user name="user_a0" password="..." permissions="inventory"/> <!-- node read -->
    <user name="user_a1" password="..." permissions="role-A1"/>   <!-- node_read,node_write,config_*,parameter_*,technique_*,directive_*,rule_* -->
  </authentication>

  "general rules around custom roles definition and error should be parsed correctly" >> {
    import AuthorizationType._

    // add a plugin built-in role and check it is available too

    sealed trait PluginAuth extends AuthorizationType { def authzKind = "pluginAuth" }
    object PluginAuth {
      final case object Read  extends PluginAuth with ActionType.Read with AuthorizationType
      final case object Edit  extends PluginAuth with ActionType.Edit with AuthorizationType
      final case object Write extends PluginAuth with ActionType.Write with AuthorizationType
      def values: Set[AuthorizationType] = Set(Read, Edit, Write)
    }
    val pluginRole = Builtin(BuiltinName.PluginRoleName("plugin"), Rights(PluginAuth.values))
    RudderRoles.registerBuiltin(pluginRole).force

    val userDetailList = getUserDetailList(userXML_3, "userXML_3")

    val roleA0 = NamedCustom(
      "role-a0",
      List(Role.forRight(Node.Read), Role.forRight(Node.Write), Role.allBuiltInRoles(Role.BuiltinName.Configuration.value))
    )
    val roleA1 = NamedCustom("role-a1", List(roleA0))
    val roleA2 = NamedCustom("role-a2", List(pluginRole))
    val roleB0 = NamedCustom("ROLE-B0", List(Role.allBuiltInRoles(Role.BuiltinName.Inventory.value)))
    val roleC0 = NamedCustom("role-c0", List(Role.allBuiltInRoles(Role.BuiltinName.RuleOnly.value)))
    val roleC1 = NamedCustom("role-c1", List(Role.allBuiltInRoles(Role.BuiltinName.RuleOnly.value)))
    val roleC2 = NamedCustom("role-c2", List())

    val parsedRoles = {
      roleA0 ::
      roleA1 ::
      roleA2 ::
      roleB0 ::
      roleC0 ::
      roleC1 ::
      roleC2 ::
      NamedCustom("role-d0", List(roleA1, roleB0, roleC0)) ::
      NamedCustom("role-e6", Nil) ::
      Nil
    }

    (userDetailList.isCaseSensitive must beTrue) and
    (userDetailList.users.size must beEqualTo(3)) and
    (userDetailList.customRoles must containTheSameElementsAs(parsedRoles)) and
    (userDetailList.users("user_a1").roles must containTheSameElementsAs(List(roleA1))) and
    (roleC2.rights must beEqualTo(Role.NoRights.rights))
  }

  /// role specific  unit tests

  "Unknown roles in user list are ignored but don't lead to no_right" >> {
    RudderRoles.parseRoles(List("configuration", "non-existing-role", "inventory")).runNow must beEqualTo(
      List(Role.allBuiltInRoles(Role.BuiltinName.Configuration.value), Role.allBuiltInRoles(Role.BuiltinName.Inventory.value))
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

  "In definition of tenants, we" should {
    val tenantXML_1 = <authentication hash="sha512" case-sensitivity="true">
      <!-- single tenants -->
      <user name="user_single" role="administrator" tenants="zoneA"/>
      <!-- multiple tenants -->
      <user name="user_multi" role="administrator" tenants="zoneA, zoneB"/>
      <!-- compat: access to all -->
      <user name="user_all_compat" role="administrator" />
      <!-- explicit access to all + check merge -->
      <user name="user_all_explicit" role="administrator" tenants="*,zoneA" />
      <!-- no tenant: none -->
      <user name="user_empty_list" role="administrator" tenants="" />
      <!-- explicit none, win over everything -->
      <user name="user_none_explicit" role="administrator" tenants="-, *, zoneA"/>
      <!-- non alnum are ignored -->
      <user name="user_ascii" role="administrator" tenants="zoneA, @reza\,,"/>
    </authentication>

    val userDetailList = getUserDetailList(tenantXML_1, "tenantXML_1")

    "be able to define one tenants" in {
      userDetailList.users("user_single").nodePerms === NodeSecurityContext.ByTenants(Chunk(TenantId("zoneA")))
    }

    "be able to define a list of tenants" in {
      userDetailList.users("user_multi").nodePerms === NodeSecurityContext.ByTenants(Chunk(TenantId("zoneA"), TenantId("zoneB")))
    }

    "have no tenants attribute means ALL for compat reason" in {
      userDetailList.users("user_all_compat").nodePerms === NodeSecurityContext.All
    }

    "have explicit '*' means ALL" in {
      userDetailList.users("user_all_explicit").nodePerms === NodeSecurityContext.All
    }

    "have an empty list means NONE" in {
      userDetailList.users("user_empty_list").nodePerms === NodeSecurityContext.None
    }

    "have explicit '-' means NONE" in {
      userDetailList.users("user_none_explicit").nodePerms === NodeSecurityContext.None
    }

    "only have access to sane ascii identifier" in {
      userDetailList.users("user_ascii").nodePerms === NodeSecurityContext.ByTenants(Chunk(TenantId("zoneA")))
    }
  }

}
