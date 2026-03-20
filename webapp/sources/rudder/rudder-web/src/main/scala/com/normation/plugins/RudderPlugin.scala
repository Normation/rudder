/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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

import bootstrap.liftweb.ClassPathResource
import bootstrap.liftweb.ConfigResource
import bootstrap.liftweb.FileSystemResource
import bootstrap.liftweb.RudderProperties
import com.normation.errors.IOResult
import com.normation.plugins.*
import com.normation.plugins.cli.RudderPackagePlugin
import com.normation.plugins.cli.RudderPackagePlugin.LicenseInfo.*
import com.normation.plugins.cli.RudderPackageService
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.rest.EndpointSchema
import com.normation.rudder.rest.data.JsonPluginDetails
import com.normation.rudder.rest.data.JsonPluginInstallStatus
import com.normation.rudder.rest.data.JsonPluginLicense
import com.normation.rudder.rest.lift.LiftApiModuleProvider
import com.normation.utils.*
import com.normation.utils.PartType.*
import com.normation.utils.Separator.Dot
import com.normation.utils.Separator.Minus
import com.normation.utils.VersionPart.*
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.syntax.*
import net.liftweb.sitemap.Menu
import scala.xml.NodeSeq
import zio.Chunk

final case class RudderPluginVersion private (rudderAbi: Version, pluginVersion: Version) {

  override def toString: String = rudderAbi.toVersionStringNoEpoch + "-" + pluginVersion.toVersionString
}

object RudderPluginVersion {

  // a special value used to indicate a plugin version parsing error
  def PARSING_ERROR(badVersion: String): RudderPluginVersion = {
    val vr = Version(0, Numeric(0), After(Dot, Numeric(0)) :: After(Dot, Numeric(1)) :: Nil)
    val vp = Version(
      0,
      Numeric(0),
      After(Dot, Numeric(0)) :: After(Dot, Numeric(1)) :: After(Minus, Chars("ERROR-PARSING-VERSION: " + badVersion)) :: Nil
    )
    new RudderPluginVersion(vr, vp)
  }

  /*
   * That method will create a plugin version from a string.
   * It may fails if the pattern is not one known for rudder plugin, which is:
   * (A.B(.C)(post1))-(x.y(.z)(post2))
   * Where part between parenthesis are optionnal,
   * A,B,x,y,z are non-empty list of digits,
   * A.B(.C)(post1) are Rudder major.minor.patch version, with patch = 0 if not specified
   * x.y(.z) are plugin major.minor.patch version - if patch not specified, assumed to be 0,
   * postN is any non blank/control char list (ex: ~alpha1)
   *
   */
  def from(version: String): Option[RudderPluginVersion] = {
    // the structure of our plugin is not really version-parsing friendly.
    // We need to split on "-" which can also be a postfix separator. So
    // we assume that there is always a digit just after the rudder/plugin "-" separator. And that the
    // pattern "-digit" happens only one time.
    val pattern = """(\S+)-(\d\S+)?""".r.pattern
    val matcher = pattern.matcher(version)
    if (matcher.matches) {

      (ParseVersion.parse(matcher.group(1)), ParseVersion.parse(matcher.group(2))) match {
        case (Right(rv), Right(pv)) => Some(RudderPluginVersion(rv, pv))
        case (x, y)                 => None
      }

    } else {
      None
    }
  }

  // normalize rudderVersion and pluginVersion to have at least 3 digits
  def normalize(v: Version): Version = {
    v match {
      // at least 3 digits
      case ok @ Version(_, _, After(Dot, _: Numeric) :: After(Dot, _: Numeric) :: tail) => ok
      // only 2 - add one 0
      case Version(epoch, major, After(Dot, minor: Numeric) :: tail)                    =>
        Version(epoch, major, After(Dot, minor) :: After(Dot, Numeric(0)) :: tail)
      // only 1 - add two 0
      case Version(epoch, major, tail)                                                  =>
        Version(epoch, major, After(Dot, Numeric(0)) :: After(Dot, Numeric(0)) :: tail)
    }
  }

  def apply(rudderVersion: Version, pluginVersion: Version): RudderPluginVersion = {
    new RudderPluginVersion(normalize(rudderVersion), normalize(pluginVersion))
  }
}

final case class PluginName(value: String) {
  if (null == value || value.length == 0) {
    ApplicationLogger.error("A plugin name can not be null nor empty")
    throw new IllegalArgumentException("A plugin name can not be null nor empty")
  }
}

