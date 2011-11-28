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

package com.normation.history
package impl

import java.io.File
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

import net.liftweb.common._
import net.liftweb.util.ControlHelpers.tryo
import FileHistoryLogRepository._

/**
 * A trait that allows to write and read datas of type T
 * to/from files
 */
trait FileMarshalling[T] {
  def fromFile(in:File) : Box[T]
  def toFile(out:File, data: T) : Box[T]
}

trait IdToFilenameConverter[ID] {
  /**
   * Create the filename from the id, and vice versa
   */
  def idToFilename(id:ID) : String
  
  /**
   * Get the ID from a path name
   */
  def filenameToId(name:String) : ID
}

/**
 * An implementation of HistoryLogRepository that save datas in 
 * FileSystem, using a layout like:
 * 
 *  /(d)root_dir/
 *    |- (d)history_id1 
 *    |   |- (f)datetime1
 *    |   |- (f)datetime2
 *    |    `...
 *    ` (d)history_name2
 *    
 * Any datas type may be used, as long as they can be read/write from
 * files
 */
class FileHistoryLogRepository[ID,T](
  val rootDir:String, 
  val marshaller:FileMarshalling[T],
  val converter:IdToFilenameConverter[ID]
) extends HistoryLogRepository[ID, DateTime,  T, DefaultHLog[ID,T]] {
  
  type HLog = DefaultHLog[ID,T]
  
   //when the the class is instanciated, try to create the directory
  //we don't want to catch exception here
  root()
  
  
  //we don't want to catch exception here
  private def root() : Box[File] = {
    for {
      dir <- tryo(new File(rootDir))
      isValid <-  if(dir.exists && !dir.isDirectory) Failure("'%s' exists and is not a directory".format(dir.getAbsolutePath)) else Full("OK")
      isCreated <- if(!dir.exists) tryo(dir.mkdirs) else Full("OK")
    } yield {
      dir
    }
  }
  
  //we don't want to catch exception here
  private def idDir(id:ID) = {
    for {
      r <- root
      dir <- tryo(new File(r, converter.idToFilename(id)))
      isValid <- if(dir.exists && !dir.isDirectory) Failure("'%s' exists and is not a directory".format(dir.getAbsolutePath)) else Full("OK")
      isCreated <- if(!dir.exists) tryo(dir.mkdirs) else Full("OK")
    } yield {
      dir
    }
  }
  
  //we don't want to catch exception here
  private def exists(id:ID) = {
    (for{
      r <- root
      dir <- tryo(new File(r, converter.idToFilename(id)))
    } yield dir.exists && dir.isDirectory).getOrElse(false)
  }
  
  /**
   * Save a report and return the ID of the saved report, and
   * it's version
   * @param historyLog
   * @return
   */
  def save(id:ID,data:T,datetime:DateTime = DateTime.now) : Box[HLog] = {
    converter.idToFilename(id) match {
      case null | "" => 
        Failure("History log name can not be null nor empty")
      case s if(s.contains(System.getProperty("file.separator"))) =>
        Failure("UUID can not contains the char '%s'".format(System.getProperty("file.separator")))
      case s => 
        val hlog = DefaultHLog(id,datetime,data)
        
        for {
          i <- idDir(hlog.id)
          file <- tryo(new File(i,vToS(hlog.version)))
          datas <- tryo(marshaller.toFile(file,hlog.data))
        } yield hlog
    }
  }
 
  /**
   * Retrieve all ids known by the repository
   */
  def getIds : Box[Seq[ID]] = {
    for {
      r <- root()
      res <- tryo(r.listFiles.collect { case(f) if(f.isDirectory) => converter.filenameToId(f.getName) })
    } yield {
      res
    }
  }
 
  /**
   * Get the list of all history logs for the given id.
   * If reading any logs  throws an error, the full result is a
   * Failure
   */
  def getAll(id:ID) : Box[Seq[HLog]] = {
    for {
      versions <- this.versions(id)
      hlogs <- {
        ( (Full(Seq()):Box[Seq[HLog]]) /: versions ) { (current, v) =>
            for {
              seq <- current
              hlog <- this.get(id,v)
            } yield seq :+ hlog
        }
      }
    } yield hlogs
  }

  /**
   * Get the list of record for the given UUID and version.
   * If no version is specified, get the last. 
   */
  def getLast(id:ID) : Box[HLog] = {
    for {
      versions <- this.versions(id) 
      version <- versions.headOption
      hlog <- this.get(id,version)
    } yield hlog
  }

  /**
   * Get the list of record for the given UUID and version.
   * If no version is specified, get the last. 
   */
  def get(id:ID, version:DateTime) : Box[HLog] = {
    for {
      i <- idDir(id)
      file <- tryo(new File(i,vToS(version)))
      data <-  marshaller.fromFile(file)
    }yield DefaultHLog(id,version,data)
  }

  
  /**
   * Return the list of version for ID.
   * Full(Empty list) or Empty if no version for the given id
   * List is sorted with last version (most recent) first
   * ( versions.head > versions.head.head )
   */
  def versions(id:ID) : Box[Seq[DateTime]] = {
    for {
      i <- idDir(id)
      if(exists(id))
      res <- tryo {
          i.listFiles.toSeq.map(f => sToV(f.getName)).
            filter(_.isDefined).map(_.get).sortWith(_ .compareTo(_) > 0)
      }
    } yield res
  }
  
}

object FileHistoryLogRepository {
  private val formatter = DateTimeFormat.forPattern("YYYY-MM-dd_HH:mm.ss.SSS")
  private def vToS(version:DateTime) = formatter.print(version)
  private def sToV(version:String):Option[DateTime] = {
    try {
      Some(formatter.parseDateTime(version))
    } catch {
      case e:IllegalArgumentException => None
    }
  }
}