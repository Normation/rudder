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

package com.normation.rudder.services.servers

import com.normation.inventory.ldap.core.InventoryDitService
import com.normation.inventory.domain._
import com.normation.inventory.ldap.core.InventoryDit
import com.unboundid.ldap.sdk._
import com.normation.ldap.sdk._
import net.liftweb.common._
import Box._

import com.normation.rudder.domain.servers.Srv

trait NodeSummaryService {

  /**
   * Retrieve minimal information about the server
   */
  def find(filter:Filter,dit:InventoryDit) : Box[Seq[Srv]]
  def find(dit:InventoryDit, id:NodeId*) : Box[Seq[Srv]]
}

import com.normation.inventory.ldap.core._
import BuildFilter._
import LDAPConstants._
import com.normation.rudder.domain.RudderLDAPConstants._
import org.joda.time.DateTime


class NodeSummaryServiceImpl(
    inventoryDitService:InventoryDitService,
    inventoryMapper:InventoryMapper,
    ldap:LDAPConnectionProvider
) extends NodeSummaryService with Loggable {

  /**
   * build a Srv from an LDAP Entry, using a node inventory
   * for the mapping part
   */
  def makeSrv(e:LDAPEntry) : Box[Srv] = {

    for {
      node <- inventoryMapper.nodeFromEntry(e)
    } yield {
      // fetch the creation datetime of the object
      val dateTime =  {e(A_OBJECT_CREATION_DATE) match {
        case None => DateTime.now()
        case Some(date) => GeneralizedTime.parse(date) match {
          case Some(value) => value.dateTime
          case None => DateTime.now()
        }
      }}

      Srv(
          id           = node.main.id
        , status       = node.main.status
        , hostname     = node.main.hostname
        , osType       = node.main.osDetails.os.kernelName
        , osName       = node.main.osDetails.os.name
        , osFullName   = node.main.osDetails.fullName
        , ips          = node.serverIps.toList
        , creationDate = dateTime
      )
    }
  }


  override def find(filter:Filter,dit:InventoryDit) : Box[Seq[Srv]] = ldap map { con =>
    con.searchOne(dit.NODES.dn, filter, Srv.ldapAttributes:_*).flatMap { makeSrv(_) }.toSeq
  }

  override def find(dit:InventoryDit,ids:NodeId*) : Box[Seq[Srv]] =
    for {
      con  <- ldap
      srvs =  ids map { id => con.get(dit.NODES.NODE.dn(id),Srv.ldapAttributes.toSeq:_*) } collect { case Full(se) => makeSrv(se) }
    } yield srvs.flatten

}