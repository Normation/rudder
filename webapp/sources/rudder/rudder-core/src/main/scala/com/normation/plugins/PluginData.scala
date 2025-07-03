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

package com.normation.plugins

import cats.Semigroup
import com.normation.utils.DateFormaterService
import com.normation.utils.Version
import io.scalaland.chimney.Transformer
import java.time.ZonedDateTime
import zio.Chunk
import zio.NonEmptyChunk
import zio.json.*

case class PluginId(value: String) extends AnyVal
object PluginId {
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

final case class Licensee(value: String)                     extends AnyVal
final case class SoftwareId(value: String)                   extends AnyVal
final case class MinVersion(value: String)                   extends AnyVal
final case class MaxVersion(value: String)                   extends AnyVal
final case class MaxNodes(value: Option[Int])                extends AnyVal
final case class StatusDisabledReason(value: Option[String]) extends AnyVal
object MaxNodes {
  val unlimited:            MaxNodes                           = MaxNodes(None)
  implicit val transformer: Transformer[MaxNodes, Option[Int]] = _.value
}
final case class AbiVersion(value: Version) extends AnyVal
final case class PluginVersion(value: Version) extends AnyVal

/**
  * Main datatype for a plugin object which enforces the errors
  * that can be associated with the plugin (on license, version, ...).
  */
final case class Plugin(
    id:            PluginId,
    name:          String,
    description:   String,
    version:       Option[String],
    status:        PluginInstallStatus,
    statusMessage: Option[String],
    pluginVersion: Version,
    abiVersion:    Version,
    pluginType:    PluginType,
    errors:        List[PluginError],
    license:       Option[PluginLicense]
)

/**
  * This object gives main information about license
  * It is designated to be read to the user. No string information
  * should be used for comparison.
  */
final case class PluginLicense(
    licensee:   Licensee,
    softwareId: SoftwareId,
    minVersion: MinVersion,
    maxVersion: MaxVersion,
    startDate:  ZonedDateTime,
    endDate:    ZonedDateTime,
    maxNodes:   MaxNodes,
    others:     Map[String, String]
) {

  /**
    * Display human information about the license validity
    */
  def display: String = {
    val nodesLimit = maxNodes.value match {
      case Some(i) if i > 0 => s"up to ${i} nodes"
      case _                => "with unlimited number of nodes"
    }

    s"Licensed to ${licensee.value} for Rudder [${minVersion.value},${maxVersion.value}] " +
    s"until ${DateFormaterService.getDisplayDate(endDate)} and ${nodesLimit}"
  }
}

sealed trait PluginType
object PluginType {
  case object Webapp      extends PluginType
  case object Integration extends PluginType
}

sealed trait PluginInstallStatus
object PluginInstallStatus {
  case object Enabled     extends PluginInstallStatus
  case object Disabled    extends PluginInstallStatus
  case object Uninstalled extends PluginInstallStatus

  def from(
      pluginType:           PluginType,
      installed:            Boolean,
      enabled:              Boolean,
      statusDisabledReason: StatusDisabledReason
  ): PluginInstallStatus = {
    import PluginType.*
    if (statusDisabledReason.value.isDefined) {
      Disabled
    } else {
      (pluginType, installed, enabled) match {
        case (_, false, _)                                => Uninstalled
        case (_, true, true) | (Integration, true, false) => Enabled
        case (Webapp, true, false)                        => Disabled
      }
    }
  }
}

/**
  * The plugins along with aggregated metadata on them
  */
final case class PluginsMetadata[EndDate](
    globalLicense: Option[GlobalPluginsLicense[EndDate]],
    plugins:       Chunk[Plugin]
)

object PluginsMetadata {
  import GlobalPluginsLicense.ToEndDate

  def empty[EndDate]: PluginsMetadata[EndDate] = apply(None, Chunk.empty)

