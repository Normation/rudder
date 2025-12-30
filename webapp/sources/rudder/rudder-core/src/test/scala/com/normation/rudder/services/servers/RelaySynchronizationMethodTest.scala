package com.normation.rudder.services.servers

import com.normation.utils.EnumLaws
import org.junit.runner.RunWith
import zio.test.Spec
import zio.test.ZIOSpecDefault
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class RelaySynchronizationMethodTest extends ZIOSpecDefault {
  private def validNames = Seq("classic", "rsync", "disabled")
  override def spec: Spec[Any, Any] = EnumLaws.laws(RelaySynchronizationMethod, validNames)
}