object RudderPluginDef {
  implicit val transformerJsonPluginDetails: Transformer[RudderPluginDef, JsonPluginDetails] = {
    Transformer
      .define[RudderPluginDef, JsonPluginDetails]
      .withFieldComputed(_.name, _.name.value)
      .withFieldComputed(_.shortName, _.shortName)
      .withFieldComputed(_.description, _.description.toString())
      .withFieldComputed(_.version, _.version.pluginVersion.toVersionStringNoEpoch)
      .withFieldComputedFrom(_.status.current)(
        _.status,
        {
          case RudderPluginLicenseStatus.EnabledNoLicense => JsonPluginInstallStatus.Enabled
          case _: RudderPluginLicenseStatus.EnabledWithLicense => JsonPluginInstallStatus.Enabled
          case _: RudderPluginLicenseStatus.Disabled           => JsonPluginInstallStatus.Disabled
        }
      )
      .withFieldComputedFrom(_.status.current)(
        _.statusMessage,
        {
          case RudderPluginLicenseStatus.Disabled(msg, _) => Some(msg)
          case _                                          => None
        }
      )
      .withFieldComputedFrom(_.status.current)(
        _.license,
        {
          case RudderPluginLicenseStatus.EnabledWithLicense(license) => Some(license.transformInto[JsonPluginLicense])
          case _                                                     => None
        }
      )
      .buildTransformer
  }
  implicit val transformerPlugin:            Transformer[RudderPluginDef, Plugin]            = {
    Transformer
      .define[RudderPluginDef, Plugin]
      .withFieldConst(_.pluginType, PluginType.Webapp)
      .withFieldComputed(_.name, _.shortName) // the RudderPluginDef name is used in plugins themselves
      .withFieldComputed(_.description, _.description.toString())
      .withFieldComputed(_.version, p => Some(p.version.pluginVersion.toVersionStringNoEpoch))
      .withFieldComputed(_.pluginVersion, _.version.pluginVersion)
      .withFieldComputed(_.abiVersion, _.version.rudderAbi)
      .withFieldComputedFrom(_.status.current)(
        _.status,
        {
          case RudderPluginLicenseStatus.EnabledNoLicense => PluginInstallStatus.Enabled
          case _: RudderPluginLicenseStatus.EnabledWithLicense => PluginInstallStatus.Enabled
          case _: RudderPluginLicenseStatus.Disabled           => PluginInstallStatus.Disabled
        }
      )
      .withFieldComputedFrom(_.status.current)(
        _.statusMessage,
        {
          case RudderPluginLicenseStatus.Disabled(msg, _) => Some(msg)
          case _                                          => None
        }
      )
      .withFieldComputedFrom(_.status.current)(
        _.license,
        {
          case RudderPluginLicenseStatus.EnabledWithLicense(license) => Some(license)
          case _                                                     => None
        }
      )
      .withFieldComputed(
        _.errors,
        plugin => {
          plugin.status.current match {
            case RudderPluginLicenseStatus.EnabledNoLicense            => List.empty
            case RudderPluginLicenseStatus.EnabledWithLicense(license) =>
              PluginError
                .fromRudderLicensedPlugin(plugin.version.pluginVersion, AbiVersion(plugin.version.rudderAbi), license)

            case RudderPluginLicenseStatus.Disabled(reason, details) =>
              PluginError
                .fromRudderDisabledPlugin(plugin.version.pluginVersion, AbiVersion(plugin.version.rudderAbi), reason, details)
          }
        }
      )
      .buildTransformer
  }
}

/**
 * Definition of a rudder plugins
 */
trait RudderPluginDef {

  /**
   * The name of the plugin, used as the plugin
   * key for plugin resolution.
   * Two different plugin may have the same key
   * to allow overriding or redefinition.
   * The fully qualified name of the plugin definition
   * class will be used as an id (not that one).
   */
  def name: PluginName

  /**
   * Plugin short-name as a string. It can be used as an identifier
   * if all your plugins come from Rudder (and so plugin name and
   * plugin shortName are the same modulo "rudder-plugin-" prefix).
   */
  def shortName: String

  def displayName: String = name.value

  /**
   * The id of the plugin. It uniquely identify a
   * plugin for rudder.
   * The fully qualified name of the plugin if used
   * for that.
   */
  final lazy val id: String = this.getClass.getName

  /**
   * A description of the module, expected to be displayed to
   * end user.
   */
  def description: NodeSeq

  /**
   * Full (i.e with Rudder version) version of the plugin.
   * For example: 7.1.5-2.3.0 for a plugin in version 2.3.0 compile against rudder 7.1.5.
   *
   * It is composed of rudderAbi version: this is the version of rudder used to build the plugin
   * (ie the 7.1.5 part in version example)
   * And of plugin own version (ie the 2.3.0 part in version example).
   */
  def version: RudderPluginVersion

  /**
   * Additional information about the version. I.E : "technical preview"
   */
  def versionInfo: Option[String]

  /*
   * Information about the plugin activation status
   * and license information.
   * In implementation, by default use
   * com.normation.plugins.AlwaysEnabledPluginStatus
   */
  def status: PluginStatus

  /*
   * If the plugin contributes APIs, they must be declared here.
   */
  def apis: Option[LiftApiModuleProvider[? <: EndpointSchema]] = None

  /**
   * The init method of the plugin. It will be called
   * at each at start of the application, so do not put
   * one-time-ever init on here.
   *
   * On the other hand, snippet registration
   * and the like should go here.
   */
  def init: Unit

  /**
   * Provide a sitemap mutator to add that
   * plugin entries in the menu
   */
  def updateSiteMap(menus: List[Menu]): List[Menu] = menus

