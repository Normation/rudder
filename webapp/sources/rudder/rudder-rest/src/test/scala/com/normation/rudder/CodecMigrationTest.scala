package com.normation.rudder

import com.normation.rudder.CodecMigrationTest.Circle
import com.normation.rudder.CodecMigrationTest.Square
import com.normation.rudder.LiftJson
import zio.Tag
import zio.json.DeriveJsonCodec
import zio.json.EncoderOps
import zio.json.JsonCodec
import zio.test.*
import zio.test.Assertion.*
import zio.test.magnolia.DeriveGen

object CodecMigrationTest extends ZIOSpecDefault {

  sealed trait Shape
  case object Circle extends Shape
  case object Square extends Shape

  case class Point(x: Int, y: Int)
  case class Virgule(x: Int, y: Int)

  val genPoint:   Gen[Any, Point]   = DeriveGen[Point]
  val genVirgule: Gen[Any, Virgule] = DeriveGen[Virgule]

  val genShape: Gen[Any, Shape] = Gen.fromIterable(Seq(Square, Circle))

  given JsonCodec[Shape] = DeriveJsonCodec.gen

  given LiftJson[Shape] = shape => {
    shape match {
      case Circle => s""""Circle""""
      case Square => s""""Square""""
    }
  }

  given JsonCodec[Point] = DeriveJsonCodec.gen

  given LiftJson[Point] = point => s"""{"x":${point.x},"y":${point.y}}"""

  given JsonCodec[Virgule] = DeriveJsonCodec.gen

  given LiftJson[Virgule] = point => s"""{"x":${point.x},"y":${point.y}}"""

  def testMigrationOnBornedType[A](gen: Gen[Any, A])(using codec: JsonCodec[A], lift: LiftJson[A], tag: Tag[A]) = {
    test(s"${tag.tag.shortName} checkAll") {
      // checkAll // let's say we have a borned type: an enum, we want to test all values so we use checkAll (not to do on an int :D, but only on a borned set)
      checkAll(gen) { instance =>
        val liftJson = lift.encode(instance)
        val zioJson  = instance.toJson
        println(s"checkAll ${tag.tag.shortName} $liftJson should be equals to $zioJson")
        assert(liftJson)(equalTo(zioJson))
      }
    }
  }

  def testMigration[A](gen: Gen[Any, A])(using codec: JsonCodec[A], lift: LiftJson[A], tag: Tag[A]) = {
    suite(s"${tag.tag.shortName}")(
      test(s"check") {
        check(gen) { instance =>
          val liftJson = lift.encode(instance)
          val zioJson  = instance.toJson
          println(s"check ${tag.tag.shortName} $liftJson should be equals to $zioJson")
          assert(liftJson)(equalTo(zioJson))
        }
      },
      test(s"checkN") {
        // checkN // check n times the TestAspect.sample don't work anymore
        checkN(2)(gen) { instance =>
          val liftJson = lift.encode(instance)
          val zioJson  = instance.toJson
          println(s"checkN ${tag.tag.shortName} $liftJson should be equals to $zioJson")
          assert(liftJson)(equalTo(zioJson))
        }
      }
    )
  }

  val spec = suite("encoding with zio-json should output exactly the same json than with liftweb.json")(
    testMigration(genPoint),   // @@ TestAspect.samples(5),
    testMigration(genVirgule), // @@ TestAspect.samples(5),
    testMigrationOnBornedType(genShape)
  ) // @@ TestAspect.samples(5)
  // @@ TestAspect.ignore

}
