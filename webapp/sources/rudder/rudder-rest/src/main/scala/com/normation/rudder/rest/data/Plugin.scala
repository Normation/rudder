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
package com.normation.rudder.rest.data

import com.normation.plugins.*
import com.normation.plugins.settings.PluginSettings
import com.normation.utils.DateFormaterService
import com.normation.utils.Version
import enumeratum.Enum
import enumeratum.EnumEntry.*
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.syntax.*
import java.time.ZonedDateTime
import zio.*
import zio.json.*

/**
  * Serialized format for the plugin license.
  */
final case class JsonPluginLicense(
    licensee:       String,
    softwareId:     String,
    minVersion:     String,
    maxVersion:     String,
    startDate:      ZonedDateTime,
    endDate:        ZonedDateTime,
    maxNodes:       Option[Int],
    additionalInfo: Map[String, String]
)

object JsonPluginLicense {
  implicit val encoder: JsonEncoder[JsonPluginLicense] = DeriveJsonEncoder.gen[JsonPluginLicense]

  implicit val transformer: Transformer[PluginLicense, JsonPluginLicense] = {
    Transformer
      .define[PluginLicense, JsonPluginLicense]
      .withFieldRenamed(_.others, _.additionalInfo)
      .buildTransformer
  }
}

/**
  * Information about registered plugins that can be used
  * for API and monitoring things using simple license aggregation.
  */
final case class JsonPluginsDetails(
    globalLimits: Option[JsonGlobalPluginLimits],
    // plugins should be sorted by id
    details:      Seq[JsonPluginDetails]
)
object JsonPluginsDetails {
  implicit val encoder: JsonEncoder[JsonPluginsDetails] = DeriveJsonEncoder.gen[JsonPluginsDetails]
}

/**
  * Global information about all plugins, mapped from aggregated license 
  */
final case class JsonGlobalPluginLimits(
    licensees: Option[NonEmptyChunk[String]],
    startDate: Option[ZonedDateTime],
    endDate:   Option[ZonedDateTime],
    maxNodes:  Option[Int]
)

object JsonGlobalPluginLimits {
  import DateFormaterService.json.encoderZonedDateTime

  implicit val encoder:     JsonEncoder[JsonGlobalPluginLimits]                                      = DeriveJsonEncoder.gen
  implicit val transformer: Transformer[GlobalPluginsLicense[ZonedDateTime], JsonGlobalPluginLimits] =
    Transformer.derive[GlobalPluginsLicense[ZonedDateTime], JsonGlobalPluginLimits]
}

/**
  * Representation of a RudderPluginDef, which is defined in rudder-web
  */
final case class JsonPluginDetails(
    id:            String,
    name:          String,
    shortName:     String,
    description:   String,
    version:       String,
    status:        JsonPluginInstallStatus,
    statusMessage: Option[String],
    license:       Option[JsonPluginLicense]
)
object JsonPluginDetails {
  implicit val encoder: JsonEncoder[JsonPluginDetails] = DeriveJsonEncoder.gen[JsonPluginDetails]
}

/**
  * Representation of plugins with metadata for plugins API using
  * counts on license dates.
  */
final case class JsonPluginsSystemDetails(
    license: Option[JsonPluginsLicense],
    plugins: Chunk[JsonPluginSystemDetails]
)
object JsonPluginsSystemDetails {
  implicit val encoder:     JsonEncoder[JsonPluginsSystemDetails]                                                   = DeriveJsonEncoder.gen[JsonPluginsSystemDetails]
  implicit val transformer: Transformer[PluginsMetadata[GlobalPluginsLicense.DateCounts], JsonPluginsSystemDetails] = {
    Transformer
      .define[PluginsMetadata[GlobalPluginsLicense.DateCounts], JsonPluginsSystemDetails]
      .withFieldRenamed(_.globalLicense, _.license)
      .withFieldComputed(_.plugins, _.plugins.map(_.transformInto[JsonPluginSystemDetails]).sortBy(_.id))
      .buildTransformer
  }
}

/**
  * Global information about all plugins, mapped from aggregated license 
  */
final case class JsonPluginsLicense(
    licensees: Option[NonEmptyChunk[String]],
    startDate: Option[ZonedDateTime],
    endDates:  Option[GlobalPluginsLicense.DateCounts],
    maxNodes:  Option[Int]
)
object JsonPluginsLicense {

  import com.normation.plugins.GlobalPluginsLicense.*

  implicit val dateFieldEncoder: JsonFieldEncoder[ZonedDateTime] =
    JsonFieldEncoder[String].contramap(DateFormaterService.serializeZDT)

