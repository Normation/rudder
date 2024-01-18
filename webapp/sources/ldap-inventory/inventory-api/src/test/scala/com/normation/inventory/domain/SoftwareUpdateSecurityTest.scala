package com.normation.inventory.domain

import com.normation.utils.EnumLaws
import zio.test.Spec
import zio.test.ZIOSpecDefault

object SoftwareUpdateSecurityTest extends ZIOSpecDefault {
  private val validNames = {
    Seq(
      "low",
      "moderate",
      "high",
      "critical"
    )
  }

  override def spec: Spec[Any, Nothing] = {
    suite("SoftwareUpdateSeverity")(
      EnumLaws.laws(SoftwareUpdateSeverity, validNames)
    )
  }
}
