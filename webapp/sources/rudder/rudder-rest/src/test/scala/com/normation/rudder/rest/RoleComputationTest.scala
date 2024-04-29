package com.normation.rudder.rest

import com.normation.rudder.AuthorizationType
import com.normation.rudder.Role
import com.normation.rudder.RudderRoles
import com.normation.rudder.users.UserManagementService.computeRoleCoverage
import com.normation.zio.*
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class RoleComputationTest extends Specification {
  def parseRoles(roles: List[String]) = RudderRoles.parseRoles(roles).runNow.toSet

  "Computation of Role Coverage over Rights" should {

    "return 'None' when parameters are empty" in {
      (computeRoleCoverage(Set(Role.allBuiltInRoles(Role.BuiltinName.User.value)), Set()) must beNone) and
      (computeRoleCoverage(Set(), Set(AuthorizationType.Compliance.Read)) must beNone) and
      (computeRoleCoverage(Set(), Set()) must beNone)
    }

    "return 'None' when authzs contains no_rights" in {
      (computeRoleCoverage(
        Set(Role.allBuiltInRoles(Role.BuiltinName.User.value)),
        Set(AuthorizationType.NoRights)
      ) must beNone) and
      (computeRoleCoverage(
        Set(Role.allBuiltInRoles(Role.BuiltinName.User.value)),
        Set(AuthorizationType.NoRights) ++ AuthorizationType.allKind
      ) must beNone)
    }

    "return a 'Custom' role for empty intersection" in {
      computeRoleCoverage(
        Set(Role.allBuiltInRoles(Role.BuiltinName.User.value)),
        Set(AuthorizationType.Compliance.Read)
      ) must beEqualTo(
        Some(Set(Role.forRight(AuthorizationType.Compliance.Read)))
      )
    }

    "contains 'Inventory' and 'Custom' roles" in {
      computeRoleCoverage(
        Role.allBuiltInRoles.values.toSet,
        Set(AuthorizationType.Compliance.Read) ++ Role.allBuiltInRoles(Role.BuiltinName.Inventory.value).rights.authorizationTypes
      ) must beEqualTo(
        Some(Set(Role.allBuiltInRoles(Role.BuiltinName.Inventory.value), Role.forRight(AuthorizationType.Compliance.Read)))
      )
    }

    "only detect 'Inventory' role" in {
      computeRoleCoverage(
        Set(Role.allBuiltInRoles(Role.BuiltinName.Inventory.value)),
        Role.allBuiltInRoles(Role.BuiltinName.Inventory.value).rights.authorizationTypes
      ) must beEqualTo(Some(Set(Role.allBuiltInRoles(Role.BuiltinName.Inventory.value))))
    }

    "only detect one custom role" in { // why ?
      val a: Set[AuthorizationType] = Set(
        AuthorizationType.UserAccount.Read,
        AuthorizationType.UserAccount.Write,
        AuthorizationType.UserAccount.Edit
      )
      computeRoleCoverage(
        Set(Role.allBuiltInRoles(Role.BuiltinName.User.value), Role.allBuiltInRoles(Role.BuiltinName.Inventory.value)),
        a
      ) must beEqualTo(Some(Set(Role.forRights(a))))
    }

    "return administrator " in {
      computeRoleCoverage(
        Role.allBuiltInRoles.values.toSet,
        AuthorizationType.allKind
      ) must beEqualTo(Some(Set(Role.Administrator)))
    }

    "allows intersection between know roles" in {
      computeRoleCoverage(
        Set(Role.allBuiltInRoles(Role.BuiltinName.Inventory.value), Role.allBuiltInRoles(Role.BuiltinName.User.value)),
        Role
          .allBuiltInRoles(Role.BuiltinName.User.value)
          .rights
          .authorizationTypes ++ Role.allBuiltInRoles(Role.BuiltinName.Inventory.value).rights.authorizationTypes
      ) must beEqualTo(
        Some(Set(Role.allBuiltInRoles(Role.BuiltinName.User.value), Role.allBuiltInRoles(Role.BuiltinName.Inventory.value)))
      )
    }

    "ignore NoRights role" in {
      computeRoleCoverage(
        Set(Role.NoRights, Role.allBuiltInRoles(Role.BuiltinName.User.value)),
        Role.allBuiltInRoles(Role.BuiltinName.User.value).rights.authorizationTypes
      ) must beEqualTo(Some(Set(Role.allBuiltInRoles(Role.BuiltinName.User.value))))
    }

    "compute inventory read_only role with a rule_only right added" in {
      computeRoleCoverage(
        Role.allBuiltInRoles.values.toSet,
        Role
          .allBuiltInRoles(Role.BuiltinName.Inventory.value)
          .rights
          .authorizationTypes ++
        Role.allBuiltInRoles(Role.BuiltinName.ReadOnly.value).rights.authorizationTypes
      ) must beEqualTo(
        Some(
          Set(
            Role.allBuiltInRoles(Role.BuiltinName.Inventory.value),
            Role.allBuiltInRoles(Role.BuiltinName.ReadOnly.value),
            Role.allBuiltInRoles(Role.BuiltinName.RuleOnly.value)
          )
        )
      )
    }
  }
}
