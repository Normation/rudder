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

package com.normation.rudder.services.servers

import com.normation.inventory.ldap.core.InventoryDitService
import com.normation.inventory.domain._
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.ldap.sdk._
import net.liftweb.common._
import com.normation.rudder.domain.servers.Srv
import com.normation.box._
import com.normation.errors.IOResult
import scalaz.zio._

trait NodeSummaryService {

  /**
   * Retrieve minimal information about the server
   */
  def find(dit:InventoryDit, id:NodeId*) : Box[Seq[Srv]]
}

import com.normation.inventory.ldap.core._
import com.normation.rudder.domain.RudderLDAPConstants._
import org.joda.time.DateTime


class NodeSummaryServiceImpl(
    inventoryDitService:InventoryDitService,
    inventoryMapper:InventoryMapper,
    ldap:LDAPConnectionProvider[RoLDAPConnection]
) extends NodeSummaryService with Loggable {

  /**
   * build a Srv from an LDAP Entry, using a node inventory
   * for the mapping part
   */
  def makeSrv(e:LDAPEntry) : IOResult[Srv] = {

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
          id             = node.main.id
        , status         = node.main.status
        , hostname       = node.main.hostname
        , osType         = node.main.osDetails.os.kernelName
        , osName         = node.main.osDetails.os.name
        , osFullName     = node.main.osDetails.fullName
        , ips            = node.serverIps.toList
        , creationDate   = dateTime
        , isPolicyServer = e.isA(OC_POLICY_SERVER_NODE)
        , serverRoles    = node.serverRoles
      )
    }
  }

  override def find(dit:InventoryDit,ids:NodeId*) : Box[Seq[Srv]] = {
    for {
      con        <- ldap
      optEntries <- ZIO.foreach(ids) { id =>
                      con.get(dit.NODES.NODE.dn(id),Srv.ldapAttributes.toSeq:_*)
                    }
      srvs       <- ZIO.foreach(optEntries.flatten) { e => makeSrv(e) }
    } yield {
      srvs
    }
  }.toBox

}
