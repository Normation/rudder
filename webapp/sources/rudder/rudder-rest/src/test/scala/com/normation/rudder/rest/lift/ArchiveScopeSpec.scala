package com.normation.rudder.rest.lift

import com.normation.rudder.rest.lift.ArchiveScope.*
import zio.test.Assertion.*
import zio.test.Spec
import zio.test.ZIOSpecDefault
import zio.test.assert

object ArchiveScopeSpec extends ZIOSpecDefault {

  val spec: Spec[Any, Nothing] = suite("ArchiveScopeSpec")(
    test("parse error should contains possible values sorted by name")(
      assert(ArchiveScope.parse("unsupported"))(
        isLeft(
          containsString(
            Seq(AllDep, Directives, Groups, NoDep, Techniques).mkString(", ")
          )
        )
      )
    )
  )

}
