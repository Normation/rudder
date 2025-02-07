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
import cats.Semigroup
import com.normation.errors.*
import com.normation.plugins.PluginSystemStatus.Disabled
import com.normation.plugins.PluginSystemStatus.Enabled
import com.normation.plugins.RudderPackageService.*
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.hooks.Cmd
import com.normation.rudder.hooks.CmdResult
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
  import GlobalPluginsLicense.EndDateImplicits.minZonedDateTime
  implicit val encoderJsonPluginsDetails: JsonEncoder[JsonPluginsDetails] = DeriveJsonEncoder.gen

  def buildDetails(plugins: Seq[JsonPluginDetails]): JsonPluginsDetails = {
    val global = GlobalPluginsLicense.from[ZonedDateTime](plugins.flatMap(_.license))
    JsonPluginsDetails(global.map(JsonGlobalPluginLimits.fromGlobalLicense), plugins)
  }
}

/**
  * Base structure for an aggregated view of licenses for multiple plugins.
  * In this file implementations need different types for the endDate field, and need them to be serializable.
  * 
  * We should pay attention to the JsonEncoder of this base structure : we want an encoder for it,
  * but it should dispatch the encoding to case class structures that are implementing the values.
  */
sealed abstract class GlobalPluginsLicense[EndDate](
    val licensees: Option[NonEmptyChunk[String]],
    // for now, min/max version is not used and is always 00-99
    val startDate: Option[ZonedDateTime],
    val endDate:   Option[EndDate],
    val maxNodes:  Option[Int]
) {
  import GlobalPluginsLicense.*

  // for efficiency : check equality and hash first before field comparison,
  // as it will mostly be the case because license information should be similar
  def combine(that: GlobalPluginsLicense[EndDate])(implicit endSemigroup: Semigroup[EndDate]) = {
    def combineOptField[A](toa: GlobalPluginsLicense[EndDate] => Option[A])(f: (A, A) => A): Option[A] = {
      implicit val s: Semigroup[A] = Semigroup.instance(f)
      Semigroup[Option[A]].combine(toa(this), toa(that))
    }
    if (this == that) this
    else {
      new GlobalPluginsLicense[EndDate](
        combineOptField(_.licensees)(_ ++ _),
        combineOptField(_.startDate)((x, y) => if (x.isAfter(y)) x else y),
        combineOptField(_.endDate)(endSemigroup.combine),
        combineOptField(_.maxNodes)(_.min(_))
      ) {}
    }
  }

  private[GlobalPluginsLicense] def sortDistinctLicensees: GlobalPluginsLicense[EndDate] = {
    withLicensees(licensees.map(_.sorted.distinct).flatMap(NonEmptyChunk.fromChunk))
  }

  protected def withLicensees(licensees: Option[NonEmptyChunk[String]]): GlobalPluginsLicense[EndDate] = {
    new GlobalPluginsLicense[EndDate](
      licensees,
      startDate,
      endDate,
      maxNodes
    ) {}
  }
}

object GlobalPluginsLicense {
  import DateFormaterService.JodaTimeToJava

  /**
    * Typeclass for proving that a type can be constructed from a Java ZonedDateTime.
    * This should be a functor to allow building wrapping datastructures but is limited to supported ones for now.
    */
  sealed private trait ToEndDate[T] {
    def fromZonedDateTime(date: ZonedDateTime): T
  }
  private object ToEndDate          {
    def apply[T](implicit ev: ToEndDate[T]) = ev

    implicit val id: ToEndDate[ZonedDateTime] = new ToEndDate[ZonedDateTime] {
      def fromZonedDateTime(date: ZonedDateTime): ZonedDateTime = date
    }

    implicit val dateCounts: ToEndDate[DateCounts] = new ToEndDate[DateCounts] {
      override def fromZonedDateTime(date: ZonedDateTime): DateCounts = DateCounts.one(date)
    }
  }

  final case class DateCount(date: ZonedDateTime, count: Int)
  // Dedicated structure for counts by date. Encoded as json list but using a Map for unicity when grouping by date (could be an opaque type)
  final case class DateCounts(value: Map[ZonedDateTime, DateCount]) {
    def values: Iterable[DateCount] = value.values
  }
  object DateCounts                                                 {
    // single date count is has a count of 1, upon aggregation counts will be added
    def one(date: ZonedDateTime): DateCounts = DateCounts(Map(date -> DateCount(date, 1)))

