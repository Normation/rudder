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

package com.normation.rudder.repository.ldap

import com.normation.GitVersion
import com.normation.errors.PureResult
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.ldap.core.InventoryDitService
import com.normation.inventory.ldap.core.InventoryDitServiceImpl
import com.normation.inventory.ldap.core.InventoryMapper
import com.normation.ldap.sdk.LDAPEntry
import com.normation.rudder.domain.NodeDit
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants.*
import com.normation.rudder.domain.properties.GenericProperty.*
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.Visibility
import com.normation.rudder.services.queries.CmdbQueryParser
import com.typesafe.config.ConfigValueType
import com.unboundid.ldap.sdk.DN
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*

/**
 * A test that checks that an global parameter in 6.0 is correctly parsed as a string in
 * 6.1, and that the save is correctly escaped.
 */
@RunWith(classOf[JUnitRunner])
class GlobalParamMigration61Test extends Specification {

  implicit class ForceGet[A](res: PureResult[A]) {
    def forceGet: A = res match {
      case Right(value) => value
      case Left(err)    => throw new IllegalArgumentException(s"Error when trying to get value: ${err.fullMsg}")
    }
  }

  val mapper: LDAPEntityMapper = {
    val softwareDN = new DN("ou=Inventories, cn=rudder-configuration")
    val acceptedNodesDitImpl: InventoryDit = new InventoryDit(
      new DN("ou=Accepted Inventories, ou=Inventories, cn=rudder-configuration"),
      softwareDN,
      "Accepted inventories"
    )
    val pendingNodesDitImpl:  InventoryDit = new InventoryDit(
      new DN("ou=Pending Inventories, ou=Inventories, cn=rudder-configuration"),
      softwareDN,
      "Pending inventories"
    )
    val removedNodesDitImpl = new InventoryDit(
      new DN("ou=Removed Inventories, ou=Inventories, cn=rudder-configuration"),
      softwareDN,
      "Removed Servers"
    )
    val inventoryDitService: InventoryDitService =
      new InventoryDitServiceImpl(pendingNodesDitImpl, acceptedNodesDitImpl, removedNodesDitImpl)

    val inventoryMapper = new InventoryMapper(inventoryDitService, pendingNodesDitImpl, acceptedNodesDitImpl, removedNodesDitImpl)

    val rudderDit       = new RudderDit(new DN("ou=Rudder, cn=rudder-configuration"))
    val nodeDit         = new NodeDit(new DN("ou=Nodes, ou=Rudder, cn=rudder-configuration"))
    val cmdbQueryParser = CmdbQueryParser.jsonRawParser(Map.empty)

    new LDAPEntityMapper(rudderDit, nodeDit, cmdbQueryParser, inventoryMapper)
  }

  val baseParam: GlobalParameter =
    GlobalParameter.parse("test", GitVersion.DEFAULT_REV, "", None, "", None, Visibility.default, None).forceGet

  // attribute that used to be set in GlobalParameter entry before 6.1.
  def setIs6_0(e: LDAPEntry): Unit = {
    e.resetValuesTo("overridable", "TRUE")
  }

  "Parsing a 6.0 parameter " should {
    "correctly unserialize a json as string" in {
      val e     = mapper.parameter2Entry(baseParam)
      val value = "{\"foo\":\"bar\"}"
      setIs6_0(e)
      e.resetValuesTo(A_PARAMETER_VALUE, value)

      mapper.entry2Parameter(e).map(_.value).forceGet must beEqualTo(value.toConfigValue)
    }

    "correctly unserialize a quoted string as escaped quote" in {
      val e     = mapper.parameter2Entry(baseParam)
      val value = "\"foo\""
      setIs6_0(e)
      e.resetValuesTo(A_PARAMETER_VALUE, value)

      mapper.entry2Parameter(e).map(_.value).forceGet must beEqualTo(value.toConfigValue)
    }

    "correctly unserialize a comment string" in {
      val e     = mapper.parameter2Entry(baseParam)
      val value = "#foo"
      setIs6_0(e)
      e.resetValuesTo(A_PARAMETER_VALUE, value)

      mapper.entry2Parameter(e).map(_.value).forceGet must beEqualTo(value.toConfigValue)
    }

    "be idempotent" in {
      val e     = mapper.parameter2Entry(baseParam)
      val value = "some value defined in 6.0"
      setIs6_0(e)
      e.resetValuesTo(A_PARAMETER_VALUE, value)

      val ffx = mapper.parameter2Entry(mapper.entry2Parameter(e).forceGet)
      mapper.entry2Parameter(ffx).map(_.value).forceGet must beEqualTo(value.toConfigValue)
    }
  }
  "Parsing a 6.1 parameter " should {
    "correctly unserialize a json as json" in {
      val e     = mapper.parameter2Entry(baseParam)
      val value = "{\"foo\":\"bar\"}"
      e.resetValuesTo(A_PARAMETER_VALUE, value)

      mapper.entry2Parameter(e).map(_.value.valueType()).forceGet must beEqualTo(ConfigValueType.OBJECT)
    }

    "correctly unserialize a quoted string as non quoted string" in {
      val e     = mapper.parameter2Entry(baseParam)
      val value = "\"foo\""
      e.resetValuesTo(A_PARAMETER_VALUE, value)

      mapper.entry2Parameter(e).map(_.valueAsString).forceGet must beEqualTo("foo")
    }

    "be idempotent" in {
      val e     = mapper.parameter2Entry(baseParam)
      val input = "in 6.1, value are quoted in LDAP"
      val value = "\"" + input + "\""
      e.resetValuesTo(A_PARAMETER_VALUE, value)

      val ffx = mapper.parameter2Entry(mapper.entry2Parameter(e).forceGet)
      mapper.entry2Parameter(ffx).map(_.valueAsString).forceGet must beEqualTo(input)
    }
  }
}
