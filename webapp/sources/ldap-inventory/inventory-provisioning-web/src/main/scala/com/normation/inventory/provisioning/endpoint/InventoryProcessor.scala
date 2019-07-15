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

package com.normation.inventory.provisioning.endpoint

import java.security.{PublicKey => JavaSecPubKey}

import com.normation.NamedZioLogger
import com.normation.errors.Chained
import com.normation.errors.IOResult
import com.normation.errors.SystemError
import com.normation.inventory.domain.CertifiedKey
import com.normation.inventory.domain.InventoryLogger
import com.normation.inventory.domain.InventoryReport
import com.normation.errors._
import com.normation.inventory.domain.InventoryError
import com.normation.inventory.domain.KeyStatus
import com.normation.inventory.domain.UndefinedKey
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.provisioning.endpoint.FusionReportEndpoint._
import com.normation.inventory.services.core.FullInventoryRepository
import com.normation.inventory.services.provisioning.InventoryDigestServiceV1
import com.normation.inventory.services.provisioning.ParsedSecurityToken
import com.normation.inventory.services.provisioning.ReportSaver
import com.normation.inventory.services.provisioning.ReportUnmarshaller
import com.normation.zio.ZioRuntime
import com.unboundid.ldif.LDIFChangeRecord
import org.joda.time.Duration
import zio._
import zio.syntax._

import scala.tools.nsc.interpreter.InputStream

object InventoryProcessingLogger extends NamedZioLogger {
  override def loggerName: String = "inventory-processing"
}

sealed trait InventoryProcessStatus { def report: InventoryReport }
object InventoryProcessStatus {
  final case class Accepted        (report: InventoryReport) extends InventoryProcessStatus
  final case class QueueFull       (report: InventoryReport) extends InventoryProcessStatus
  final case class SignatureInvalid(report: InventoryReport) extends InventoryProcessStatus
  final case class MissingSignature(report: InventoryReport) extends InventoryProcessStatus
}


object StatusLog {
  implicit class LogMessage(status: InventoryProcessStatus) {
    def msg: String = status match {
      case InventoryProcessStatus.MissingSignature(report) =>
        s"Rejecting Inventory '${report.name}' for Node '${report.node.main.id.value}' because its signature is missing. " +
        s"You can go back to unsigned state by running the following command on the Rudder Server: " +
        s"'/opt/rudder/bin/rudder-keys reset-status ${report.node.main.id.value}'"

      case InventoryProcessStatus.SignatureInvalid(report) =>
        s"Rejecting Inventory '${report.name}' for Node '${report.node.main.id.value}' because the Inventory signature is " +
        s"not valid: the Inventory was not signed with the same agent key as the one saved within Rudder for that Node. If " +
        s"you updated the agent key on this node, you can update the key stored within Rudder with the following command on " +
        s"the Rudder Server: '/opt/rudder/bin/rudder-keys change-key ${report.node.main.id.value} <your new public key>'. " +
        s"If you did not change the key, please ensure that the node sending that inventory is actually the node registered " +
        s"within Rudder"

      case InventoryProcessStatus.QueueFull(report) =>
        s"Rejecting Inventory '${report.name}' for Node '${report.node.main.id.value}' because processing queue is full."

      case InventoryProcessStatus.Accepted(report) =>
        s"Inventory '${report.name}' for Node '${report.node.main.id.value}' added to processing queue."
    }
  }
}



