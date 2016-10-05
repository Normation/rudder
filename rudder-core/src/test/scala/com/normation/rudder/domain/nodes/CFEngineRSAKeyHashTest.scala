/*
*************************************************************************************
* Copyright 2016 Normation SAS
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

package com.normation.rudder.domain.nodes

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner._
import net.liftweb.common._
import com.normation.inventory.domain.PublicKey

@RunWith(classOf[JUnitRunner])
class CFEngineRSAKeyHashTest extends Specification with Loggable {

  val key = PublicKey("""
-----BEGIN RSA PUBLIC KEY-----
MIIBCAKCAQEAv76gYG9OaFpc0eBeUXDM3WsRWyuHco3DpWnKrrpqQwylpEH26gRb
cu/L5KWc1ihj1Rv/AU3dkQL5KdXatSrWOLUMmYcQc5DYSnZacbdHIGLn11w1PHsw
9P2pivwQyIF3k4zqANtlZ3iZN4AXZpURI4VVhiBYPwZ4XgHPibcuJHiyNyymiHpT
HX9H0iaEIwyJMPjzRH+piFRmSeUylHfQLqb6AkD3Dg3Nxe9pbxNbk1saqgHFF4kd
Yh3O5rVto12XqisGWIbsmsT0XFr6V9+/sde/lpjI4AEcHR8oFYX5JP9/SXPuRJfQ
lEl8vn5PHTY0mMrNAcM7+rzpkOW2c7b8bwIBIw==
-----END RSA PUBLIC KEY-----
""")

  val expected = "8d3270d42486e8d6436d06ed5cc5034f"

  "Producing the magic MD5 hash" should {
    "give the same result has CFEngine" in {

      CFEngineKey.getHash(key) must beEqualTo(Full(expected))
    }
    }
}
