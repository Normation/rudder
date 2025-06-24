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

import com.normation.NamedZioLogger
import com.normation.errors.*
import com.normation.inventory.domain.*
import com.normation.inventory.ldap.core.LDAPConstants.*
import com.normation.inventory.services.core.ReadOnlySoftwareDAO
import com.normation.inventory.services.core.WriteOnlySoftwareDAO
import com.normation.ldap.sdk.*
import com.normation.ldap.sdk.BuildFilter.EQ
import com.normation.ldap.sdk.BuildFilter.IS
import com.normation.ldap.sdk.BuildFilter.OR
import com.normation.zio.*
import com.unboundid.ldap.sdk.DN
import zio.{System as _, *}
import zio.syntax.*

object TimingDebugLoggerPure extends NamedZioLogger {
  override def loggerName: String = "debug_timing"
}

class ReadOnlySoftwareDAOImpl(
    inventoryDitService: InventoryDitService,
    ldap:                LDAPConnectionProvider[RoLDAPConnection],
    mapper:              InventoryMapper
) extends ReadOnlySoftwareDAO {

  private def search(con: RoLDAPConnection, ids: Seq[SoftwareUuid]): IOResult[Seq[Software]] = {
    val NB_BATCH_SOFTWARE = 2000

    // fetching 10 000 software make this method timeout
    // so we break it in smaller pieces
    for {
      n0     <- currentTimeMillis
      _      <- TimingDebugLoggerPure.debug(s"Search software from ${ids.size} ids")
      result <- ZIO.foreach(ids.grouped(NB_BATCH_SOFTWARE).to(Iterable)) { softIds =>
                  for {
                    n1      <- currentTimeMillis
                    entries <- con.searchOne(
                                 inventoryDitService.getSoftwareBaseDN,
                                 OR(softIds map { (x: SoftwareUuid) => EQ(A_SOFTWARE_UUID, x.value) }*)
                               )
                    n2      <- currentTimeMillis

                    _ <- TimingDebugLoggerPure.trace(s"Fetched ${entries.size} entries from LDAP in ${n2 - n1} ms")

                    soft <- ZIO.foreach(entries) { entry =>
                              ZIO
                                .fromEither(mapper.softwareFromEntry(entry))
                                .chainError(
                                  s"Error when mapping LDAP entry '${entry.dn.toString}' to a software. Entry details: ${entry.toLDIFString()}"
                                )
                            }
                    n3   <- currentTimeMillis

                    _ <- TimingDebugLoggerPure.trace(s"Converting ${soft.size} entries into softwares in ${n3 - n2} ms")

                  } yield {
                    soft
                  }
                }
      n5     <- currentTimeMillis
      _      <- TimingDebugLoggerPure.debug(s"Fetched and converted ${result.size} ids to software in ${n5 - n0} ms")
    } yield {
      result.flatten.toSeq
    }
  }

  override def getSoftware(ids: Seq[SoftwareUuid]): IOResult[Seq[Software]] = {
    if (ids.isEmpty) Seq().succeed
    else {
      (for {
        con   <- ldap
        softs <- search(con, ids)
      } yield {
        softs
      })
    }
  }

  /**
   * softwares
   */
  override def getSoftwareByNode(nodeIds: Set[NodeId], status: InventoryStatus): IOResult[Map[NodeId, Seq[Software]]] = {

    val dit      = inventoryDitService.getDit(status)
    val orFilter = BuildFilter.OR(nodeIds.toSeq.map(x => EQ(A_NODE_UUID, x.value))*)
    (for {
      con         <- ldap
      n3          <- currentTimeMillis
      nodeEntries <- con.searchOne(dit.NODES.dn, orFilter, Seq(A_NODE_UUID, A_SOFTWARE_DN)*)
      n4          <- currentTimeMillis
      _           <- TimingDebugLoggerPure.debug(s"Fetching ${nodeEntries.size} nodes entries in ${n4 - n3} ms")

      softwareByNode = (for {
                         e  <- nodeEntries
                         id <- e(A_NODE_UUID)
                         vs  = for {
                                 dn <- e.valuesForChunk(A_SOFTWARE_DN)
                                 s  <- inventoryDitService.getDit(AcceptedInventory).SOFTWARE.SOFT.idFromDN(new DN(dn)).toOption
                               } yield {
                                 s
                               }
                       } yield {
                         (NodeId(id), vs)
                       }).toMap
      n5            <- currentTimeMillis
      _             <- TimingDebugLoggerPure.debug(s"Fetching ${softwareByNode.size} software by nodes in ${n5 - n4} ms") // 17s

      softwareIds = softwareByNode.values.flatten.toSeq
      n6         <- currentTimeMillis
      _          <- TimingDebugLoggerPure.debug(s"Converting softwareByNode to softwareIds in ${n6 - n5} ms")

      software <- search(con, softwareIds)
      n7       <- currentTimeMillis
      _        <- TimingDebugLoggerPure.debug(s"Searching softwares in ${n7 - n6} ms")

    } yield {
      val softMap = software.map(s => (s.id, s)).toMap
      softwareByNode.map {
        case (node, ids) =>
          (node, ids.flatMap(s => softMap.get(s)))
      }
    })
  }

  def getAllSoftwareIds(): IOResult[Set[SoftwareUuid]] = {
    for {
      con           <- ldap
      softwareEntry <- con.searchOne(inventoryDitService.getSoftwareBaseDN, IS(OC_SOFTWARE), A_SOFTWARE_UUID)
      ids           <- ZIO.foreach(softwareEntry) { entry =>
                         entry(A_SOFTWARE_UUID) match {
                           case Some(value) => value.succeed
                           case _           =>
                             Inconsistency(s"Missing attribute ${A_SOFTWARE_UUID} for entry ${entry.dn.toString} ${entry.toString()}").fail
                         }
                       }
      softIds        = ids.map(id => SoftwareUuid(id)).toSet
    } yield {
      softIds
    }
  }

  def getSoftwaresForAllNodes(): IOResult[Set[SoftwareUuid]] = {
    // Accepted Dit, to get the software
    val acceptedDit = inventoryDitService.getDit(AcceptedInventory)

    val NB_BATCH_NODES = 50

    // We need to search on the parent parent, as acceptedDit.NODES.dn.getParent ou=Accepted inventories
    val nodeBaseSearch = acceptedDit.NODES.dn.getParent.getParent

    for {
      con <- ldap

      // This method may do too many LDAP query, and cause error (see https://issues.rudder.io/issues/17176 and https://issues.rudder.io/issues/16636)
      // fetch all nodes
      nodes           <- con.searchSub(nodeBaseSearch, IS(OC_NODE), A_NODE_UUID)
      mutSetSoftwares <- Ref.make[scala.collection.mutable.Set[SoftwareUuid]](scala.collection.mutable.Set())
      _               <- ZIO.foreach(nodes.grouped(NB_BATCH_NODES).to(Iterable)) {
                           nodeEntries => // batch by 50 nodes to avoid destroying ram/ldap con
                             for {
                               nodeIds <- ZIO.foreach(nodeEntries) { e =>
                                            IOResult
                                              .attempt(e(A_NODE_UUID).map(NodeId(_)))
                                              .notOptional(s"Missing mandatory attribute '${A_NODE_UUID}'")
                                          }
                               t2      <- currentTimeMillis
                               ids     <- {
                                 val orFilter = BuildFilter.OR(nodeIds.map(x => EQ(A_NODE_UUID, x.value))*)
                                 for {
                                   softwareEntry <- con.searchSub(nodeBaseSearch, orFilter, A_SOFTWARE_DN)
                                 } yield {
                                   softwareEntry.flatMap(entry => entry.valuesFor(A_SOFTWARE_DN)).distinct
                                 }
                               }
                               results <- ZIO.foreach(ids)(id => SoftwareUuid.SoftwareUuidFromDnString(id)).either
                               t3      <- currentTimeMillis
                               _       <- InventoryProcessingLogger.debug(s"Software DNs from ${NB_BATCH_NODES} nodes fetched in ${t3 - t2} ms")
                               _       <- results match { // we don't want to return "results" because we need on-site dedup.
                                            case Right(softIds) =>
                                              mutSetSoftwares.update(_ ++ softIds)
                                            case Left(err)      =>
                                              err.fail // otherwise the time is wrong
                                          }
                             } yield {
                               ()
                             }
                         }
      softwares       <- mutSetSoftwares.get
    } yield {
      softwares.toSet
    }
  }

  def getNodesBySoftwareName(softName: String): IOResult[List[(NodeId, Software)]] = {

    val n1 = System.currentTimeMillis
    for {

      con     <- ldap
      n2       = System.currentTimeMillis
      _       <- TimingDebugLoggerPure.trace(s"init ldap: ${n2 - n1}ms")
      entries <- con.searchOne(inventoryDitService.getSoftwareBaseDN, EQ(A_NAME, softName)).map(_.toVector)
      n3       = System.currentTimeMillis
      _       <- TimingDebugLoggerPure.trace(s"soft request ldap: ${n3 - n2}ms")
      res     <- ZIO.foreach(entries) { entry =>
                   val dit = inventoryDitService.getDit(AcceptedInventory)
                   for {
                     soft        <- ZIO
                                      .fromEither(mapper.softwareFromEntry(entry))
                                      .chainError(s"Error when mapping LDAP entry '${entry.dn}' to a software. Entry details: ${entry}")
                     nodeEntries <- con.searchSub(
                                      dit.NODES.dn,
                                      BuildFilter.AND(IS(OC_NODE), EQ(A_SOFTWARE_DN, dit.SOFTWARE.SOFT.dn(soft.id).toString)),
                                      A_NODE_UUID
                                    )
                     nodeIds     <- ZIO.foreach(nodeEntries) { e =>
                                      IOResult
                                        .attempt(e(A_NODE_UUID).map(NodeId(_)))
                                        .notOptional(s"Missing mandatory attribute '${A_NODE_UUID}'")
                                    }
                   } yield {
                     nodeIds.map((_, soft))
                   }
                 }

      n4 = System.currentTimeMillis
      _  = TimingDebugLoggerPure.trace(s"node request ldap: ${n4 - n3}ms")

    } yield {
      res.flatten.toList
    }
  }
}

class WriteOnlySoftwareDAOImpl(
    inventoryDit: InventoryDit,
    ldap:         LDAPConnectionProvider[RwLDAPConnection]
) extends WriteOnlySoftwareDAO {

  def deleteSoftwares(softwareIds: Seq[SoftwareUuid], batchSize: Int = 1000): IOResult[Unit] = {
    val total = softwareIds.size
    for {
      con <- ldap
      dns  = softwareIds.map(inventoryDit.SOFTWARE.SOFT.dn(_))
      res <- dns.grouped(batchSize).zipWithIndex.toSeq.accumulate {
               case (list, i) =>
                 for {
                   _ <- list.accumulate(con.delete(_))
                   _ <- InventoryProcessingLogger.debug(s"[deleting software] ${i * batchSize}/${total} software deleted")
                 } yield ()
             }
      _   <- InventoryProcessingLogger.debug(s"[deleting software] ${total}/${total} software deleted")
    } yield ()
  }
}
