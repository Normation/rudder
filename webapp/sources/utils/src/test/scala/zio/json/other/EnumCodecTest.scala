/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
 *************************************************************************************
 *
 * This file is part of Rudder.
 *
 * Rudder is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * In accordance with the terms of section 7 (7. Additional Terms.) of
 * the GNU General Public License version 3, the copyright holders add
 * the following Additional permissions:
 * Notwithstanding to the terms of section 5 (5. Conveying Modified Source
 * Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
 * Public License version 3, when you create a Related Module, this
 * Related Module is not considered as a part of the work and may be
 * distributed under the license agreement of your choice.
 * A "Related Module" means a set of sources files including their
 * documentation that, without modification of the Source Code, enables
 * supplementary functions or services in addition to those offered by
 * the Software.
 *
 * Rudder is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

 *
 *************************************************************************************
 */
package zio.json.other

import enumeratum.*
import org.junit.*
import org.junit.runner.RunWith
import zio.*
import zio.json.*
import zio.json.enumeratum.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.junit.ZTestJUnitRunner

/*
 * It's absolutely mandatory to define that out of ZIOSpecDefault
 * because macro used in it breaks in cryptic way enumeratum "findValues" macro
 */
object Data {
  /*
   * exact derivation from the exact scala name.
   * Warning, unstable to refactoring
   */
  sealed trait A extends EnumEntry

  object A extends Enum[A] with EnumCodecCaseSensitive[A] {
    case object CaseOne extends A
    case object casetwo extends A

    override def values: IndexedSeq[A] = findValues
  }

  case class ContentA(a: A) derives JsonCodec

  /*
   * default derivation encode lower case, decode case insensitive.
   * (Use override entryName to be stable to refactoring)
   */
  sealed trait B(override val entryName: String) extends EnumEntry

  object B extends Enum[B] with EnumCodec[B] {
    case object CaseOne        extends B("CaseOne")
    case object CaseRefactored extends B("casetwo")

    override def values: IndexedSeq[B] = findValues
  }

  case class ContentB(a: B) derives JsonCodec
}

@RunWith(classOf[ZTestJUnitRunner])
class EnumCodecTest extends ZIOSpecDefault {

  import zio.json.other.Data.*

  val s1   = """{"a":"CaseOne"}"""
  val s1lc = """{"a":"caseone"}"""
  val s2   = """{"a":"casetwo"}"""
  val s2cc = """{"a":"CaseTwo"}"""

  def spec = suiteAll("encoding enum") {

    suite("Using scala case object name")(
      test("encode CaseOne") {
        assert(ContentA(A.CaseOne).toJson)(equalTo(s1))
      },
      test("decode CaseOne ok") {
        assert(s1.fromJson[ContentA])(isRight(equalTo(ContentA(A.CaseOne))))
      },
      test("fail decode caseone (lower case)") {
        assert(s1lc.fromJson[ContentA])(isLeft)
      },
      test("encode casetwo") {
        assert(ContentA(A.casetwo).toJson)(equalTo(s2))
      },
      test("decode casetwo ok") {
        assert(s2.fromJson[ContentA])(isRight(equalTo(ContentA(A.casetwo))))
      },
      test("fail decode CaseTwo (camel case)") {
        assert(s2cc.fromJson[ContentA])(isLeft)
      }
    )

    suite("Using scala case object name")(
      test("encode CaseOne as is") {
        assert(ContentB(B.CaseOne).toJson)(equalTo(s1))
      },
      test("decode CaseOne ok") {
        assert(s1.fromJson[ContentB])(isRight(equalTo(ContentB(B.CaseOne))))
      },
      test("decode caseone (lower case) ok") {
        assert(s1lc.fromJson[ContentB])(isRight(equalTo(ContentB(B.CaseOne))))
      },
      test("encode casetwo as is using entryName") {
        assert(ContentB(B.CaseRefactored).toJson)(equalTo(s2))
      },
      test("decode casetwo ok") {
        assert(s2.fromJson[ContentB])(isRight(equalTo(ContentB(B.CaseRefactored))))
      },
      test("decode CaseTwo (camel case) ok") {
        assert(s2cc.fromJson[ContentB])(isRight(equalTo(ContentB(B.CaseRefactored))))
      }
    )
  }
}
