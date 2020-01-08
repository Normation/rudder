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

package com.normation.rudder.inventory

import java.io.InputStream
import java.security.{PublicKey => JavaSecPubKey}

import com.normation.NamedZioLogger
import com.normation.errors.Chained
import com.normation.errors.IOResult
import com.normation.errors.SystemError
import com.normation.inventory.domain.CertifiedKey
import com.normation.inventory.domain.InventoryLogger
import com.normation.inventory.domain.InventoryReport
import com.normation.errors._
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.SecurityToken
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.services.core.FullInventoryRepository
import com.normation.inventory.services.provisioning.InventoryDigestServiceV1
import com.normation.inventory.services.provisioning.ReportSaver
import com.normation.inventory.services.provisioning.ReportUnmarshaller
import com.normation.zio.ZioRuntime
import com.unboundid.ldif.LDIFChangeRecord
import org.joda.time.Duration
import org.joda.time.format.PeriodFormat
import zio._
import zio.syntax._

object InventoryProcessingLogger extends NamedZioLogger {
  override def loggerName: String = "inventory-processing"
}

sealed trait InventoryProcessStatus {
  def reportName: String
  def     nodeId: NodeId

}
final object InventoryProcessStatus {
  final case class Accepted        (reportName: String, nodeId: NodeId) extends InventoryProcessStatus
  final case class QueueFull       (reportName: String, nodeId: NodeId) extends InventoryProcessStatus
  final case class SignatureInvalid(reportName: String, nodeId: NodeId) extends InventoryProcessStatus
  final case class MissingSignature(reportName: String, nodeId: NodeId) extends InventoryProcessStatus
}


object StatusLog {
  implicit class LogMessage(status: InventoryProcessStatus) {
    def msg: String = status match {
      case InventoryProcessStatus.MissingSignature(reportName, nodeId) =>
        s"Rejecting Inventory '${reportName}' for Node '${nodeId.value}' because its signature is missing. " +
        s"You can go back to unsigned state by running the following command on the Rudder Server: " +
        s"'/opt/rudder/bin/rudder-keys reset-status ${nodeId.value}'"

      case InventoryProcessStatus.SignatureInvalid(reportName, nodeId) =>
        s"Rejecting Inventory '${reportName}' for Node '${nodeId.value}' because the Inventory signature is " +
        s"not valid: the Inventory was not signed with the same agent key as the one saved within Rudder for that Node. If " +
        s"you updated the agent key on this node, you can update the key stored within Rudder with the following command on " +
        s"the Rudder Server: '/opt/rudder/bin/rudder-keys change-key ${nodeId.value} <your new public key>'. " +
        s"If you did not change the key, please ensure that the node sending that inventory is actually the node registered " +
        s"within Rudder"

      case InventoryProcessStatus.QueueFull(reportName, nodeId) =>
        s"Rejecting Inventory '${reportName}' for Node '${nodeId.value}' because processing queue is full."

      case InventoryProcessStatus.Accepted(reportName, nodeId) =>
        s"Inventory '${reportName}' for Node '${nodeId.value}' added to processing queue."
    }
  }
}



