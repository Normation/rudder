package com.normation.utils

import izumi.reflect.Tag
import java.util.Locale
import zio.test.Assertion.*
import zio.test.Gen
import zio.test.Spec
import zio.test.assert
import zio.test.check
import zio.test.suite
import zio.test.test

object EnumLaws {
  import scala.reflect.Selectable.reflectiveSelectable

  def laws[A <: { def parse(s: String): Either[String, Any]; def values: Iterable[Any] }](
      `enum`:     A,
      validNames: Seq[String]
  )(implicit name: Tag[A]): Spec[Any, Nothing] = {
    val caseGen = Gen.elements[String => String](_.toLowerCase(Locale.US), _.toUpperCase(Locale.US))

    val validNameGen = Gen.elements(validNames*).flatMap(name => caseGen.map(_.apply(name)))

    val invalidNameGen = Gen.oneOf(Gen.string).filterNot(name => validNames.contains(name.toLowerCase(Locale.US)))

    suite(s"enum laws for ${name.tag}")(
      test("parse should be able to parse valid known entries regardless of the case")(
        check(validNameGen) { name =>
          val actual = `enum`.parse(name)
          assert(actual)(isRight(anything))
        }
      ),
      test("parse should be return Left for any unknown name")(
        check(invalidNameGen) { name =>
          val actual = `enum`.parse(name)
          assert(actual)(isLeft(anything))
        }
      ),
      test("parse error should provide possible values")(
        check(invalidNameGen) { name =>
          val actual = `enum`.parse(name)
          assert(actual)(isLeft(validNames.foldLeft(isNonEmptyString)(_ && containsString(_))))
        }
      ),
      test("values should return as many entries as validNames")(
        assert(`enum`.values)(hasSize(equalTo(validNames.length)))
      )
    )
  }
}
