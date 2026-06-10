/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

package com.normation.plugins.cli

import com.normation.errors.*
import com.normation.plugins.*
import com.normation.plugins.cli.RudderPackagePlugin.LicenseInfo
import com.normation.rudder.hooks.Cmd
import com.normation.rudder.hooks.CmdResult
import com.normation.rudder.hooks.RunNuCommand
import com.normation.utils.DateFormaterService
import io.scalaland.chimney.Transformer
import java.time.ZonedDateTime
import zio.*
import zio.json.*

@jsonMemberNames(SnakeCase)
final case class RudderPackagePlugin(
    name:            String,
    version:         Option[String],
    latestVersion:   Option[String],
    installed:       Boolean,
    enabled:         Boolean,
    webappPlugin:    Boolean,
    requiresLicense: Boolean,
    description:     String,
    license:         Option[LicenseInfo]
)
object RudderPackagePlugin {

  // License representation is limited to these fields in rudder package
  @jsonMemberNames(SnakeCase)
  final case class LicenseInfo(
      startDate: ZonedDateTime,
      endDate:   ZonedDateTime
  )
  object LicenseInfo {
    import DateFormaterService.json.decoderZonedDateTime
    implicit val decoder: JsonDecoder[LicenseInfo] = DeriveJsonDecoder.gen[LicenseInfo]

    /**
      * Plugin license information is passed down from Webapp context
      * (registered plugins), as it is not returned by rudder-package
      */
    implicit def transformer(implicit
        licensee:   Licensee,
        softwareId: SoftwareId,
        minVersion: MinVersion,
        maxVersion: MaxVersion
    ): Transformer[LicenseInfo, PluginLicense] = {
      Transformer
        .define[LicenseInfo, PluginLicense]
        .withFieldConst(_.licensee, licensee)
        .withFieldConst(_.softwareId, softwareId)
        .withFieldConst(_.minVersion, minVersion)
        .withFieldConst(_.maxVersion, maxVersion)
        .withFieldConst(_.maxNodes, MaxNodes.unlimited)
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
      rudderFullVersion:    String,
      abiVersion:           AbiVersion,
      pluginVersion:        PluginVersion,
      statusDisabledReason: StatusDisabledReason,
      transformLicense:     Transformer[LicenseInfo, PluginLicense]
  ): Transformer[RudderPackagePlugin, Plugin] = {
    val _ = transformLicense // variable is used below
    Transformer
      .define[RudderPackagePlugin, Plugin]
      .withFieldComputed(_.id, p => PluginId(p.name))
      .withFieldComputed(
        _.status,
        p => {
          PluginInstallStatus.from(
            if (p.webappPlugin) PluginType.Webapp else PluginType.Integration,
            installed = p.installed,
            enabled = p.enabled,
            statusDisabledReason = statusDisabledReason
          )
        }
      )
      .withFieldComputed(_.version, l => l.version.orElse(l.latestVersion)) // version : only when installed
      .withFieldConst(_.abiVersion, abiVersion.value)                       // field is computed upstream
      .withFieldConst(_.pluginVersion, pluginVersion.value)                 // field is computed upstream
      .withFieldComputed(_.pluginType, p => if (p.webappPlugin) PluginType.Webapp else PluginType.Integration)
      .withFieldConst(_.statusMessage, statusDisabledReason.value)
      .withFieldComputed(
        _.errors,
        PluginError.fromRudderPackagePlugin(_)
      )
      .buildTransformer
  }
}

trait RudderPackageService {
  import RudderPackageService.*

  def update(): IOResult[Option[PluginSettingsError]]

  def listAllPlugins(): IOResult[Chunk[RudderPackagePlugin]]

  def installPlugins(plugins: Chunk[String]): IOResult[Unit]

  def removePlugins(plugins:            Chunk[String]): IOResult[Unit]
  def changePluginInstallStatus(status: PluginInstallStatus, plugins: Chunk[String]): IOResult[Unit]
}

object RudderPackageService {