    implicit object semigroup extends Semigroup[DateCounts] {
      override def combine(a: DateCounts, b: DateCounts): DateCounts = {
        DateCounts((a.value.toList ::: b.value.toList).groupBy { case (date, _) => date }.map {
          case (k, v) => k -> DateCount(k, v.map { case (_, dc) => dc.count }.sum)
        }.toMap)
      }
    }
  }

  // Instances for aggregating plugin licenses : end date has specific combination logic needing public typeclass instances
  object EndDateImplicits {
    // this instance is specifically to take the mininum of dates, for end date
    implicit val minZonedDateTime: Semigroup[ZonedDateTime] = Semigroup.instance((x, y) => if (x.isBefore(y)) x else y)
  }

  def fromLicenseInfo[T: ToEndDate: Semigroup](info: PluginLicenseInfo): GlobalPluginsLicense[T] = {
    new GlobalPluginsLicense[T](
      Some(NonEmptyChunk(info.licensee)),
      Some(info.startDate.toJava),
      Some(ToEndDate[T].fromZonedDateTime(info.endDate.toJava)),
      info.maxNodes
    ) {}
  }

  def from[T: ToEndDate: Semigroup](licenses: Seq[PluginLicenseInfo]): Option[GlobalPluginsLicense[T]] = {
    NonEmptyChunk
      .fromIterableOption(licenses)
      .map(from(_))
      .flatMap(r => Option.when(r != empty)(r))
  }

  private def empty = new GlobalPluginsLicense[Unit](None, None, None, None) {}

  private def from[T: ToEndDate: Semigroup](licenses: NonEmptyChunk[PluginLicenseInfo]): GlobalPluginsLicense[T] = {
    licenses
      .reduceMapLeft(fromLicenseInfo(_)) { case (lim, lic) => lim.combine(fromLicenseInfo(lic)) }
      .sortDistinctLicensees
  }
}

/*
 * Global limit information about plugins (the most restrictive, with the minimum end date )
 */
final case class JsonGlobalPluginLimits(
    override val licensees: Option[NonEmptyChunk[String]],
    override val startDate: Option[ZonedDateTime],
    override val endDate:   Option[ZonedDateTime],
    override val maxNodes:  Option[Int]
) extends GlobalPluginsLicense[ZonedDateTime](licensees, startDate, endDate, maxNodes)

object JsonGlobalPluginLimits {
  import DateFormaterService.json.encoderZonedDateTime

  implicit val encoderGlobalPluginLimits: JsonEncoder[JsonGlobalPluginLimits] = DeriveJsonEncoder.gen

  // upcast the global licenses after aggregation
  def fromGlobalLicense(license: GlobalPluginsLicense[ZonedDateTime]): JsonGlobalPluginLimits = {
    import license.*
    JsonGlobalPluginLimits(licensees, startDate, endDate, maxNodes)
  }
}

sealed trait PluginSystemStatus {
  def value: String
}
object PluginSystemStatus       {
  case object Enabled  extends PluginSystemStatus { override val value: String = "enabled"  }
  case object Disabled extends PluginSystemStatus { override val value: String = "disabled" }

  implicit val transformerJson: Transformer[PluginSystemStatus, JsonPluginSystemStatus] =
    Transformer.derive[PluginSystemStatus, JsonPluginSystemStatus]
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
object JsonPluginDetails        {
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

  implicit val encoder: JsonEncoder[JsonPluginSystemStatus] = JsonEncoder[String].contramap(_.entryName)
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
    case object LicenseNeededError         extends Kind
    case object LicenseExpiredError        extends Kind
    case object LicenseNearExpirationError extends Kind
    case object AbiVersionError            extends Kind
    override def values: IndexedSeq[Kind] = findValues
  }

  case object LicenseNeededError extends PluginManagementError {
    override def kind:       Kind.LicenseNeededError.type = Kind.LicenseNeededError
    override def displayMsg: String                       = "A license is needed for the plugin"
  }

