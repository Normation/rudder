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

import com.normation.NamedZioLogger
import com.normation.errors.RudderError
import com.unboundid.ldap.sdk.Modification
import com.unboundid.ldap.sdk.ModificationType.REPLACE
import com.unboundid.ldif._
import org.joda.time.DateTime
import scalaz.zio._
import scalaz.zio.syntax._
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
      reportName : String
    , comments   : Option[String]
    , tag        : Option[String]
    , LDIFRecords: => Seq[LDIFRecord]
  ) : Task[String]
}

object DefaultLDIFReportLogger {
  val logger = new NamedZioLogger("trace.ldif.in.file") {}
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
        "_" + DateTime.now().toString("YYYY-MM-dd_HH.mm.ss.SS") +
        (opType.map("_" + _).getOrElse("")) +
        ".LDIF")
  }

  def log(
      reportName : String
    , comments   : Option[String]
    , tag        : Option[String]
    , LDIFRecords: => Seq[LDIFRecord]
  ) : Task[String] = {
    val LDIFFile = fileFromName(reportName,tag)
    IO.bracket(IO.effect(new LDIFWriter(LDIFFile)))(writer =>  IO.effect(writer.close).catchAll(ex =>
      logger.debug("LDIF log for report processing: " + LDIFFile.getAbsolutePath)
    )) { writer =>
      (if (logger.internalLogger.isTraceEnabled) {

        val ldif = LDIFRecords //that's important, else we evaluate again and again LDIFRecords

        Task.effect(
          if(ldif.nonEmpty) { //don't check it if logger trace is not enabled
            writer.writeLDIFRecord(ldif.head, comments.getOrElse(null))

            ldif.tail.foreach { LDIFRecord => writer.writeLDIFRecord(LDIFRecord) }
          } else {
            //write a dummy record
            val c = comments.getOrElse("") + "(There was no record to log, a dummy modification is added in log as a placeholder)"
            writer.writeLDIFRecord(new LDIFModifyChangeRecord("cn=dummy", new Modification(REPLACE,"dummy", "dummy")), c)
          }
        )
      } else {
        UIO.unit
      }) *> UIO.succeed(LDIFFile.getAbsolutePath)
    }
  }
}