  // PluginSettings configuration could cause errors with known codes and stderr in rudder-package
  sealed abstract class PluginSettingsError extends RudderError
  object PluginSettingsError {
    final case class InvalidCredentials(override val msg: String) extends PluginSettingsError
    final case class Unauthorized(override val msg: String)       extends PluginSettingsError

    private val sanitizeCmdResult = (res: CmdResult) => res.strip

    /**
      * Maps known errors codes from rudder package and adapt the error message to have no color
      */
    def fromResult(cmdResult: CmdResult): PureResult[Option[PluginSettingsError]] = {
      val result = sanitizeCmdResult(cmdResult)
      result.code match {
        case 0 | 15 => Right(None) // 15: SIGTERM when services are restarted
        case 2      => Right(Some(PluginSettingsError.InvalidCredentials(result.stderr)))
        case 3      => Right(Some(PluginSettingsError.Unauthorized(result.stderr)))
        case _      => Left(Inconsistency(result.debugString()))
      }
    }
  }

}

/*
 * We assume that command in config is in format: `/path/to/main/command args1 args2 etc`
 */
class RudderPackageCmdService(configCmdLine: String) extends RudderPackageService {

  val configCmdRes = configCmdLine.split(" ").toList match {
    case Nil       => Left(Unexpected(s"Invalid command for rudder package from configuration: '${configCmdLine}'"))
    case h :: tail => Right((h, tail))
  }

  override def update(): IOResult[Option[RudderPackageService.PluginSettingsError]] = {
    // In case of error we need to check the result
    for {
      res          <- runCmd("update" :: Nil)
      (cmd, result) = res
      err          <-
        RudderPackageService.PluginSettingsError
          .fromResult(result)
          .toIO
          .chainError(s"An error occurred while updating plugins list with '${cmd.display}'")
    } yield {
      err
    }
  }

  override def listAllPlugins(): IOResult[Chunk[RudderPackagePlugin]] = {
    for {
      result  <- runCmdOrFail("list" :: "--all" :: "--format=json" :: Nil)(
                   s"An error occurred while listing packages"
                 )
      plugins <- result.stdout
                   .fromJson[Chunk[RudderPackagePlugin]]
                   .toIO
                   .chainError("Could not parse plugins definition")
    } yield {
      plugins
    }
  }

  override def installPlugins(plugins: Chunk[String]): IOResult[Unit] = {
    runCmdOrFail("install" :: plugins.toList)(
      s"An error occurred while installing plugins"
    ).unit
  }

  override def removePlugins(plugins: Chunk[String]): IOResult[Unit] = {
    runCmdOrFail("remove" :: plugins.toList)(
      s"An error occurred while removing plugins"
    ).unit
  }

  override def changePluginInstallStatus(status: PluginInstallStatus, plugins: Chunk[String]): IOResult[Unit] = {
    import com.normation.plugins.PluginInstallStatus.*
    val action = status match {
      case Enabled     => "enable"
      case Disabled    => "disable"
      case Uninstalled => "uninstall"
    }
    runCmdOrFail(action :: plugins.toList)(
      s"An error occurred while doing command to ${action} plugins"
    ).unit
  }

  private def runCmd(params: List[String]):                         IOResult[(Cmd, CmdResult)] = {
    for {
      configCmd   <- configCmdRes.toIO
      cmd          = Cmd(configCmd._1, configCmd._2 ::: params, Map("NO_COLOR" -> "1"), None)
      packagesCmd <- RunNuCommand.run(cmd)
      result      <- packagesCmd.await
    } yield {
      (cmd, result)
    }
  }
  private def runCmdOrFail(params: List[String])(errorMsg: String): IOResult[CmdResult]        = {
    runCmd(params).reject {
      case (cmd, result) if result.code != 0 => Inconsistency(s"${errorMsg} with '${cmd.display}': ${result.debugString()}")
    }.map(_._2)
  }
}
