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
import com.normation.errors.{IOResult, Inconsistency}
import net.liftweb.json.JsonAST.JValue
import net.liftweb.json.JsonDSL._
import net.liftweb.json.Serialization.write
import net.liftweb.json.{JString, JsonParser}
import zio._
import zio.syntax._
import java.nio.charset.StandardCharsets

final case class Secret(name: String, value: String)

class SecretVaultService(
  jsonDbPath: String
) {

  private val secretsFile  = File(jsonDbPath)

  private[this] def parseVersion1(json: JValue): IOResult[List[Secret]] = {
    implicit val formats = net.liftweb.json.DefaultFormats
    println((json \ "secrets"))
    IOResult.effect((json \ "secrets").extract[List[Secret]])
  }

  private[this] def parseVersionOther(json: JValue, formatVersion: String): IOResult[List[Secret]] = {
    formatVersion match {
      case s => Inconsistency(s"Version format ${s} unknown").fail
    }
  }

  private[this] def getSecretJsonContent: IOResult[List[Secret]] = {
    val content = secretsFile.contentAsString(StandardCharsets.UTF_8)
    for {
      json     <- IOResult.effect(JsonParser.parse(content))
      secrets <- (json \ "formatVersion") match {
                   case JString("1.0")        => parseVersion1(json)
                   case JString(otherVersion) => parseVersionOther(json, otherVersion)
                   case v                     =>
                     Inconsistency(s"Invalid format when parsing secret file ${secretsFile.path}, expected ${}, got ").fail
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
               val f = secretsFile.createFileIfNotExists(true)
               f.write(json)
             }
           }
    } yield ()
  }

  def getSecrets: IOResult[List[Secret]] = {
    for {
      s <- getSecretJsonContent
    } yield s
  }

  private[this] def replaceInFile(formatVersion: String, secrets: List[Secret]): IOResult[Unit] = {
    IOResult.effect{
      secretsFile.clear()
      implicit val formats = net.liftweb.json.DefaultFormats
      val json =
        ( ("formatVersion" -> formatVersion)
        ~ ("secrets" -> secrets.map(serializeSecret))
        )
      secretsFile.write(net.liftweb.json.prettyRender(json))
    }
  }

  private[this] def doesSecretExists(searchIn: List[Secret], toFind: Secret): Boolean = {
    searchIn.map(_.name).contains(toFind.name)
  }

  def addSecret(formatVersion: String, s: Secret): IOResult[Unit] = {
    for {
      secrets  <- getSecretJsonContent
      _        <- ZIO.when(!doesSecretExists(secrets, s)) {
                    val newSecrets     = s :: secrets
                    for {
                      _ <- replaceInFile(formatVersion, newSecrets)
                    } yield ()
                 }
    } yield ()
  }

  def deleteSecret(formatVersion: String, secretId: String): IOResult[Unit] = {
    for {
      secrets     <- getSecretJsonContent
      newSecrets  =  secrets.filter(_.name != secretId)
      _           <- replaceInFile(formatVersion, newSecrets)
    } yield ()
  }

  def updateSecret(formatVersion: String, updatedSecret: Secret): IOResult[Unit] = {
    for {
      secrets <- getSecretJsonContent
      _       <- ZIO.when(doesSecretExists(secrets, updatedSecret)) {
                   val newSecrets = secrets.map( s => if(s.name == updatedSecret.name) updatedSecret else s)
                   for {
                     _ <- replaceInFile(formatVersion, newSecrets)
                   } yield ()
                }
    } yield ()
  }

  def serializeSecret(secret : Secret): JValue = {
    (   ("name"      -> secret.name)
      ~ ("value"     -> secret.value)
    )
  }

}
