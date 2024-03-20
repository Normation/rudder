package com.normation.inventory.domain

import zio.Scope
import zio.test.Spec
import zio.test.TestEnvironment
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object AgentTypeEnumTest extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("AgentType")(
    test("should never remove existing type for backward compatibility") {
      assertTrue(
        AgentType.values ==
        IndexedSeq(AgentType.CfeEnterprise, AgentType.CfeCommunity, AgentType.Dsc)
      )
    }
  )
}