class InventoryProcessor(
    unmarshaller    : ReportUnmarshaller
  , reportSaver     : ReportSaver[Seq[LDIFChangeRecord]]
  , val maxQueueSize: Int
  , repo            : FullInventoryRepository[Seq[LDIFChangeRecord]]
  , digestService   : InventoryDigestServiceV1
  , checkAliveLdap  : () => IOResult[Unit]
  , nodeInventoryDit: InventoryDit
) {

  val logger = InventoryProcessingLogger

  /*
   * We manage inventories buffering with a bounded queue.
   * In case of overflow, we reject new inventories.
   */
  val queue = ZioRuntime.unsafeRun(Queue.dropping[InventoryReport](maxQueueSize))

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
                         InventoryProcessStatus.SignatureInvalid(report).succeed
                       }
           } yield {
             saved
           }
        }
      }
    }

    def saveNoSignature(report: InventoryReport, keyStatus: KeyStatus): IOResult[InventoryProcessStatus] = {
      // Check if we need a signature or not
      (keyStatus match {
        // Status is undefined => We accept unsigned inventory
        case UndefinedKey => checkQueueAndSave(report)
        // We are in certified state, refuse inventory with no signature
        case CertifiedKey => InventoryProcessStatus.MissingSignature(report).succeed
      }).chainError(
        // An error occurred while checking inventory key status
        s"Error when trying to check inventory key status for Node '${report.node.main.id.value}'"
      )
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

    def checkCertificateSubject(inventoryReport: InventoryReport, optSubject: Option[List[(String, String)]]): IO[InventoryError, Unit] = {
      //format subject
      def formatSubject(list: List[(String, String)]) = list.map(kv => s"${kv._1}=${kv._2}").mkString(",")

      optSubject match {
        case None => // OK
          UIO.unit
        case Some(list) =>
          // in rudder, we ensure that at list one (k,v) pair is "UID = the node id". If missing, it's an error

          list.find { case (k,v) => k == ParsedSecurityToken.nodeidOID } match {
            case None        => InventoryError.SecurityToken(s"Certificate subject doesn't contain node ID in 'UID' attribute: ${formatSubject(list)}").fail
            case Some((k,v)) =>
              if(v.trim.toLowerCase == inventoryReport.node.main.id.value.toLowerCase) {
                UIO.unit
              } else {
                InventoryError.SecurityToken(s"Certificate subject doesn't contain same node ID in 'UID' attribute as inventory node ID: ${formatSubject(list)}").fail
              }
          }
      }
    }


    // actuall report processing logic


    val start = System.currentTimeMillis()

    val res = for {
      _            <- logger.debug(s"Start parsing inventory '${info.fileName}'")
      report       <- parseSafe(info.inventoryStream, info.fileName).chainError("Can't parse the input inventory, aborting")
      secPair      <- digestService.getKey(report).chainError(s"Error when trying to check inventory key for Node '${report.node.main.id.value}'")
      parsed       <- digestService.parseSecurityToken(secPair._1)
      _            <- checkCertificateSubject(report, parsed.subject)
      afterParsing =  System.currentTimeMillis()
      _            =  logger.debug(s"Inventory '${report.name}' parsed in ${printer.print(new Duration(afterParsing, System.currentTimeMillis).toPeriod)} ms, now saving")
      saved        <- info.optSignatureStream match { // Do we have a signature ?
                        // Signature here, check it
                        case Some(sig) =>
                          saveWithSignature(report, parsed.publicKey, info.inventoryStream, sig).chainError("Error when trying to check inventory signature")

                        // There is no Signature
                        case None =>
                          saveNoSignature(report, secPair._2).chainError(s"Error when trying to check inventory key status for Node '${report.node.main.id.value}'")
                      }
      _            <- logger.debug(s"Inventory '${report.name}' for node '${report.node.main.id.value}' pre-processed in ${printer.print(new Duration(start, System.currentTimeMillis).toPeriod)} ms")
    } yield {
      saved
    }


    res map { status =>
        import com.normation.inventory.provisioning.endpoint.StatusLog.LogMessage
        status match {
          case InventoryProcessStatus.MissingSignature(_) => logger.error(status.msg)
          case InventoryProcessStatus.SignatureInvalid(_) => logger.error(status.msg)
          case InventoryProcessStatus.QueueFull(_)        => logger.warn(status.msg)
          case InventoryProcessStatus.Accepted(_)         => logger.trace(status.msg)
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
                 InventoryProcessStatus.Accepted(report).succeed
               } else {
                 InventoryProcessStatus.QueueFull(report).succeed
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
      _     <- logger.trace(s"Start post processing of inventory '${report.name}' for node '${report.node.main.id.value}'")
      start <- UIO(System.currentTimeMillis)
      saved <- reportSaver.save(report).chainError("Can't merge inventory report in LDAP directory, aborting").either
      _     <- saved match {
                 case Left(err) =>
                   logger.error(s"Error when trying to process report: ${err.fullMsg}")
                 case Right(report) =>
                   logger.debug("Report saved.")
               }
      _      <- logger.info(s"Report '${report.name}' for node '${report.node.main.hostname}' [${report.node.main.id.value}] (signature:${report.node.main.keyStatus.value}) "+
                s"processed in ${printer.print(new Duration(start, System.currentTimeMillis).toPeriod)} ms")
    } yield ()
  }
}
