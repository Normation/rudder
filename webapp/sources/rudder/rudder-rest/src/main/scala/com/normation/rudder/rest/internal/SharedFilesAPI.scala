/*
 ***************************o**********************************************************
 * Copyright 2016 Normation SwnloaAS
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

package com.normation.rudder.rest.internal

import better.files._
import com.normation.box._
import com.normation.errors._
import com.normation.errors.IOResult
import com.normation.rudder.rest.RestExtractorService
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.PosixFilePermissions
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.http.JsonResponse
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.http.StreamingResponse
import net.liftweb.http.rest.RestHelper
import net.liftweb.json.JsonAST.JArray
import net.liftweb.json.JsonAST.JField
import net.liftweb.json.JsonAST.JNull
import net.liftweb.json.JsonAST.JObject
import net.liftweb.json.JsonAST.JString
import net.liftweb.json.JsonAST.JValue
import org.joda.time.DateTime
import org.joda.time.Instant
import scala.jdk.CollectionConverters._
import zio.ZIO
import zio.syntax._

class SharedFilesAPI(
    restExtractor:    RestExtractorService,
    sharedFolderPath: String,
    configRepoPath:   String
) extends RestHelper with Loggable {

  def checkPathAndContinue(path: String, baseFolder: File)(fun: File => IOResult[LiftResponse]): IOResult[LiftResponse] = {
    IOResult.attemptZIO {
      val filePath = baseFolder / path.dropWhile(_.equals('/'))
      if (baseFolder.contains(filePath, false)) {
        fun(filePath)
      } else {
        Unexpected(s"Unauthorized access to file ${filePath.name}").fail
      }
    }
  }
  def serialize(file: File):                                                                     IOResult[JValue]       = {
    import net.liftweb.json.JsonDSL._
    IOResult.attempt(s"Error when serializing file ${file.name}") {
      val date = new DateTime(Instant.ofEpochMilli(Files.getLastModifiedTime(file.path, File.LinkOptions.noFollow: _*).toMillis))
      (("name"    -> file.name)
      ~ ("size"   -> (try { file.size }
      catch { case _: NoSuchFileException => 0L }))
      ~ ("type"   -> (if (file.isDirectory) "dir" else "file"))
      ~ ("date"   -> date.toString("yyyy-MM-dd HH:mm:ss"))
      ~ ("rights" -> file.permissionsAsString(File.LinkOptions.noFollow)))
    }
  }
  def errorResponse(message: String):                                                            LiftResponse           = {
    import net.liftweb.json.JsonDSL._
    val content = {
      (("success" -> false)
      ~ ("error"  -> message))
    }
    JsonResponse(content, Nil, Nil, 500)
  }
  val basicSuccessResponse:                                                                      LiftResponse           = {
    import net.liftweb.json.JsonDSL._
    val content = {
      (("success" -> true)
      ~ ("error"  -> JNull))
    }
    JsonResponse(content, Nil, Nil, 200)
  }
  def downloadFile(file: File):                                                                  IOResult[LiftResponse] = {
    IOResult.attemptZIO {
      if (file.exists) {
        if (file.isRegularFile) {
          val fileSize = file.size
          val headers  = {
            ("Content-type"        -> "application/octet-stream") ::
            ("Content-length"      -> fileSize.toString) ::
            ("Content-disposition" -> s"attachment; filename=${file.name}") ::
            Nil
          }
          StreamingResponse(file.newInputStream, () => {}, fileSize, headers, Nil, 200).succeed
        } else {
          Unexpected(s"File '${file.name}' is not a regular file").fail
        }
      } else {
        Unexpected(s"File '${file.name}' does not exist").fail
      }
    }
  }
  def directoryContent(directory: File):                                                         IOResult[LiftResponse] = {

    IOResult.attemptZIO {
      if (directory.exists) {
        if (directory.isDirectory()) {
          val jsonFiles: IOResult[List[JValue]] = directory.children.toSeq.accumulate(serialize)
          jsonFiles.map { files =>
            val result = JObject(List(JField("result", JArray(files))))
            JsonResponse(result, List(), List(), 200)
          }
        } else {
          Unexpected(s"File '${directory.name}' is not a directory").fail
        }
      } else {
        Unexpected(s"File '${directory.name}' does not exist").fail
      }
    }
  }
  def fileContent(file: File): IOResult[LiftResponse] = {
    IOResult.attemptZIO {
      if (file.exists) {
        if (file.isRegularFile) {
          import net.liftweb.json.JsonDSL._
          val result = JObject(List(JField("result", file.contentAsString(StandardCharsets.UTF_8))))
          JsonResponse(result, List(), List(), 200).succeed
        } else {
          Unexpected(s"File '${file.name}' is not a regular file").fail
        }
      } else {
        Unexpected(s"File '${file.name}' does not exist").fail
      }
    }
  }

  def editFile(content: String)(file: File):     IOResult[LiftResponse] = {
    IOResult.attemptZIO {
      if (file.exists) {
        if (file.isRegularFile) {
          file.write(content)
          basicSuccessResponse.succeed
        } else {
          Unexpected(s"File '${file.name}' is not a regular file").fail
        }
      } else {
        Unexpected(s"File '${file.name}' does not exist").fail
      }
    }
  }
  def setPerms(rawPerms: String)(file: File):    IOResult[LiftResponse] = {
    IOResult.attempt {
      val perms = PosixFilePermissions.fromString(rawPerms).asScala.toSet
      file.setPermissions(perms)
      basicSuccessResponse
    }
  }
  def removeFile(file: File):                    IOResult[LiftResponse] = {
    IOResult.attempt {
      file.delete(true)
      basicSuccessResponse
    }
  }
  def moveToDirectory(oldFile: File)(dir: File): IOResult[LiftResponse] = {
    IOResult.attemptZIO {
      if (oldFile.exists) {
        oldFile.moveToDirectory(dir)
        basicSuccessResponse.succeed
      } else {
        Unexpected(s"File '${oldFile.name}' does not exist").fail
      }
    }
  }
  def copyToDirectory(oldFile: File)(dir: File): IOResult[LiftResponse] = {
    IOResult.attemptZIO {
      if (oldFile.exists) {
        oldFile.copyToDirectory(dir)
        basicSuccessResponse.succeed
      } else {
        Unexpected(s"File '${oldFile.name}' does not exist").fail
      }
    }
  }
  def renameFile(oldFile: File)(newFile: File):  IOResult[LiftResponse] = {

    IOResult.attemptZIO {
      if (oldFile.exists) {
        oldFile.moveTo(newFile)
        basicSuccessResponse.succeed
      } else {
        Unexpected(s"File '${oldFile.name}' does not exist").fail
      }
    }
  }

  def createFile(newFile: File): IOResult[LiftResponse] = {
    IOResult.attempt {
      newFile.createFileIfNotExists(false)
      basicSuccessResponse
    }
  }

  def createFolder(newdirectory: File): IOResult[LiftResponse] = {
    IOResult.attempt {
      newdirectory.createDirectoryIfNotExists(false)
      basicSuccessResponse
    }
  }

  def requestDispatch(basePath: File): PartialFunction[Req, () => Box[LiftResponse]] = {

    case Get(Nil, req)  => {
      (req.params.get("action") match {
        case None                    => Failure("'action' is not defined in request")
        case Some("download" :: Nil) =>
          req.params.get("path") match {
            case Some(path :: Nil) =>
              checkPathAndContinue(path, basePath)(downloadFile).toBox
            case None              =>
              Failure("Path of file to download is not defined")
            case Some(values)      =>
              Failure("Too many values in request for path of file to download")
          }
        case Some(action :: Nil)     =>
          Failure("Action not supported")
        case Some(actions)           =>
          Failure("Too many values in request for action")
      }) match {
        case Full(response) =>
          response
        case eb: EmptyBox =>
          val fail = eb ?~! s"An error occurred while looking into directory"
          logger.error(fail.messageChain)
          errorResponse(fail.messageChain)
      }
    }
    case Post(Nil, req) => {
      req.params.get("destination") match {
        case Some(dest :: Nil) =>
          for {
            file <- req.uploadedFiles
          } yield {
            for {
              in  <- file.fileStream.autoClosed
              out <- (basePath / dest.replaceFirst("/", "") / file.fileName).createFileIfNotExists().newOutputStream.autoClosed
            } yield {
              in.pipeTo(out)
            }
          }
          basicSuccessResponse
        case _                 =>
          (req.json match {
            case Full(json) =>
              def simpleAction(actionName: String, itemName: String, action: File => IOResult[LiftResponse]) = {
                json \ itemName match {
                  case JString(path) =>
                    checkPathAndContinue(path, basePath)(f => {
                      (IOResult.attemptZIO(s"An error occurred while running action '${actionName}' ") {
                        action(f)
                      })
                    }).toBox
                  case _             => Failure(s"'${itemName}' is not correctly defined for '${actionName}' action")
                }
              }

              def actionWithParam(
                  actionName: String,
                  itemName:   String,
                  paramName:  String,
                  action:     String => File => IOResult[LiftResponse]
              ) = {
                json \ itemName match {
                  case JString(item) =>
                    json \ paramName match {
                      case JString(param) =>
                        checkPathAndContinue(item, basePath)(action(param)).toBox
                      case _              => Failure(s"'${paramName}' is not correctly defined for '${actionName}' action")
                    }
                  case _             => Failure(s"'${itemName}' is not correctly defined for '${actionName}' action")
                }
              }

              def actionList(
                  actionName: String,
                  itemName:   String,
                  paramName:  String,
                  action:     String => File => IOResult[LiftResponse]
              ) = {
                json \ itemName match {
                  case JArray(items) =>
                    ZIO
                      .foreach(items) {
                        case JString(item) =>
                          json \ paramName match {
                            case JString(param) =>
                              checkPathAndContinue(item, basePath)(action(param))
                            case _              => Unexpected(s"'${paramName}' is not correctly defined for '${actionName}' action").fail
                          }
                        case item          =>
                          Unexpected(
                            s"a value from array '${itemName}', for action '${actionName}' is not valid, should be a string but is: ${net.liftweb.json
                                .compactRender(item)}"
                          ).fail
                      }
                      .map(_ => basicSuccessResponse)
                      .toBox
                  case _             => Failure(s"'${itemName}' is not correctly defined for '${actionName}' action")
                }
              }

              json \ "action" match {

                case JString("list") =>
                  simpleAction("list", "path", directoryContent)

                case JString("getContent") =>
                  simpleAction("getContent", "item", fileContent)

                case JString("createFolder") =>
                  simpleAction("createFolder", "newPath", createFolder)

                case JString("createFile") =>
                  simpleAction("createFolder", "newPath", createFile)

                case JString("edit") =>
                  actionWithParam("edit", "item", "content", editFile)

                case JString("rename") =>
                  actionWithParam(
                    "rename",
                    "item",
                    "newItemPath",
                    (newItem => oldFile => checkPathAndContinue(newItem, basePath)(renameFile(oldFile)))
                  )

                case JString("remove") =>
                  json \ "items" match {
                    case JArray(items) =>
                      ZIO
                        .foreach(items) {
                          case JString(item) =>
                            checkPathAndContinue(item, basePath)(removeFile)
                          case item          =>
                            Unexpected(
                              s"a value from array 'items', for action 'remove' is not valid, should be a string but is: ${net.liftweb.json
                                  .compactRender(item)}"
                            ).fail
                        }
                        .map(_ => basicSuccessResponse)
                        .toBox
                    case _             => Failure("'item' is not correctly defined for 'getContent' action")
                  }

                case JString("changePermissions") =>
                  actionList("changePermissions", "items", "perms", setPerms)

                case JString("move") =>
                  actionList(
                    "move",
                    "items",
                    "newPath",
                    (newItem => oldFile => checkPathAndContinue(newItem, basePath)(moveToDirectory(oldFile)))
                  )

                case JString("copy") =>
                  actionList(
                    "copy",
                    "items",
                    "newPath",
                    (newItem => oldFile => checkPathAndContinue(newItem, basePath)(copyToDirectory(oldFile)))
                  )

                case _ => Failure("Action not supported")
              }
            case _          => Failure("'action' is not defined in json data")
          }) match {
            case Full(response) =>
              response
            case eb: EmptyBox =>
              val fail = eb ?~! s"An error occurred while looking into directory"
              logger.error(fail.messageChain)
              errorResponse(fail.messageChain)
          }
      }
    }
  }

  def ncfRequestDispatch: PartialFunction[Req, () => Box[LiftResponse]] = {
    new PartialFunction[Req, () => Box[LiftResponse]] {
      def isDefinedAt(req: Req): Boolean                 = {
        req.path.partPath match {
          case "draft" :: techniqueId :: techniqueVersion :: _ =>
            val path = File(s"${configRepoPath}/workspace/${techniqueId}/${techniqueVersion}/resources")
            val pf   = requestDispatch(path)
            pf.isDefinedAt(req.withNewPath(req.path.drop(3)))
          case techniqueId :: techniqueVersion :: categories   =>
            val path = File(
              s"${configRepoPath}/techniques/${categories.mkString("/")}/${techniqueId}/${techniqueVersion}/resources"
            )
            val pf   = requestDispatch(path)
            pf.isDefinedAt(req.withNewPath(req.path.drop(req.path.partPath.size)))
          case _                                               =>
            false
        }
      }
      def apply(req: Req):       () => Box[LiftResponse] = {
        req.path.partPath match {
          case "draft" :: techniqueId :: techniqueVersion :: _ =>
            val path = File(s"${configRepoPath}/workspace/${techniqueId}/${techniqueVersion}/resources")
            path.createIfNotExists(true, true)
            val pf   = requestDispatch(path)
            pf.apply(req.withNewPath(req.path.drop(3)))
          case techniqueId :: techniqueVersion :: categories   =>
            val path = File(
              s"${configRepoPath}/techniques/${categories.mkString("/")}/${techniqueId}/${techniqueVersion}/resources"
            )
            path.createIfNotExists(true, true)
            val pf   = requestDispatch(path)
            pf.apply(req.withNewPath(req.path.drop(req.path.partPath.size)))
          case _                                               =>
            (() => Failure("invalid request on shared file api"))
        }
      }
    }

  }
  serve("secure" :: "api" :: "sharedfile" :: Nil prefix requestDispatch(File(sharedFolderPath)))

  serve("secure" :: "api" :: "resourceExplorer" :: Nil prefix ncfRequestDispatch)
}
