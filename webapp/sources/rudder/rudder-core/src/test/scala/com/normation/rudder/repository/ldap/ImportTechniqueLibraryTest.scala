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
package com.normation.rudder.repository.ldap

import com.normation.ldap.listener.InMemoryDsConnectionProvider
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.policies.ActiveTechniqueCategory
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.rudder.repository.ActiveTechniqueCategoryContent
import com.normation.zio.*
import com.unboundid.ldap.sdk.DN
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*

/**
 * Test import of a new technique library
 */
@RunWith(classOf[JUnitRunner])
class ImportTechniqueLibraryTest extends Specification {

  val bootstrapLDIFs: List[String] = ("ldap/bootstrap.ldif" :: "ldap-data/technique-library-data.ldif" :: Nil) map { name =>
    // toURI is needed for https://issues.rudder.io/issues/19186
    this.getClass.getClassLoader.getResource(name).toURI.getPath
  }

  val ldap: InMemoryDsConnectionProvider[RwLDAPConnection] =
    InitTestLDAPServer.newLdapConnectionProvider(InitTestLDAPServer.schemaLDIFs, bootstrapLDIFs)

  val rudderDit  = new RudderDit(new DN("ou=Rudder, cn=rudder-configuration"))
  val ldapMapper = new LDAPEntityMapper(rudderDit, null, null, null)
  val importer   = new ImportTechniqueLibraryImpl(rudderDit, ldap, ldapMapper, new ZioTReentrantLock("test"))

  val base = "techniqueCategoryId=Active Techniques,ou=Rudder,cn=rudder-configuration"

  sequential

  "When we restore an empty lib, we" should {

    "swap without error" in {
      importer
        .swapActiveTechniqueLibrary(
          ActiveTechniqueCategoryContent(
            ActiveTechniqueCategory(
              ActiveTechniqueCategoryId("Active Techniques"),
              "Active Techniques",
              "Restored root",
              Nil,
              Nil,
              true,
              None
            ),
            Set(),
            Set()
          )
        )
        .runNow must not(throwA[Throwable])

    }

    "correctly load and read back test-entries" in {
      // to look with you eyes on the content
      // ldap.server.exportToLDIF("/tmp/rudder-content.ldif", true, true)

      ldap.server.assertEntriesExist(base) must not(throwA[AssertionError])

      // system directive are copied back
      ldap.server.assertEntriesExist(
        "directiveId=server-common-root,activeTechniqueId=server-common,techniqueCategoryId=Rudder Internal," + base
      ) must not(throwA[AssertionError])

      // user policies are not copied back and now missing
      ldap.server.assertEntryMissing(
        "activeTechniqueId=packageManagement,techniqueCategoryId=applications," + base
      ) must not(throwA[AssertionError])

      // but benchmarks are here
      ldap.server.assertEntriesExist(
        "directiveId=144b4459-5a1f-475e-aa8a-aee2575b569b-rsc_01_section1,activeTechniqueId=rsc_01_section1,techniqueCategoryId=rsc_01,techniqueCategoryId=benchmarks," + base
      ) must not(throwA[AssertionError])

    }
  }
}
