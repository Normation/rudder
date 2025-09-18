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

package com.normation.ldap.sdk

import com.normation.ldap.listener.InMemoryDsConnectionProvider
import com.normation.zio.*
import com.unboundid.ldap.sdk.DN
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*

/**
 * A simple test class to check that the demo data file is up to date
 * with the schema (there may still be a desynchronization if both
 * demo-data, test data and test schema for UnboundID are not synchronized
 * with OpenLDAP Schema).
 */
@RunWith(classOf[JUnitRunner])
class LoadDemoDataTest extends Specification {

  val schemaLDIFs: List[String] = (
    "00-core" ::
      "01-pwpolicy" ::
      "04-rfc2307bis" ::
      "05-rfc4876" ::
      Nil
  ) map { name =>
    // the toURI is need to take care of https://issues.rudder.io/issues/19186 :(
    this.getClass.getClassLoader.getResource("ldap-data/schema/" + name + ".ldif").toURI.getPath
  }

  val baseDN = "cn=rudder-configuration"
  val bootstrapLDIFs: List[String] = ("ldap-data/bootstrap.ldif" :: Nil) map { name =>
    this.getClass.getClassLoader.getResource(name).toURI.getPath
  }

  val numEntries: Int = bootstrapLDIFs.foldLeft(0) {
    case (x, path) =>
      val reader = new com.unboundid.ldif.LDIFReader(path)
      var i      = 0
      while (reader.readEntry != null) i += 1
      i + x
  }

  val ldap: InMemoryDsConnectionProvider[RwLDAPConnection with RoLDAPConnection] = {
    InMemoryDsConnectionProvider[RwLDAPConnection with RoLDAPConnection](
      baseDNs = baseDN :: Nil,
      schemaLDIFPaths = schemaLDIFs,
      bootstrapLDIFPaths = bootstrapLDIFs
    )
  }

  "The in memory LDAP directory" should {

    "correctly load and read back test-entries" in {

      ldap.server.countEntries === numEntries
    }

    "correctly error on a bad move" in {

      val dn        = new DN("ou=Machines,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration")
      val newParent = new DN("ou=Not Existing Place For Inventories,ou=Inventories,cn=rudder-configuration")

      /*
       * Failure message is:
       * Can not move 'biosName=bios1,machineId=machine2,ou=Machines,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration' to new parent
       * 'machineId=machine-does-not-exists,ou=Machines,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration': Unable to modify the DN of entry
       * 'biosName=bios1,machineId=machine2,ou=Machines,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration' because the parent for the new DN
       * 'biosName=bios1,machineId=machine-does-not-exists,ou=Machines,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration' does not exist.
       */
      ldap.newConnection.flatMap(_.move(dn, newParent)).either.runNow must beLeft
      ldap.newConnection.flatMap(_.exists(dn)).runNow must beTrue
    }

    "Missing attribute is a grave message that should not be ignored (#10067)" in {

      val dn    = new DN("ou=Machines,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration")
      val entry = LDAPEntry(dn)
      entry.addValues("falseAttribute", "anything")

      val res = ldap.newConnection.flatMap(_.save(entry)).either.runNow

      res must beAnInstanceOf[Left[LDAPRudderError, ?]]
    }
  }
}