  implicit val dateCountEncoder:  JsonEncoder[DateCount]          = DeriveJsonEncoder.gen[DateCount]
  implicit val dateCountsEncoder: JsonEncoder[DateCounts]         = JsonEncoder[Chunk[DateCount]].contramap(m => Chunk.from(m.values))
  implicit val encoder:           JsonEncoder[JsonPluginsLicense] = DeriveJsonEncoder.gen[JsonPluginsLicense]

  implicit val transformer: Transformer[GlobalPluginsLicense[DateCounts], JsonPluginsLicense] = {
    Transformer
      .define[GlobalPluginsLicense[DateCounts], JsonPluginsLicense]
      .withFieldRenamed(_.endDate, _.endDates)
      .buildTransformer
  }
}

final case class JsonPluginSystemDetails(
    id:            String,
    name:          String,
    description:   String,
    version:       Option[String],
    status:        JsonPluginInstallStatus,
    statusMessage: Option[String],
    pluginVersion: Version,
    abiVersion:    Version,
    pluginType:    JsonPluginType,
    errors:        List[JsonPluginError],
    license:       Option[JsonPluginLicense]
)
object JsonPluginSystemDetails {
  implicit val encoderPluginId: JsonEncoder[PluginId]                = JsonEncoder[String].contramap(_.value)
  implicit val encoderVersion:  JsonEncoder[Version]                 = JsonEncoder[String].contramap(_.toVersionString)
  implicit val encoder:         JsonEncoder[JsonPluginSystemDetails] = DeriveJsonEncoder.gen[JsonPluginSystemDetails]

  implicit val transformer: Transformer[Plugin, JsonPluginSystemDetails] = {
    Transformer.derive[Plugin, JsonPluginSystemDetails]
  }
}

sealed trait JsonPluginType extends Lowercase
object JsonPluginType       extends Enum[JsonPluginType] {
  case object Webapp      extends JsonPluginType
  case object Integration extends JsonPluginType

  override def values: IndexedSeq[JsonPluginType] = findValues

  implicit val encoder: JsonEncoder[JsonPluginType] = JsonEncoder[String].contramap(_.entryName)

  implicit val transformer: Transformer[PluginType, JsonPluginType] =
    Transformer.derive[PluginType, JsonPluginType]
}

sealed trait JsonPluginInstallStatus extends Lowercase
object JsonPluginInstallStatus       extends Enum[JsonPluginInstallStatus] {
  case object Enabled     extends JsonPluginInstallStatus
  case object Disabled    extends JsonPluginInstallStatus
  case object Uninstalled extends JsonPluginInstallStatus

  override def values: IndexedSeq[JsonPluginInstallStatus] = findValues

  implicit val encoder: JsonEncoder[JsonPluginInstallStatus] = JsonEncoder[String].contramap(_.entryName)

  implicit val transformer: Transformer[PluginInstallStatus, JsonPluginInstallStatus] =
    Transformer.derive[PluginInstallStatus, JsonPluginInstallStatus]
}

final case class JsonPluginError(
    error:   String,
    message: String
)
object JsonPluginError {
  implicit val encoder:     JsonEncoder[JsonPluginError]              = DeriveJsonEncoder.gen[JsonPluginError]
  implicit val transformer: Transformer[PluginError, JsonPluginError] = {
    Transformer
      .define[PluginError, JsonPluginError]
      .withFieldComputed(_.error, _.kind.entryName)
      .withFieldComputed(_.message, _.displayMsg)
      .buildTransformer
  }
}

/**
 * (De)Serialized version of the PluginSettings structure,
 * without leaking passwords.
 */
final case class JsonPluginSettings(
    url:                        Option[String],
    username:                   Option[String],
    @jsonExclude password:      Option[String],
    proxyUrl:                   Option[String],
    proxyUser:                  Option[String],
    @jsonExclude proxyPassword: Option[String]
)

object JsonPluginSettings {
  implicit val encoder: JsonEncoder[JsonPluginSettings] = DeriveJsonEncoder.gen[JsonPluginSettings]
  implicit val decoder: JsonDecoder[JsonPluginSettings] = DeriveJsonDecoder.gen[JsonPluginSettings]

  implicit val transformer:     Transformer[PluginSettings, JsonPluginSettings] =
    Transformer.derive[PluginSettings, JsonPluginSettings]
  implicit val backTransformer: Transformer[JsonPluginSettings, PluginSettings] =
    Transformer.derive[JsonPluginSettings, PluginSettings]
}
