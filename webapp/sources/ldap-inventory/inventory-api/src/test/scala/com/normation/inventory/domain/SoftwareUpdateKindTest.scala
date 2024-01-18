package com.normation.inventory.domain

import com.normation.utils.EnumLaws
import zio.test.Spec
import zio.test.ZIOSpecDefault

object SoftwareUpdateKindTest extends ZIOSpecDefault {
  private val validNames = {
    Seq(
      "none",
      "defect",
      "security",
      "enhancement"
    )
  }
  override def spec: Spec[Any, Nothing] = {
    suite("SoftwareUpdateKind")(
      EnumLaws.laws(SoftwareUpdateKind, validNames)
    )
  }
}
