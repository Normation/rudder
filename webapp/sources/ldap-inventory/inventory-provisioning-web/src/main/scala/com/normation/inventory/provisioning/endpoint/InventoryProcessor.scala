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

import com.normation.NamedZioLogger
import com.normation.ZioLogger
import com.normation.errors.Chained
import com.normation.errors.IOResult
import com.normation.errors.SystemError
import com.normation.inventory.domain.CertifiedKey
import com.normation.inventory.domain.InventoryError
import com.normation.inventory.domain.InventoryLogger
import com.normation.inventory.domain.InventoryReport
import com.normation.inventory.domain.InventoryResult.InventoryResult
import com.normation.inventory.domain.UndefinedKey
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.provisioning.endpoint.FusionReportEndpoint._
import com.normation.inventory.services.core.FullInventoryRepository
import com.normation.inventory.services.provisioning.InventoryDigestServiceV1
import com.normation.inventory.services.provisioning.ReportSaver
import com.normation.inventory.services.provisioning.ReportUnmarshaller
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.zio.ZioRuntime
import com.unboundid.ldif.LDIFChangeRecord
import monix.execution.Ack
import monix.execution.Ack.Continue
import monix.execution.Scheduler
import monix.execution.atomic.AtomicInt
import monix.reactive.Observer
import monix.reactive.OverflowStrategy.Unbounded
import monix.reactive.observers.BufferedSubscriber
import monix.reactive.observers.Subscriber
import org.joda.time.Duration

import scala.tools.nsc.interpreter.InputStream
import scala.util.control.NonFatal
import scalaz.zio._
import scalaz.zio.syntax._

object InventoryProcessingLogger extends NamedZioLogger("inventory-processing")

