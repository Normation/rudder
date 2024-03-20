package com.normation.rudder.domain.nodes

import com.normation.utils.EnumLaws
import zio.test.*

object NodeStateTest extends ZIOSpecDefault {

  private val validNames = Seq(
    "enabled",
    "ignored",
    "empty-policies",
    "initializing",
    "preparing-eol"
  )

  val spec: Spec[Any, Nothing] = suite("NodeState")(
    EnumLaws.laws(NodeState, validNames),
    test("labeledPairs should be backward compatible")(
      assertTrue(
        NodeState.labeledPairs ==
        List(
          NodeState.Initializing  -> "node.states.initializing",
          NodeState.Enabled       -> "node.states.enabled",
          NodeState.EmptyPolicies -> "node.states.empty-policies",
          NodeState.Ignored       -> "node.states.ignored",
          NodeState.PreparingEOL  -> "node.states.preparing-eol"
        )
      )
    )
  )

}