  def fromPlugins[EndDate: ToEndDate: Semigroup](plugins: Iterable[Plugin]): PluginsMetadata[EndDate] = {
    if (plugins.isEmpty) empty
    else {
      PluginsMetadata(
        GlobalPluginsLicense.from[EndDate](plugins.flatMap(_.license)),
        Chunk.from(plugins)
      )
    }
  }
}

/**
  * Base structure for an aggregated view of licenses for multiple plugins.
  * Implementations need different types for some fields.
  */
sealed abstract class GlobalPluginsLicense[EndDate](
    val licensees: Option[NonEmptyChunk[String]],
    // for now, min/max version is not used and is always 00-99
    val startDate: Option[ZonedDateTime],
    val endDate:   Option[EndDate],
    val maxNodes:  Option[Int]
) {
  def combine(that: GlobalPluginsLicense[EndDate])(implicit endSemigroup: Semigroup[EndDate]) = {
    def combineOptField[A](toa: GlobalPluginsLicense[EndDate] => Option[A])(f: (A, A) => A): Option[A] = {
      implicit val s: Semigroup[A] = Semigroup.instance(f)
      Semigroup[Option[A]].combine(toa(this), toa(that))
    }
    new GlobalPluginsLicense[EndDate](
      combineOptField(_.licensees)(_ ++ _),
      combineOptField(_.startDate)((x, y) => if (x.isAfter(y)) x else y),
      combineOptField(_.endDate)(endSemigroup.combine),
      combineOptField(_.maxNodes)(_.min(_))
    ) {}
  }

  private[GlobalPluginsLicense] def sortDistinctLicensees: GlobalPluginsLicense[EndDate] = {
    withLicensees(licensees.map(_.sorted.distinct).flatMap(NonEmptyChunk.fromChunk(_)))
  }

  protected def withLicensees(licensees: Option[NonEmptyChunk[String]]): GlobalPluginsLicense[EndDate] = {
    new GlobalPluginsLicense[EndDate](
      licensees,
      startDate,
      endDate,
      maxNodes
    ) {}
  }

  private def isDefined = licensees.isDefined || startDate.isDefined || endDate.isDefined || maxNodes.isDefined
}

object GlobalPluginsLicense {

  /**
    * Typeclass for proving that a type can be constructed from a Java ZonedDateTime.
    * This should be a functor to allow building wrapping datastructures but is limited to supported ones for now.
    */
  sealed private[plugins] trait ToEndDate[T] {
    def fromZonedDateTime(date: ZonedDateTime): T
  }
  private[plugins] object ToEndDate          {
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

  def fromLicense[T: ToEndDate: Semigroup](info: PluginLicense): GlobalPluginsLicense[T] = {
    new GlobalPluginsLicense[T](
      Some(NonEmptyChunk(info.licensee.value)),
      Some(info.startDate),
      Some(ToEndDate[T].fromZonedDateTime(info.endDate)),
      info.maxNodes.value
    ) {}
  }

  def from[T: ToEndDate: Semigroup](licenses: Iterable[PluginLicense]): Option[GlobalPluginsLicense[T]] = {
    NonEmptyChunk
      .fromIterableOption(licenses)
      .map(from(_))
      .flatMap(r => Option.when(r.isDefined)(r))
  }

  private def from[T: ToEndDate: Semigroup](licenses: NonEmptyChunk[PluginLicense]): GlobalPluginsLicense[T] = {
    licenses
      .reduceMapLeft(fromLicense(_)) { case (lim, lic) => lim.combine(fromLicense(lic)) }
      .sortDistinctLicensees
  }
}

/**
  * Global information about all plugins, retaining all licensees,
  * but only the last start date and first end date and least number of supported nodes
  */
final case class GlobalPluginsLicenseLimits(
    override val licensees: Option[NonEmptyChunk[String]],
    override val startDate: Option[ZonedDateTime],
    override val endDate:   Option[ZonedDateTime],
    override val maxNodes:  Option[Int]
) extends GlobalPluginsLicense[ZonedDateTime](licensees, startDate, endDate, maxNodes)
object GlobalPluginsLicenseLimits {
  def from(base: GlobalPluginsLicense[ZonedDateTime]): GlobalPluginsLicenseLimits = {
    import base.*
    GlobalPluginsLicenseLimits(licensees, startDate, endDate, maxNodes)
  }
}

/**
  * Global information about all plugins, retaining all licensees,
  * but only the last start date and counts of end dates
  */
final case class GlobalPluginsLicenseCounts(
    override val licensees: Option[NonEmptyChunk[String]],
    override val startDate: Option[ZonedDateTime],
    override val endDate:   Option[GlobalPluginsLicense.DateCounts],
    override val maxNodes:  Option[Int]
) extends GlobalPluginsLicense[GlobalPluginsLicense.DateCounts](licensees, startDate, endDate, maxNodes)
