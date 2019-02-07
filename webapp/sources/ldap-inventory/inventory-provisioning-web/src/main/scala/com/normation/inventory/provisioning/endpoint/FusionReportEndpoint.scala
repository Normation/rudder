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


import scala.util.control.NonFatal

import com.normation.inventory.services.core.FullInventoryRepository
import com.unboundid.ldif.LDIFChangeRecord

import org.joda.time.Duration
import org.joda.time.format.PeriodFormat
import org.springframework.http.{ HttpStatus, ResponseEntity }
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.multipart.support.DefaultMultipartHttpServletRequest

import javax.servlet.http.HttpServletRequest
import monix.execution.Ack
import monix.execution.Ack.Continue
import monix.execution.Scheduler
import monix.execution.atomic.AtomicInt
import monix.reactive.Observer
import monix.reactive.OverflowStrategy.Unbounded
import monix.reactive.observers.BufferedSubscriber
import monix.reactive.observers.Subscriber
import net.liftweb.common._
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestMethod.GET
import org.springframework.web.bind.annotation.RequestMethod.POST

import FusionReportEndpoint._
import com.normation.inventory.services.provisioning.ReportSaver
import com.normation.inventory.domain.CertifiedKey
import com.normation.inventory.services.provisioning.ReportUnmarshaller
import com.normation.inventory.domain.UndefinedKey
import com.normation.inventory.services.provisioning.InventoryDigestServiceV1
import com.normation.inventory.domain.InventoryReport
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.inventory.ldap.core.InventoryDit

object FusionReportEndpoint{
  val printer = PeriodFormat.getDefault
}


