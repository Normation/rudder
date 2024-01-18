package com.normation.rudder

import com.normation.rudder.Role.Administrator
import com.normation.rudder.Role.NoRights
import zio.test.Spec
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object RoleSpec extends ZIOSpecDefault {

  val spec: Spec[Any, Nothing] = suite("RoleSpec")(
    test("values should list specialBuiltIn values")(
      assertTrue(Role.specialBuiltIn == Set[Role](Administrator, NoRights))
    )
  )
}