  /**
    * Sum type for license expiration error with case disjunction
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
  )(implicit rudderFullVersion: String, abiVersion: RudderPackagePlugin.AbiVersion): List[PluginManagementError] = {
    List(
      validateAbiVersion(rudderFullVersion, abiVersion),
      validateLicenseNeeded(plugin.requiresLicense, plugin.license),
      plugin.license.flatMap(l => validateLicenseExpiration(l.endDate))
    ).flatten
  }

  private def validateAbiVersion(
      rudderFullVersion: String,
      abiVersion:        RudderPackagePlugin.AbiVersion
  ): Option[RudderAbiVersionError] = {
    if (rudderFullVersion != abiVersion.value.toVersionString)
      Some(RudderAbiVersionError(rudderFullVersion))
    else
      None
  }

  private def validateLicenseNeeded(
      requiresLicense: Boolean,
      license:         Option[RudderPackagePlugin.LicenseInfo]
  ): Option[LicenseNeededError.type] = {
    if (requiresLicense && license.isEmpty)
      Some(LicenseNeededError)
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

case class PluginId(value: String) extends AnyVal
object PluginId           {
  implicit val decoder:     JsonDecoder[PluginId]         = JsonDecoder[String].mapOrFail(parse)
  implicit val encoder:     JsonEncoder[PluginId]         = JsonEncoder[String].contramap(_.value)
  implicit val transformer: Transformer[PluginId, String] = Transformer.derive[PluginId, String]

  private val pluginIdRegex = """^(\p{Alnum}[\p{Alnum}-_]*)$""".r

  /**
    * Ensure that plugin ID is alpha-num and hyphen
    */
  def parse(s: String): Either[String, PluginId] = {
    s match {
      case pluginIdRegex(_) => Right(PluginId(s))
      case _                => Left(s"Invalid plugin ID: '$s'. Plugin ID must be alphanumeric with hyphens.")
    }
  }
}

final case class JsonPluginsLicense(
    licensees: Option[NonEmptyChunk[String]],
    startDate: Option[ZonedDateTime],
    endDates:  Option[GlobalPluginsLicense.DateCounts],
    maxNodes:  Option[Int]
)
object JsonPluginsLicense {

  import DateFormaterService.JodaTimeToJava
  import GlobalPluginsLicense.*

  implicit val dateFieldEncoder: JsonFieldEncoder[ZonedDateTime] =
    JsonFieldEncoder[String].contramap(DateFormaterService.serializeZDT)

  implicit val dateCountEncoder:  JsonEncoder[DateCount]          = DeriveJsonEncoder.gen[DateCount]
  implicit val dateCountsEncoder: JsonEncoder[DateCounts]         = JsonEncoder[Chunk[DateCount]].contramap(m => Chunk.from(m.values))
  implicit val encoder:           JsonEncoder[JsonPluginsLicense] = DeriveJsonEncoder.gen[JsonPluginsLicense]

  // copy the endDate to endDates
  def from(global: GlobalPluginsLicenseCounts): JsonPluginsLicense = {
    import global.*
    JsonPluginsLicense(licensees, startDate, endDate, maxNodes)
  }
}

/**
  * Global license information about many plugins, aggregated such that :
  *  - more than 1 distinct licensees can exist for the collection of all plugins
  *  - end date of plugins licenses are aggregated with counts, so that we know how many plugin license expire at a given date
  **/
final case class GlobalPluginsLicenseCounts(
    override val licensees: Option[NonEmptyChunk[String]],
    override val startDate: Option[ZonedDateTime],
    // serves for the aggregation, but the json field is "endDates"
    override val endDate:   Option[GlobalPluginsLicense.DateCounts],
    override val maxNodes:  Option[Int]
) extends GlobalPluginsLicense(licensees, startDate, endDate, maxNodes)

object GlobalPluginsLicenseCounts {
  // upcast the global licenses after aggregation
  def fromGlobalLicense(license: GlobalPluginsLicense[GlobalPluginsLicense.DateCounts]): GlobalPluginsLicenseCounts = {
    import license.*
    GlobalPluginsLicenseCounts(licensees, startDate, endDate, maxNodes)
  }
}