@Controller
class FusionReportEndpoint(
    unmarshaller    : ReportUnmarshaller
  , reportSaver     : ReportSaver[Seq[LDIFChangeRecord]]
  , maxQueueSize    : Int
  , repo            : FullInventoryRepository[Seq[LDIFChangeRecord]]
  , digestService   : InventoryDigestServiceV1
  , ldap            : LDAPConnectionProvider[RwLDAPConnection]
  , nodeInventoryDit: InventoryDit
) extends Loggable {



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


  /**
   * A status URL that just allow to check
   * that the endpoint is alive
   */
  @RequestMapping(
    value  = Array("/api/status"),
    method = Array(RequestMethod.GET)
  )
  def checkStatus() = new ResponseEntity("OK", HttpStatus.OK)


  /**
   * Info on current number of elements in queue
   */
  @RequestMapping(
    value  = Array("/api/info"),
    method = Array(RequestMethod.GET)
  )
  def queueInfo() = {
    // get the current size, the remaining of the answer is based on that
    val current = queueSize.get
    //must be coherent with can do, current = 49 < max = 50 => not saturated
    val saturated = current >= maxQueueSize
    val code = if(saturated) HttpStatus.TOO_MANY_REQUESTS else HttpStatus.OK
    val json = s"""{"queueMaxSize":$maxQueueSize, "queueFillCount":$current, "queueSaturated":$saturated}"""
    val headers = new HttpHeaders()
    headers.add("content-type", "application/json")
    new ResponseEntity(json, headers, code)
  }

  /**
   * The actual endpoint. It's here that
   * upload requests arrive
   * @param request
   * @return
   */
  @RequestMapping(
    value  = Array("/upload"),
    method = Array(RequestMethod.POST)
  )
  def onSubmit(request: HttpServletRequest) = {
    def defaultBadAnswer(reason: String) = {
      new ResponseEntity(
          s"""${reason}.
              |You have to POST a request with exactly one file in attachment (with 'content-disposition': file) with parameter name 'file'
              |For example, for curl, use: curl -F "file=@path/to/file"
          |""".stripMargin
        , HttpStatus.PRECONDITION_FAILED
      )
    }

    /*
     * Before actually trying to save, check that LDAP is up to at least
     * avoid the case where we are telling the use "everything is fine"
     * but just fail after.
     */
    def save(report: InventoryReport) = {
      checkLdapAlive match {
        case Full(ok) =>

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
            new ResponseEntity("Inventory correctly received and sent to inventory processor.\n", HttpStatus.ACCEPTED)
          } else {
            new ResponseEntity("Too many inventories waiting to be saved.\n", HttpStatus.SERVICE_UNAVAILABLE)
          }

        case eb: EmptyBox =>
          val e = (eb ?~! s"There is an error with the LDAP backend preventing acceptation of inventory '${report.name}'")
          logger.error(e.messageChain)
          new ResponseEntity(e.messageChain, HttpStatus.INTERNAL_SERVER_ERROR)
      }
    }

    /*
     * A method that check LDAP health status.
     * It must be quick and simple.
     */
    def checkLdapAlive: Box[String] = {
      for {
        con <- ldap
        res <- con.get(nodeInventoryDit.NODES.dn, "1.1")
      } yield {
        "ok"
      }
    }

    def parseInventory(inventoryFile : MultipartFile, signatureFile : Option[MultipartFile]): ResponseEntity[String]= {
      val inventoryFileName = inventoryFile.getOriginalFilename()
      //copy the session file somewhere where it won't be deleted on that method return
      logger.info(s"New input inventory: '${inventoryFileName}'")
      logger.trace(s"Start post parsing inventory '${inventoryFileName}'")
      try {
        val in = inventoryFile.getInputStream
        val start = System.currentTimeMillis

        (unmarshaller.fromXml(inventoryFile.getName,in) ?~! "Can't parse the input inventory, aborting") match {
          case Full(r) =>
            // set inventory file name to original one, because "file" is not very telling
            val report = r.copy(name = inventoryFileName)

            val afterParsing = System.currentTimeMillis
            logger.info(s"Inventory '${report.name}' parsed in ${printer.print(new Duration(start, afterParsing).toPeriod)} ms, now checking signature")
            // Do we have a signature ?
            signatureFile match {
              // Signature here, check it
              case Some(sig) => {
                val signatureStream = sig.getInputStream()
                val inventoryStream = inventoryFile.getInputStream()
                val response = for {
                  digest  <- digestService.parse(signatureStream)
                  (key,_) <- digestService.getKey(report)
                  checked <- digestService.check(key, digest, inventoryFile.getInputStream)
                  } yield {
                    if (checked) {
                      // Signature is valid, send it to save engine
                      logger.info(s"Inventory '${report.name}' signature checked in ${printer.print(new Duration(afterParsing, System.currentTimeMillis).toPeriod)} ms, now saving")
                      // Set the keyStatus to Certified
                      // For now we set the status to certified since we want pending inventories to have their inventory signed
                      // When we will have a 'pending' status for keys we should set that value instead of certified
                      val certifiedReport = report.copy(node = report.node.copyWithMain(main => main.copy(keyStatus = CertifiedKey)))
                      save(certifiedReport)
                    } else {
                      // Signature is not valid, reject inventory
                      val msg = s"Rejecting Inventory '${report.name}' for Node '${report.node.main.id.value}' because the Inventory signature is not valid: the Inventory was not signed with the same agent key as the one saved within Rudder for that Node. If you updated the agent key on this node, you can update the key stored within Rudder with the following command on the Rudder Server: '/opt/rudder/bin/rudder-keys change-key ${report.node.main.id.value} <your new public key>'. If you did not change the key, please ensure that the node sending that inventory is actually the node registered within Rudder"
                      logger.error(msg)
                      new ResponseEntity(msg, HttpStatus.UNAUTHORIZED)
                    }
                  }
                signatureStream.close()
                inventoryStream.close()
                response match {
                  case Full(response) =>
                    // Response of signature checking is ok send it
                    response
                  case eb: EmptyBox =>
                    // An error occurred while checking signature
                    logger.error(eb)
                    val fail = eb ?~! "Error when trying to check inventory signature"
                    logger.error(fail.messageChain)
                    logger.debug(s"Time to error: ${printer.print(new Duration(start, System.currentTimeMillis).toPeriod)} ms")
                    new ResponseEntity(fail.messageChain, HttpStatus.PRECONDITION_FAILED)
                }
              }

              // There is no Signature
              case None =>
                // Check if we need a signature or not
                digestService.getKey(report) match {
                  // Status is undefined => We accept unsigned inventory
                  case Full((_,UndefinedKey)) => {
                    save(report)
                  }
                  // We are in certified state, refuse inventory with no signature
                  case Full((_,CertifiedKey))  =>
                    val msg = s"Rejecting Inventory '${report.name}' for Node '${report.node.main.id.value}' because its signature is missing. You can go back to unsigned state by running the following command on the Rudder Server: '/opt/rudder/bin/rudder-keys reset-status ${report.node.main.id.value}'"
                    logger.error(msg)
                    new ResponseEntity(msg, HttpStatus.UNAUTHORIZED)
                  // An error occurred while checking inventory key status
                  case eb: EmptyBox =>
                    logger.error(eb)
                    val fail = eb ?~! s"Error when trying to check inventory key status for Node '${report.node.main.id.value}'"
                    logger.error(fail.messageChain)
                    logger.debug(s"Time to error: ${printer.print(new Duration(start, System.currentTimeMillis).toPeriod)} ms")
                    new ResponseEntity(fail.messageChain, HttpStatus.PRECONDITION_FAILED)
                }
            }

          // Error during parsing
          case eb : EmptyBox =>
            val fail = eb ?~! "Error when trying to parse inventory"
            logger.error(fail.messageChain)
            fail.rootExceptionCause.foreach { exp => logger.error(s"Exception was: ${exp}") }
            logger.debug(s"Time to error: ${printer.print(new Duration(start, System.currentTimeMillis).toPeriod)} ms")
            new ResponseEntity(fail.messageChain, HttpStatus.PRECONDITION_FAILED)
        }
      } catch {
        case NonFatal(ex) =>
          val msg = s"Error when trying to parse inventory '${inventoryFileName}': ${ex.getMessage}"
          logger.error(msg, ex)
          new ResponseEntity(msg, HttpStatus.PRECONDITION_FAILED)
      }
    }

    request match {
      case multipart: DefaultMultipartHttpServletRequest =>
        import scala.collection.JavaConverters._
        val params : scala.collection.mutable.Map[String, MultipartFile] = multipart.getFileMap.asScala

        /*
         * params are :
         * * 'file' => The inventory file
         * * 'signature' => The signature file
         */
        val inventoryParam = "file"
        val signatureParam = "signature"

        (params.get(inventoryParam), params.get(signatureParam)) match {
          // No inventory, error
          case (None,_) =>
            defaultBadAnswer("No inventory sent")
          // Only inventory sent
          case (Some(inventory),None) => {
            parseInventory(inventory,None)
          }
          // An inventory and signature check them!
          case (Some(inventory), Some(signature)) => {
            parseInventory(inventory,Some(signature))
          }
        }
    }
  }


  /**
   * Encapsulate the logic to process new incoming inventories.
   *
   * The processing is purelly synchrone and monotheaded,
   * asynchronicity and multithreading are managed in the caller.
   *
   * It is not the consumer that manage the queue size, it only
   * decrease it when it terminates a processing.
   *
   */
  class ProcessInventoryObserver(queueSize: AtomicInt) extends Observer.Sync[InventoryReport] {

    /*
     * The only caller. It is not allowed to throw (non fatal) exceptions
     */
    def onNext(report: InventoryReport): Ack = {

      try {
        saveReport(report)
      } catch {
        case NonFatal(ex) =>
          logger.error(s"Error when processing inventory report '${report.name}': ${ex.getMessage}", ex)
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
      logger.error(s"The async inventory proccessor got an 'error' message with the following exception: ${ex.getMessage}", ex)
    }

    /*
     * That one should not happens, as the normal usage is to wait
     * for the closure of web server
     */
    def onComplete(): Unit = {
      logger.error(s"The async inventory proccessor got an 'termination' message.")
    }

  }


  private def saveReport(report:InventoryReport) : Unit = {
    logger.trace("Start post processing of report %s".format(report.name))
    try {
      val start = System.currentTimeMillis
      (reportSaver.save(report) ?~! "Can't merge inventory report in LDAP directory, aborting") match {
        case Empty => logger.error("The report is empty, not saving anything")
        case f:Failure =>
          logger.error("Error when trying to process report: %s".format(f.messageChain),f)
        case Full(report) =>
          logger.debug("Report saved.")
      }
      logger.info(s"Report '${report.name}' for node '${report.node.main.hostname}' [${report.node.main.id.value}] (signature:${report.node.main.keyStatus.value}) "+
                  s"processed in ${printer.print(new Duration(start, System.currentTimeMillis).toPeriod)} ms")
    } catch {
      case e:Exception =>
        logger.error("Exception when processing report %s".format(report.name))
        logger.error("Reported exception is: ", e)
    }
  }
}
