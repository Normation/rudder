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
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.rest.EndpointSchema
import com.normation.rudder.rest.lift.LiftApiModuleProvider
import com.normation.utils._
import com.normation.utils.PartType._
import com.normation.utils.Separator.Dot
import com.normation.utils.Separator.Minus
import com.normation.utils.VersionPart._
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import net.liftweb.sitemap.Menu
import scala.xml.NodeSeq

final case class PluginVersion private (rudderAbi: Version, pluginVersion: Version) {

  override def toString = rudderAbi.toVersionStringNoEpoch + "-" + pluginVersion.toVersionString
}

object PluginVersion {

  // a special value used to indicate a plugin version parsing error
  def PARSING_ERROR(badVersion: String) = {
    val vr = Version(0, Numeric(0), After(Dot, Numeric(0)) :: After(Dot, Numeric(1)) :: Nil)
    val vp = Version(
      0,
      Numeric(0),
      After(Dot, Numeric(0)) :: After(Dot, Numeric(1)) :: After(Minus, Chars("ERROR-PARSING-VERSION: " + badVersion)) :: Nil
    )
    new PluginVersion(vr, vp)
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
  def from(version: String): Option[PluginVersion] = {
    // the structure of our plugin is not really version-parsing friendly.
    // We need to split on "-" which can also be a postfix separator. So
    // we assume that there is always a digit just after the rudder/plugin "-" separator. And that the
    // pattern "-digit" happens only one time.
    val pattern = """(\S+)-(\d\S+)?""".r.pattern
    val matcher = pattern.matcher(version)
    if (matcher.matches) {

      (ParseVersion.parse(matcher.group(1)), ParseVersion.parse(matcher.group(2))) match {
        case (Right(rv), Right(pv)) => Some(PluginVersion(rv, pv))
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

  def apply(rudderVersion: Version, pluginVersion: Version) = {
    new PluginVersion(normalize(rudderVersion), normalize(pluginVersion))
  }
}

final case class PluginName(value: String) {
  if (null == value || value.length == 0) {
    ApplicationLogger.error("A plugin name can not be null nor empty")
    throw new IllegalArgumentException("A plugin name can not be null nor empty")
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
  final lazy val id = this.getClass.getName

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
  def version: PluginVersion

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
  def apis: Option[LiftApiModuleProvider[_ <: EndpointSchema]] = None

  /*
   * A visual representation of status/license information, with a
   * default (overridable) rendering
   */
  def statusInformation(): NodeSeq = {
    import net.liftweb.util.Helpers._

    val info = (
      <div class="license-card">
        <div id="license-information" class="license-info">
          <h4><span>License information</span> <i class="license-icon ion ion-ribbon-b"></i></h4>
          <div class="license-information-details"></div>
        </div>
      </div>
    )

    def details(i: PluginLicenseInfo) = {
      <table class="table-license">
        <tr>
          <td>Licensee:</td> <td>{i.licensee}</td>
        </tr>
        <tr>
          <td>Supported version:</td> <td>from {i.minVersion} to {i.maxVersion}</td>
        </tr>
        <tr>
          <td>Validity period:</td> <td>from {i.startDate.toString("YYYY-MM-dd")} to {i.endDate.toString("YYYY-MM-dd")}</td>
        </tr>
        <tr>
          <td>Allowed number of nodes:</td> <td>{i.maxNodes.map(_.toString).getOrElse("Unlimited")}</td>
        </tr>
        {
        if (i.others.isEmpty) {
          NodeSeq.Empty
        } else {
          <tr>
              <td>Other properties for that license:</td>
              <td>
                <ul>
                  {i.others.toList.sortBy(_._1).map { case (k, v) => <li>{s"${k}: ${v}"}</li> }}
                </ul>
              </td>
            </tr>
        }
      }
      </table>
    }

    status.current match {
      case PluginStatusInfo.EnabledNoLicense =>
        NodeSeq.Empty

      case PluginStatusInfo.Disabled(msg, optDetails) =>
        (".license-card [class+]" #> "critical" andThen
        ".license-information-details" #> (
          <div>{
            optDetails match {
              case None    =>
                <p><i class="txt-critical fa fa-exclamation-triangle"></i>It was impossible to read information about the license.</p>
              case Some(i) => details(i)
            }
          }
            <p class="txt-critical">{msg}</p>
          </div>
        ))(info)

      case PluginStatusInfo.EnabledWithLicense(i) =>
        val (callout, warningTxt) = if (i.endDate.minusMonths(1).isBeforeNow) {
          ("warning", <p class="txt-warning"><i class="fa fa-exclamation-triangle"></i>Plugin license expires in a few days.</p>)
        } else {
          ("", NodeSeq.Empty)
        }

        (".license-card [class+]" #> callout andThen
        ".license-information-details" #> (
          <p>This binary version of this plugin is suject to license with the following information:</p>
           <div>
             {details(i)}
             {warningTxt}
           </div>
        ))(info)
    }
  }

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
   * the will be overriden (the last in the sequence win).
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
