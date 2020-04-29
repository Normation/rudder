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

package com.normation.inventory.ldap.core

import com.normation.ldap.sdk._
import BuildFilter.{EQ, IS, OR}
import com.normation.inventory.domain._
import com.normation.inventory.services.core._
import LDAPConstants._
import net.liftweb.common._
import Box._
import com.normation.utils.Control.{bestEffort, sequence}
import com.unboundid.ldap.sdk.DN
import net.liftweb.util.Helpers.tryo

class ReadOnlySoftwareDAOImpl(
  inventoryDitService:InventoryDitService,
  ldap:LDAPConnectionProvider[RoLDAPConnection],
  mapper:InventoryMapper
) extends ReadOnlySoftwareDAO with Loggable {

  private[this] def search(con: RoLDAPConnection, ids: Seq[SoftwareUuid]) = {
    sequence(con.searchOne(inventoryDitService.getSoftwareBaseDN, OR(ids map {x:SoftwareUuid => EQ(A_SOFTWARE_UUID,x.value) }:_*))) { entry =>
      mapper.softwareFromEntry(entry) ?~! s"Error when mapping LDAP entry '${entry.dn}' to a software. Entry details: ${entry}"
    }
  }

  override def getSoftware(ids:Seq[SoftwareUuid]) : Box[Seq[Software]] = {
    if(ids.isEmpty) Full(Seq())
    else for {
      con   <- ldap
      softs <- search(con, ids)
    } yield {
      softs
    }
  }


  /**
   * softwares
   */
  override def getSoftwareByNode(nodeIds: Set[NodeId], status: InventoryStatus): Box[Map[NodeId, Seq[Software]]] = {

    val dit = inventoryDitService.getDit(status)

    val orFilter = BuildFilter.OR(nodeIds.toSeq.map(x => EQ(A_NODE_UUID,x.value)):_*)

    for {
      con            <- ldap
      nodeEntries    =  con.searchOne(dit.NODES.dn, orFilter, Seq(A_NODE_UUID, A_SOFTWARE_DN):_*)
      softwareByNode =  (nodeEntries.flatMap { e =>
                          e(A_NODE_UUID).map { id =>
                            (NodeId(id), e.valuesFor(A_SOFTWARE_DN).flatMap(dn => inventoryDitService.getDit(AcceptedInventory).SOFTWARE.SOFT.idFromDN(dn)))
                          }
                        }).toMap
      softwareIds    =  softwareByNode.values.flatten.toSeq
      software       <- search(con, softwareIds)
    } yield {
      softwareByNode.mapValues { ids => software.filter(s => ids.contains(s.id)) }
    }
  }

  def getAllSoftwareIds() : Box[Set[SoftwareUuid]] = {
    for {
      con     <- ldap
      softwareEntry = con.searchOne(inventoryDitService.getSoftwareBaseDN, IS(OC_SOFTWARE), A_SOFTWARE_UUID)
      ids     <- sequence(softwareEntry) { entry =>
        entry(A_SOFTWARE_UUID) match {
          case Some(value) => Full(value)
          case _ => Failure(s"Missing attribute ${A_SOFTWARE_UUID} for entry ${entry.dn} ${entry.toString()}")
        }
      }
      softIds       =  ids.map(id => SoftwareUuid(id)).toSet
    } yield {
      softIds
    }
  }

  def getSoftwaresForAllNodes() : Box[Set[SoftwareUuid]] = {
    // Accepted Dit, to get the software
    val acceptedDit = inventoryDitService.getDit(AcceptedInventory)

    val NB_BATCH_NODES = 25

    // We need to search on the parent parent, as acceptedDit.NODES.dn.getParent ou=Accepted inventories
    val nodeBaseSearch = acceptedDit.NODES.dn.getParent.getParent
    var mutSetSoftwares: scala.collection.mutable.Set[SoftwareUuid] = scala.collection.mutable.Set[SoftwareUuid]()

    (for {
      con           <- ldap

      // fetch all nodes
      nodes        = con.searchSub(nodeBaseSearch, IS(OC_NODE), A_NODE_UUID)
      batchedNodes = nodes.grouped(NB_BATCH_NODES).toSeq
      _            <- sequence(batchedNodes) { nodeEntries: Seq[LDAPEntry] =>
                             // We need to help garbage collection by enclosing parts in { }
                             val (boxedIds, t2) = {
                               val (softwareEntries, t2) = {
                                 val nodeIds = nodeEntries.flatMap(_ (A_NODE_UUID)).map(NodeId(_))
                                 val t2 = System.currentTimeMillis
                                 val orFilter = BuildFilter.OR(nodeIds.map(x => EQ(A_NODE_UUID, x.value)): _*)
                                 (tryo(con.searchSub(nodeBaseSearch, orFilter, A_SOFTWARE_DN)), t2)
                               }
                               (softwareEntries.map(x => x.flatMap(entry => entry.valuesFor(A_SOFTWARE_DN)).toSet.toSeq)
                               , t2)
                             }
                             for {
                               softIds <- boxedIds
                               results = sequence(softIds) { id => acceptedDit.SOFTWARE.SOFT.idFromDN(new DN(id)) }
                               t3 = System.currentTimeMillis()
                               _ = logger.debug(s"Software DNs from ${NB_BATCH_NODES} nodes fetched in ${t3 - t2}ms")
                             } yield {
                               results match { // we don't want to return "results" because we need on-site dedup.
                                 case Full(softIds) =>
                                   mutSetSoftwares = mutSetSoftwares ++ softIds
                                   Full(Unit)
                                 case failure =>
                                   failure // otherwise the time is wrong
                               }
                             }
                           }
    } yield {
      ()
    }).map(_ => mutSetSoftwares.toSet)
  }

}

class WriteOnlySoftwareDAOImpl(
     inventoryDit  :InventoryDit
   , ldap          :LDAPConnectionProvider[RwLDAPConnection]
) extends WriteOnlySoftwareDAO {


  def deleteSoftwares(softwareIds: Seq[SoftwareUuid]): Box[Seq[String]] = {
    for {
      con <- ldap
      dns = softwareIds.map(inventoryDit.SOFTWARE.SOFT.dn(_))
      res <- bestEffort(dns) { dn =>
        con.delete(dn)
      }
    } yield {
      res.flatten.map( entry => entry.getDN)
    }
  }
}
