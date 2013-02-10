/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
*************************************************************************************
*/

package com.normation.ldap.ldif

import com.unboundid.ldif._
import com.unboundid.ldap.sdk.{DN,Entry}
import com.normation.ldap.sdk.LDAPTree
import org.slf4j.{Logger, LoggerFactory}
import java.io.File

/**
 * A service that allows to log LDAP objects into
 * files if a trace log level is enable for the
 * log unit "loggerName".
 *
 * Be careful, enabling that logger may be
 * extremely heavy in log size.
 */
trait LDIFFileLogger {

  /**
   * The name of the log unit to set to trace
   * to enable LDIF output.
   */
  def loggerName : String

  /**
   * Root directory for LDIF traces.
   */
  def ldifTraceRootDir : String

  /**
   * Write the given tree as a set of LDIF records in
   * the trace directory.
   */
  def tree(tree:LDAPTree) : Unit

  /**
   * Write the given record in the trace directory.
   */
  def record(LDIFRecord: => LDIFRecord,comment:Option[String] = None) : Unit

  def records(LDIFRecords: => Seq[LDIFRecord]) : Unit
}

class DummyLDIFFileLogger extends LDIFFileLogger {
  val loggerName = "dummy logger - no output"
  val ldifTraceRootDir = "no a real ldifTraceRootDir"
  def tree(tree:LDAPTree) {}
  def record(LDIFRecord: => LDIFRecord,comment:Option[String] = None) {}
  def records(LDIFRecords: => Seq[LDIFRecord]) {}
}


/**
 * A simple logger that just print LDIF in a
 * configured directory.
 * This class should never raise an exception, as it's
 * only use to log.
 */
object DefaultLDIFFileLogger {
  val defaultTraceDir = System.getProperty("java.io.tmpdir") +
    System.getProperty("file.separator") + "ldifTrace"
  val defaultLoggerName = "trace.ldif.in.file"
}



class DefaultLDIFFileLogger(
    override val loggerName:String = DefaultLDIFFileLogger.defaultLoggerName,
    override val ldifTraceRootDir:String = DefaultLDIFFileLogger.defaultTraceDir
) extends Slf4jLDIFLogger {
  override def isLogLevel : Boolean = logger.isTraceEnabled
  override def log(s:String) : Unit = logger.trace(s)
  override def logE(s:String,e:Exception): Unit = logger.trace(s,e)
}

/**
 * A trait that let the log level be specified,
 * even if the idea is only to use a trace level.
 */
trait Slf4jLDIFLogger extends LDIFFileLogger {
  def isLogLevel : Boolean
  def log(s:String) : Unit
  def logE(s:String,e:Exception): Unit

  val logger = LoggerFactory.getLogger(loggerName)

  def rootDir() = {
    val dir = new File(ldifTraceRootDir)
    if(!dir.exists()) dir.mkdirs
    dir
  }

  protected def traceFileName(dn:DN, opType:String) : String = {
    val fileName = dn.getRDNStrings().map( _.replaceAll(File.separator, "|")).reverse.mkString("/")
    fileName + "-" + System.currentTimeMillis.toString + "-" + opType + ".ldif"
  }

  protected def createTraceFile(fileName:String) : File = {
    new File(rootDir, fileName)
  }

  private def errorMessage(e:Exception,filename:String) : Unit =
    logE(s"Exception when loggin LDIF trace in ${filename} (ignored)",e)


  private def writeRecord(ldifWriter:LDIFWriter,LDIFRecord:LDIFRecord,comment:Option[String] = None) {
    comment match {
      case None => ldifWriter.writeLDIFRecord(LDIFRecord)
      case Some(c) => ldifWriter.writeLDIFRecord(LDIFRecord,c)
    }
  }

  override def tree(tree:LDAPTree) {
    if(isLogLevel) {
      val filename = traceFileName(tree.root.dn, "CONTENT")
      try {
        val ldif = createTraceFile(filename)
        log("Printing LDIF trace of Entity Tree : " + ldif.getAbsolutePath)
        val writer = new LDIFWriter(ldif)
        try {
          tree.foreach { e =>
            writeRecord(writer,e.backed)
          }
        } finally {
          writer.close
        }
      } catch {
        case e:Exception => errorMessage(e,filename)
      }
    }
  }

  override def record(record: => LDIFRecord,comment:Option[String] = None) {
    if(isLogLevel) {
      var writer:LDIFWriter = null
      val opType = record match {
        case _:Entry => "CONTENT"
        case _:LDIFAddChangeRecord => "ADD"
        case _:LDIFDeleteChangeRecord => "DELETE"
        case _:LDIFModifyChangeRecord => "MODIFY"
        case _:LDIFModifyDNChangeRecord => "MODIFY_DN"
        case _ => "UNKNOWN_OP"
      }
      val filename = traceFileName(record.getParsedDN,opType)

      try {
        val ldif = createTraceFile(filename)
        log("Printing LDIF trace of unitary operation on record in : " + ldif.getAbsolutePath)
        writer = new LDIFWriter(ldif)
        writeRecord(writer,record,comment)
      } catch {
        case e:Exception => errorMessage(e,filename)
      } finally {
        if(null != writer) writer.close
      }
    }
  }

  override def records(records: => Seq[LDIFRecord]) {
    if(isLogLevel) {
      var writer:LDIFWriter = null
      val filename = traceFileName(records.head.getParsedDN, "RECORDS")
      try {
        if(records.nonEmpty) { //don't check it if logger trace is not enabled
          val ldif = createTraceFile(filename)
          //create parent directory if it does not exists
          ldif.getParentFile().mkdirs()
          //save ldif
          log("Printing LDIF trace of operations on records in : " + ldif.getAbsolutePath)
          writer = new LDIFWriter(ldif)
          records.foreach { record => writeRecord(writer,record) }
        } else {
          log("Nothing to print as record list is empty")
        }
      } catch {
        case e:Exception => errorMessage(e,filename)
      } finally {
        if(null != writer) writer.close
      }
    }
  }
}