class InventoryProcessor(
    unmarshaller    : ReportUnmarshaller
  , reportSaver     : ReportSaver[Seq[LDIFChangeRecord]]
  , val maxQueueSize: Int
  , val maxParallel : Long
  , repo            : FullInventoryRepository[Seq[LDIFChangeRecord]]
  , digestService   : InventoryDigestServiceV1
  , checkAliveLdap  : () => IOResult[Unit]
  , nodeInventoryDit: InventoryDit
) {

  // logs are not available here, need "print"
  println(s"INFO Configure inventory processing with parallelism of '${maxParallel}' and queue size of '${maxQueueSize}'")

  // we want to limit the number of reports concurrently parsed
  lazy val xmlParsingSemaphore = ZioRuntime.unsafeRun(Semaphore.make(maxParallel))

  /*
   * We manage inventories buffering with a bounded queue.
   * In case of overflow, we reject new inventories.
   */
  lazy val queue = ZioRuntime.unsafeRun(Queue.dropping[InventoryReport](maxQueueSize))

  def currentQueueSize: Int = ZioRuntime.unsafeRun(queue.size)

  /*
   * Saving reports is a loop which consume items from queue
   */
  def loop(): UIO[Nothing] = {
    queue.take.flatMap(saveReport).flatMap(_ => loop())
  }

  // start the inventory processing
  ZioRuntime.unsafeRun(loop().fork)

  // we need something that can produce the stream several time because we are reading the file several time - not very
  // optimized :/
  def saveInventory(info: SaveInventoryInfo): IOResult[InventoryProcessStatus] = {

    def saveWithSignature(report: InventoryReport, publicKey: JavaSecPubKey, newInventoryStream: () => InputStream, newSignature: () => InputStream): IOResult[InventoryProcessStatus] = {
      ZIO.bracket(Task.effect(newInventoryStream()).mapError(SystemError(s"Error when reading inventory file '${report.name}'", _)))(is => Task.effect(is.close()).run) { inventoryStream =>
        ZIO.bracket(Task.effect(newSignature()).mapError(SystemError(s"Error when reading signature for inventory file '${report.name}'", _)))(is => Task.effect(is.close()).run) { signatureStream =>

          for {
            digest  <- digestService.parse(signatureStream)
            checked <- digestService.check(publicKey, digest, inventoryStream)
            saved   <- if (checked) {
                         // Signature is valid, send it to save engine
                         // Set the keyStatus to Certified
                         // For now we set the status to certified since we want pending inventories to have their inventory signed
                         // When we will have a 'pending' status for keys we should set that value instead of certified
                         val certifiedReport = report.copy(node = report.node.copyWithMain(main => main.copy(keyStatus = CertifiedKey)))
                         checkQueueAndSave(certifiedReport)
                       } else {
                         // Signature is not valid, reject inventory
                         InventoryProcessStatus.SignatureInvalid(report.name, report.node.main.id).succeed
                       }
           } yield {
             saved
           }
        }
      }
    }

    def parseSafe(newInventoryStream: () => InputStream, inventoryFileName: String): IOResult[InventoryReport] = {
      ZIO.bracket(Task.effect(newInventoryStream()).mapError(SystemError(s"Error when trying to read inventory file '${inventoryFileName}'", _)) )(
        is => Task.effect(is.close()).run
      ){ is =>
        for {
          r <- unmarshaller.fromXml(inventoryFileName, is)
        } yield {
          // use the provided file name as report name, else it's a generic one setted by fusion (like "report")
          r.copy(name = inventoryFileName)
        }
      }
    }




    // actuall report processing logic


    val start = System.currentTimeMillis()

    val res = for {
      report       <- xmlParsingSemaphore.withPermit(
                        InventoryProcessingLogger.debug(s"Start parsing inventory '${info.fileName}'") *>
                        parseSafe(info.inventoryStream, info.fileName).chainError("Can't parse the input inventory, aborting") <*
                        InventoryProcessingLogger.trace(s"Parsing done for inventory '${info.fileName}'")
                      )
      secPair      <- digestService.getKey(report).chainError(s"Error when trying to check inventory key for Node '${report.node.main.id.value}'")
      parsed       <- digestService.parseSecurityToken(secPair._1)
      _            <- parsed.subject match {
                        case None       => UIO.unit
                        case Some(list) => SecurityToken.checkCertificateSubject(report.node.main.id, list)
                      }
      afterParsing =  System.currentTimeMillis()
      reportName   = report.name
      nodeId       = report.node.main.id
      _            =  InventoryProcessingLogger.debug(s"Inventory '${report.name}' parsed in ${PeriodFormat.getDefault.print(new Duration(afterParsing, System.currentTimeMillis).toPeriod)} ms, now saving")
      saved        <- info.optSignatureStream match { // Do we have a signature ?
                        // Signature here, check it
                        case Some(sig) =>
                          saveWithSignature(report, parsed.publicKey, info.inventoryStream, sig).chainError("Error when trying to check inventory signature")

                        // There is no Signature
                        case None =>
                          Inconsistancy(s"Error, inventory '${report.node}' has no signature, which is not supported anymore in Rudder 6.0. " +
                                        s"Please check that your node's agent is compatible with that version.").fail
                      }
      _            <- InventoryProcessingLogger.debug(s"Inventory '${report.name}' for node '${report.node.main.id.value}' pre-processed in ${PeriodFormat.getDefault.print(new Duration(start, System.currentTimeMillis).toPeriod)} ms")
    } yield {
      saved
    }


    res map { status =>
        import com.normation.rudder.inventory.StatusLog.LogMessage
        status match {
          case InventoryProcessStatus.MissingSignature(_,_) => InventoryProcessingLogger.error(status.msg)
          case InventoryProcessStatus.SignatureInvalid(_,_) => InventoryProcessingLogger.error(status.msg)
          case InventoryProcessStatus.QueueFull(_,_)        => InventoryProcessingLogger.warn(status.msg)
          case InventoryProcessStatus.Accepted(_,_)         => InventoryProcessingLogger.trace(status.msg)
        }
    } catchAll { err =>
        val fail = Chained(s"Error when trying to process inventory '${info.fileName}'", err)
        InventoryLogger.error(fail.fullMsg) *> err.fail
    }
    res
  }

  /*
   * Before actually trying to save, check that LDAP is up to at least
   * avoid the case where we are telling the use "everything is fine"
   * but just fail after.
   */
  def checkQueueAndSave(report: InventoryReport): IOResult[InventoryProcessStatus] = {

    (for {
      _     <- checkAliveLdap()
      canDo <- queue.offer(report)
      res   <- if(canDo) {
                 InventoryProcessStatus.Accepted(report.name, report.node.main.id).succeed
               } else {
                 InventoryProcessStatus.QueueFull(report.name, report.node.main.id).succeed
               }
    } yield {
      res
    }).catchAll { err =>
      val e = Chained(s"There is an error with the LDAP backend preventing acceptation of inventory '${report.name}'", err)
      InventoryLogger.error(err.fullMsg) *> e.fail
    }
  }



  /**
   * Encapsulate the logic to process new incoming inventories.
   *
   * The processing is successlly synchrone and monothreaded,
   * asynchronicity and multithreading are managed in the caller.
   *
   * It is not the consumer that manage the queue size, it only
   * decrease it when it terminates a processing.
   *
   */
  def saveReport(report:InventoryReport): UIO[Unit] = {
    for {
      _     <- InventoryProcessingLogger.trace(s"Start post processing of inventory '${report.name}' for node '${report.node.main.id.value}'")
      start <- UIO(System.currentTimeMillis)
      saved <- reportSaver.save(report).chainError("Can't merge inventory report in LDAP directory, aborting").either
      _     <- saved match {
                 case Left(err) =>
                   InventoryProcessingLogger.error(s"Error when trying to process report: ${err.fullMsg}")
                 case Right(report) =>
                   InventoryProcessingLogger.debug("Report saved.")
               }
      _      <- InventoryProcessingLogger.info(s"Report '${report.name}' for node '${report.node.main.hostname}' [${report.node.main.id.value}] (signature:${report.node.main.keyStatus.value}) "+
                s"processed in ${PeriodFormat.getDefault.print(new Duration(start, System.currentTimeMillis).toPeriod)} ms")
    } yield ()
  }
}
