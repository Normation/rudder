package com.normation.rudder.api

import com.normation.utils.EnumLaws
import zio.test.Spec
import zio.test.ZIOSpecDefault

object ApiAuthorizationKindTest extends ZIOSpecDefault {

  private val validNames = Seq(
    "none",
    "ro",
    "rw",
    "acl"
  )

  val spec: Spec[Any, Nothing] = suite("ApiAuthorizationKind")(
    EnumLaws.laws(ApiAuthorizationKind, validNames)
  )

}
