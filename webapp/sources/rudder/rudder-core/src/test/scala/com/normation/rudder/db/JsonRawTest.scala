/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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

package com.normation.rudder.db

import org.junit.runner.RunWith
import zio.json.*
import zio.test.*
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class JsonRawTest extends ZIOSpecDefault {

  case class Doc(name: String) derives JsonCodec

  // more documents than one chunk holds, so that the chunks really have to be put back in order
  val chunkSize = 3
  val docs: List[(Int, JsonRaw)] = (1 to 10).toList.map(i => (i, JsonRaw(s"""{"name":"doc-${i}"}""")))

  def spec: Spec[Any, Any] = {
    suite("Parsing json documents read raw from the database")(
      test("gives back every document with its own key, in order") {
        for {
          parsed <- JsonRaw.parseAllPar[Int, Doc, Doc](docs, chunkSize)(identity)
        } yield {
          assertTrue(parsed.size == docs.size) &&
          assertTrue(parsed.toList == (1 to 10).toList.map(i => (i, Doc(s"doc-${i}"))))
        }
      },
      test("applies the conversion to each document") {
        for {
          parsed <- JsonRaw.parseAllPar[Int, Doc, String](docs, chunkSize)(_.name)
        } yield assertTrue(parsed.toList.map(_._2) == (1 to 10).toList.map(i => s"doc-${i}"))
      },
      test("fails if one document cannot be parsed, like the database read would") {
        val withOneBad = docs.take(4) :+ ((99, JsonRaw("""{"name":42}""")))
        for {
          res <- JsonRaw.parseAllPar[Int, Doc, Doc](withOneBad, chunkSize)(identity).either
        } yield assertTrue(res.isLeft)
      },
      test("accepts having nothing to parse") {
        for {
          parsed <- JsonRaw.parseAllPar[Int, Doc, Doc](Nil, chunkSize)(identity)
        } yield assertTrue(parsed.isEmpty)
      }
    )
  }
}