sealed trait InventoryProcessStatus { def report: InventoryReport }
final object InventoryProcessStatus {
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
  , ldap            : LDAPConnectionProvider[RwLDAPConnection]
  , nodeInventoryDit: InventoryDit
) {

  val logger = InventoryProcessingLogger

  // current queue size
  lazy val queueSize = AtomicInt(0)

  // the asynchrone, bufferised processor
  lazy val reportProcess = {
    // the synchronize report processor
    // we use the i/o scheduler given the kind of task for report processor
    val syncReportProcess = Subscriber(new ProcessInventoryObserver(queueSize), Scheduler.io())

    // and we async/buf it. The "overflow" strategy is unbound, because we
    // will manage it by hand with the queuesize
    BufferedSubscriber[InventoryReport](syncReportProcess, Unbounded)
  }

  // we need something that can produce the stream several time because we are reading the file several time - not very
  // optimized :/
  def saveInventory(newInventoryStream: () => InputStream, inventoryFileName: String, optNewSignatureStream : Option[() => InputStream]): IOResult[InventoryProcessStatus] = {

    def saveWithSignature(report: InventoryReport, newInventoryStream: () => InputStream, newSignature: () => InputStream): InventoryResult[InventoryProcessStatus] = {
      ZIO.bracket(Task.effect(newInventoryStream()).mapError(SystemError(s"Error when reading inventory file '${report.name}'", _)))(is => Task.effect(is.close()).run) { inventoryStream =>
        ZIO.bracket(Task.effect(newSignature()).mapError(SystemError(s"Error when reading signature for inventory file '${report.name}'", _)))(is => Task.effect(is.close()).run) { signatureStream =>
          for {
            digest  <- digestService.parse(signatureStream)
            key     <- digestService.getKey(report)
            checked <- digestService.check(key._1, digest, inventoryStream)
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

    def saveNoSignature(report: InventoryReport): IOResult[InventoryProcessStatus] = {
      // Check if we need a signature or not
      digestService.getKey(report).flatMap {
        // Status is undefined => We accept unsigned inventory
        case (_,UndefinedKey) => {
          checkQueueAndSave(report)
        }
        // We are in certified state, refuse inventory with no signature
        case (_,CertifiedKey)  =>
          InventoryProcessStatus.MissingSignature(report).succeed
      }.chainError(
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


    // actuall report processing logic


    val start = System.currentTimeMillis()

    val res = for {
      _            <- logger.debug(s"Start parsing inventory '${inventoryFileName}'")
      report       <- parseSafe(newInventoryStream, inventoryFileName).chainError("Can't parse the input inventory, aborting")
      afterParsing =  System.currentTimeMillis()
      _            =  logger.debug(s"Inventory '${report.name}' parsed in ${printer.print(new Duration(afterParsing, System.currentTimeMillis).toPeriod)} ms, now saving")
      saved        <- optNewSignatureStream match { // Do we have a signature ?
                        // Signature here, check it
                        case Some(sig) =>
                          saveWithSignature(report, newInventoryStream, sig).chainError("Error when trying to check inventory signature")

                        // There is no Signature
                        case None =>
                          saveNoSignature(report).chainError(s"Error when trying to check inventory key status for Node '${report.node.main.id.value}'")
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
        val fail = Chained(s"Error when trying to process inventory '${inventoryFileName}'", err)
        InventoryLogger.error(fail.fullMsg) *> err.fail
    }
    res
  }

  /*
   * Before actually trying to save, check that LDAP is up to at least
   * avoid the case where we are telling the use "everything is fine"
   * but just fail after.
   */
  def checkQueueAndSave(report: InventoryReport): InventoryResult[InventoryProcessStatus] = {
    /*
     * A method that check LDAP health status.
     * It must be quick and simple.
     */
    def checkLdapAlive(ldap: LDAPConnectionProvider[RwLDAPConnection]): IOResult[String] = {
      for {
        con <- ldap
        res <- con.get(nodeInventoryDit.NODES.dn, "1.1")
      } yield {
        "ok"
      }
    }

    checkLdapAlive(ldap).flatMap { _ =>
      val canDo = synchronized {
        if(queueSize.incrementAndGet(1) <= maxQueueSize) {
          // the decrement will be done by the report processor
          true
        } else {
          // clean the not used increment
          queueSize.decrement(1)
          false
        }
      }

      if(canDo) {
        //queue the inventory processing
        reportProcess.onNext(report)
        InventoryProcessStatus.Accepted(report).succeed
      } else {
        InventoryProcessStatus.QueueFull(report).succeed
      }
    }.catchAll { err =>
      val e = Chained(s"There is an error with the LDAP backend preventing acceptation of inventory '${report.name}'", err)
      InventoryLogger.error(err.fullMsg) *> e.fail
    }
  }



  /**
   * Encapsulate the logic to process new incoming inventories.
   *
   * The processing is successlly synchrone and monotheaded,
   * asynchronicity and multithreading are managed in the caller.
   *
   * It is not the consumer that manage the queue size, it only
   * decrease it when it terminates a processing.
   *
   */
  protected class ProcessInventoryObserver(queueSize: AtomicInt) extends Observer.Sync[InventoryReport] {

    /*
     * The only caller. It is not allowed to throw (non fatal) exceptions
     */
    def onNext(report: InventoryReport): Ack = {

      try {
        saveReport(report)
      } catch {
        case NonFatal(ex) =>
          logger.logEffect.error(s"Error when processing inventory report '${report.name}': ${ex.getMessage}", ex)
      }
      // always tell the feeder that the report is processed
      queueSize.decrement(1)
      // this is the contract of observer
      Continue
    }

    /*
     * That one should not be called. Log the error.
     */
    def onError(ex: Throwable): Unit =  {
      logger.logEffect.error(s"The async inventory proccessor got an 'error' message with the following exception: ${ex.getMessage}", ex)
    }

    /*
     * That one should not happens, as the normal usage is to wait
     * for the closure of web server
     */
    def onComplete(): Unit = {
      logger.logEffect.error(s"The async inventory proccessor got an 'termination' message.")
    }

  }


  private def saveReport(report:InventoryReport) : Unit = {
    logger.logEffect.trace(s"Start post processing of inventory '${report.name}' for node '${report.node.main.id.value}'")
    val start = System.currentTimeMillis
    //end of the world, evaluate
    ZioRuntime.unsafeRun(reportSaver.save(report).chainError("Can't merge inventory report in LDAP directory, aborting").either) match {
      case Left(err) =>
        logger.logEffect.error(s"Error when trying to process report: ${err.fullMsg}")
      case Right(report) =>
        logger.logEffect.debug("Report saved.")
    }
    logger.logEffect.info(s"Report '${report.name}' for node '${report.node.main.hostname}' [${report.node.main.id.value}] (signature:${report.node.main.keyStatus.value}) "+
                s"processed in ${printer.print(new Duration(start, System.currentTimeMillis).toPeriod)} ms")
  }
}
