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

package com.normation.inventory.provisioning.endpoint


import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.multipart.support.DefaultMultipartHttpServletRequest
import org.springframework.http.{HttpStatus,ResponseEntity}
import com.normation.inventory.domain._
import com.normation.inventory.services.provisioning._
import net.liftweb.common._
import scala.collection.JavaConversions._
import org.joda.time.Duration
import org.joda.time.format.PeriodFormat
import org.slf4j.LoggerFactory
import java.io.{IOException, File, FileInputStream, InputStream, FileOutputStream}
import FusionReportEndpoint._
import com.unboundid.ldif.LDIFChangeRecord
import javax.servlet.http.HttpServletRequest

object FusionReportEndpoint{
  val printer = PeriodFormat.getDefault
}

@Controller
class FusionReportEndpoint(
    unmarshaller:ReportUnmarshaller,
    reportSaver:ReportSaver[Seq[LDIFChangeRecord]],
    queueSize: Int
) extends Loggable {

  //start the report processor actor
  ReportProcessor.start


  /**
   * A status URL that just allow to check
   * that the endpoint is alive
   */
  @RequestMapping(
    value = Array("/api/status"),
    method = Array(RequestMethod.GET)
  )
  def checkStatus() = new ResponseEntity("OK", HttpStatus.OK)

  /**
   * The actual endpoint. It's here that
   * upload requests arrive
   * @param request
   * @return
   */
  @RequestMapping(
    value = Array("/upload"),
    method = Array(RequestMethod.POST)
  )
  def onSubmit(request: HttpServletRequest) = {
    def defaultBadAnswer(reason: String) = new ResponseEntity(s"""${reason} sent. You have to POST a request with exactly one file in attachment (with 'content-disposition': file)
                               |For example, for curl, use: curl -F "file=@path/to/file"
                               |""".stripMargin, HttpStatus.PRECONDITION_FAILED)


    request match {
      case multipart: DefaultMultipartHttpServletRequest =>

        val files = multipart.getFileMap.values

        if(files.size <= 0) {
          defaultBadAnswer("No inventory")
        } else if(files.size == 1) {
          val reportFile = files.toSeq(0)
          //copy the session file somewhere where it won't be deleted on that method return
          logger.info("New input inventory: '%s'".format(reportFile.getOriginalFilename))
          //val reportFile = copyFileToTempDir(f)

          var in : InputStream = null
          logger.trace("Start post parsing inventory '%s'".format(reportFile.getOriginalFilename))
          try {
            in = reportFile.getInputStream
            val start = System.currentTimeMillis

            (unmarshaller.fromXml(reportFile.getName,in) ?~! "Can't parse the input inventory, aborting") match {
              case Full(report) if(null != report) =>
                logger.info(s"Inventory '${reportFile.getOriginalFilename}' parsed in ${printer.print(new Duration(start, System.currentTimeMillis).toPeriod)}, sending to save engine.\n")
                //send report to asynchronous report processor
                (ReportProcessor !? report) match {
                  case OkToSave =>
                    //release connection
                    new ResponseEntity("Inventory correctly received and sent to inventory processor.\n", HttpStatus.ACCEPTED)
                  case TooManyInQueue =>
                    new ResponseEntity("Too many inventories waiting to be saved.\n", HttpStatus.SERVICE_UNAVAILABLE)
                }
              case f@Failure(_,_,_) =>
                val msg = "Error when trying to parse inventory: %s".format(f.failureChain.map( _.msg).mkString("\n", "\ncause: ", "\n"))
                logger.error(msg)
                f.rootExceptionCause.foreach { exp => logger.error("Exception was: ", exp) }
                logger.debug("Time to error: %s".format(printer.print(new Duration(start, System.currentTimeMillis).toPeriod)))
                new ResponseEntity(msg, HttpStatus.PRECONDITION_FAILED)
              case _ =>
                val msg = "The inventory is empty, not saving anything."
                logger.error(msg)
                logger.debug("Time to error: %s".format(printer.print(new Duration(start, System.currentTimeMillis).toPeriod)))
                new ResponseEntity(msg, HttpStatus.PRECONDITION_FAILED)
            }
          } catch {
            case e:Exception =>
              val msg = "Exception when processing inventory '%s'".format(reportFile.getOriginalFilename)
              logger.error(msg)
              logger.error("Reported exception is: ", e)
              new ResponseEntity(msg+"\n", HttpStatus.PRECONDITION_FAILED)
          } finally {
            in.close
          }
        } else {
          //more than one file: we don't know the one to take, so we just ask the user to only send one file
          defaultBadAnswer("Too many files")
        }

      case _ =>
        defaultBadAnswer("No inventory")
    }
  }





  //two message to know if the backend accept to process the report
  case object OkToSave
  case object TooManyInQueue


  import scala.actors.Actor
  import Actor._

  /**
   * An asynchronous actor process the query
   */
  private object ReportProcessor extends Actor {
    self =>

    //a message from the processor to self
    //saying it finish processing
    case object Processed

    var inQueue = 0

    //that actor only check the number of queued elements and decide to
    //queue it or not
    override def act = {
      loop { react {
          case i:InventoryReport =>

            if(inQueue < queueSize) {
              actualProcessor ! i
              reply(OkToSave)
              inQueue += 1
            } else {
              logger.warn(s"Not processing inventory ${i.name} because there is already the maximum number (${inQueue}) of inventory waiting to be processed")
              reply(TooManyInQueue)
            }

          case Processed =>
            inQueue -= 1
      } }
    }

    //this is the actor that process the report
    val actualProcessor = new Actor {
      override def act = {
        loop { react {
            case i:InventoryReport =>
              saveReport(i)
              self ! Processed
        } }
      }
    }

    actualProcessor.start

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
      logger.info("Report %s processed in %s".format(report.name, printer.print(new Duration(start, System.currentTimeMillis).toPeriod)))
    } catch {
      case e:Exception =>
        logger.error("Exception when processing report %s".format(report.name))
        logger.error("Reported exception is: ", e)
    }
  }
}
