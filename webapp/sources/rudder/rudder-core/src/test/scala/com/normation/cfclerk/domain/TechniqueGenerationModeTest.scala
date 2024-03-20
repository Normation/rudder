package com.normation.cfclerk.domain

import com.normation.utils.EnumLaws
import zio.test.Spec
import zio.test.ZIOSpecDefault

object TechniqueGenerationModeTest extends ZIOSpecDefault {

  private val validNames = Seq(
    "merged",
    "separated",
    "separated-with-parameters"
  )

  val spec: Spec[Any, Nothing] = suite("TechniqueGenerationMode")(
    EnumLaws.laws(TechniqueGenerationMode, validNames)
  )

}
