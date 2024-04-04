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

import better.files.File
import com.normation.errors.*
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.syntax.*
import java.nio.charset.StandardCharsets
import org.joda.time.DateTime
import zio.json.*
import zio.syntax.*

/*
 * This file is able to read information from /var/rudder/packages/index.json
 * (package management file for plugins)
 */

// this is only used internally but liftweb can't hand it if not top level
final protected case class JsonPluginFile(plugins: Map[String, JsonPluginRaw])
protected object JsonPluginFile {
  implicit val decoder: JsonDecoder[JsonPluginFile] = DeriveJsonDecoder.gen[JsonPluginFile]
}

/**
  * The external json format of a plugin in the index file.
  *
  * Optional fields are there to obtain empty lists when mapping none
  */
final protected case class JsonPluginRaw(
    name:           String,
    version:        RudderPluginVersion,
    files:          Option[List[String]],
    `jar-files`:    Option[List[String]],
    `build-commit`: String,
    `build-date`:   DateTime
)
protected object JsonPluginRaw {
  import com.normation.utils.DateFormaterService.json.*
  implicit val pluginVersionDecoder: JsonDecoder[RudderPluginVersion] = {
    JsonDecoder[String].mapOrFail(version =>
      RudderPluginVersion.from(version).toRight(s"Version is not a valid plugin version: ${version}")
    )
  }
  implicit val decoder:              JsonDecoder[JsonPluginRaw]       = DeriveJsonDecoder.gen[JsonPluginRaw]

  implicit val transformer: Transformer[JsonPluginRaw, JsonPluginDef] = {
    Transformer
      .define[JsonPluginRaw, JsonPluginDef]
      .withFieldComputed(_.files, _.files.getOrElse(List.empty))
      .withFieldComputed(_.jars, _.`jar-files`.getOrElse(List.empty))
      .withFieldRenamed(_.`build-commit`, _.buildCommit)
      .withFieldRenamed(_.`build-date`, _.buildDate)
      .buildTransformer
  }
}

final case class JsonPluginDef(
    name:        String,
    version:     RudderPluginVersion,
    files:       List[String],
    jars:        List[String],
    buildCommit: String,
    buildDate:   DateTime
)

/*
 * The service in charge of reading the package file with plugin info.
 * By convention, in Rudder, its path is at /var/rudder/packages/index.json
 */
class ReadPluginPackageInfo(val index: File) {
  import ReadPluginPackageInfo.*

  def check: IOResult[Boolean] = {
    IOResult.attempt {
      index.isRegularFile && index.isReadable
    }
  }

  def readIndex(): IOResult[String] = {
    IOResult.attempt {
      index.contentAsString(StandardCharsets.UTF_8)
    }
  }

  def getInfo(): IOResult[List[JsonPluginDef]] = {
    IOResult.attempt(index.exists).flatMap { exists =>
      if (exists) {
        check *> readIndex()
          .flatMap(parseJsonPluginFileDefs(_).toIO)
      } else List.empty.succeed
    }
  }

}

object ReadPluginPackageInfo {
  def parseJsonPluginFileDefs(s: String): Either[String, List[JsonPluginDef]] =
    s.fromJson[JsonPluginFile].map(_.plugins.values.toList.map(_.transformInto[JsonPluginDef]))
}
