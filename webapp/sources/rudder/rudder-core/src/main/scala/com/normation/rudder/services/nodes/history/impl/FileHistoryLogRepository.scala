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

package com.normation.rudder.services.nodes.history.impl

import com.normation.errors.*
import com.normation.inventory.domain.InventoryError
import com.normation.rudder.services.nodes.history.HistoryLogRepository
import java.io.File
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import scala.reflect.ClassTag
import zio.*
import zio.syntax.*

/**
 * A trait that allows to write and read datas of type T
 * to/from files
 */
trait FileMarshalling[T] {
  def fromFile(in: File): IOResult[T]
  def toFile(out:  File, data: T): IOResult[T]
}

trait IdToFilenameConverter[ID] {

  /**
   * Create the filename from the id, and vice versa
   */
  def idToFilename(id: ID): String

  /**
   * Get the ID from a path name
   */
  def filenameToId(name: String): ID
}

trait VersionToFilenameConverter[V] {

  /**
   * Create the filename from the version
   */
  def versionToFilename(version: V): String

  /**
   * Get a validated version from a path name
   */
  def filenameToVersion(name: String): Option[V]
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
 * files.
 *
 * Version is DateTime but can be abstracted over, if DefaultHLog is abstracted.
 */
class FileHistoryLogRepository[ID: ClassTag, T](
    val rootDir:          String,
    val parser:           FileMarshalling[T],
    val converter:        IdToFilenameConverter[ID],
    val versionConverter: VersionToFilenameConverter[DateTime]
) extends HistoryLogRepository[ID, DateTime, T, DefaultHLog[ID, T]] {

  type HLog = DefaultHLog[ID, T]

  // when the the class is instanciated, try to create the directory
  // we don't want to catch exception here
  root

  // we don't want to catch exception here
  private def root: IOResult[File] = {
    for {
      dir       <- ZIO.succeed(new File(rootDir))
      isValid   <- ZIO.when(dir.exists && !dir.isDirectory) {
                     InventoryError.System(s"'${dir.getAbsolutePath}' exists and is not a directory").fail
                   }
      isCreated <- ZIO.when(!dir.exists) {
                     ZIO.attempt(dir.mkdirs).mapError(e => InventoryError.System(s"Error creating '${rootDir}': ${e.getMessage}"))
                   }
    } yield {
      dir
    }
  }

  private def idDir(id: ID) = {
    for {
      r         <- root
      dir       <- ZIO.succeed(new File(r, converter.idToFilename(id)))
      isValid   <- ZIO.when(dir.exists && !dir.isDirectory) {
                     InventoryError.System(s"'${dir.getAbsolutePath}' and is not a directory").fail
                   }
      isCreated <- ZIO.when(!dir.exists) {
                     ZIO
                       .attempt(dir.mkdirs)
                       .mapError(e => InventoryError.System(s"Error creating '${dir.getAbsolutePath}': '${e.getMessage}'"))
                   }
    } yield {
      dir
    }
  }

  def getFile(id: ID): IOResult[File] = {
    idDir(id)
  }

  /**
   * Save an inventory and return the ID of the saved inventory, and
   * its version
   */
  def save(id: ID, data: T, datetime: DateTime = DateTime.now(DateTimeZone.UTC)): IOResult[HLog] = {
    converter.idToFilename(id) match {
      case null | ""                                                         =>
        InventoryError.Inconsistency("History log name can not be null nor empty").fail
      case s if (s.contains(java.lang.System.getProperty("file.separator"))) =>
        InventoryError.Inconsistency(s"UUID can not contains the char '${java.lang.System.getProperty("file.separator")}'").fail
      case s                                                                 =>
        val hlog = DefaultHLog(id, datetime, data)

        for {
          i     <- idDir(hlog.id)
          file  <- ZIO.succeed(new File(i, versionConverter.versionToFilename(hlog.version)))
          datas <- parser.toFile(file, hlog.data)
        } yield hlog
    }
  }

  /**
   * Retrieve all ids known by the repository
   */
  def getIds: IOResult[Seq[ID]] = {
    for {
      r   <- root
      res <- ZIO
               .attempt(r.listFiles.collect { case (f) if (f.isDirectory) => converter.filenameToId(f.getName) })
               .mapError(e => InventoryError.System(s"Error when trying to get file names"))
    } yield {
      res.toSeq
    }
  }

  /**
   * Get the record for the given UUID and version if exists
   */
  def get(id: ID, version: DateTime): IOResult[Option[HLog]] = {
    for {
      i    <- idDir(id)
      file <- ZIO.succeed(new File(i, versionConverter.versionToFilename(version)))
      data <- ZIO.whenZIO(IOResult.attempt(file.exists()))(parser.fromFile(file))
    } yield data.map(d => DefaultHLog(id, version, d))
  }

  // we don't want to catch exception here
  private def exists(id: ID) = {
    for {
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
  def versions(id: ID): IOResult[Seq[DateTime]] = {
    for {
      i   <- idDir(id)
      ok  <- exists(id)
      res <- if (ok) {
               ZIO
                 .attempt(
                   i.listFiles.toSeq
                     .map(f => versionConverter.filenameToVersion(f.getName))
                     .filter(_.isDefined)
                     .map(_.get)
                     .sortWith(_.compareTo(_) > 0)
                 )
                 .mapError(e => InventoryError.System(s"Error when listing file in '${i.getAbsolutePath}'"))
             } else ZIO.succeed(Seq())
    } yield res
  }

}
