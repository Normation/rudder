/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.inventory.ldap.core

import com.unboundid.ldap.sdk.DN
import com.normation.ldap.sdk._
import BuildFilter.{EQ,OR}
import com.normation.inventory.domain._
import com.normation.inventory.services.core.ReadOnlySoftwareDAO
import LDAPConstants._
import net.liftweb.common._
import Box._
import com.normation.utils.Control.sequence

class ReadOnlySoftwareDAOImpl(
  inventoryDitService:InventoryDitService,
  ldap:LDAPConnectionProvider[RoLDAPConnection],
  mapper:InventoryMapper
) extends ReadOnlySoftwareDAO {

  override def getSoftware(ids:Seq[SoftwareUuid]) : Box[Seq[Software]] = {
    if(ids.isEmpty) Full(Seq())
    else for {
      con <- ldap
      softs <- sequence(con.searchOne(inventoryDitService.getSoftwareBaseDN, OR(ids map {x:SoftwareUuid => EQ(A_SOFTWARE_UUID,x.value) }:_*))) { entry =>
        mapper.softwareFromEntry(entry) ?~! "Error when mapping LDAP entry %s to a software".format(entry)
      }
    } yield {
      softs
    }
  }

  override def getSoftware(id:NodeId, inventoryStatus:InventoryStatus) : Box[Seq[Software]] = {
    val server = inventoryDitService.getDit(inventoryStatus).NODES.NODE
    for {
      con <- ldap
      serverEntry  <- con.get(server.dn(id), A_SOFTWARE_DN)
      softs <- sequence(serverEntry.valuesFor(A_SOFTWARE_DN).toSeq) { softDN =>
        for {
          e <- con.get(softDN)
          soft <- mapper.softwareFromEntry(e)
        } yield {
          soft
        }
      }
    } yield {
      softs
    }
  }
}