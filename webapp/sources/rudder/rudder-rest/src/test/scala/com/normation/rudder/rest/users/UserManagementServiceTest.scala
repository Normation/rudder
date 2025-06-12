package com.normation.rudder.rest.users

import com.normation.rudder.AuthorizationType
import com.normation.rudder.Role
import com.normation.rudder.users.UserManagementService
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class UserManagementServiceTest extends Specification {

  "UserManagementService" should {
    "split permissions in roles and authz" in {
      val allRoles = Role.allBuiltInRoles.values.toSet
      val parsed   = UserManagementService.parsePermissions(
        Set("node_write", "node_read", "read_only", "some_unknown_permission")
      )(using allRoles)

      parsed must be equalTo (
        (
          Set(Role.allBuiltInRoles(Role.BuiltinName.ReadOnly.value)),
          Set(AuthorizationType.Node.Write),
          Set("some_unknown_permission")
        )
      )
    }
  }

}
