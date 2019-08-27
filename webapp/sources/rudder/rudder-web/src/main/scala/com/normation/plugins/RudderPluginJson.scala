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

package com.normation.plugins

import java.nio.charset.StandardCharsets

import better.files.File
import org.joda.time.DateTime
import zio._
import zio.syntax._
import com.normation.errors._
import net.liftweb.json._
import org.joda.time.format.ISODateTimeFormat

/*
 * This file is able to read information from /var/rudder/packages/index.json
 * (package management file for plugins)
 */

// this is only used internally but liftweb can't hand it if not top level
protected final case class JsonPluginFile(plugins: Map[String, JValue])

// this is only used internally but liftweb can't hand it if not top level
protected final case class JsonPluginRaw(
    name          : String
  , version       : String
  , files         : List[String]
  , `jar-files`   : List[String]
  , `build-commit`: String
  , `build-date`  : String
)

final case class JsonPluginDef(
    name       : String
  , version    : PluginVersion
  , files      : List[String]
  , jars       : List[String]
  , buildCommit: String
  , buildDate  : DateTime
)

/*
 * The service in charge of reading the package file with plugin info.
 * By convention, in Rudder, path = /var/rudder/packages/index.json
 */
class ReadPluginPackageInfo(path: String) {

  val index = File(path)

  def check: IOResult[Boolean] = {
    IOResult.effect {
      index.isRegularFile && index.isReadable
    }
  }

  def readIndex(): IOResult[String] = {
    IOResult.effect {
      index.contentAsString(StandardCharsets.UTF_8)
    }
  }

  def parseFile(json: String): IOResult[JsonPluginFile] = {
    IOResult.effect {
      implicit val formats = net.liftweb.json.DefaultFormats
      JsonParser.parse(json).extract[JsonPluginFile]
    }
  }

  def decodeOne(plugin: JValue): IOResult[JsonPluginDef] = {
    implicit val formats = net.liftweb.json.DefaultFormats
    for {
      p       <- IOResult.effect(plugin.extract[JsonPluginRaw])
      date    <- IOResult.effect(DateTime.parse(p.`build-date`, ISODateTimeFormat.dateTimeNoMillis()))
      version <- PluginVersion.from(p.version).notOptional(s"Version for '${p.name}' is not a valid plugin version: ${p.version}")
    } yield {
      JsonPluginDef(p.name, version, p.files, p.`jar-files`, p.`build-commit`, date)
    }
  }

  def decode(plugin: JsonPluginFile): IOResult[List[Either[RudderError, JsonPluginDef]]] = {
    ZIO.foreach(plugin.plugins) { case (name, jvalue) =>
      decodeOne(jvalue).mapError(err => Chained(s"Error when decoding plugin information for entry '${name}'", err)).either
    }
  }

  def parseJson(json: String): IOResult[List[Either[RudderError, JsonPluginDef]]] = {
    parseFile(json) >>= decode
  }

  def getInfo(): IOResult[List[Either[RudderError, JsonPluginDef]]] = {
    IOResult.effect(index.exists).flatMap { exists =>
      if(exists) check *> readIndex() >>= parseJson
      else List().succeed
    }
  }
}
