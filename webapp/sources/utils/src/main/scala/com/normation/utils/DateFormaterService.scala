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

package com.normation.utils

import com.normation.errors.Inconsistency
import com.normation.errors.PureResult
import io.scalaland.chimney.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.SignStyle
import java.time.temporal.ChronoField.*
import java.util.TimeZone
import org.joda.time.DateTime
import org.joda.time.DateTimeFieldType
import org.joda.time.DateTimeZone
import org.joda.time.Duration
import org.joda.time.chrono.ISOChronology
import org.joda.time.format.*
import scala.util.control.NonFatal
import zio.*
import zio.json.*

object DateFormaterService {

  extension (self: DateTime) {
    def toJavaInstant: Instant = Instant.ofEpochMilli(self.getMillis)

    // see https://stackoverflow.com/a/47753227 - read other solution and the comment in them, too
    def toZonedDateTime: ZonedDateTime = self.toJavaInstant.atZone(ZoneId.of(self.getZone.getID, ZoneId.SHORT_IDS))
  }

  extension (self: Instant) {
    def toJodaDateTime: DateTime = new DateTime(self.toEpochMilli, DateTimeZone.UTC)
  }

  implicit class JavaTimeToJoda(x: ZonedDateTime) {
    // see https://stackoverflow.com/a/37335420 - read other solution and the comment in them, too
    def toJoda: DateTime =
      new DateTime(x.toInstant.toEpochMilli, DateTimeZone.forTimeZone(TimeZone.getTimeZone(x.getZone)))
  }

  trait DateTimeCodecs {
    implicit val encoderDateTime: JsonEncoder[DateTime] = JsonEncoder[String].contramap(serialize)
    implicit val decoderDateTime: JsonDecoder[DateTime] = JsonDecoder[String].mapOrFail(parseDate(_).left.map(_.fullMsg))
    implicit val codecDateTime:   JsonCodec[DateTime]   = new JsonCodec[DateTime](encoderDateTime, decoderDateTime)

    implicit val encoderInstant: JsonEncoder[Instant] = JsonEncoder[String].contramap(_.toString)
    implicit val decoderInstant: JsonDecoder[Instant] = JsonDecoder[String].mapOrFail(parseInstant(_).left.map(_.fullMsg))

    implicit val codecInstant: JsonCodec[Instant] = JsonCodec(encoderInstant, decoderInstant)

    implicit val encoderZonedDateTime: JsonEncoder[ZonedDateTime] = JsonEncoder[String].contramap(serializeZDT)
    implicit val decoderZonedDateTime: JsonDecoder[ZonedDateTime] =
      JsonDecoder[String].mapOrFail(parseDateZDT(_).left.map(_.fullMsg))

    implicit val transformDateTime: Transformer[DateTime, ZonedDateTime] = _.toZonedDateTime

    implicit val transformZonedDateTime: Transformer[ZonedDateTime, DateTime] = { x =>
      new DateTime(x.toInstant.toEpochMilli, DateTimeZone.UTC)
    }

    implicit val transformDateTimeInstant: Transformer[DateTime, Instant] = _.toJavaInstant

    implicit val transformInstantDateTime: Transformer[Instant, DateTime] = _.toJodaDateTime

    implicit val transformInstantLocalDate: Transformer[Instant, LocalDate] = { x => LocalDate.ofInstant(x, ZoneOffset.UTC) }

  }

  object json extends DateTimeCodecs

  val displayDateFormat: DateTimeFormatter = {
    new DateTimeFormatterBuilder()
      .append(ISODateTimeFormat.date())
      .appendLiteral(' ')
      .append(ISODateTimeFormat.timeNoMillis())
      .toFormatter
      .withZoneUTC()
  }

  val javatimeRfcDateformat:   java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
  val rfcDateformat:           DateTimeFormatter                  = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ssZZ")
  val rfcDateformatWithMillis: DateTimeFormatter                  = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")

  val javaDisplayDateFormat: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("YYYY-MM-dd HH:mm:ssZ")

  /*
   * Display date must be used only for the user facing date in non serialized form
   * (for ex: in a web page).
   */
  def getDisplayDate(date: DateTime): String = {
    date.toString(displayDateFormat)
  }

  def getDisplayDate(date: ZonedDateTime): String = {
    date.format(javaDisplayDateFormat)
  }

  def getDisplayDate(instant: Instant): String = {
    getDisplayDate(toDateTime(instant))
  }

  /*
   * Format a date for serialisation (json, database, etc). We use
   * ISO 8601 (rfc 3339) for that (without millis)
   */
  def serialize(datetime: DateTime): String = datetime.toString(ISODateTimeFormat.dateTimeNoMillis.withZoneUTC())

