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
import com.normation.errors.*
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.hooks.Cmd
import com.normation.rudder.hooks.RunNuCommand
import com.normation.utils.DateFormaterService
import com.normation.utils.Version
import enumeratum.Enum
import enumeratum.EnumEntry
import enumeratum.EnumEntry.*
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.syntax.*
import java.time.ZonedDateTime
import java.util.Properties
import org.joda.time.DateTime
import zio.*
import zio.json.*
import zio.syntax.*

case class PluginSettings(
    url:           Option[String],
    username:      Option[String],
    password:      Option[String],
    proxyUrl:      Option[String],
    proxyUser:     Option[String],
    proxyPassword: Option[String]
) {
  def isDefined: Boolean = {
    // Also, the special case : username="username" is empty
    val hasDefaultUser = username.contains("username")
    !isEmpty && !hasDefaultUser
  }

  // Strings are in fact non-empty strings
  private def isEmpty = url.isEmpty &&
    username.isEmpty &&
    password.isEmpty &&
    proxyUrl.isEmpty &&
    proxyUser.isEmpty &&
    proxyPassword.isEmpty
}

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

trait PluginSystemStatus  {
  def value: String
}
object PluginSystemStatus {
  case object Enabled  extends PluginSystemStatus { override val value: String = "enabled"  }
  case object Disabled extends PluginSystemStatus { override val value: String = "disabled" }
}

final case class JsonPluginDetails(
    id:            String,
    name:          String,
    shortName:     String,
    description:   String,
    version:       String,
    status:        PluginSystemStatus,
    statusMessage: Option[String],
    license:       Option[PluginLicenseInfo]
)
object JsonPluginDetails  {
  implicit val encoderPluginSystemStatusRest: JsonEncoder[PluginSystemStatus] = JsonEncoder[String].contramap(_.value)
  implicit val encoderPluginDetails:          JsonEncoder[JsonPluginDetails]  = DeriveJsonEncoder.gen
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
  implicit val encoder: JsonEncoder[PluginLicenseInfo] = DeriveJsonEncoder.gen
}

trait PluginSettingsService {
  def checkIsSetup():       IOResult[Boolean]
  def readPluginSettings(): IOResult[PluginSettings]
  def writePluginSettings(settings: PluginSettings): IOResult[Unit]
}

