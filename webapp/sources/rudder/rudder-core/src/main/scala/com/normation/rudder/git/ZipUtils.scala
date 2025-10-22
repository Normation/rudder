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
package com.normation.rudder.git

import com.normation.box.IOManaged
import com.normation.errors.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.*
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import scala.collection.Seq
import scala.jdk.CollectionConverters.*
import zio.*
import zio.syntax.*

object ZipUtils {

  // we need to ensure that path is never null, which can happen because we interact with Java
  final case class Zippable(path: String, useContent: Option[(InputStream => IOResult[Any]) => IOResult[Any]]) {
    if (null == path) {
      throw new IllegalArgumentException(
        s"A zippable can not be created with an 'null' path (useContent ${if (useContent.isEmpty) "is empty" else "is defined"})"
      )
    }
  }

  object Zippable {
    def make(path: String, content: Option[IOManaged[InputStream]]): Zippable = {
      def getUseContent(x: IOManaged[InputStream]): (InputStream => IOResult[Any]) => IOResult[Any] = {
        (use: InputStream => IOResult[Any]) => ZIO.scoped(x.flatMap(use))
      }
      Zippable(path, content.map(x => getUseContent(x)))
    }
  }

  // unzip from a file to another file/directory
  def unzip(zip: ZipFile, intoDir: File): IOResult[Unit] = {
    if (intoDir.exists && intoDir.isDirectory && intoDir.canWrite) {
      IOResult.attempt(s"Error while unzipping file '${zip.getName}'") {
        zip.entries.asScala.foreach { entry =>
          val file = new File(intoDir, entry.getName)
          if (entry.isDirectory()) {
            file.mkdirs
          } else {
            file.getParentFile.mkdir
            FileUtils.copyInputStreamToFile(zip.getInputStream(entry), file)
          }
        }
      }
    } else {
      Inconsistency(
        s"Directory '${intoDir.getPath}' is not a valid directory to unzip file: please, check permission and existence"
      ).fail
    }
  }

  // unzip an input stream into zip entries
  def getZipEntries(name: String, zip: InputStream): IOResult[Chunk[(ZipEntry, Option[Array[Byte]])]] = {
    import better.files.*
    // ah, Java. It was a long time ago. I didn't miss you.

    // effect-full and all horror, must be encapsulated.
    // Be careful, entry.getSize can't be trusted
    def readEntry(zipis: ZipInputStream, entry: ZipEntry): Array[Byte] = {
      if (entry.getSize > Int.MaxValue) throw new IllegalArgumentException(s"Zip content is too big, can not read it in Memory")
      else {
        val array = new ByteArrayOutputStream()
        // read content
        zipis.pipeTo(array)
        // entry need to be closed
        zipis.closeEntry()
        array.toByteArray
      }
    }

    IOResult.attempt(s"Error while unzipping file '${name}'") {
      val zipis = new ZipInputStream(zip)
      var entry: ZipEntry = zipis.getNextEntry
      var entries = Chunk[(ZipEntry, Option[Array[Byte]])]()
      while (entry != null) {
        entries = entries :+ (if (entry.isDirectory) {
                                (entry, None)
                              } else {
                                (entry, Some(readEntry(zipis, entry)))
                              })
        entry = zipis.getNextEntry
      }
      entries
    }
  }

  /**
   * Zippable must be ordered from root to children (deep first),
   * and all path must be relative to root.
   * A zippable without a file content is considered to
   * be a directory
   */

  def zip(zipout: OutputStream, toAdds: Seq[Zippable]): IOResult[Unit] = {
    // we must ensure that each entry is unique, else zip fails
    val unique = toAdds.distinctBy(_.path)
    ZIO.acquireReleaseWith(IOResult.attempt(new ZipOutputStream(zipout)))(zout => {
      // if the connection is interrupted, for ex if you use curl without a --output arg,
      // then the usual effectUioUnit(zout.close()) leads to a big stack trace (unactionnable, uninteresting).
      ZIO.attemptBlocking(zout.close()).orElseSucceed(ZIO.unit)
    }) { zout =>
      val addToZout = (is: InputStream) => IOResult.attempt("Error when copying file")(IOUtils.copy(is, zout))

      ZIO.foreachDiscard(unique) { x =>
        val name = x.useContent match {
          case None     =>
            if (x.path.endsWith("/")) {
              x.path
            } else {
              x.path + "/"
            }
          case Some(is) =>
            if (x.path.endsWith("/")) {
              x.path.substring(0, x.path.size - 1)
            } else {
              x.path
            }
        }
        IOResult.attempt(zout.putNextEntry(new ZipEntry(name))) *> (
          x.useContent match {
            case None    => ().succeed
            case Some(x) => x(addToZout)
          }
        )
      }
    }
  }

  /**
   * Create the seq of zippable from a directory or file.
   * If it's a directory, all children are added recursively,
   * and their names are relative to the root.
   * For a file, only its basename is added.
   *
   * The returned Zippable are ordered from root to children (deep first),
   * and all path must be relative to root like that:
   * root/
   * root/file-a
   * root/file-b
   * root/dir-a/
   * root/dir-b/
   * root/dir-b/file-a
   * root/dir-b/file-b
   * etc
   *
   * root name itself is not used.
   */
  def toZippable(file: File): Seq[Zippable] = {
    if (file.getParent == null) {
      throw new IllegalArgumentException("Can not zippify the root directory: create and use a directory")
    }
    val base = file.getParentFile.toURI

    def getPath(f: File): Option[String] = {
      base.relativize(f.toURI).getPath match {
        case null => None
        case p    => Some(p)
      }
    }

    def recZippable(f: File, existing: Seq[Zippable]): Seq[Zippable] = {
      // sort accordingly to the required convention
      def sortFile(files: Array[java.io.File]): Array[java.io.File] = {
        files.sortWith {
          case (file1, file2) =>
            (file1.isDirectory, file2.isDirectory) match {
              case (true, true) | (false, false) => file1.getName.compareTo(file2.getName) <= 0
              case (true, false)                 => false
              case (false, true)                 => true
            }
        }
      }

      getPath(f) match {
        case None       => existing
        case Some(path) =>
          if (f.isDirectory) {
            val c = existing :+ Zippable(path, None)

            sortFile(f.listFiles).foldLeft(c)((seq, ff) => recZippable(ff, seq))
          } else {
            def buildContent(use: InputStream => Any): IOResult[Any] = {
              ZIO.acquireReleaseWith(IOResult.attempt(new FileInputStream(f)))(is => effectUioUnit(is.close())) { is =>
                IOResult.attempt("Error when using file")(use(is))
              }
            }

            existing :+ Zippable(path, Some(buildContent))
          }
      }
    }
    recZippable(file, Seq())
  }

  /*
   * Check that a ZipEntry does not contain "../" and so is a risk for ZipSlip path traversal
   * attack. Most of the time, you really just don't want to have "../" anywhere in a Zip archive.
   */
  def checkForZipSlip(entry: ZipEntry): IOResult[Unit] = {
    if (entry.getName.contains("../")) Inconsistency(s"Zip entry '${entry.getName}' contains '../' which is forbidden").fail
    else if (entry.getName.startsWith("/"))
      Inconsistency(s"Zip entry '${entry.getName}' has an absolute path, only relative path are accepted").fail
    else ZIO.unit
  }
}
