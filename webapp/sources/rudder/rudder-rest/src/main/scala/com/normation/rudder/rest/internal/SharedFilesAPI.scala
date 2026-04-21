/*
 *************************************************************************************
 * Copyright 2016 Normation SAS
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

import better.files.*
import cats.syntax.apply.*
import com.normation.box.*
import com.normation.errors.*
import com.normation.rudder.rest.OldInternalApiAuthz
import com.normation.rudder.rest.RudderJsonRequest.*
import com.normation.rudder.rest.RudderJsonResponse
import com.normation.rudder.rest.internal.SharedFileApiProcessing.*
import com.normation.rudder.rest.internal.SharedFilePostQueries.*
import com.normation.rudder.users.UserService
import com.normation.utils.DateFormaterService.toJodaDateTime
import com.normation.utils.FileUtils.*
import com.normation.zio.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.PosixFilePermissions
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.http.StreamingResponse
import net.liftweb.http.rest.RestHelper
import org.apache.commons.fileupload2.core.FileUploadSizeException
import scala.jdk.CollectionConverters.*
import scala.util.Try
import zio.ZIO
import zio.json.*
import zio.json.ast.*
import zio.json.ast.Json.*
import zio.syntax.*

object SharedFilesAPI {
  // Configured max number of bytes in an uploaded file in Lift :
  // LiftRules.maxMimeSize and LiftRules.maxMimeFileSize are the configured values,
  // set to 8MB (default value of LiftRules.maxMimeSize)
  val MAX_FILE_SIZE: Int = 8388608
}

@jsonExplicitNull
final case class JStatusResponse(success: Boolean, error: Option[String]) derives JsonEncoder

final private case class JResponseResult[A](result: A) derives JsonEncoder

final private case class JFileInfo(
    name:   String,
    size:   Long,
    `type`: String,
    date:   String,
    rights: String
) derives JsonEncoder

private object JFileInfo {

  // this one is not a Transformer because impure

  def from(file: File): IOResult[JFileInfo] = {
    IOResult.attempt(s"Error when reading information about '${file.name}'") {
      val date = Files.getLastModifiedTime(file.path, File.LinkOptions.noFollow*).toInstant.toJodaDateTime
      JFileInfo(
        file.name,
        try { file.size }
        catch { case _: NoSuchFileException => 0L },
        if (file.isDirectory) "dir" else "file",
        date.toString("yyyy-MM-dd HH:mm:ss"),
        file.permissionsAsString(using File.LinkOptions.noFollow)
      )
    }
  }
}

class SharedFilesAPI(
    userService:      UserService,
    sharedFolderPath: String,
    configRepoPath:   String
) extends RestHelper with Loggable {

  private def errorResponse(message: String, code: Int = 500): LiftResponse = {
    RudderJsonResponse.LiftJsonResponse(JStatusResponse(false, Some(message)), false, code)
  }

  def requestDispatch(basePath: File): PartialFunction[Req, () => Box[LiftResponse]] = {

    case Get(Nil, req) => {
      given prettify: Boolean = false
      given action:   String  = "readFileResource"

      OldInternalApiAuthz.withReadConfig(userService.getCurrentUser) {
        (req.params.get("action") match {
          case None                    => Failure("'action' is not defined in request")
          case Some("download" :: Nil) =>
            req.params.get("path") match {
              case Some(path :: Nil) =>
                checkPathAndContinue(path, basePath)(SharedFileApiProcessing.downloadFile).toBox
              case None              =>
                Failure("Path of file to download is not defined")
              case Some(values)      =>
                Failure("Too many values in request for path of file to download")
            }
          case Some(action_ :: Nil)    =>
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
    }

    case Post(Nil, req) => {
      given prettify: Boolean = false
      given action:   String  = "readFileResource"

      OldInternalApiAuthz.withWriteConfig(userService.getCurrentUser) {
        // Accessing any of params, body and uploadedFiles on request may cause an Exception : Lift initializes
        // these as lazy val at the same time
        // ZIO introduced mapN since 2.1.7, we will likely want ZIO.attempt instead of cats in 8.3
        (
          Try(req.params),
          Try(req.uploadedFiles)
        ).mapN {
          case (params, uploadedFiles) => {
            params.get("destination") match {
              case Some(dest :: Nil) =>
                // process uploaded files as a side effect.
                // We check that we have only one file uploaded, else return an error to avoid that
                // the last as decided by the uploaded-files logic wins and overide others.

                uploadedFiles match {
                  case Nil         => errorResponse(s"Missing file to copy to ${dest}")
                  case file :: Nil =>
                    sanitizePath(basePath, dest.replaceFirst("/", "") + '/' + file.fileName).flatMap { path =>
                      IOResult
                        .attempt(s"Could not copy uploaded file to destination ${dest}")(for {
                          in  <- file.fileStream.autoClosed
                          out <- path.newOutputStream.autoClosed
                        } yield {
                          in.pipeTo(out)
                        })
                        .either
                        .map {
                          case Left(err) => errorResponse(err.fullMsg)
                          case Right(_)  => basicSuccessResponse
                        }
                    }.runNow
                  case several     =>
                    errorResponse(
                      s"This API only support one uploaded file to copy to ${dest}, but ${several.size} were provided"
                    )
                }

              case _ =>
                val action = JsonCursor.field("action").isString

                (req.fromJson[Json].toBox match {
                  case Full(json) => {
                    (json.get(action) match {

                      case Right(Str("list")) =>
                        simpleAction(json, basePath, "list", "path", directoryContent)

                      case Right(Str("getContent")) =>
                        simpleAction(json, basePath, "getContent", "item", fileContent)

                      case Right(Str("createFolder")) =>
                        simpleAction(json, basePath, "createFolder", "newPath", createFolder)

                      case Right(Str("createFile")) =>
                        simpleAction(json, basePath, "createFolder", "newPath", createFile)

                      case Right(Str("edit")) =>
                        actionWithParam(json, basePath, "edit", "item", "content", editFile)

                      case Right(Str("rename")) =>
                        actionWithParam(
                          json,
                          basePath,
                          "rename",
                          "item",
                          "newItemPath",
                          (newItem => oldFile => checkPathAndContinue(newItem, basePath)(renameFile(oldFile)))
                        )

                      case Right(Str("remove")) =>
                        val items = JsonCursor.field("items").isArray
                        json.get(items) match {
                          case Right(Arr(list)) =>
                            ZIO
                              .foreach(list) {
                                case Str(item) =>
                                  checkPathAndContinue(item, basePath)(removeFile)
                                case item      =>
                                  Unexpected(
                                    s"a value from array 'items', for action 'remove' is not valid, should be a string but is: ${item.toJson}"
                                  ).fail
                              }
                              .map(_ => basicSuccessResponse)
                              .toBox
                          case _                => Failure("'item' is not correctly defined for 'getContent' action")
                        }

                      case Right(Str("changePermissions")) =>
                        actionList(json, basePath, "changePermissions", "items", "perms", setPerms)

                      case Right(Str("move")) =>
                        actionList(
                          json,
                          basePath,
                          "move",
                          "items",
                          "newPath",
                          (newItem => oldFile => checkPathAndContinue(newItem, basePath)(moveToDirectory(oldFile)))
                        )

                      case Right(Str("copy")) =>
                        actionList(
                          json,
                          basePath,
                          "copy",
                          "items",
                          "newPath",
                          (newItem => oldFile => checkPathAndContinue(newItem, basePath)(copyToDirectory(oldFile)))
                        )

                      case _ => Failure("Action not supported")
                    })

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
        }.recover {
          // Lift uses apache file upload under the hood and this file exception can happen because of LiftRules.maxMimeSize.
          // The value should be approximately matching MAX_FILE_SIZE, if it is not the case then change either of them.
          case ex: FileUploadSizeException => {
            // This is rounded to MB which is the order of magnitude of file upload limit
            val allowedSizeMb: Long = ex.getPermitted / 1024 / 1024
            errorResponse(s"File exceeds the maximum upload size of ${allowedSizeMb}MB", code = 413)
          }
        }
          .fold(
            throw _,
            identity
          )
      }
    }
  }

  private def ncfRequestDispatch: PartialFunction[Req, () => Box[LiftResponse]] = {
    new PartialFunction[Req, () => Box[LiftResponse]] {
      override def isDefinedAt(req: Req): Boolean = {
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

      override def apply(req: Req): () => Box[LiftResponse] = {
        req.path.partPath match {
          case "draft" :: techniqueId :: techniqueVersion :: _ =>
            val path = {
              val base = File(configRepoPath)
              checkSanitizedIsIn(base, base / "workspace" / techniqueId / techniqueVersion / "resources").runNow
            }
            path.createIfNotExists(asDirectory = true, createParents = true)
            val pf   = requestDispatch(path)
            pf.apply(req.withNewPath(req.path.drop(3)))
          case techniqueId :: techniqueVersion :: categories   =>
            val path = sanitizePath(
              File(configRepoPath),
              ("techniques" :: categories) :+ techniqueId :+ techniqueVersion :+ "resources"
            ).runNow
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

/*
 * A separated object for the post cases
 */