class FilePluginSettingsService(pluginConfFile: File, readSetupDone: IOResult[Boolean], writeSetupDone: Boolean => IOResult[Unit])
    extends PluginSettingsService {

  /**
    * Watch the rudder_setup_done setting to see if the plugin settings has been setup.
    * It has the side effect of updating the `rudder_setup_done` setting.
    *
    * @return the boolean with the semantics of :
    *  rudder_setup_done && !(is_setting_default || is_setting_empty)
    * and false when the plugin settings are not set, and setup is not done
    */
  def checkIsSetup(): IOResult[Boolean] = {
    readSetupDone
      .flatMap(isSetupDone => {
        if (isSetupDone) {
          true.succeed
        } else {
          // we may need to update setup_done if settings are defined
          readPluginSettings().map(_.isDefined).flatMap {
            case true  =>
              ApplicationLoggerPure.info(
                s"Read plugin settings properties file ${pluginConfFile.pathAsString} with a defined configuration, rudder_setup_done setting is marked as `true`. Go to Rudder Setup page to change the account credentials."
              ) *> writeSetupDone(true).as(true)
            case false =>
              // the plugin settings are not set, setup is not done
              false.succeed
          }
        }
      })
      .tapError(err => ApplicationLoggerPure.error(s"Could not get setting `rudder_setup_done` : ${err.fullMsg}"))
  }

  def readPluginSettings(): IOResult[PluginSettings] = {

    val p = new Properties()
    for {
      _ <- IOResult.attempt(s"Reading properties from ${pluginConfFile.pathAsString}")(p.load(pluginConfFile.newInputStream))

      url            <- IOResult.attempt(s"Getting plugin repository url in ${pluginConfFile.pathAsString}") {
                          val res = p.getProperty("url", "")
                          if (res == "") None else Some(res)
                        }
      userName       <-
        IOResult.attempt(s"Getting user name for plugin download in ${pluginConfFile.pathAsString}") {
          val res = p.getProperty("username", "")
          if (res == "") None else Some(res)
        }
      pass           <-
        IOResult.attempt(s"Getting password for plugin download in ${pluginConfFile.pathAsString}") {
          val res = p.getProperty("password", "")
          if (res == "") None else Some(res)
        }
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

  def writePluginSettings(update: PluginSettings): IOResult[Unit] = {
    for {
      base <- readPluginSettings()
      _    <- IOResult.attempt({
                val settings = base.copy(
                  url = update.url orElse base.url,
                  username = update.username orElse base.username,
                  password = update.password orElse base.password,
                  proxyUrl = update.proxyUrl orElse base.proxyUrl,
                  proxyUser = update.proxyUser orElse base.proxyUser,
                  proxyPassword = update.proxyPassword orElse base.proxyPassword
                )
                pluginConfFile.write(s"""[Rudder]
                                     |url = ${settings.url.getOrElse("")}
                                     |username = ${settings.username.getOrElse("")}
                                     |password = ${settings.password.getOrElse("")}
                                     |proxy_url = ${settings.proxyUrl.getOrElse("")}
                                     |proxy_user = ${settings.proxyUser.getOrElse("")}
                                     |proxy_password = ${settings.proxyPassword.getOrElse("")}
                                     |""".stripMargin)
              })
    } yield {}
  }
}

sealed trait PluginType extends Lowercase
object PluginType       extends Enum[PluginType] {
  case object Webapp      extends PluginType
  case object Integration extends PluginType

  override def values: IndexedSeq[PluginType] = findValues
}

sealed trait JsonPluginSystemStatus extends Lowercase
object JsonPluginSystemStatus       extends Enum[JsonPluginSystemStatus] {
  case object Enabled     extends JsonPluginSystemStatus
  case object Disabled    extends JsonPluginSystemStatus
  case object Uninstalled extends JsonPluginSystemStatus

  override def values: IndexedSeq[JsonPluginSystemStatus] = findValues
}

/**
 * An error enumeration to identify plugin management errors and the associated messages
 */
sealed trait PluginManagementError {
  def kind:       PluginManagementError.Kind
  def displayMsg: String
}
object PluginManagementError       {
  sealed trait Kind extends EnumEntry with Dotcase
  object Kind       extends Enum[Kind] {
    case object LicenseExpiredError        extends Kind
    case object LicenseNearExpirationError extends Kind
    case object AbiVersionError            extends Kind
    override def values: IndexedSeq[Kind] = findValues
  }

  /**
    * Sum type for license expiration error with disjoined cases
    */
  sealed trait LicenseExpirationError    extends PluginManagementError
  case object LicenseExpiredError        extends LicenseExpirationError {
    override def kind:       Kind.LicenseExpiredError.type = Kind.LicenseExpiredError
    override def displayMsg: String                        = "Plugin license error require your attention"
  }
  case object LicenseNearExpirationError extends LicenseExpirationError {
    override def kind:       Kind.LicenseNearExpirationError.type = Kind.LicenseNearExpirationError
    override def displayMsg: String                               = "Plugin license near expiration"
  }

  final case class RudderAbiVersionError(rudderFullVersion: String) extends PluginManagementError {
    override def kind:       Kind.AbiVersionError.type = Kind.AbiVersionError
    override def displayMsg: String                    =
      s"This plugin was not built for current Rudder ABI version ${rudderFullVersion}. You should update it to avoid code incompatibilities."
  }

  def fromRudderPackagePlugin(
      plugin: RudderPackagePlugin
  )(implicit rudderFullVersion: String, abiVersion: Version): List[PluginManagementError] = {
    List(
      validateAbiVersion(rudderFullVersion, abiVersion),
      plugin.license.flatMap(l => validateLicenseExpiration(l.endDate))
    ).flatten
  }

  private def validateAbiVersion(rudderFullVersion: String, abiVersion: Version): Option[RudderAbiVersionError] = {
    if (rudderFullVersion != abiVersion.toVersionString)
      Some(RudderAbiVersionError(rudderFullVersion))
    else
      None
  }

  /**
   * license near expiration : 1 month before now.
   */
  private def validateLicenseExpiration(endDate: DateTime): Option[LicenseExpirationError] = {
    if (endDate.isBeforeNow()) {
      Some(LicenseExpiredError)
    } else if (endDate.minusMonths(1).isBeforeNow())
      Some(LicenseNearExpirationError)
    else
      None
  }

}

final case class JsonPluginSystemDetails(
    id:            String,
    name:          String,
    description:   String,
    version:       String,
    status:        JsonPluginSystemStatus,
    statusMessage: Option[String],
    abiVersion:    Version,
    pluginType:    PluginType,
    errors:        List[JsonPluginManagementError],
    license:       Option[PluginLicenseInfo]
)

final case class JsonPluginManagementError(
    error:   String,
    message: String
)
object JsonPluginManagementError {
  implicit val transformer: Transformer[PluginManagementError, JsonPluginManagementError] = {
    Transformer
      .define[PluginManagementError, JsonPluginManagementError]
      .withFieldComputed(_.error, _.kind.entryName)
      .withFieldComputed(_.message, _.displayMsg)
      .buildTransformer
  }
}

object JsonPluginSystemDetails {
  implicit val encoderPluginSystemStatusRest: JsonEncoder[JsonPluginSystemStatus]    = JsonEncoder[String].contramap(_.entryName)
  implicit val encoderPluginType:             JsonEncoder[PluginType]                = JsonEncoder[String].contramap(_.entryName)
  implicit val encoderPluginManagementError:  JsonEncoder[JsonPluginManagementError] =
    DeriveJsonEncoder.gen[JsonPluginManagementError]
  implicit val encoderVersion:                JsonEncoder[Version]                   = JsonEncoder[String].contramap(_.toVersionString)
  implicit val encoderPluginSystemDetails:    JsonEncoder[JsonPluginSystemDetails]   = DeriveJsonEncoder.gen[JsonPluginSystemDetails]
}

@jsonMemberNames(SnakeCase)
final case class RudderPackagePlugin(
    name:          String,
    version:       Option[String], // version is only available when installed
    latestVersion: String,
    installed:     Boolean,
    enabled:       Boolean,
    webappPlugin:  Boolean,
    description:   String,
    license:       Option[RudderPackagePlugin.LicenseInfo]
)
object RudderPackagePlugin     {
  // types for passing implicits
  final case class Licensee(value: String)      extends AnyVal
  final case class SoftwareId(value: String)    extends AnyVal
  final case class MinVersion(value: String)    extends AnyVal
  final case class MaxVersion(value: String)    extends AnyVal
  final case class MaxNodes(value: Option[Int]) extends AnyVal

  // License representation is limited to these fields in rudder package
  @jsonMemberNames(SnakeCase)
  final case class LicenseInfo(
      startDate: DateTime,
      endDate:   DateTime
  )
  object LicenseInfo {
    import DateFormaterService.json.decoderDateTime
    implicit val decoder: JsonDecoder[LicenseInfo] = DeriveJsonDecoder.gen[LicenseInfo]
    implicit def transformer(implicit
        licensee:   Licensee,
        softwareId: SoftwareId,
        minVersion: MinVersion,
        maxVersion: MaxVersion,
        maxNodes:   MaxNodes
    ): Transformer[LicenseInfo, PluginLicenseInfo] = {
      Transformer
        .define[LicenseInfo, PluginLicenseInfo]
        .withFieldConst(_.licensee, licensee.value)
        .withFieldConst(_.softwareId, softwareId.value)
        .withFieldConst(_.minVersion, minVersion.value)
        .withFieldConst(_.maxVersion, maxVersion.value)
        .withFieldConst(_.maxNodes, maxNodes.value)
        .withFieldConst(_.others, Map.empty[String, String])
        .buildTransformer
    }
  }

  implicit val decoder: JsonDecoder[RudderPackagePlugin] = DeriveJsonDecoder.gen[RudderPackagePlugin]

  /**
    * When joining plugin information from rudder package and global information from registered plugins,
    * we can return needed plugin details
    */
  implicit def transformer(implicit
      rudderFullVersion: String,
      abiVersion:        Version,
      transformLicense:  Transformer[LicenseInfo, PluginLicenseInfo]
  ): Transformer[RudderPackagePlugin, JsonPluginSystemDetails] = {
    val _ = transformLicense // variable is used below
    Transformer
      .define[RudderPackagePlugin, JsonPluginSystemDetails]
      .withFieldComputed(_.id, _.name)
      .withFieldComputed(
        _.status,
        p => {
          (p.installed, p.enabled) match {
            case (true, true)  => JsonPluginSystemStatus.Enabled
            case (true, false) => JsonPluginSystemStatus.Disabled
            case (false, _)    => JsonPluginSystemStatus.Uninstalled
          }
        }
      )
      .withFieldComputed(_.version, l => l.version.getOrElse(l.latestVersion))
      .withFieldConst(_.abiVersion, abiVersion) // field is computed upstream
      .withFieldComputed(_.pluginType, p => if (p.webappPlugin) PluginType.Webapp else PluginType.Integration)
      .withFieldConst(_.statusMessage, None)
      .withFieldComputed(
        _.errors,
        PluginManagementError.fromRudderPackagePlugin(_).map(_.transformInto[JsonPluginManagementError])
      )
      .buildTransformer
  }
}

/**
  * A service that encapsulate rudder package operations and its representation of plugins.
  */
trait RudderPackageService {
  def listAllPlugins(): IOResult[Chunk[RudderPackagePlugin]]

  def installPlugins(plugins: Chunk[String]): IOResult[Unit]

  def removePlugins(plugins:           Chunk[String]): IOResult[Unit]
  def changePluginSystemStatus(status: PluginSystemStatus, plugins: Chunk[String]): IOResult[Unit]
}

/*
 * We assume that command in config is in format: `/path/to/main/command args1 args2 etc`
 */
class RudderPackageCmdService(configCmdLine: String) extends RudderPackageService {

  val configCmdRes = configCmdLine.split(" ").toList match {
    case Nil       => Left(Unexpected(s"Invalid command for rudder package from configuration: '${configCmdLine}'"))
    case h :: tail => Right((h, tail))
  }

  override def listAllPlugins(): IOResult[Chunk[RudderPackagePlugin]] = {
    for {
      configCmd   <- configCmdRes.toIO
      cmd          = Cmd(configCmd._1, configCmd._2 ::: "list" :: "--all" :: "--format=json" :: Nil, Map.empty, None)
      packagesCmd <- RunNuCommand.run(cmd)
      result      <- packagesCmd.await
      _           <- ZIO.when(result.code != 0) {
                       Inconsistency(
                         s"An error occurred while listing packages with '${cmd.display}':\n code: ${result.code}\n stderr: ${result.stderr}\n stdout: ${result.stdout}"
                       ).fail
                     }

      plugins <- result.stdout.fromJson[Chunk[RudderPackagePlugin]].toIO.chainError("Could not parse plugins definition")
    } yield {
      plugins
    }
  }

  override def installPlugins(plugins: Chunk[String]): IOResult[Unit] = ???

  override def removePlugins(plugins: Chunk[String]): IOResult[Unit] = ???

  override def changePluginSystemStatus(status: PluginSystemStatus, plugins: Chunk[String]): IOResult[Unit] = ???

}

/**
  * A service to manage plugins, it is an abstraction over system administration of plugins
  */
trait PluginSystemService {

  def list(): IOResult[Chunk[JsonPluginSystemDetails]]

  def install(plugins:     Chunk[String]): IOResult[Unit]
  def remove(plugins:      Chunk[String]): IOResult[Unit]
  def updateStatus(status: PluginSystemStatus, plugins: Chunk[String]): IOResult[Unit]

}
