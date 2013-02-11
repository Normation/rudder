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

package com.normation.rudder.services.system

import java.io.{FileOutputStream,File}
import net.liftweb.http.FileParamHolder
import org.slf4j.LoggerFactory
import FileManager._
import com.normation.exceptions.TechnicalException

object FileManager {
  val logger = LoggerFactory.getLogger(classOf[FileManager])
}

class FileManager(rootPath:String) {

  def rootDirectory() : File = {
    val root = new File(rootPath)
    //check that the directory exists, we can write on it
    if(!root.exists) {
      if(!root.mkdirs) throw new TechnicalException("Can't create upload direcorty %s. Check rights".format(rootPath))
    }
    if(!root.isDirectory) throw new TechnicalException("File %s is not a directory. Please choose an other directory path")

    if(!root.canWrite) throw new TechnicalException("Directory %s is not writable. Please check rigths")

    root
  }

  //only list files under rootDirectory
  def getFiles() : Seq[File] =
    rootDirectory.listFiles.filter(_.isFile)

  def copyFileToRootDir(src:FileParamHolder) : Unit = {
    val in = src.fileStream
    val fout = new File(rootDirectory, src.fileName)
    logger.debug("Saving {} to {}", src.name, fout.getAbsolutePath)
    val out = new FileOutputStream(fout)
    // Transfer bytes from in to out
    val buf = new Array[Byte](1024)
    var len = 0
    while ({len = in.read(buf) ; len} > 0) { out.write(buf, 0, len) }
    in.close
    out.close
    ()
  }

}
