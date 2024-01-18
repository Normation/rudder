package com.normation.rudder.api

import com.normation.utils.EnumLaws
import zio.test.Spec
import zio.test.ZIOSpecDefault

object HttpActionTest extends ZIOSpecDefault {

  private val validNames = Seq(
    "HEAD",
    "GET",
    "PUT",
    "POST",
    "DELETE"
  )

  val spec: Spec[Any, Nothing] = suite("HttpAction")(
    EnumLaws.laws(HttpAction, validNames)
  )

}
