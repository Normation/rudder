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

object FusionReportEndpoint{
  val printer = PeriodFormat.getDefault
}

@Controller
class FusionReportEndpoint(
    unmarshaller:ReportUnmarshaller,
    reportSaver:ReportSaver[Seq[LDIFChangeRecord]]
) extends Loggable {
  
  //start the report processor actor
  ReportProcessor.start
  
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
  def onSubmit(request:DefaultMultipartHttpServletRequest) = {

    val files = request.getFileMap.values
    
    files.size match {
      case 0 => new ResponseEntity("""No report sent. You have to POST a request with exactly one file in attachment (with 'content-disposition': file)
                                     |For example, for curl, use: curl -F "file=@path/to/file"
                                     |""".stripMargin, HttpStatus.PRECONDITION_FAILED)
      case 1 =>
        val reportFile = files.toSeq(0)
        //copy the session file somewhere where it won't be deleted on that method return
        logger.info("New input report: '%s'".format(reportFile.getOriginalFilename))
        //val reportFile = copyFileToTempDir(f)
        
        var in : InputStream = null
        logger.trace("Start post parsing report '%s'".format(reportFile.getOriginalFilename))
        try {
          in = reportFile.getInputStream
          val start = System.currentTimeMillis

          (unmarshaller.fromXml(reportFile.getName,in) ?~! "Can't parse the input report, aborting") match {
            case Full(report) if(null != report) => 
              logger.info("Report '%s' parsed in %s, sending to save engine.".format(reportFile.getOriginalFilename, printer.print(new Duration(start, System.currentTimeMillis).toPeriod)))
              //send report to asynchronous report processor
              ReportProcessor ! report
              //release connection
              new ResponseEntity("Report correctly received and sent to report processor.", HttpStatus.ACCEPTED)
            case f@Failure(_,_,_) => 
              val msg = "Error when trying to parse report: %s".format(f.failureChain.map( _.msg).mkString("\n", "\ncause: ", "\n"))
              logger.error(msg)
              f.rootExceptionCause.foreach { exp => logger.error("Exception was: ", exp) }
              logger.debug("Time to error: %s".format(printer.print(new Duration(start, System.currentTimeMillis).toPeriod)))
              new ResponseEntity(msg, HttpStatus.PRECONDITION_FAILED)
            case _ => 
              val msg = "The report is empty, not saving anything."
              logger.error(msg)
              logger.debug("Time to error: %s".format(printer.print(new Duration(start, System.currentTimeMillis).toPeriod)))
              new ResponseEntity(msg, HttpStatus.PRECONDITION_FAILED)
          }
        } catch {
          case e => 
            val msg = "Exception when processing report '%s'".format(reportFile.getOriginalFilename)
            logger.error(msg)
            logger.error("Reported exception is: ", e)
            new ResponseEntity(msg, HttpStatus.PRECONDITION_FAILED)
        } finally {
          in.close
        }
        
        //clean-up
//        try {
//          if(! new File(reportFile.getAbsolutePath).delete) {
//            logger.error("Error when trying to delete temporary report file '%s'. You will have to delete it by hand.".format(reportFile.getAbsolutePath))
//          }
//        } catch {
//          case e => 
//            logger.error("Exception when processing report {}",reportFile.getAbsolutePath)
//            logger.error("Reported exception is: ", e)
//        }
    }
  }

  import scala.actors.Actor
  import Actor._
  
  /**
   * An asynchronous actor process the query
   */
  private object ReportProcessor extends Actor {
    override def act = {
      loop {
        react {
          case i:InventoryReport => saveReport(i)
        }
      }
    }
  }

//  private def copyFileToTempDir(src:MultipartFile) : File = {
//    val in = src.getInputStream
//    val fout = File.createTempFile(src.getName+"_"+System.currentTimeMillis, ".tmp")
//    logger.debug("Saving {} to {} for post-processing", src.getName, fout.getAbsolutePath)
//    val out = new FileOutputStream(fout)
//    // Transfer bytes from in to out 
//    val buf = new Array[Byte](1024)
//    var len = 0
//    while ({len = in.read(buf) ; len} > 0) { out.write(buf, 0, len) } 
//    in.close
//    out.close
//    fout
//  }
  
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
      case e => 
        logger.error("Exception when processing report %s".format(report.name))
        logger.error("Reported exception is: ", e)
    } 
  }
}
