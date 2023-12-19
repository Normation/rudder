/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

package com.normation.rudder.facts.nodes

import better.files._
import com.normation.inventory.ldap.core.FullInventoryRepositoryImpl
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.ldap.core.InventoryDitService
import com.normation.inventory.ldap.core.InventoryDitServiceImpl
import com.normation.inventory.ldap.core.InventoryMapper
import com.normation.inventory.ldap.core.ReadOnlySoftwareDAOImpl
import com.normation.inventory.ldap.provisioning._
import com.normation.ldap.ldif.DefaultLDIFFileLogger
import com.normation.ldap.listener.InMemoryDsConnectionProvider
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.RoLDAPConnection
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.rudder.domain.NodeDit
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.rudder.repository.ldap.ZioTReentrantLock
import com.normation.utils.StringUuidGeneratorImpl
import com.unboundid.ldap.sdk.DN

object MockLdapFactStorage {

  val tmp: File = File.newTemporaryDirectory("rudder-test-ldap-schema-files-")
  tmp.deleteOnExit(true)

  val ldifLogger = new DefaultLDIFFileLogger("TestQueryProcessor", "/tmp/normation/rudder/ldif")

  // init of in memory LDAP directory
  val schemaLDIFs:    List[String] = {
    val schemaDir = tmp / "schema"
    schemaDir.createDirectories()

    (
      "00-core" ::
      "01-pwpolicy" ::
      "04-rfc2307bis" ::
      "05-rfc4876" ::
      "099-0-inventory" ::
      "099-1-rudder" ::
      Nil
    ) map { name =>
      // toURI is needed for https://issues.rudder.io/issues/19186
      val dest = schemaDir / s"${name}.ldif"
      dest.writeText(Resource.getAsString(s"ldap-data/schema/${name}.ldif"))
      dest.pathAsString
    }
  }
  val bootstrapLDIFs: List[String] = {
    val bootstrapDir = tmp / "bootstrap"
    bootstrapDir.createDirectories()

    ("ldap/bootstrap.ldif" :: "ldap-data/inventory-sample-data.ldif" :: Nil) map { name =>
      // toURI is needed for https://issues.rudder.io/issues/19186
      val dest = bootstrapDir / name.split("/")(1)
      dest.writeText(Resource.getAsString(name))
      dest.pathAsString
    }
  }

  val ldap: InMemoryDsConnectionProvider[RwLDAPConnection] = InMemoryDsConnectionProvider[RwLDAPConnection](
    baseDNs = "cn=rudder-configuration" :: Nil,
    schemaLDIFPaths = schemaLDIFs,
    bootstrapLDIFPaths = bootstrapLDIFs,
    ldifLogger
  )

  // for easier access in tests
  def testServer = ldap.server

  // close your eyes for next line
  val ldapRo: LDAPConnectionProvider[RoLDAPConnection] = ldap.asInstanceOf[LDAPConnectionProvider[RoLDAPConnection]]

  val acceptedDIT = new InventoryDit(
    new DN("ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration"),
    new DN("ou=Inventories,cn=rudder-configuration"),
    "test"
  )

  val removedDIT = new InventoryDit(
    new DN("ou=Removed Inventories,ou=Inventories,cn=rudder-configuration"),
    new DN("ou=Inventories,cn=rudder-configuration"),
    "test"
  )
  val pendingDIT = new InventoryDit(
    new DN("ou=Pending Inventories,ou=Inventories,cn=rudder-configuration"),
    new DN("ou=Inventories,cn=rudder-configuration"),
    "test"
  )
  val nodeDit    = new NodeDit(new DN("cn=rudder-configuration"))
  val rudderDit  = new RudderDit(new DN("ou=Rudder, cn=rudder-configuration"))
  val inventoryDitService: InventoryDitService = new InventoryDitServiceImpl(pendingDIT, acceptedDIT, removedDIT)
  val inventoryMapper             = new InventoryMapper(inventoryDitService, pendingDIT, acceptedDIT, removedDIT)
  val ldapMapper                  = new LDAPEntityMapper(rudderDit, nodeDit, acceptedDIT, null, inventoryMapper)
  val ldapFullInventoryRepository = new FullInventoryRepositoryImpl(inventoryDitService, inventoryMapper, ldap)
  val softwareGet                 = new ReadOnlySoftwareDAOImpl(inventoryDitService, ldapRo, inventoryMapper)
  val softwareSave                = new NameAndVersionIdFinder("check_name_and_version", ldapRo, inventoryMapper, acceptedDIT)

  val nodeFactStorage = new LdapNodeFactStorage(
    ldap,
    nodeDit,
    inventoryDitService,
    ldapMapper,
    inventoryMapper,
    new ZioTReentrantLock("node-lock"),
    ldapFullInventoryRepository,
    softwareGet,
    softwareSave,
    new StringUuidGeneratorImpl()
  )

}
