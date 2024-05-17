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

package com.normation.plugins

import better.files.File
import com.normation.errors
import com.normation.errors.IOResult
import com.normation.utils.DateFormaterService
import java.time.ZonedDateTime
import java.util.Properties
import org.joda.time.DateTime
import zio.*
import zio.json.*

case class PluginSettings(
    url:           String,
    username:      String,
    password:      String,
    proxyUrl:      Option[String],
    proxyUser:     Option[String],
    proxyPassword: Option[String]
)

/*
 * Information about registered plugins that can be used
 * for API and monitoring things.
 */
final case class JsonPluginsDetails(
    globalLimits: Option[JsonGlobalPluginLimits],
    // plugins should be sorted by id
    details:      Seq[JsonPluginDetails]
)
object JsonPluginsDetails {
  implicit val encoderJsonPluginsDetails: JsonEncoder[JsonPluginsDetails] = DeriveJsonEncoder.gen

  def buildDetails(plugins: Seq[JsonPluginDetails]): JsonPluginsDetails = {
    val limits = JsonGlobalPluginLimits.getGlobalLimits(plugins.flatMap(_.license))
    JsonPluginsDetails(limits, plugins)
  }
}

/*
 * Global limit information about plugins (the most restrictive)
 */
final case class JsonGlobalPluginLimits(
    licensees: Option[Chunk[String]],
    // for now, min/max version is not used and is always 00-99
    startDate: Option[ZonedDateTime],
    endDate:   Option[ZonedDateTime],
    maxNodes:  Option[Int]
)
object JsonGlobalPluginLimits {
  import DateFormaterService.JodaTimeToJava
  import DateFormaterService.json.encoderZonedDateTime
  implicit val encoderGlobalPluginLimits: JsonEncoder[JsonGlobalPluginLimits] = DeriveJsonEncoder.gen

  def empty = JsonGlobalPluginLimits(None, None, None, None)
  // from a list of plugins, create the global limits
  def getGlobalLimits(licenses: Seq[PluginLicenseInfo]): Option[JsonGlobalPluginLimits] = {
    def comp[A](a: Option[A], b: Option[A], compare: (A, A) => A): Option[A] = (a, b) match {
      case (None, None)       => None
      case (Some(x), None)    => Some(x)
      case (None, Some(y))    => Some(y)
      case (Some(x), Some(y)) => Some(compare(x, y))
    }

    val limits = licenses.foldLeft(empty) {
      case (lim, lic) =>
        JsonGlobalPluginLimits(
          lim.licensees match {
            case Some(l) => Some(l.appended(lic.licensee).distinct.sorted)
            case None    => Some(Chunk(lic.licensee))
          }, // I'm not sure what we want here nor its meaning
          comp[ZonedDateTime](lim.startDate, Some(lic.startDate.toJava), (x, y) => if (x.isAfter(y)) x else y),
          comp[ZonedDateTime](lim.endDate, Some(lic.endDate.toJava), (x, y) => if (x.isBefore(y)) x else y),
          comp[Int](lim.maxNodes, lic.maxNodes, (x, y) => if (x < y) x else y)
        )
    }
    if (limits == empty) None else Some(limits)
  }
}

trait JsonPluginStatus   {
  def value: String
}
object JsonPluginStatus  {
  case object Enabled  extends JsonPluginStatus { override val value: String = "enabled"  }
  case object Disabled extends JsonPluginStatus { override val value: String = "disabled" }
}

final case class JsonPluginDetails(
    id:            String,
    name:          String,
    shortName:     String,
    description:   String,
    version:       String,
    status:        JsonPluginStatus,
    statusMessage: Option[String],
    license:       Option[PluginLicenseInfo]
)
object JsonPluginDetails {
  implicit val encoderPluginStatusRest: JsonEncoder[JsonPluginStatus]  = JsonEncoder[String].contramap(_.value)
  implicit val encoderPluginDetails:    JsonEncoder[JsonPluginDetails] = DeriveJsonEncoder.gen
}

/*
 * This object gives main information about license information.
 * It is designated to be read to the user. No string information
 * should be used for comparison.
 */
final case class PluginLicenseInfo(
    licensee:   String,
    softwareId: String,
    minVersion: String,
    maxVersion: String,
    startDate:  DateTime,
    endDate:    DateTime,
    maxNodes:   Option[Int],
    @jsonField("additionalInfo")
    others:     Map[String, String]
)
object PluginLicenseInfo {
  import DateFormaterService.json.encoderDateTime
  implicit val encoderPluginLicenseInfo: JsonEncoder[PluginLicenseInfo] = DeriveJsonEncoder.gen
}

trait PluginSettingsService {
  def readPluginSettings(): IOResult[PluginSettings]
  def writePluginSettings(settings: PluginSettings): IOResult[Unit]
}

class FilePluginSettingsService(pluginConfFile: File) extends PluginSettingsService {

  def readPluginSettings(): ZIO[Any, errors.RudderError, PluginSettings] = {

    val p = new Properties()
    for {
      _ <- IOResult.attempt(s"Reading properties from ${pluginConfFile.pathAsString}")(p.load(pluginConfFile.newInputStream))

      url            <- IOResult.attempt(s"Getting plugin repository url in ${pluginConfFile.pathAsString}")(p.getProperty("url"))
      userName       <-
        IOResult.attempt(s"Getting user name for plugin download in ${pluginConfFile.pathAsString}")(p.getProperty("username"))
      pass           <-
        IOResult.attempt(s"Getting password for plugin download in ${pluginConfFile.pathAsString}")(p.getProperty("password"))
      proxy          <- IOResult.attempt(s"Getting proxy for plugin download in ${pluginConfFile.pathAsString}") {
                          val res = p.getProperty("proxy_url", "")
                          if (res == "") None else Some(res)
                        }
      proxy_user     <- IOResult.attempt(s"Getting proxy for plugin download in ${pluginConfFile.pathAsString}") {
                          val res = p.getProperty("proxy_user", "")
                          if (res == "") None else Some(res)
                        }
      proxy_password <- IOResult.attempt(s"Getting proxy for plugin download in ${pluginConfFile.pathAsString}") {
                          val res = p.getProperty("proxy_password", "")
                          if (res == "") None else Some(res)
                        }
    } yield {
      PluginSettings(url, userName, pass, proxy, proxy_user, proxy_password)
    }
  }

  def writePluginSettings(settings: PluginSettings): IOResult[Unit] = {
    IOResult.attempt({
      pluginConfFile.write(s"""[Rudder]
                              |url = ${settings.url}
                              |username = ${settings.username}
                              |password = ${settings.password}
                              |proxy_url = ${settings.proxyUrl.getOrElse("")}
                              |proxy_user = ${settings.proxyUser.getOrElse("")}
                              |proxy_password = ${settings.proxyPassword.getOrElse("")}
                              |""".stripMargin)
    })

  }
}
