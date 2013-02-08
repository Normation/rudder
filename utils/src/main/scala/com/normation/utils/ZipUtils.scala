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

package com.normation.utils

import java.util.zip.ZipFile
import java.io.File
import net.liftweb.common._
import scala.collection.JavaConversions._
import org.apache.commons.io.FileUtils
import java.io.InputStream
import java.util.zip.ZipOutputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import org.apache.commons.io.IOUtils
import java.io.FileInputStream
import java.io.OutputStream

object ZipUtils {

  case class Zippable(path:String, useContent:Option[(InputStream => Box[Any]) => Box[Any]])
  
  def unzip(zip: ZipFile, intoDir: File) : Box[Unit] = {
    if(intoDir.exists && intoDir.isDirectory && intoDir.canWrite) {
      try {
        zip.entries.foreach { entry => 
          val file = new File(intoDir, entry.getName)
          if(entry.isDirectory()) {
            file.mkdirs
          } else {
            file.getParentFile.mkdir
            FileUtils.copyInputStreamToFile(zip.getInputStream(entry), file)
          }
        }
        Full({})
      } catch {
        case e:Exception => Failure(s"Error while unzipping file '${zip.getName}'", Full(e), Empty)
      }
    } else Failure(s"Directory '${intoDir.getPath}' is not a valid directory to unzip file: please, check permission and existence")
  }
  
  
  /**
   * Zippable must be ordered from root to children (deep first),
   * and all path must be relative to root. 
   * A zippable without a file content is considered to 
   * be a directory
   */
  def zip(zipout:OutputStream, toAdds:Seq[Zippable]) : Box[Unit] = {
    var zout:ZipOutputStream = null
    try {
      zout = new ZipOutputStream(zipout)
      val addToZout = (is:InputStream) => try { 
        Full(IOUtils.copy(is, zout))
      } catch {
        case e:Exception => Failure("Error when copying file", Full(e),Empty)
      }
      
      toAdds.foreach { x =>
        val name = x.useContent match {
          case None => 
            if(x.path.endsWith("/")) x.path else x.path + "/"
          case Some(is) =>
            if(x.path.endsWith("/")) x.path.substring(0,x.path.size -1) else x.path
        }
        zout.putNextEntry(new ZipEntry(name))
        x.useContent.foreach { use => 
          use(addToZout)
        }
      }
      Full()
    } catch {
      case e:Exception => Failure("Error when trying to zip file", Full(e), Empty)
    } finally {
      if(null != zout) zout.close
    }
  }
  
  /**
   * Create the seq of zippable from a directory or file.
   * If it's a directory, all children are added recursively, 
   * and there name are relative to the root.
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
   */
  def toZippable(file:File) : Seq[Zippable] = {
    if(file.getParent == null) {
      throw new IllegalArgumentException("Can not zippify the root directory: create and use a directory")
    }
    val base = file.getParentFile.toURI
    def getPath(f:File) = base.relativize(f.toURI).getPath
    
    def recZippable(f:File, existing:Seq[Zippable]) : Seq[Zippable] = {
      //sort accordingly to the required convention
      def sortFile(files: Array[java.io.File]) : Array[java.io.File] = {
        files.sortWith { case(file1, file2) => 
          (file1.isDirectory, file2.isDirectory) match {
            case (true, true) | (false, false) => file1.getName.compareTo(file2.getName) <= 0
            case (true, false) => false
            case (false, true) => true
          }
        }
      }
      
      if(f.isDirectory) {
        val c = existing :+ Zippable(getPath(f), None)
        
        
        (c /: sortFile(f.listFiles)) { (seq,ff) =>
          recZippable(ff,seq)
        }
      } else {
        def buildContent(use: InputStream => Any) : Box[Any] = {
          var is : FileInputStream = null
          try {
            is = new FileInputStream(f)
            Full(use(is))
          } catch {
            case e:Exception => Failure("Error when using file", Full(e), Empty)
          } finally {
            if(null != is) is.close
          }
        }
        existing :+ Zippable(getPath(f), Some(buildContent _))
      }
    }
    
    recZippable(file, Seq())
  }
}