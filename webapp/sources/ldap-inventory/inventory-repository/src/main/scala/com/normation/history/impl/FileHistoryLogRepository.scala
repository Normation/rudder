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

import com.normation.history.impl.FileHistoryLogRepository._
import com.normation.inventory.domain.InventoryError
import com.normation.errors._
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import zio._
import zio.syntax._

import scala.reflect.ClassTag

/**
 * A trait that allows to write and read datas of type T
 * to/from files
 */
trait FileMarshalling[T] {
  def fromFile(in:File) : IOResult[T]
  def toFile(out:File, data: T) : IOResult[T]
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
class FileHistoryLogRepository[ID:ClassTag,T](
  val rootDir  : String,
  val parser   : FileMarshalling[T],
  val converter: IdToFilenameConverter[ID]
) extends HistoryLogRepository[ID, DateTime,  T, DefaultHLog[ID,T]] {

  type HLog = DefaultHLog[ID,T]

   //when the the class is instanciated, try to create the directory
  //we don't want to catch exception here
  root


  //we don't want to catch exception here
  private def root: IOResult[File] = {
    for {
      dir       <- ZIO.succeed(new File(rootDir))
      isValid   <- ZIO.when(dir.exists && !dir.isDirectory) { InventoryError.System(s"'${dir.getAbsolutePath}' exists and is not a directory").fail }
      isCreated <- ZIO.when(!dir.exists) { ZIO.attempt(dir.mkdirs).mapError(e => InventoryError.System(s"Error creating '${rootDir}': ${e.getMessage}")) }
    } yield {
      dir
    }
  }

  //we don't want to catch exception here
  private def idDir(id:ID) = {
    for {
      r         <- root
      dir       <- ZIO.succeed(new File(r, converter.idToFilename(id)))
      isValid   <- ZIO.when(dir.exists && !dir.isDirectory) { InventoryError.System(s"'${dir.getAbsolutePath}' and is not a directory").fail }
      isCreated <- ZIO.when(!dir.exists) { ZIO.attempt(dir.mkdirs).mapError(e => InventoryError.System(s"Error creating '${dir.getAbsolutePath}': '${e.getMessage}'")) }
    } yield {
      dir
    }
  }


  /**
   * Save an inventory and return the ID of the saved inventory, and
   * its version
   */
  def save(id: ID, data: T, datetime: DateTime = DateTime.now) : IOResult[HLog] = {
    converter.idToFilename(id) match {
      case null | "" =>
        InventoryError.Inconsistency("History log name can not be null nor empty").fail
      case s if(s.contains(java.lang.System.getProperty("file.separator"))) =>
        InventoryError.Inconsistency(s"UUID can not contains the char '${java.lang.System.getProperty("file.separator")}'").fail
      case s =>
        val hlog = DefaultHLog(id, datetime, data)

        for {
          i     <- idDir(hlog.id)
          file  <- ZIO.succeed(new File(i,vToS(hlog.version)))
          datas <- parser.toFile(file,hlog.data)
        } yield hlog
    }
  }

  /**
   * Retrieve all ids known by the repository
   */
  def getIds : IOResult[Seq[ID]] = {
    for {
      r   <- root
      res <- ZIO.attempt(r.listFiles.collect { case(f) if(f.isDirectory) => converter.filenameToId(f.getName) }).mapError(e =>
                InventoryError.System(s"Error when trying to get file names")
              )
     } yield {
      res.toSeq
    }
  }

  /**
   * Get the list of all history logs for the given id.
   * If reading any logs  throws an error, the full result is a
   * Failure
   */
  def getAll(id:ID) : IOResult[Seq[HLog]] = {
    for {
      versions <- this.versions(id)
      hlogs    <- ZIO.foldLeft(versions)(Seq[HLog]()) { (current, v) =>
                    this.get(id,v).map(hlog => current :+ hlog)
                  }
    } yield hlogs
  }

  /**
   * Get the list of record for the given UUID and version.
   * If no version is specified, get the last.
   */
  def getLast(id:ID) : IOResult[HLog] = {
    for {
      versions <- this.versions(id)
      version  <- versions.headOption.notOptional(s"No version available for ${id}")
      hlog     <- this.get(id,version)
    } yield hlog
  }

  /**
   * Get the list of record for the given UUID and version.
   * If no version is specified, get the last.
   */
  def get(id:ID, version:DateTime) : IOResult[HLog] = {
    for {
      i    <- idDir(id)
      file <- ZIO.succeed(new File(i,vToS(version)))
      data <- parser.fromFile(file)
    }yield DefaultHLog(id,version,data)
  }


  //we don't want to catch exception here
  private def exists(id:ID) = {
    for{
      r   <- root
      dir <- ZIO.succeed(new File(r, converter.idToFilename(id)))
    } yield {
      dir.exists && dir.isDirectory
    }
  }

  /**
   * Return the list of version for ID.
   * IO(Empty list) if no version for the given id
   * List is sorted with last version (most recent) first
   * ( versions.head > versions.head.head )
   */
  def versions(id:ID) : IOResult[Seq[DateTime]] = {
    for {
      i  <- idDir(id)
      ok <- exists(id)
      res <- if(ok) ZIO.attempt(i.listFiles.toSeq.map(f => sToV(f.getName)).
              filter(_.isDefined).map(_.get).sortWith(_ .compareTo(_) > 0)).mapError(e =>
                InventoryError.System(s"Error when listing file in '${i.getAbsolutePath}'")
              )
             else ZIO.succeed(Seq())
    } yield res
  }

}

object FileHistoryLogRepository {
  private val formatter = ISODateTimeFormat.dateTime()
  private def vToS(version:DateTime) = formatter.print(version)
  private def sToV(version:String):Option[DateTime] = {
    try {
      Some(formatter.parseDateTime(version))
    } catch {
      case e:IllegalArgumentException => None
    }
  }
}
