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

package com.normation.inventory.ldap.provisioning

import com.normation.inventory.domain.InventoryProcessingLogger
import com.normation.inventory.domain.InventoryReport
import com.normation.errors._
import com.normation.inventory.services.provisioning._
import com.unboundid.ldif.LDIFChangeRecord
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.inventory.ldap.core._
import com.normation.ldap.ldif.LDIFNoopChangeRecord
import com.normation.ldap.sdk.LDAPEntry
import com.normation.ldap.sdk.LDAPIOResult.LDAPIOResult
import com.normation.ldap.sdk.LDAPRudderError
import com.normation.ldap.sdk.LDAPRudderError.FailureResult
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.zio._
import com.unboundid.ldap.sdk.AddRequest
import com.unboundid.ldap.sdk.ModifyRequest
import com.unboundid.ldap.sdk.ResultCode
import zio._
import zio.syntax._

/**
 * Post-commit convention:
 * - Post-commit can't modify InventoryReport
 * - if succed, they can enhanced the list of ChangeRecords,
 *   but at least must forward existing LDIFChangeRecords
 * - a post commit which returns Failure or Empty stop the post-commit pipeline
 *
 */

class DefaultReportSaver(
  ldapConnectionProvider:LDAPConnectionProvider[RwLDAPConnection],
  dit:InventoryDit,
  mapper:InventoryMapper,
  override val preCommitPipeline:Seq[PreCommit],
  override val postCommitPipeline:Seq[PostCommit[Seq[LDIFChangeRecord]]]
) extends PipelinedReportSaver[Seq[LDIFChangeRecord]] {

  /*
   * From a domain point of view, in rudder software are almost equals to their
   * name+version.
   * We can't considere software immutable because once in a while, like in 6.2, we
   * add new attributes like "sourceName".
   * But we can be optimistic on the save lock, since we are sure that all dn are different.
   * Since it's a copy of "con.save" without the lock on software, we can reach a case where
   * we tried to save a soft that is already here, or update a soft that was deleted. It
   * should be a very rare case, and so we can specificaly handle them by hand.
   */
  def saveSoftware(con: RwLDAPConnection, entry: LDAPEntry): LDAPIOResult[LDIFChangeRecord] = {
    (con.get(entry.dn) flatMap {
      case None =>
        con.applyAdd(new AddRequest(entry.backed))
      case Some(existing) =>
        val mods = LDAPEntry.merge(existing,entry, false, true)
        if(!mods.isEmpty) {
          import scala.jdk.CollectionConverters._
          con.applyModify(new ModifyRequest(entry.dn.toString, mods.asJava))
        } else LDIFNoopChangeRecord(entry.dn).succeed
    }).catchSome {
      case FailureResult(_, code) if (code.getResultCode == ResultCode.ENTRY_ALREADY_EXISTS) =>
        // ok, someone was quicker
        (new LDIFNoopChangeRecord(entry.dn)).succeed
      case FailureResult(_, code) if(code.getResultCode == ResultCode.NO_SUCH_OBJECT) =>
        // try a real save with a lock
        con.save(entry)
    }
  }

  def commitChange(report:InventoryReport) : IOResult[Seq[LDIFChangeRecord]] = {

    for {
      con <- ldapConnectionProvider
      t0  <- currentTimeMillis
      //we really want to save each software, and not the software tree as a whole - just think about the diff...
      d0  <- ZIO.foreachParN(50)(report.applications){ x => saveSoftware(con, mapper.entryFromSoftware(x)) }
      t1  <- currentTimeMillis
      _   <- InventoryProcessingLogger.timing.trace(s"Saving software: ${t1 - t0} ms")

      d1  <- con.saveTree(mapper.treeFromMachine(report.machine), deleteRemoved = true)
      t2  <- currentTimeMillis
      _   <- InventoryProcessingLogger.timing.trace(s"Saving machine: ${t2 - t1} ms")

      d2  <- con.saveTree(mapper.treeFromNode(report.node), deleteRemoved = true)
      t3  <- currentTimeMillis
      _   <- InventoryProcessingLogger.timing.trace(s"Saving node: ${t3 - t2} ms")

      d3  <- ZIO.foreach(report.vms) { x =>con.saveTree(mapper.treeFromMachine(x), deleteRemoved = true) }
      t4  <- currentTimeMillis
      _   <- InventoryProcessingLogger.timing.trace(s"Saving vms: ${t4-t3} ms")
    } yield {
      d0 ++ d1 ++ d2 ++ d3.flatten
    }
  }
}