  def basePackage: String

  /**
   * A list of config files to get properties from.
   * If some properties are defined in different config files,
   * the will be overridden (the last in the sequence win).
   */
  def configFiles: Seq[ConfigResource]

  /**
   * Build the map of config properties.
   */
  lazy val config: Config = {
    val configs = configFiles.map {
      case ClassPathResource(name)  => ConfigFactory.load(name)
      case FileSystemResource(file) => ConfigFactory.load(ConfigFactory.parseFile(file))
    }

    if (configs.isEmpty) {
      RudderProperties.config
    } else {
      configs.reduceLeft[Config] {
        case (config, newConfig) =>
          newConfig.withFallback(config)
      }
    }
  }

}

/*
 * The plugin modules: it provides a pluginDef.
 * Given the plombing, it must:
 * - be an object
 * - be in package bootstrap.rudder.plugin
 */
trait RudderPluginModule {
  def pluginDef: RudderPluginDef
}

/**
  * Implementation of service to manage plugin installed on the system.
  * Uses information from registered plugin joined with rudder package plugins to get plugin details
  */
class PluginsServiceImpl(
    rudderPackageService: RudderPackageService,
    pluginDefs:           => Map[PluginName, RudderPluginDef],
    rudderFullVersion:    String
) extends PluginService {
  override def updateIndex(): IOResult[Option[RudderPackageService.PluginSettingsError]] = {
    rudderPackageService.update()
  }

  override def list(): IOResult[Chunk[Plugin]] = {
    rudderPackageService
      .listAllPlugins()
      .map(_.map(mergePluginDef(_)))
  }

  override def install(plugins: Chunk[PluginId]): IOResult[Unit] = {
    // install all plugins, rudder package already handles installed ones
    rudderPackageService.installPlugins(plugins.map(_.transformInto[String]))
  }

  override def remove(plugins: Chunk[PluginId]): IOResult[Unit] = {
    rudderPackageService.removePlugins(plugins.map(_.transformInto[String]))
  }

  override def updateStatus(status: PluginInstallStatus, plugins: Chunk[PluginId]): IOResult[Unit] = {
    rudderPackageService.changePluginInstallStatus(status, plugins.map(_.transformInto[String]))
  }

  private def unknownLicensee = Licensee("unknown")

  // Licensee can be the first value from licensed plugins, as it is supposed to be global
  // When there are no licenced plugin, for display purpose we fallback to "unknown"
  implicit def licensee: Licensee = {
    pluginDefs.values
      .flatMap(_.transformInto[JsonPluginDetails].license)
      .headOption
      .map(l => Licensee(l.licensee))
      .getOrElse(unknownLicensee)
  }

  private object defaultValues {
    implicit val softwareId: SoftwareId = SoftwareId("")
    implicit val minVersion: MinVersion = MinVersion("0.0.0-0.0.0")
    implicit val maxVersion: MaxVersion = MaxVersion("99.99.0-99.99.0")
    implicit val maxNodes:   MaxNodes   = MaxNodes(None)
  }

  private def mergePluginDef(p: RudderPackagePlugin) = {
    implicit val rudderVersion: String = rudderFullVersion
    pluginDefs
      .get(PluginName("rudder-plugin-" + p.name)) // rudder package name does not have the prefix used in names
      .flatMap(pluginDef => {
        val details = pluginDef.transformInto[JsonPluginDetails]
        implicit val statusDisabledReason: StatusDisabledReason = StatusDisabledReason(pluginDef.status.current match {
          case RudderPluginLicenseStatus.Disabled(reason, _) => Some(reason)
          case _                                             => None
        })
        implicit val abiVersion:           AbiVersion           = AbiVersion(pluginDef.version.rudderAbi)
        implicit val pluginVersion:        PluginVersion        =
          PluginVersion(pluginDef.version.pluginVersion)

        // plugin listed from rudder package but with no license information :
        // - we can parse version, or else return one that is different
        details.license.map(license => {
          implicit val softwareId: SoftwareId = SoftwareId(license.softwareId)
          implicit val minVersion: MinVersion = MinVersion(license.minVersion)
          implicit val maxVersion: MaxVersion = MaxVersion(license.maxVersion)

          p.transformInto[Plugin]
        })
      })
      .getOrElse {
        // default implicits
        import defaultValues.*

        // we need to attempt to parse versions from latest available version
        val bothVersions   = p.latestVersion.flatMap(RudderPluginVersion.from)
        def defaultVersion = Version(0, PartType.Numeric(0), List.empty)
        implicit val abiVersion:           AbiVersion           =
          AbiVersion(bothVersions.map(_.rudderAbi).getOrElse(defaultVersion))
        implicit val pluginVersion:        PluginVersion        =
          PluginVersion(bothVersions.map(_.pluginVersion).getOrElse(defaultVersion))
        implicit val statusDisabledReason: StatusDisabledReason = StatusDisabledReason(None)

        p.transformInto[Plugin]
      }
  }
}