final case class JsonPluginsSystemDetails(
    license: Option[JsonPluginsLicense],
    plugins: Chunk[JsonPluginSystemDetails]
)
object JsonPluginsSystemDetails   {
  import JsonPluginSystemDetails.*

  implicit val encoder: JsonEncoder[JsonPluginsSystemDetails] = DeriveJsonEncoder.gen[JsonPluginsSystemDetails]

  def buildDetails(plugins: Chunk[JsonPluginSystemDetails]): JsonPluginsSystemDetails = {
    val global = GlobalPluginsLicense.from[GlobalPluginsLicense.DateCounts](plugins.flatMap(_.license))
    JsonPluginsSystemDetails(global.map(g => JsonPluginsLicense.from(GlobalPluginsLicenseCounts.fromGlobalLicense(g))), plugins)
  }
}
final case class JsonPluginSystemDetails(
    id:            PluginId,
    name:          String,
    description:   String,
    version:       Option[String],
    status:        JsonPluginSystemStatus,
    statusMessage: Option[String],
    pluginVersion: Version,
    abiVersion:    Version,
    pluginType:    PluginType,
    errors:        List[JsonPluginManagementError],
    license:       Option[PluginLicenseInfo]
)

final case class JsonPluginManagementError(
    error:   String,
    message: String
)
object JsonPluginManagementError  {
  implicit val transformer: Transformer[PluginManagementError, JsonPluginManagementError] = {
    Transformer
      .define[PluginManagementError, JsonPluginManagementError]
      .withFieldComputed(_.error, _.kind.entryName)
      .withFieldComputed(_.message, _.displayMsg)
      .buildTransformer
  }
}

object JsonPluginSystemDetails {
  implicit val encoderPluginId:              JsonEncoder[PluginId]                  = JsonEncoder[String].contramap(_.value)
  implicit val encoderPluginType:            JsonEncoder[PluginType]                = JsonEncoder[String].contramap(_.entryName)
  implicit val encoderPluginManagementError: JsonEncoder[JsonPluginManagementError] =
    DeriveJsonEncoder.gen[JsonPluginManagementError]
  implicit val encoderVersion:               JsonEncoder[Version]                   = JsonEncoder[String].contramap(_.toVersionString)
  implicit val encoderPluginSystemDetails:   JsonEncoder[JsonPluginSystemDetails]   = DeriveJsonEncoder.gen[JsonPluginSystemDetails]
}

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
    license:         Option[RudderPackagePlugin.LicenseInfo]
)
object RudderPackagePlugin     {
  // types for passing implicits
  final case class Licensee(value: String)       extends AnyVal
  final case class SoftwareId(value: String)     extends AnyVal
  final case class MinVersion(value: String)     extends AnyVal
  final case class MaxVersion(value: String)     extends AnyVal
  final case class MaxNodes(value: Option[Int])  extends AnyVal
  final case class AbiVersion(value: Version)    extends AnyVal
  final case class PluginVersion(value: Version) extends AnyVal

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
      abiVersion:        AbiVersion,
      pluginVersion:     PluginVersion,
      transformLicense:  Transformer[LicenseInfo, PluginLicenseInfo]
  ): Transformer[RudderPackagePlugin, JsonPluginSystemDetails] = {
    val _ = transformLicense // variable is used below
    Transformer
      .define[RudderPackagePlugin, JsonPluginSystemDetails]
      .withFieldComputed(_.id, p => PluginId(p.name))
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
      .withFieldComputed(_.version, l => l.version.orElse(l.latestVersion)) // version : only when installed
      .withFieldConst(_.abiVersion, abiVersion.value)       // field is computed upstream
      .withFieldConst(_.pluginVersion, pluginVersion.value) // field is computed upstream
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
  import RudderPackageService.*

  def update(): IOResult[Option[PluginSettingsError]]

  def listAllPlugins(): IOResult[Chunk[RudderPackagePlugin]]

  def installPlugins(plugins: Chunk[String]): IOResult[Unit]

  def removePlugins(plugins:           Chunk[String]): IOResult[Unit]
  def changePluginSystemStatus(status: PluginSystemStatus, plugins: Chunk[String]): IOResult[Unit]
}

