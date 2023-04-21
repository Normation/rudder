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
package com.normation.rudder.services.servers

import com.normation.errors._
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.RemovedInventory
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.inventory.ldap.core.LDAPFullInventoryRepository
import com.normation.ldap.sdk._
import com.normation.ldap.sdk.BuildFilter._
import com.normation.rudder.domain.logger.NodeLoggerPure
import com.normation.rudder.services.nodes.NodeInfoService.A_MOD_TIMESTAMP
import org.joda.time.DateTime
import zio.{System => _, _}

trait PurgeDeletedNodes {

  /**
    * Purge from the removed inventories ldap tree all nodes modified before date
    */
  def purgeDeletedNodesPreviousDate(date: DateTime): IOResult[Seq[NodeId]]
}

class PurgeDeletedNodesImpl(
    ldap:         LDAPConnectionProvider[RwLDAPConnection],
    deletedDit:   InventoryDit,
    fullNodeRepo: LDAPFullInventoryRepository
) extends PurgeDeletedNodes {
  override def purgeDeletedNodesPreviousDate(date: DateTime): IOResult[Seq[NodeId]] = {
    for {
      con            <- ldap
      deletedEntries <- con.search(
                          deletedDit.NODES.dn,
                          One,
                          AND(IS(OC_NODE), LTEQ(A_MOD_TIMESTAMP, GeneralizedTime(date).toString))
                        )
      _              <- NodeLoggerPure.Delete.trace(s"Found ${deletedEntries.length} older than ${date}")

      ids <- ZIO.foreach(deletedEntries)(e => deletedDit.NODES.NODE.idFromDN(e.dn).toIO)

      _ <- ZIO.foreach(ids)(id => fullNodeRepo.delete(id, RemovedInventory))
    } yield {
      ids
    }
  }
}
