package com.normation.rudder.batch

import com.normation.utils.EnumLaws
import org.junit.runner.RunWith
import zio.test.Spec
import zio.test.ZIOSpecDefault
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class PolicyGenerationTriggerTest extends ZIOSpecDefault {
  private val validNames = Seq("all", "none", "onlyManual")
  override def spec: Spec[Any, Nothing] = EnumLaws.laws(PolicyGenerationTrigger, validNames)
}
