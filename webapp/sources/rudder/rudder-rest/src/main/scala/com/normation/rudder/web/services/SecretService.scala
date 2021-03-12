/*
*************************************************************************************
* Copyright 2021 Normation SAS
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
package com.normation.rudder.web.services

import better.files.File
import com.normation.NamedZioLogger
import com.normation.errors.IOResult
import com.normation.errors.Inconsistency
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.secrets._
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.web.services.CurrentUserService.actor
import com.normation.utils.StringUuidGenerator
import net.liftweb.json.JsonAST.JValue
import net.liftweb.json.JsonDSL._
import net.liftweb.json.JString
import net.liftweb.json.JsonParser
import zio._
import zio.syntax._

import java.nio.charset.StandardCharsets

trait SecretService {
  def getSecrets: IOResult[List[Secret]]
  def addSecret(s: Secret): IOResult[Unit]
  def deleteSecret(secretId: String): IOResult[Unit]
  def updateSecret(newSecret: Secret): IOResult[Unit]
}

class FileSystemSecretRepository(
    jsonDbPath   : String
  , actionLogger : EventLogRepository
  , uuidGen      : StringUuidGenerator
) extends SecretService {

  private val secretsFile  = File(jsonDbPath)
  val logger = NamedZioLogger(this.getClass.getName)

  private[this] def parseVersion1(json: JValue): IOResult[List[Secret]] = {
    implicit val formats = net.liftweb.json.DefaultFormats
    IOResult.effect((json \ "secrets").extract[List[Secret]])
  }

  private[this] def getSecretJsonContent: IOResult[List[Secret]] = {
    val content = secretsFile.contentAsString(StandardCharsets.UTF_8)
    for {
      json    <- IOResult.effect(JsonParser.parse(content))
      secrets <- json \ "formatVersion" match {
                   case JString("1.0")        => parseVersion1(json)
                   case JString(otherVersion) =>
                     Inconsistency(s"Format `${otherVersion}` is not supported in ${secretsFile.path}, expected `formatVersion` 1.0").fail
                   case _                     =>
                     Inconsistency(s"Invalid format when parsing secret file ${secretsFile.path}, expected `formatVersion` 1.0").fail
      }
    } yield secrets
  }

  def init(version: String): IOResult[Unit]  = {
    val json =
      s"""
        | {
        |   "formatVersion" : "$version"
        |   "secrets":[
        |
        |   ]
        | }
        |
        |""".stripMargin
    for {
      _ <- ZIO.when(secretsFile.notExists){
             IOResult.effect{
               val f = secretsFile.createFileIfNotExists(createParents = true)
               f.write(json)
             }
           }
    } yield ()
  }

  override def getSecrets: IOResult[List[Secret]] = {
    for {
      s <- getSecretJsonContent
    } yield s
  }

  private[this] def replaceInFile(formatVersion: String, secrets: List[Secret]): IOResult[Unit] = {
    IOResult.effect{
      secretsFile.clear()
      val json =
        ( ("formatVersion" -> formatVersion)
        ~ ("secrets" -> secrets.map(Secret.serializeSecret))
        )
      secretsFile.write(net.liftweb.json.prettyRender(json))
    }
  }

  private[this] def doesSecretExists(searchIn: List[Secret], toFind: Secret): Boolean = {
    searchIn.exists(_.name == toFind.name)
  }

  override def addSecret(s: Secret): IOResult[Unit] = {
    val reason = "Add a secret"
    val modId  = ModificationId(uuidGen.newUuid)
    val formatVersion = "1.0"
    for {
      _        <- init(formatVersion)
      secrets  <- getSecretJsonContent
      _        <- ZIO.when(!doesSecretExists(secrets, s)) {
                    val newSecrets = s :: secrets
                    for {
                      _ <- replaceInFile(formatVersion, newSecrets)
                      _ <- actionLogger.saveAddSecret(modId, actor, s, Some(reason)).catchAll { e =>
                        logger.error(s"Error when trying to create event log `${modId.value}` for adding secret `${s.name}`, cause : ${e.fullMsg}")
                      }
                    } yield ()
                 }
    } yield ()
  }

  override def deleteSecret(secretId: String): IOResult[Unit] = {
    val reason = "Delete a secret"
    val modId  = ModificationId(uuidGen.newUuid)
    val formatVersion = "1.0"
    for {
      _               <- init(formatVersion)
      secrets         <- getSecretJsonContent
      secretToRemove  =  secrets.find(_.name == secretId)
      _               <- ZIO.when(secretToRemove.isDefined) {
                           val newSecrets = secrets.filter(_.name != secretId)
                           for {
                             _  <- replaceInFile(formatVersion, newSecrets)
                             _ <- secretToRemove match {
                               case Some(s) => actionLogger.saveDeleteSecret(modId, actor, s, Some(reason)).catchAll { e =>
                                 logger.error(s"Error when trying to create event log `${modId.value}` for deleting secret `${s.name}`, cause : ${e.fullMsg}")
                               }
                               case None    =>  // this case should never happen since secretToRemove is defined
                                 Inconsistency(s"Error secret `${secretId}` doesn't exists").fail
                             }
                           } yield ()
                        }
    } yield ()
  }

  override def updateSecret(newSecret: Secret): IOResult[Unit] = {
    val reason = "Update a secret"
    val modId  = ModificationId(uuidGen.newUuid)
    val formatVersion = "1.0"
    for {
      _         <- init(formatVersion)
      secrets   <- getSecretJsonContent
      oldSecret = secrets.find(_.name == newSecret.name)
      _         <- ZIO.when(oldSecret.isDefined) {
                     // Only one secret should be replaced here
                     val newSecrets = secrets.map( s => if(s.name == newSecret.name) newSecret else s)
                     for {
                       _ <- replaceInFile(formatVersion, newSecrets)
                       _ <- oldSecret match {
                         case Some(s) => actionLogger.saveModifySecret(modId, actor, s, newSecret, Some(reason)).catchAll { e =>
                           logger.error(s"Error when trying to update event log `${modId.value}` for updating secret `${s.name}`, cause : ${e.fullMsg}")
                         }
                         case None    =>  // this case should never happen since oldSecret is defined
                           Inconsistency(s"Error secret `${newSecret.name}` doesn't exists`").fail
                       }
                     } yield ()
                  }
    } yield ()
  }
}
