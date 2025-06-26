/*
 *************************************************************************************
 * Copyright 2020 Normation SAS
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

package com.normation.rudder.api

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestApiData extends Specification {

  implicit class EitherGet[A](either: Either[String, A]) {
    def get: A = either match {
      case Left(err) => throw new RuntimeException(s"Error in test: ${err}")
      case Right(v)  => v
    }
  }

  "Sorting API" should {
    "correctly sort path of different size" in {

      val apis = {
        "/a/b/c" ::
        "/a/*" ::
        "/a" ::
        "/a/b/c/**" ::
        Nil
      }

      val expected = {
        "/a/b/c/**" ::
        "/a/b/c" ::
        "/a/*" ::
        "/a" ::
        Nil
      }

      apis.map(AclPath.parse(_).get).sorted(using AclPath.orderingaAclPath) must beEqualTo(expected.map(AclPath.parse(_).get))
    }
    "correctly sort path of same size" in {

      val apis = {
        "/something/path2" ::
        "/something/*" ::
        "/something/**" ::
        "/something/path1" ::
        Nil
      }

      val expected = {
        "/something/path1" ::
        "/something/path2" ::
        "/something/*" ::
        "/something/**" ::
        Nil
      }

      apis.map(AclPath.parse(_).get).sorted(using AclPath.orderingaAclPath) must beEqualTo(expected.map(AclPath.parse(_).get))
    }
  }

}
