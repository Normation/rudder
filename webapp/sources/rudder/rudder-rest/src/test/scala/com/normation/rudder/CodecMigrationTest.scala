package com.normation.rudder

import zio.Tag
import zio.json.DeriveJsonCodec
import zio.json.EncoderOps
import zio.json.JsonCodec
import zio.test.*
import zio.test.Assertion.*
import zio.test.magnolia.DeriveGen

object CodecMigrationTest extends ZIOSpecDefault {

  case class Point(x: Int, y: Int)
  case class Virgule(x: Int, y: Int)

  val genPoint: Gen[Any, Point] = {
    for x <- Gen.int
    y     <- Gen.int
    yield Point(x, y)
  }
  val genVirgule: Gen[Any, Virgule] = {
    for x <- Gen.int
    y     <- Gen.int
    yield Virgule(x, y)
  }

  trait LiftJson[A] {
    def encode(a: A): String
  }

  given JsonCodec[Point] = DeriveJsonCodec.gen

  given LiftJson[Point] = point => s"""{"x":${point.x},"y":${point.y}}"""

  given JsonCodec[Virgule] = DeriveJsonCodec.gen

  given LiftJson[Virgule] = point => s"""{"x":${point.x},"y":${point.y}}"""

  def testMigration[A](gen: Gen[Any, A])(using codec: JsonCodec[A], lift: LiftJson[A], tag: Tag[A]) = {
    test(s"${tag.tag.shortName}") {
      check(gen) { instance =>

        val liftJson = lift.encode(instance)
        val zioJson = instance.toJson

        assert(liftJson)(equalTo(zioJson))
      }
    }
  }

  val spec = suite("encoding with zio-json should output exactly the same json than with liftweb.json")(
    testMigration(genPoint),
    testMigration(genVirgule),
  )

}
