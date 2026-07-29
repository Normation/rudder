/*
 *************************************************************************************
 * Copyright 2019 Normation SAS
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

package com.normation.rudder.rest.lift

import better.files.*
import com.normation.errors.*
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.ports.InventoryFileWatcherPort
import com.normation.rudder.rest.ApiModuleProvider
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.InventoryApi as API
import com.normation.rudder.rest.syntax.*
import com.normation.rudder.rest.RestError
import com.normation.rudder.rest.RestUtils.effectiveResponse
import com.normation.rudder.rest.RestUtils.toJsonError
import com.normation.rudder.rest.RestUtils.toJsonResponse
import com.normation.rudder.rest.implicits.*
import com.normation.utils.FileUtils
import net.liftweb.http.FileParamHolder
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import zio.*
import zio.syntax.*

object InventoryApi {

  val sigExtension = ".sign"

  def getInventoryAndSignatureFileName(rawInventoryFile: String, rawSigFile: String): (String, String) = {
    val inventoryFile = File(rawInventoryFile)
    val sigFile       = File(rawSigFile)

    // remove gz extension for sig name comparison with inventory (optionally compressed) file
    val simpleOrig = {
      if (inventoryFile.name.endsWith(".gz")) inventoryFile.name.substring(0, inventoryFile.name.length - 3)
      else inventoryFile.name
    }

    if (sigFile.name.startsWith(simpleOrig)) { // assume extension is ok
      (inventoryFile.name, sigFile.name)
    } else {
      // we assume that anything that is not ending by .gz is a simple signature, whatever its extension
      // and we derive signature name from inventory file name enterily
      val ext = sigExtension + (if (sigFile.name.endsWith(".gz")) ".gz" else "")
      (inventoryFile.name, simpleOrig + ext)
    }
  }
}

class InventoryApi(
    inventoryFileWatcher: InventoryFileWatcherPort,
    incomingInventoryDir: File
) extends LiftApiModuleProvider[API] {

  def schemas: ApiModuleProvider[API] = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map {
      case API.UploadInventory    => UploadInventory
      case API.FileWatcherStart   => FileWatcherStart
      case API.FileWatcherStop    => FileWatcherStop
      case API.FileWatcherRestart => FileWatcherRestart
    }
  }

  /*
   * POST inventory files in a "content-dispotion: file" format, and
   * get file with name "file" and "signature", for ex produced by:
   *   curl -F "file=@path/to/file" -F "signature=@path/to/signature"
   *
   * Uploaded files are put in `/var/rudder/inventories/incoming` for processing.
   * Signature file mandatory now
   */
  object UploadInventory extends LiftApiModule0 {
    val schema: API.UploadInventory.type = API.UploadInventory
    val FILE = "file"
    val SIG  = "signature"

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      def writeFile(item: FileParamHolder, file: File) = {
        ZIO.acquireReleaseWith(IOResult.attempt(item.fileStream))(is => effectUioUnit(is.close())) { is =>
          IOResult.attempt(file.outputStream.foreach(is.pipeTo(_)))
        }
      }
      def parseInventory(pretty: Boolean, inventoryFile: FileParamHolder, signatureFile: FileParamHolder): IOResult[String] = {
        // here, we are at the end of our world. Evaluate ZIO and see what happen.
        // SECURITY: the multipart file names are attacker-controlled. We only ever keep their
        // basename (see InventoryApi.signatureFileName / File(_).name) so that a crafted name like
        // `pwn/../../../etc/cron.d/pwn` cannot escape the incoming inventory directory, and we
        // additionally route both writes through FileUtils.sanitizePath as a defense-in-depth jail.
        val (invName, sigName) = InventoryApi.getInventoryAndSignatureFileName(inventoryFile.fileName, signatureFile.fileName)

        for {
          sigPath <- FileUtils.sanitizePath(incomingInventoryDir, sigName)
          invPath <- FileUtils.sanitizePath(incomingInventoryDir, invName)
          _       <- writeFile(signatureFile, sigPath)
          _       <- writeFile(inventoryFile, invPath)
        } yield s"Inventory '${invName}' added to processing queue."
      }

      val prog = (req.uploadedFiles.find(_.name == FILE), req.uploadedFiles.find(_.name == SIG)) match {
        case (Some(inv), Some(sig)) =>
          parseInventory(params.prettify, inv, sig)
        case (_, _)                 =>
          Unexpected(s"Missing uploaded file with parameter name '${FILE}' or '${SIG}'").fail
      }

      prog.toLiftResponseZero(params, schema)
    }
  }

  object FileWatcherStart extends LiftApiModule0 {
    val schema:                                                                                                API.FileWatcherStart.type = API.FileWatcherStart
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse              = {
      inventoryFileWatcher
        .startWatcher()
        .as("Incoming inventory watcher started")
        .toLiftResponseOne(params, schema, _ => None)
    }
  }

  object FileWatcherStop extends LiftApiModule0 {
    val schema:                                                                                                API.FileWatcherStop.type = API.FileWatcherStop
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse             = {
      inventoryFileWatcher
        .stopWatcher()
        .as("Incoming inventory watcher stopped")
        .toLiftResponseOne(params, schema, _ => None)
    }
  }

  object FileWatcherRestart extends LiftApiModule0 {
    val schema:                                                                                                API.FileWatcherRestart.type = API.FileWatcherRestart
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse                = {
      inventoryFileWatcher
        .restartWatcher()
        .as("Incoming inventory watcher restarted")
        .toLiftResponseOne(params, schema, _ => None)
    }
  }

}
