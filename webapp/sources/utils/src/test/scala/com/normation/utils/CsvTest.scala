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

package com.normation.utils

import Csv.*
import org.junit.runner.RunWith
import zio.test.*
import zio.test.junit.ZTestJUnitRunner
import zio.test.magnolia.DeriveGen

@RunWith(classOf[ZTestJUnitRunner])
class CsvTest extends ZIOSpecDefault {
  import CsvTest.*

  override def spec: Spec[Any, Any] = suite("Csv")(
    testSimpleCaseClass,
    testNestedCaseClass
  )
}

opaque type Email = String
object Email   {
  def apply(s: String): Email = s
  given Csv[Email] = Csv.instance(a => a)
  given Csv.Header.One[Email] with {}
  given DeriveGen[Email] = summon[DeriveGen[String]]
}
object CsvTest {
  final case class Person(name: String, email: Email, address: Address) derives DeriveGen, Csv, Csv.Header
  final case class Address(city: String, zipCode: Int) derives DeriveGen, Csv, Csv.Header

  def testSimpleCaseClass: Spec[Any, Any] = {
    test("simple case class")(check(DeriveGen[Address])(a => {
      val csv      = List(a).toCsv
      val expected = s""""City","Zip Code"
                        |"${a.city}","${a.zipCode}"
                        |""".stripMargin
      assertTrue(csv == expected)
    }))
  }

  def testNestedCaseClass: Spec[Any, Any] = {
    test("nested case class")(check(DeriveGen[Person])(p => {
      val csv      = List(p).toCsv
      val expected = s""""Name","Email","City","Zip Code"
                        |"${p.name}","${p.email}","${p.address.city}","${p.address.zipCode}"
                        |""".stripMargin
      assertTrue(csv == expected)
    }))
  }

}
