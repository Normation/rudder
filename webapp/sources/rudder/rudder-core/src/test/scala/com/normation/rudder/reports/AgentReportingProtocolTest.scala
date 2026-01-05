package com.normation.rudder.reports

import com.normation.utils.EnumLaws
import org.junit.runner.RunWith
import zio.test.Spec
import zio.test.ZIOSpecDefault
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class AgentReportingProtocolTest extends ZIOSpecDefault {
  private val validNames = Seq("HTTPS")
  override def spec: Spec[Any, Any] = EnumLaws.laws(AgentReportingProtocol, validNames)
}