  def serializeZDT(datetime: ZonedDateTime): String =
    datetime.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC))

  def serializeInstant(instant: Instant): String = instant.toString

  def toDateTime(instant: Instant): DateTime = json.transformInstantDateTime.transform(instant)

  def toLocalDate(instant: Instant): LocalDate = json.transformInstantLocalDate.transform(instant)

  def parseDate(date: String): PureResult[DateTime] = {
    try {
      Right(ISODateTimeFormat.dateTimeNoMillis().withZoneUTC().parseDateTime(date))
    } catch {
      case NonFatal(ex) => Left(Inconsistency(s"String '${date}' can't be parsed as an ISO date/time: ${ex.getMessage}"))
    }
  }

  /*
   * Parse a string as an Instant.
   * The string must be an RFC 3339 date time.
   * We are more lenient than java strict instant parsing, since we accept time zone.
   */
  def parseInstant(instant: String): PureResult[Instant] = {
    try {
      Right(Instant.parse(instant))
    } catch {
      case NonFatal(ex) =>
        // try to parse as a ISO DateTime with time zone
        parseDateZDT(instant).fold(
          _ => Left(Inconsistency(s"String '${instant}' can't be parsed as an ISO instant: ${ex.getMessage}")),
          zdt => Right(zdt.toInstant)
        )
    }
  }

  def parseDateOnly(date: String): PureResult[DateTime] = {
    try {
      Right(ISODateTimeFormat.date().parseDateTime(date).withZone(DateTimeZone.UTC))
    } catch {
      case NonFatal(ex) => Left(Inconsistency(s"String '${date}' can't be parsed as an ISO date: ${ex.getMessage}"))
    }
  }

  // ISO date time with timezone
  def parseDateZDT(date: String): PureResult[ZonedDateTime] = {
    try {
      Right(ZonedDateTime.parse(date, java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME))
    } catch {
      case NonFatal(ex) => Left(Inconsistency(s"String '${date}' can't be parsed as an ISO date/time: ${ex.getMessage}"))
    }
  }

  def parseDateOnlyZDT(date: String): PureResult[LocalDate] = {
    try {
      Right(LocalDate.parse(date, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE))
    } catch {
      case NonFatal(ex) => Left(Inconsistency(s"String '${date}' can't be parsed as an ISO date: ${ex.getMessage}"))
    }
  }

  val dateFormatTimePicker = "yyyy-MM-dd HH:mm"
  def parseDateTimePicker(date: String): PureResult[DateTime] = {
    try {
      Right(DateTimeFormat.forPattern(dateFormatTimePicker).parseDateTime(date))
    } catch {
      case NonFatal(ex) =>
        Left(Inconsistency((s"String '${date}' can't be parsed as a date (expected pattern: yyyy-MM-dd): ${ex.getMessage}")))
    }
  }

  private val basicDateTimeFormatter = {
    val date = new java.time.format.DateTimeFormatterBuilder()
      .appendValue(YEAR, 4, 10, SignStyle.NEVER)
      .appendValue(MONTH_OF_YEAR, 2)
      .appendValue(DAY_OF_MONTH, 2)
      .toFormatter()

    val time = new java.time.format.DateTimeFormatterBuilder()
      .appendValue(HOUR_OF_DAY, 2)
      .appendValue(MINUTE_OF_HOUR, 2)
      .optionalStart
      .appendValue(SECOND_OF_MINUTE, 2)
      .optionalStart
      .appendFraction(NANO_OF_SECOND, 0, 3, true)
      .appendOffsetId()
      .toFormatter()

    new java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive
      .append(date)
      .appendLiteral('T')
      .append(time)
      .toFormatter()
  }

  def formatAsBasicDateTime(instant: Instant): String = basicDateTimeFormatter.format(instant.atOffset(ZoneOffset.UTC))

  def getDisplayDateTimePicker(date: DateTime): String = {
    date.toString(dateFormatTimePicker)
  }
  // for joda time
  private val jodaPeriodFormatter = new PeriodFormatterBuilder()
    .appendDays()
    .appendSuffix(" day", " days")
    .appendSeparator(", ")
    .appendHours()
    .appendSuffix(" hour", " hours")
    .appendSeparator(", ")
    .appendMinutes()
    .appendSuffix(" min", " min")
    .appendSeparator(", ")
    .appendSeconds()
    .appendSuffix(" s", " s")
    .toFormatter()

  // for java.time, we don't have any built-in formatter, so we need to do it by hand
  def formatJavaDuration(d: java.time.Duration): String = {
    d.render
  }

  /*
   * Format the interval of time between start and end date time, with a
   * coarse granularity of seconds, and "less than 1 s" for smaller values.
   */
  private def broadlyFormatPeriod(duration: Duration): String = {
    if (duration.getMillis < 1000) "less than 1 s"
    else jodaPeriodFormatter.print(duration.toPeriod(ISOChronology.getInstanceUTC))
  }

  /*
   * Format the interval of time between start and end date time, with a
   * coarse granularity of seconds, and "less than 1 s" for smaller values.
   */
  def getBroadlyFormatedPeriod(start: DateTime, end: DateTime): String = {
    broadlyFormatPeriod(new Duration(start, end))
  }

  /**
   * For git tags or branches, we can't use ISO8601 valid format since ":" is forbidden.
   * So we use a:
   * YYYY-MM-dd_HH_mm_ss.SSS
   */
  val gitTagFormat: DateTimeFormatter = new DateTimeFormatterBuilder()
    .appendYear(4, 4)
    .appendLiteral('-')
    .appendFixedDecimal(DateTimeFieldType.monthOfYear(), 2)
    .appendLiteral('-')
    .appendFixedDecimal(DateTimeFieldType.dayOfMonth(), 2)
    .appendLiteral('_')
    .appendFixedDecimal(DateTimeFieldType.hourOfDay(), 2)
    .appendLiteral('-')
    .appendFixedDecimal(DateTimeFieldType.minuteOfHour(), 2)
    .appendLiteral('-')
    .appendFixedDecimal(DateTimeFieldType.secondOfMinute(), 2)
    .appendLiteral('.')
    .appendFractionOfSecond(3, 9)
    .toFormatter()
    .withZoneUTC()

}
