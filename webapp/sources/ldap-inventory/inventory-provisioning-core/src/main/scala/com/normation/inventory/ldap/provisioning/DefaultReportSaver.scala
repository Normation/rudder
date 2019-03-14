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

import cats.data.NonEmptyList
import com.normation.inventory.domain.InventoryReport
import com.normation.inventory.services.provisioning._
import com.unboundid.ldif.LDIFChangeRecord
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.inventory.ldap.core._
import com.normation.ldap.sdk.LdapResult.LdapResult
import net.liftweb.common._
import net.liftweb.common.Loggable
import com.normation.ldap.sdk.RwLDAPConnection
import scalaz.zio._
import scalaz.zio.syntax._
import com.normation.ldap.sdk.LdapResultRudderError
import com.normation.ldap.sdk.LdapResult._

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
) extends PipelinedReportSaver[Seq[LDIFChangeRecord]] with Loggable {

  def commitChange(report:InventoryReport) : Box[Seq[LDIFChangeRecord]] = {

    /*
     * we are saving with one connection by type of object so
     * that an LDAPException in one don't stop the
     * other to be saved
     */
    var results = List[LdapResult[Seq[LDIFChangeRecord]]]()

    val t0 = System.currentTimeMillis

    //we really want to save each software, and not the software tree as a whole - just think about the diff...
    report.applications foreach { x =>
      results = {
        for {
          con <- ldapConnectionProvider
          res <- con.save(mapper.entryFromSoftware(x))
        } yield { Seq(res) }
      } :: results
    }

    val t1 = System.currentTimeMillis
    logger.trace(s"Saving software: ${t1-t0} ms")

    results = {
      for {
        con <- ldapConnectionProvider
        res <- con.saveTree(mapper.treeFromMachine(report.machine), deleteRemoved = true)
      } yield { res }
    } :: results

    val t2 = System.currentTimeMillis
    logger.trace(s"Saving machine: ${t2-t1} ms")

    results = {
      for {
        con <- ldapConnectionProvider
        res <- con.saveTree(mapper.treeFromNode(report.node), deleteRemoved = true)
      } yield {
        res
      }
    } :: results

    val t3 = System.currentTimeMillis
    logger.trace(s"Saving node: ${t3-t2} ms")

    //finally, vms
    report.vms foreach { x =>
       results = { for {
          con <- ldapConnectionProvider
          res <- con.saveTree(mapper.treeFromMachine(x), deleteRemoved = true)
        } yield { res }
      } :: results
    }

    val t4 = System.currentTimeMillis
    logger.trace(s"Saving vms: ${t4-t3} ms")

    /*
     * TODO : what to do when there is a mix a failure/success ?
     * LDAP does not have transaction, so... Try to restore,
     * redo, what else ?
     *
     * For now, even on partial failure (means: not all fail),
     * we consider it as a success and forward to post process
     */
    val accumulated = ZIO.foldLeft(results)((List.empty[LdapResultRudderError], List.empty[Seq[LDIFChangeRecord]])) { case ((errors, oks), x) =>
      x.fold(err => (err :: errors, oks), ok => (errors, ok :: oks))
    }
    accumulated.flatMap { case (errors, successes) =>
      if(successes.isEmpty && errors.nonEmpty) {
        //merge errors and return now
        LdapResultRudderError.Accumulated(NonEmptyList.fromListUnsafe(errors)).fail
      } else { //ok, so at least one non error. Log errors, merge non error, and post-process it
        ZIO.foreach(errors)(e =>
            ZIO.effect(logger.error(s"Report processing will be incomplete, found error: ${e.msg}")).orElse(ZIO.unit)) *> successes.flatten.succeed
      }
    }
  }
}
