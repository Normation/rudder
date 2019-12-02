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

import java.nio.file.Files

import better.files._
import better.files.File.Attributes
import com.normation.errors._
import com.normation.rudder.inventory.InventoryFileWatcher
import com.normation.rudder.inventory.InventoryProcessStatus
import com.normation.rudder.inventory.InventoryProcessingLogger
import com.normation.rudder.inventory.InventoryProcessor
import com.normation.rudder.inventory.SaveInventoryInfo
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.ApiVersion
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.HttpStatus
import com.normation.rudder.rest.RestError
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.RestUtils.effectiveResponse
import com.normation.rudder.rest.RestUtils.toJsonError
import com.normation.rudder.rest.RestUtils.toJsonResponse
import com.normation.rudder.rest.{InventoryApi => API}
import com.normation.zio.ZioRuntime
import net.liftweb.http.FileParamHolder
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.json.JsonDSL._
import zio._
import zio.syntax._

class InventoryApi (
    restExtractorService: RestExtractorService
  , inventoryProcessor  : InventoryProcessor
  , inventoryFileWatcher: InventoryFileWatcher
) extends LiftApiModuleProvider[API] {

  def schemas = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => e match {
      case API.QueueInformation   => QueueInformation
      case API.UploadInventory    => UploadInventory
      case API.FileWatcherStart   => FileWatcherStart
      case API.FileWatcherStop    => FileWatcherStop
      case API.FileWatcherRestart => FileWatcherRestart
    }).toList
  }

  object QueueInformation extends LiftApiModule0 {
    val tooManyRequestError = new RestError {
        override def code: Int = 429 // too many requests
      }
    val schema = API.QueueInformation
    val restExtractor = restExtractorService
    val actionName = "queueInformation"
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      // Get the current size, the remaining of the answer is based on that.
      // The "+1" is because the fiber waiting for inventory give 1 slot,
      // if don't "+1", we start at -1 when no inventories are here.
      val current = inventoryProcessor.currentQueueSize + 1
      //must be coherent with can do, current = 49 < max = 50 => not saturated
      val saturated = current >= inventoryProcessor.maxQueueSize
      val json = (
                    ("queueMaxSize"   -> inventoryProcessor.maxQueueSize)
                  ~ ("queueSaturated" -> saturated)
                 )

      if(saturated) {
        effectiveResponse (None, json, tooManyRequestError, actionName, params.prettify
        )
      } else {
        toJsonResponse(None, json)(actionName, params.prettify)
      }
    }
  }

  /*
   * POST inventory files in a "content-dispotion: file" format, and
   * get file with name "file" and "signature", for ex produced by:
   *   curl -F "file=@path/to/file" -F "signature=@path/to/signature"
   *
   * Uploaded files are stored in a temporary file (which is particularly
   * inefficient for inventory already uploaded to rudder).
   */
  object UploadInventory extends LiftApiModule0 {
    val schema = API.UploadInventory
    val restExtractor = restExtractorService
    val FILE = "file"
    val SIG = "signature"
    val sigExtension = ".sign"

    object UNAUTHORIZED        extends RestError { override val code: Int = 401 }
    object PRECONDITION_FAILED extends RestError { override val code: Int = 412 }
    object SERVICE_UNAVAILABLE extends RestError { override val code: Int = 503 }
    object ACCEPTED extends HttpStatus {
      val code = 200
      val status = "success"
      val container = "data"
    }
    val tempDir = File(Files.createTempDirectory("rudder-rest-uploaded-inventories", Attributes.default:_*))
    implicit val actionName = "uploadInventory"

    def parseInventory(pretty: Boolean, inventoryFile: FileParamHolder, signatureFile: Option[FileParamHolder]): LiftResponse = {
      // here, we are at the end of our world. Evaluate ZIO and see what happen.
      val originalFilename = inventoryFile.name
      val signatureFilename = originalFilename + sigExtension

      def writeFile(item: FileParamHolder, file: File) = ZIO.bracket(IOResult.effect(item.fileStream))(is => effectUioUnit(is.close())) { is =>
        IOResult.effect(file.outputStream.foreach(is.pipeTo(_))) *> file.succeed
      }
      def optWrite(item: Option[FileParamHolder], file: File) = item match {
        case None    => None.succeed
        case Some(i) => writeFile(i, file).map(Some(_))
      }

      val prog =
        ZIO.bracket(writeFile(inventoryFile, File(tempDir, originalFilename)))(f => effectUioUnit(f.delete())) { inv =>
          ZIO.bracket(optWrite(signatureFile, File(tempDir, signatureFilename)))(opt => opt.fold(UIO.unit)(f => effectUioUnit(f.delete()))) { optSig =>
            inventoryProcessor.saveInventory(SaveInventoryInfo(originalFilename, () => inv.newFileInputStream, optSig.map(f => () => f.newFileInputStream))).map {
              status =>
                import com.normation.rudder.inventory.StatusLog.LogMessage
                status match {
                  case InventoryProcessStatus.MissingSignature(_,_) => effectiveResponse(None, status.msg, UNAUTHORIZED, actionName, pretty)
                  case InventoryProcessStatus.SignatureInvalid(_,_) => effectiveResponse(None, status.msg, UNAUTHORIZED, actionName, pretty)
                  case InventoryProcessStatus.QueueFull(_,_)        => effectiveResponse(None, status.msg, SERVICE_UNAVAILABLE, actionName, pretty)
                  case InventoryProcessStatus.Accepted(_,_)         => effectiveResponse(None, status.msg, ACCEPTED, actionName, pretty)
                }
            }
          }
        }

      ZioRuntime.runNow(prog.catchAll { eb =>
        val fail = Chained(s"Error when trying to process inventory '${originalFilename}'", eb)
        InventoryProcessingLogger.error(fail.fullMsg) *>
        effectiveResponse(None, fail.fullMsg, PRECONDITION_FAILED, actionName, pretty).succeed
      })
    }

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val prettify = params.prettify
      // we need at least the file, else the request is in error
      (req.uploadedFiles.find(_.name == FILE), req.uploadedFiles.find(_.name == SIG)) match {
        case (None, _) =>
          toJsonError(None, "Missing uploaded file with parameter name 'file'")
        case (Some(inv), sig) =>
          parseInventory(params.prettify, inv, sig)
      }
    }
  }

  object FileWatcherStart extends LiftApiModule0 {
    val schema = API.FileWatcherStart
    val restExtractor = restExtractorService
    implicit val actionName = "fileWatcherStart"
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val pretty = params.prettify
      inventoryFileWatcher.startWatcher() match {
        case Right(()) =>
          toJsonResponse(None, "Incoming inventory watcher started")
        case Left(ex) =>
          toJsonError(None, s"Error when trying to start incoming inventories file watcher. Reported exception was: ${ex.fullMsg}.")
      }
    }
  }

  object FileWatcherStop extends LiftApiModule0 {
    val schema = API.FileWatcherStop
    val restExtractor = restExtractorService
    implicit val actionName = "fileWatcherStop"
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val pretty = params.prettify
      inventoryFileWatcher.stopWatcher() match {
        case Right(()) =>
          toJsonResponse(None, "Incoming inventory watcher stopped")
        case Left(ex) =>
          toJsonError(None, s"Error when trying to stop incoming inventories file watcher. Reported exception was: ${ex.fullMsg}.")
      }
    }
  }

  object FileWatcherRestart extends LiftApiModule0 {
    val schema = API.FileWatcherRestart
    val restExtractor = restExtractorService
    implicit val actionName = "frileWatcherRestart"
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val pretty = params.prettify
      (for {
        _ <- inventoryFileWatcher.stopWatcher()
        _ <- inventoryFileWatcher.startWatcher()
      } yield ())  match {
        case Right(()) =>
          toJsonResponse(None, "Incoming inventory watcher restarted")
        case Left(ex) =>
          toJsonError(None, s"Error when trying to restart incoming inventories file watcher. Reported exception was: ${ex.fullMsg}.")
      }
    }
  }

}

