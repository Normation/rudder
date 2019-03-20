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


import com.normation.errors.Chained
import com.normation.zio.ZioRuntime
import javax.servlet.http.HttpServletRequest
import org.joda.time.format.PeriodFormat
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.multipart.support.DefaultMultipartHttpServletRequest
import scalaz.zio._
import scalaz.zio.syntax._

object FusionReportEndpoint{
  val printer = PeriodFormat.getDefault
}


@Controller
class FusionReportEndpoint(
    inventoryProcessor: InventoryProcessor
  , inventoryFileWatcher: InventoryFileWatcher
) {


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
    val current = inventoryProcessor.queueSize.get
    //must be coherent with can do, current = 49 < max = 50 => not saturated
    val saturated = current >= inventoryProcessor.maxQueueSize
    val code = if(saturated) HttpStatus.TOO_MANY_REQUESTS else HttpStatus.OK
    val json = s"""{"queueMaxSize":${inventoryProcessor.maxQueueSize}, "queueFillCount":$current, "queueSaturated":$saturated}"""
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



    def parseInventory(inventoryFile : MultipartFile, signatureFile : Option[MultipartFile]): ResponseEntity[String]= {
      // here, we are at the end of our world. Evaluate ZIO and see what happen.
      ZioRuntime.unsafeRun(inventoryProcessor.saveInventory(() => inventoryFile.getInputStream, inventoryFile.getOriginalFilename, signatureFile.map(f => () => f.getInputStream)).map {
        status =>
          import com.normation.inventory.provisioning.endpoint.StatusLog.LogMessage
          status match {
            case InventoryProcessStatus.MissingSignature(_) => new ResponseEntity(status.msg, HttpStatus.UNAUTHORIZED)
            case InventoryProcessStatus.SignatureInvalid(_) => new ResponseEntity(status.msg, HttpStatus.UNAUTHORIZED)
            case InventoryProcessStatus.QueueFull(_)        => new ResponseEntity(status.msg, HttpStatus.SERVICE_UNAVAILABLE)
            case InventoryProcessStatus.Accepted(_)         => new ResponseEntity(status.msg, HttpStatus.ACCEPTED)
          }
      }.catchAll { eb =>
        val fail = Chained(s"Error when trying to process inventory '${inventoryFile.getOriginalFilename}'", eb)
        InventoryProcessingLogger.error(fail.fullMsg) *>
        new ResponseEntity(fail.fullMsg, HttpStatus.PRECONDITION_FAILED).succeed
      })
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

          case (Some(inventory), sig) => {
            InventoryProcessingLogger.info(s"API got new inventory file '${inventory.getOriginalFilename}' with signature ${if(sig.isDefined) "" else "not "}available: process.")
            parseInventory(inventory, sig)
          }
        }
    }
  }


  /**
   * Start the inventory file watcher
   */
  @RequestMapping(
    value  = Array("/api/watcher/start"),
    method = Array(RequestMethod.POST)
  )
  def startWatcher() = {
    inventoryFileWatcher.startWatcher() match {
      case Right(()) =>
        new ResponseEntity("Incoming inventory watcher started", HttpStatus.OK)
      case Left(ex) =>
        new ResponseEntity(s"Error when trying to start incoming inventories file watcher. Reported exception was: ${ex.getMessage()}.", HttpStatus.INTERNAL_SERVER_ERROR)
    }
  }

  /**
   * Stop the inventory file watcher
   */
  @RequestMapping(
    value  = Array("/api/watcher/stop"),
    method = Array(RequestMethod.POST)
  )
  def stopWatcher() = {
    inventoryFileWatcher.stopWatcher() match {
      case Right(()) =>
        new ResponseEntity("Incoming inventory watcher stoped", HttpStatus.OK)
      case Left(ex) =>
        new ResponseEntity(s"Error when trying to stop incoming inventories file watcher. Reported exception was: ${ex.getMessage()}.", HttpStatus.INTERNAL_SERVER_ERROR)
    }
  }

  /**
   * Restart the inventory file watcher
   */
  @RequestMapping(
    value  = Array("/api/watcher/restart"),
    method = Array(RequestMethod.POST)
  )
  def restartWatcher() = {
    inventoryFileWatcher.stopWatcher()
    inventoryFileWatcher.startWatcher()
    new ResponseEntity("OK", HttpStatus.OK)
  }
}
