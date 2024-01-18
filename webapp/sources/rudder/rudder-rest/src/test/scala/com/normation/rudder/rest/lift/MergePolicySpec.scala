package com.normation.rudder.rest.lift

import com.normation.rudder.rest.lift.MergePolicy.KeepRuleGroups
import com.normation.rudder.rest.lift.MergePolicy.OverrideAll
import zio.test.*
import zio.test.Assertion.*

object MergePolicySpec extends ZIOSpecDefault {

  val spec: Spec[Any, Nothing] = suite("foo")(
    test("parse error should contains possible values sorted by name")(
      assert(MergePolicy.parse("unsupported"))(
        isLeft(
          containsString(
            Seq(KeepRuleGroups, OverrideAll).mkString(", ")
          )
        )
      )
    )
  )

}
