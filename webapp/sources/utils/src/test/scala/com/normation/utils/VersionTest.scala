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

package com.normation.utils

import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._

@RunWith(classOf[JUnitRunner])
class VersionTest extends Specification {

  def forceParse(s: String) = {
    ParseVersion.parse(s) match {
      case Left(err) => throw new IllegalArgumentException(s"Error in test when parsing version '${s}': ${err}")
      case Right(v)  => v
    }
  }

  sequential

  "parse version" >> {
    import PartType._
    import Separator._
    import VersionPart._
    def parse(s: String) = ParseVersion.parse(s).getOrElse(throw new RuntimeException(s"Can not parse: ${s}"))

    parse("1.0") === com.normation.utils.Version(0, Numeric(1), After(Dot, Numeric(0)) :: Nil)
    parse("1.0-alpha10") === com.normation.utils.Version(0,
        Numeric(1)
      , After(Dot, Numeric(0)) ::
        Before(Minus, Alpha("alpha")) ::
        After(None, Numeric(10)) ::
        Nil
    )
    parse("1.1.0") === com.normation.utils.Version(0,
        Numeric(1)
      , After(Dot, Numeric(1)) ::
        After(Dot, Numeric(0)) ::
        Nil
    )
    parse("2.0") === com.normation.utils.Version(0,
        Numeric(2)
      , After(Dot, Numeric(0)) ::
        Nil
    )
    parse("1~~a") === Version(0,
        Numeric(1)
      , Before(Tilde, Chars("")) ::
        Before(Tilde, Chars("a")) ::
        Nil
    )
    parse("1~~") === com.normation.utils.Version(0,
        Numeric(1)
      , Before(Tilde, Chars("")) ::
        Before(Tilde, Chars("")) ::
        Nil
    )
  }

  "Equal versions" should {
    equalVersions("2:18ajk~pl~", "2:18ajk~pl~")
    equalVersions("1.0", "1.0")
    equalVersions("0:7bf", "7bf")
  }

  "Different sizes" should {
    increasingVersions("1.0", "1.0.1")
    increasingVersions("1.0", "1.0.0.0.0")
    increasingVersions("1", "1.0.0.0.0.0.1")
  }

  "numbered preversion" should {
    increasingVersions("1.0-alpha5", "1.0-alpha10")
    increasingVersions("1.0-SNAPSHOT", "1.0-alpha1")
    increasingVersions("1.0-beta1", "1.0-rc1")
  }

  "preversion" should {
    increasingVersions("1.0-alpha5", "1.0")
    increasingVersions("1.0-SNAPSHOT", "1.0")
    increasingVersions("1.0-beta1", "1.0")
    increasingVersions("1.0~anything", "1.0")
    increasingVersions("1.0~1", "1.0")
  }

  "number and char without separators" should {
    increasingVersions("1.release3", "1.release10")
    increasingVersions("1.3b", "1.10a")
  }

  "Two clearly differents versions" should {
    increasingVersions("0:1~0", "2.2.0~beta1")
    increasingVersions("0:1~1", "1:0")
    increasingVersions("1.0", "1.1")
    increasingVersions("1.0", "1.0.1")
    increasingVersions("1.1.0", "2.0")
  }

  "Slighty increasing versions after ~" should {
    increasingVersions("1~~", "1~~a")
    increasingVersions("1~~a", "1~")
    increasingVersions("1~", "1")
    increasingVersions("1", "1a")
  }

  "Epoch" should {
    "not be printed when toString, if 0" in {
      forceParse("0:5abc~").toVersionString === "5abc~"
    }

    "be printed when toString, if > 0" in {
      forceParse("2:2bce").toVersionString === "2:2bce"
    }
  }

  "Invalid version" should {

    val msg1 = "Error when parsing 'a:18' as a version. Only ascii (non-control, non-space) chars are allowed in a version string."
    "return left".format(msg1) in {
      ParseVersion.parse("a:18") must beLeft[String].like { case e => e must contain(msg1) }
    }

    val msg2 = "Error when parsing 'a15' as a version. Only ascii (non-control, non-space) chars are allowed in a version string."
    "return left".format(msg2) in {
      ParseVersion.parse("a15") must beLeft[String].like { case e => e must contain(msg2) }
    }
  }

  private[this] def equalVersions(version1: String, version2: String) = {
    //the actual comparison test
    "be so that '%s' == '%s'".format(version1, version2) in {
      forceParse(version1) == forceParse(version2) must beTrue
    }
    "be so that '%s' > '%s' is false".format(version1, version2) in {
      forceParse(version1) > forceParse(version2) must beFalse
    }
    "be so that '%s' < '%s' is false".format(version1, version2) in {
      forceParse(version1) < forceParse(version2) must beFalse
    }
  }

  // test if version1 < version2
  private[this] def increasingVersions(version1: String, version2: String) = {
    //the actual comparison test
    "be so that '%s' < '%s'".format(version1, version2) in {
      forceParse(version1) < forceParse(version2) must beTrue
    }
    "be so that '%s' > '%s' is false".format(version1, version2) in {
      forceParse(version1) > forceParse(version2) must beFalse
    }
    "be so that '%s' == '%s' is false".format(version1, version2) in {
      forceParse(version1) == forceParse(version2) must beFalse
    }
  }
}

