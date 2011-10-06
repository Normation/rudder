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

import com.unboundid.ldap.sdk.Modification
import com.unboundid.ldap.sdk.ModificationType.REPLACE
import com.normation.inventory.ldap.core._
import com.unboundid.ldif._ 
import org.joda.time.DateTime

/*
 * Log given LDIF record in a file
 * with given name (a timestamp will be added)
 * File will be stored under a configured directory 
 */
trait LDIFReportLogger {
  /**
   * 
   * @param reportName
   *  a name from witch the log id will be derived
   * @param comments
   *  an optional comment to append at the top of the file
   * @param tag
   *  an optional tag to put in the file name, like REPORT, MODIFICATION, etc
   * @param LDIFRecords
   *  list of records to log
   * @return the generated id / path for the log
   */
  def log(
      reportName:String,
      comments: Option[String],
      tag:Option[String],
      LDIFRecords: => Seq[LDIFRecord]) : String
}

object DefaultLDIFReportLogger {
  import org.slf4j.LoggerFactory
  val logger = LoggerFactory.getLogger(classOf[DefaultLDIFReportLogger])
  val defaultLogDir = System.getProperty("java.io.tmpdir") + 
    System.getProperty("file.separator") + "LDIFLogReport"
}

import java.io.File
import DefaultLDIFReportLogger.logger

class DefaultLDIFReportLogger(val LDIFLogDir:String = DefaultLDIFReportLogger.defaultLogDir) extends LDIFReportLogger {
  
  def rootDir = {
    val dir = new File(LDIFLogDir)
    if(!dir.exists()) dir.mkdirs
    dir
  }
  
  protected def fileFromName(name:String, opType:Option[String]) : File = {
    val fileName = name.replaceAll(File.separator, "|")
    
    //time stamp are not that much readable, use a YYYYMMDD-HH.MM.SSS format
    new File(rootDir, 
        fileName + 
        "_" + new DateTime().toString("YYYY-MM-dd_HH.mm.ss.SS") + 
        (opType.map("_" + _).getOrElse("")) + 
        ".LDIF")
  }

  def log(
      reportName:String,
      comments: Option[String],
      tag:Option[String],
      LDIFRecords: => Seq[LDIFRecord]) : String = {
    
      var writer:LDIFWriter = null
      val LDIFFile = fileFromName(reportName,tag)
      try {
        logger.debug("LDIF log for report processing: " + LDIFFile.getAbsolutePath)
        writer = new LDIFWriter(LDIFFile)
        
        
        if(LDIFRecords.nonEmpty) { //don't check it if logger trace is not enabled
          writer.writeLDIFRecord(LDIFRecords.head, comments.getOrElse(null))
          LDIFRecords.tail.foreach { LDIFRecord => writer.writeLDIFRecord(LDIFRecord) }
        } else {
          //write a dummy recored
          val c = comments.getOrElse("") + "(There was no record to log, a dummy modification is added in log as a placeholder)"
          writer.writeLDIFRecord(new LDIFModifyChangeRecord("cn=dummy", new Modification(REPLACE,"dummy", "dummy")), c)          
        }
        
        
      } catch {
        case e:Exception => logger.error("Exception when loggin (ignored)",e)
      } finally {
        if(null != writer) writer.close
      }
    
      LDIFFile.getAbsolutePath
  }
}