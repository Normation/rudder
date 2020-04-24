/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.cfclerk.domain

import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._

@RunWith(classOf[JUnitRunner])
class TechniqueVersionTest extends Specification {

  sequential

  "parse version" >> {
    import Separator._
    import PartType._
    import VersionPart._
    def parse(s: String) = ParseVersion.parse(s).getOrElse(throw new RuntimeException(s"Can not parse: ${s}"))

    parse("1.0") === Version(0, Numeric(1), After(Dot, Numeric(0)) :: Nil)
    parse("1.0-alpha10") === Version(0,
        Numeric(1)
      , After(Dot, Numeric(0)) ::
        Before(Minus, Alpha("alpha")) ::
        After(None, Numeric(10)) ::
        Nil
    )
    parse("1.1.0") === Version(0,
        Numeric(1)
      , After(Dot, Numeric(1)) ::
        After(Dot, Numeric(0)) ::
        Nil
    )
    parse("2.0") === Version(0,
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
    parse("1~~") === Version(0,
        Numeric(1)
      , Before(Tilde, Chars("")) ::
        Before(Tilde, Chars("")) ::
        Nil
    )
  }


  "Different sizes" should {
    increasingVersions("1.0", "1.0.1")
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

  "Equal versions" should {
    equalVersions("2:18ajk~pl~", "2:18ajk~pl~")
    equalVersions("0:7bf", "7bf")
    equalVersions("1.0", "1.0")
  }

  "Epoch" should {
    "not be printed when toString, if 0" in {
      TechniqueVersion("0:5abc~").toString === "5abc~"
    }

    "be printed when toString, if > 0" in {
      TechniqueVersion("2:2bce").toString === "2:2bce"
    }
  }

  "Invalid version" should {

    val msg1 = "The version format of a technique should be : [epoch:]upstream_version"
    "throw a TechniqueVersionFormatException : %s".format(msg1) in {
      TechniqueVersion("a:18") must throwA[TechniqueVersionFormatException].like { case e => e.getMessage must contain(msg1) }
    }

    val msg2 = "The upstream_version should start with a digit"
    "throw a TechniqueVersionFormatException : %s".format(msg2) in {
      TechniqueVersion("a15") must throwA[TechniqueVersionFormatException].like { case e => e.getMessage must contain(msg2) }
    }
  }

  private[this] def equalVersions(version1: String, version2: String) = {
    //the actual comparison test
    "be so that '%s' == '%s'".format(version1, version2) in {
      TechniqueVersion(version1) == TechniqueVersion(version2) must beTrue
    }
    "be so that '%s' > '%s' is false".format(version1, version2) in {
      TechniqueVersion(version1) > TechniqueVersion(version2) must beFalse
    }
    "be so that '%s' < '%s' is false".format(version1, version2) in {
      TechniqueVersion(version1) < TechniqueVersion(version2) must beFalse
    }
  }

  // test if version1 < version2
  private[this] def increasingVersions(version1: String, version2: String) = {
    //the actual comparison test
    "be so that '%s' < '%s'".format(version1, version2) in {
      TechniqueVersion(version1) < TechniqueVersion(version2) must beTrue
    }
    "be so that '%s' > '%s' is false".format(version1, version2) in {
      TechniqueVersion(version1) > TechniqueVersion(version2) must beFalse
    }
    "be so that '%s' == '%s' is false".format(version1, version2) in {
      TechniqueVersion(version1) == TechniqueVersion(version2) must beFalse
    }
  }
}