object SharedFilePostQueries {

  def checkPathAndContinue(path: String, baseFolder: File)(
      fun: File => IOResult[LiftResponse]
  ): IOResult[LiftResponse] = {
    sanitizePath(baseFolder, path).flatMap(fun)
  }

  def simpleAction(
      json:       Json,
      basePath:   File,
      actionName: String,
      itemName:   String,
      action:     File => IOResult[LiftResponse]
  ): Box[LiftResponse] = {
    val itemCursor = JsonCursor.field(itemName).isString

    json.get(itemCursor) match {
      case Right(Str(path)) =>
        checkPathAndContinue(path, basePath)(f => {
          (
            IOResult.attemptZIO(s"An error occurred while running action '${actionName}' ") {
              action(f)
            }
          )
        }).toBox

      case _ => Failure(s"'${itemName}' is not correctly defined for '${actionName}' action")
    }
  }

  def actionWithParam(
      json:       Json,
      basePath:   File,
      actionName: String,
      itemName:   String,
      paramName:  String,
      action:     String => File => IOResult[LiftResponse]
  ): Box[LiftResponse] = {
    val itemCursor  = JsonCursor.field(itemName).isString
    val paramCursor = JsonCursor.field(paramName).isString

    json.get(itemCursor) match {
      case Right(Str(item)) =>
        json.get(paramCursor) match {
          case Right(Str(param)) =>
            checkPathAndContinue(item, basePath)(action(param)).toBox
          case _                 =>
            Failure(s"'${paramName}' is not correctly defined for '${actionName}' action")
        }
      case _                =>
        Failure(s"'${itemName}' is not correctly defined for '${actionName}' action")
    }
  }