object RudderPackageService {

  // PluginSettings configuration could cause errors with known codes and colored stderr in rudder-package
  sealed abstract class PluginSettingsError extends RudderError
  object PluginSettingsError {
    final case class InvalidCredentials(override val msg: String) extends PluginSettingsError
    final case class Unauthorized(override val msg: String)       extends PluginSettingsError

    private val colorRegex = "\u001b\\[[0-9;]*m".r
    private val uncolor    = (str: String) => colorRegex.replaceAllIn(str, "")

    private val sanitizeCmdResult = (res: CmdResult) => res.transform(uncolor.compose(_.strip))

    /**
      * Maps known errors codes from rudder package and adapt the error message to have no color
      */
    def fromResult(cmdResult: CmdResult): PureResult[Option[PluginSettingsError]] = {
      val result = sanitizeCmdResult(cmdResult)
      result.code match {
        case 0 => Right(None)
        case 2 => Right(Some(PluginSettingsError.InvalidCredentials(result.stderr)))
        case 3 => Right(Some(PluginSettingsError.Unauthorized(result.stderr)))
        case _ => Left(Inconsistency(result.debugString()))
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

  override def update(): IOResult[Option[PluginSettingsError]] = {
    // In case of error we need to check the result
    for {
      res          <- runCmd("update" :: Nil)
      (cmd, result) = res
      err          <-
        PluginSettingsError
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

  override def changePluginSystemStatus(status: PluginSystemStatus, plugins: Chunk[String]): IOResult[Unit] = {
    val action = status match {
      case Enabled  => "enable"
      case Disabled => "disable"
    }
    runCmdOrFail(action :: plugins.toList)(
      s"An error occurred while changin plugin status to ${status.value}"
    ).unit
  }

  private def runCmd(params: List[String]):                         IOResult[(Cmd, CmdResult)] = {
    for {
      configCmd   <- configCmdRes.toIO
      cmd          = Cmd(configCmd._1, configCmd._2 ::: params, Map.empty, None)
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

/**
  * A service to manage plugins, it is an abstraction over system administration of plugins
  */
trait PluginSystemService {

  def updateIndex(): IOResult[Option[PluginSettingsError]]
  def list():        IOResult[Chunk[JsonPluginSystemDetails]]
  def install(plugins:     Chunk[PluginId]): IOResult[Unit]
  def remove(plugins:      Chunk[PluginId]): IOResult[Unit]
  def updateStatus(status: PluginSystemStatus, plugins: Chunk[PluginId]): IOResult[Unit]

}

/**
  * Implementation for tests, will do any operation without any error
  */
class InMemoryPluginSystemService(ref: Ref[Map[PluginId, JsonPluginSystemDetails]]) extends PluginSystemService {
  override def updateIndex(): IOResult[Option[PluginSettingsError]] = {
    ZIO.none
  }

  override def list(): UIO[Chunk[JsonPluginSystemDetails]] = {
    ref.get.map(m => Chunk.fromIterable(m.values))
  }

  override def install(plugins: Chunk[PluginId]): UIO[Unit] = {
    updatePluginStatus(JsonPluginSystemStatus.Enabled, plugins)
  }

  override def remove(plugins: Chunk[PluginId]): UIO[Unit] = {
    updatePluginStatus(JsonPluginSystemStatus.Uninstalled, plugins)
  }

  override def updateStatus(status: PluginSystemStatus, plugins: Chunk[PluginId]): UIO[Unit] = {
    updatePluginStatus(status.transformInto[JsonPluginSystemStatus], plugins)
  }

  private def updatePluginStatus(status: JsonPluginSystemStatus, plugins: Chunk[PluginId]) = {
    ref.update(m => m ++ plugins.flatMap(id => m.get(id).map(p => id -> p.copy(status = status))))
  }
}

object InMemoryPluginSystemService {
  def make(initialPlugins: List[JsonPluginSystemDetails]): UIO[InMemoryPluginSystemService] = {
    for {
      ref <- Ref.make(initialPlugins.map(p => p.id -> p).toMap)
    } yield new InMemoryPluginSystemService(ref)
  }
}
