/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.cfclerk.domain


import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._


/**
 * Test ordering on TechniqueCategoryId
 */
@RunWith(classOf[JUnitRunner])
class TechniqueCategoryIdTest extends Specification {

  /**
   * We are dealing with that tree:
   *     A
   *    / \
   *   B   G
   *  / \   \
   * C   D   H
   *    / \   \
   *   E   F   I
   *
   * The order is: A B C D E F G H I
   */

  val a = RootTechniqueCategoryId / "A"
  val b = a / "B"
  val c = a / "B" / "C"
  val d = a / "B" / "D"
  val e = a / "B" / "D" / "E"
  val f = a / "B" / "D" / "F"
  val g = a / "G"
  val h = a / "G" / "H"
  val i = a / "G" / "H" / "I"

  val tree = Set(a, b, c, d, e, f, g, h, i)

  "A string representation of a node" should {
    "be" in { a.toString === "/A" }
    "be" in { e.toString === "/A/B/D/E" }
  }

  "Two node build differently" should {
    "be equals" in {
      b === SubTechniqueCategoryId(TechniqueCategoryName("B"),
              SubTechniqueCategoryId(TechniqueCategoryName("A"), RootTechniqueCategoryId)
            )
    }
  }

  "A tree describe in the comment" should {
    "have 9 nodes" in {
      tree.size === 9
    }
  }

  "A path could be transformed to root" should {
    "when it is empty" in {
      TechniqueCategoryId.buildId("") === RootTechniqueCategoryId
    }

    "when it is only a slash" in {
      TechniqueCategoryId.buildId("/") === RootTechniqueCategoryId
    }

    "when it is only several slashes" in {
      TechniqueCategoryId.buildId("////") === RootTechniqueCategoryId
    }

    "when it is only blanck chars" in {
      TechniqueCategoryId.buildId(" ") === RootTechniqueCategoryId
      TechniqueCategoryId.buildId("\n") === RootTechniqueCategoryId
      TechniqueCategoryId.buildId("\t") === RootTechniqueCategoryId
      TechniqueCategoryId.buildId("  \n  \t") === RootTechniqueCategoryId
    }

    "when it is only several slashes and blank chars" in {
      TechniqueCategoryId.buildId(" /\n/  ") === RootTechniqueCategoryId
    }
  }

  "A path could be transformed to an id" should {
    "when it is root" in {
      TechniqueCategoryId.buildId("root") === RootTechniqueCategoryId / "root"
    }

    "when it is composed by several levels" in {
      TechniqueCategoryId.buildId("/a/b/c") ===
        RootTechniqueCategoryId / "a" / "b" / "c"
    }

    "when some level are blank and are ignored" in {
      TechniqueCategoryId.buildId("/a//b/ \t /c/") ===
        RootTechniqueCategoryId / "a" / "b" / "c"
    }
  }

}

