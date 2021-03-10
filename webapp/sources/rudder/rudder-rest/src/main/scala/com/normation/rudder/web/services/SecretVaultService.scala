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

import better.files.Dsl.SymbolicOperations
import better.files.File
import com.normation.errors.IOResult
import com.normation.errors.Inconsistency
import com.normation.zio._
import net.liftweb.json.JsonAST.JValue
import net.liftweb.json.JsonParser
import net.liftweb.json.Serialization.write
import net.liftweb.json.JsonDSL._
import zio.Ref
import zio._
import zio.syntax._

import java.nio.charset.StandardCharsets

final case class Metadata(author: Option[String], formatVersion: Option[String], date: Option[String])
final case class Secret(name: String, value: String)
final case class JsonSecretFile(metadata: Metadata, secrets: List[Secret])

class SecretVaultService(
  jsonDbPath: String
) {

  private val secretsFile  = File(jsonDbPath)

  private[this] val secretsCache: Ref[List[Secret]] = (for {
    v <- Ref.make[List[Secret]](List.empty)
  } yield v).runNow


  private[this] def getSecretJsonContent: IOResult[JsonSecretFile] = {
    IOResult.effectM("Unable to get content of Secret vault file") {
      for {
        jsonSecretFile <- if (secretsFile.exists && secretsFile.isReadable) {
          val json = secretsFile.contentAsString(StandardCharsets.UTF_8)
          implicit val formats = net.liftweb.json.DefaultFormats
          JsonParser.parse(json).extract[JsonSecretFile].succeed
        } else {
          Inconsistency(s"Secret vault file not found or not readable: ${secretsFile.path.toString}").fail
        }
      } yield {
        jsonSecretFile
      }
    }
  }

  def reloadFromFile: IOResult[Unit] = {
    for {
      secretsFromFile <- getSecretJsonContent
      _               <- secretsCache.set(secretsFromFile.secrets)
    } yield {}
  }

  def init: IOResult[Unit]  = {
    val json =
      """
        |{
        |  "metadata": {
        |  }
        |
        |  "secrets": [
        |  ]
        |}
        |
        |""".stripMargin
    for {
      _ <- ZIO.when(secretsFile.notExists){
             IOResult.effect{
               val f = secretsFile.createFileIfNotExists(true)
               f.write(json)
             }
           }
      _ <- reloadFromFile
    } yield ()
  }

  def getSecrets: IOResult[List[Secret]] = {
    for {
      secrets <- secretsCache.get
    } yield {
      secrets
    }
  }

  private[this] def replaceInFile(jsonSecretFile: JsonSecretFile): IOResult[Unit] = {
    IOResult.effect{
      secretsFile.clear()
      implicit val formats = net.liftweb.json.DefaultFormats
      secretsFile <<  write(jsonSecretFile)
    }
  }

  private[this] def doesSecretExists(searchIn: List[Secret], toFind: Secret): Boolean = {
    searchIn.map(_.name).contains(toFind.name)
  }

  def addSecret(secret: Secret): IOResult[Unit] = {
    for {
      content  <- getSecretJsonContent
      sInCache <- secretsCache.get
      _ <- ZIO.when(!doesSecretExists(content.secrets, secret) && !doesSecretExists(sInCache, secret)) {
             val newSecrets     = secret :: content.secrets
             val contentToWrite = content.copy(secrets = newSecrets)
             for {
               _ <- replaceInFile(contentToWrite)
               _ <- secretsCache.update(secret :: _)
             } yield ()
      }
    } yield ()
  }

  def deleteSecret(secretId: String): IOResult[Unit] = {
    for {
      content        <- getSecretJsonContent
      newSecrets     =  content.secrets.filter(_.name != secretId)
      contentToWrite = content.copy(secrets = newSecrets)
      _              <- replaceInFile(contentToWrite)
      _              <- secretsCache.update(_.filter(_.name != secretId))
    } yield ()
  }

  def updateSecret(secret: Secret): IOResult[Unit] = {
    for {
      content        <- getSecretJsonContent
      sInCache <- secretsCache.get
      _ <- ZIO.when(doesSecretExists(content.secrets, secret) && doesSecretExists(sInCache, secret)) {
        val newSecrets     = content.secrets.map( s => if(s.name == secret.name) secret else s)
        val contentToWrite = content.copy(secrets = newSecrets)
        for {
          _ <- replaceInFile(contentToWrite)
          _ <- secretsCache.update(_.map( s => if(s.name == secret.name) secret else s))
        } yield ()
      }
    } yield ()
  }
}