  def actionList(
      json:       Json,
      basePath:   File,
      actionName: String,
      itemName:   String,
      paramName:  String,
      action:     String => File => IOResult[LiftResponse]
  ): Box[LiftResponse] = {
    val itemCursor  = JsonCursor.field(itemName).isArray
    val paramCursor = JsonCursor.field(paramName).isString

    json.get(itemCursor) match {
      case Right(Arr(items)) =>
        ZIO
          .foreach(items) {
            case Str(item) =>
              json.get(paramCursor) match {
                case Right(Str(param)) =>
                  checkPathAndContinue(item, basePath)(action(param))
                case _                 =>
                  Unexpected(s"'${paramName}' is not correctly defined for '${actionName}' action").fail
              }
            case item      =>
              Unexpected(
                s"a value from array '${itemName}', for action '${actionName}' is not valid, should be a string but is: ${item.toJson}"
              ).fail
          }
          .map(_ => basicSuccessResponse)
          .toBox

      case _ =>
        Failure(s"'${itemName}' is not correctly defined for '${actionName}' action")
    }
  }

}

object SharedFileApiProcessing {

  val basicSuccessResponse: LiftResponse = {
    RudderJsonResponse.LiftJsonResponse(JStatusResponse(true, None), false, 200)
  }

  def downloadFile(file: File): IOResult[LiftResponse] = {
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

  def directoryContent(directory: File): IOResult[LiftResponse] = {
    IOResult.attemptZIO {
      if (directory.exists) {
        if (directory.isDirectory()) {
          for {
            children  <- IOResult.attempt(directory.children.toSeq)
            jsonFiles <- ZIO.foreach(children)(JFileInfo.from)
          } yield {
            RudderJsonResponse.LiftJsonResponse(JResponseResult(jsonFiles), false, 200)
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
          val result = JResponseResult(file.contentAsString(using StandardCharsets.UTF_8))
          RudderJsonResponse.LiftJsonResponse(result, false, 200).succeed
        } else {
          Unexpected(s"File '${file.name}' is not a regular file").fail
        }
      } else {
        Unexpected(s"File '${file.name}' does not exist").fail
      }
    }
  }

  def editFile(content: String)(file: File): IOResult[LiftResponse] = {
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

  def setPerms(rawPerms: String)(file: File): IOResult[LiftResponse] = {
    IOResult.attempt {
      val perms = PosixFilePermissions.fromString(rawPerms).asScala.toSet
      file.setPermissions(perms)
      basicSuccessResponse
    }
  }

  def removeFile(file: File): IOResult[LiftResponse] = {
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

  def renameFile(oldFile: File)(newFile: File): IOResult[LiftResponse] = {

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

}
