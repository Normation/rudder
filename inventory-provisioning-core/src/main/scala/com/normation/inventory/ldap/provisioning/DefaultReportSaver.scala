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

package com.normation.inventory.ldap.provisioning

import com.normation.inventory.domain.InventoryReport
import com.normation.inventory.services.provisioning._
import com.unboundid.ldif.LDIFChangeRecord
import com.normation.ldap.sdk.{LDAPConnectionProvider,LDAPEntry}
import com.normation.inventory.ldap.core._
import com.normation.inventory.domain._
import net.liftweb.common.{Box,Full,Empty,EmptyBox,Failure}
import scala.collection.mutable.Buffer
import org.slf4j.LoggerFactory
import com.normation.ldap.sdk.BuildFilter
import net.liftweb.common.Loggable

/**
 * Post-commit convention:
 * - Post-commit can't modify InventoryReport
 * - if succed, they can enhanced the list of ChangeRecords, 
 *   but at least must forward existing LDIFChangeRecords
 * - a post commit which returns Failure or Empty stop the post-commit pipeline
 *
 */

class DefaultReportSaver(
  ldapConnectionProvider:LDAPConnectionProvider,
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
    var results = List[Box[Seq[LDIFChangeRecord]]]()
    
    //we really want to save each software, and not the software tree as a whole - just think about the diff...
    report.applications foreach { x =>
      results = {
        for {
          con <- ldapConnectionProvider
          res <- con.save(mapper.entryFromSoftware(x))
        } yield { Seq(res) }  
      } :: results 
    }
    
    results = {
      for {
        con <- ldapConnectionProvider
        res <- con.saveTree(mapper.treeFromMachine(report.machine))
      } yield { res }
    } :: results

    
    results = {
      for {
        con <- ldapConnectionProvider

        res <- con.saveTree(mapper.treeFromNode(report.node))
      } yield {
        res }
    } :: results
    
    //finally, vms
    report.vms foreach { x =>
       results = { for {
          con <- ldapConnectionProvider
          res <- con.saveTree(mapper.treeFromMachine(x))
        } yield { res }
      } :: results
    }
    
    /*
     * TODO : what to do when there is a mix a failure/success ? 
     * LDAP does not have transaction, so... Try to restore, 
     * redo, what else ? 
     * 
     * For now, even on partial failure (means: not all fail), 
     * we consider it as a success and forward to post process
     */
    if(results.forall( _.isEmpty )) {
      //merge errors and return now
      (Failure("")/:results){ (fail,r) => r match {
        case Failure(m,_,_) => fail ?~! m
        case _ => fail
      } }
    } else { //ok, so at least one non error. Log errors, merge non error, and post-process it
      
      val changes : Seq[LDIFChangeRecord] = (Seq[LDIFChangeRecord]() /: results){ (records,r) => r match {
        case f:Failure => 
          logger.error("Report processing will be incomplete, found error: %s".format(f.messageChain))
          f.rootExceptionCause.foreach { ex => 
            logger.error("Error was caused by exception: %s".format(ex.getMessage))
          }
          records
        case Empty => records //can't log anything relevant ? 
        case Full(seq) => records ++ seq
      } }
      
      //we don't want to avoid post-processing, so always return changes
      Full(changes)
      
    }
  }
}
