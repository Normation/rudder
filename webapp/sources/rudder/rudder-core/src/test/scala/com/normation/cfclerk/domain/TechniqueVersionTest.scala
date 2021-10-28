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

import com.normation.GitVersion.Revision
import com.normation.utils.ParseVersion
import com.normation.utils.PartType
import com.normation.utils.Separator
import com.normation.utils.Version
import com.normation.utils.VersionPart

import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._

@RunWith(classOf[JUnitRunner])
class TechniqueVersionTest extends Specification {
  sequential

  "technique version are simple" should {
    TechniqueVersion.parse("1.0-alpha5") must beLeft()
    TechniqueVersion.parse("1.0-SNAPSHOT") must beLeft()
    TechniqueVersion.parse("1.0-beta1") must beLeft()
    TechniqueVersion.parse("1.0~anything") must beLeft()
    TechniqueVersion.parse("1.0~1") must beLeft()
    TechniqueVersion.parse("1.a") must beLeft()
    TechniqueVersion.parse("1:a") must beLeft()
    TechniqueVersion.parse("1.0") must beRight()
    TechniqueVersion.parse("4.8.1.5") must beRight()
    val v = TechniqueVersion(Version(0, PartType.Numeric(1), VersionPart.After(Separator.Dot, PartType.Numeric(0)) :: Nil), Revision("21dae29cc95a8b492325a0fb7625eac07fae3868"))
    TechniqueVersion.parse("1.0+21dae29cc95a8b492325a0fb7625eac07fae3868") must beRight(v.getOrElse(throw new Exception("error in test init")))
  }

}

