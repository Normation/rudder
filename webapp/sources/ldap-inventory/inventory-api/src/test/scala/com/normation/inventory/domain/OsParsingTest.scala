/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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
package com.normation.inventory.domain

import zio.*
import zio.test.*
import zio.test.Assertion.*

object OsParsingTest extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("all os")(
    // for windows, we look at the <OPERATINGSYSTEM><FULL_NAME> tag
    suite("Windows OS parsing") {
      import com.normation.inventory.domain.WindowsType.*
      Chunk(
        test("windows 2008") {
          assert(parseFromInventory("Microsoft Windows Server 2008 Standard"))(equalTo(Windows2008))
        },
        test("windows 2008 r2") {
          assert(parseFromInventory("Microsoft Windows Server 2008 R2 Standard"))(equalTo(Windows2008R2))
        },
        test("windows 2016 r2") {
          assert(parseFromInventory("Microsoft Windows Server 2016 Datacenter"))(equalTo(Windows2016))
        },
        test("windows 2025") {
          assert(parseFromInventory("Microsoft Windows Server 2025 Datacenter"))(equalTo(Windows2025))
        },
        test("windows 11") {
          assert(parseFromInventory("Microsoft Windows 11 Enterprise Evaluation"))(equalTo(Windows11))
        }
      )
    },
    // for Linux, we look at the <OPERATINGSYSTEM><NAME> tag
    suite("Linux OS parsing") {
      import com.normation.inventory.domain.LinuxType.*

      Chunk(
        test("Alma")(assert(parseFromInventory("almalinux"))(equalTo(AlmaLinux))),
        test("Amazon")(assert(parseFromInventory("amazon linux"))(equalTo(AmazonLinux))),
        test("Android")(assert(parseFromInventory("android"))(equalTo(Android))),
        test("Centos")(assert(parseFromInventory("centos"))(equalTo(Centos))),
        test("Debian")(assert(parseFromInventory("debian"))(equalTo(Debian))),
        test("Fedora")(assert(parseFromInventory("fedora"))(equalTo(Fedora))),
        test("Kali")(assert(parseFromInventory("kali"))(equalTo(Kali))),
        test("Mint")(assert(parseFromInventory("mint"))(equalTo(Mint))),
        test("Oracle")(assert(parseFromInventory("oracle"))(equalTo(Oracle))),
        test("Raspbian")(assert(parseFromInventory("raspbian"))(equalTo(Raspbian))),
        test("Redhat")(assert(parseFromInventory("redhat"))(equalTo(Redhat))),
        test("Rocky")(assert(parseFromInventory("rocky"))(equalTo(RockyLinux))),
        test("Scientific")(assert(parseFromInventory("scientific"))(equalTo(Scientific))),
        test("Slackware")(assert(parseFromInventory("slackware"))(equalTo(Slackware))),
        test("Suse")(assert(parseFromInventory("suse"))(equalTo(Suse))),
        test("Tuxedo")(assert(parseFromInventory("tuxedo"))(equalTo(Tuxedo))),
        test("Ubuntu")(assert(parseFromInventory("ubuntu"))(equalTo(Ubuntu)))
      )
    }
  )
}
