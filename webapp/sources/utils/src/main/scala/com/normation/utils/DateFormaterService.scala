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
import java.time.ZonedDateTime
import java.time.ZoneId
import org.joda.time.DateTime
import org.joda.time.DateTimeFieldType
import org.joda.time.Duration
import org.joda.time.chrono.ISOChronology
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import org.joda.time.format.DateTimeFormatterBuilder
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.format.PeriodFormatterBuilder
import scala.util.control.NonFatal
import zio.json.*

object DateFormaterService {

  implicit class JodaTimeToJava(d: DateTime) {
    def toJava: ZonedDateTime = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(d.getMillis), ZoneId.of(d.getZone.getID))
  }

  object json {
    implicit val encoderDateTime: JsonEncoder[DateTime] = JsonEncoder[String].contramap(serialize)
    implicit val decoderDateTime: JsonDecoder[DateTime] = JsonDecoder[String].mapOrFail(parseDate(_).left.map(_.fullMsg))
    implicit val codecDateTime:   JsonCodec[DateTime]   = new JsonCodec[DateTime](encoderDateTime, decoderDateTime)

    implicit val encoderZonedDateTime: JsonEncoder[ZonedDateTime] = JsonEncoder[String].contramap(serializeZDT)
    implicit val decoderZonedDateTime: JsonDecoder[ZonedDateTime] =
      JsonDecoder[String].mapOrFail(parseDateZDT(_).left.map(_.fullMsg))

    implicit val codecZonedDateTime: JsonCodec[ZonedDateTime] =
      new JsonCodec[ZonedDateTime](encoderZonedDateTime, decoderZonedDateTime)
  }

  val displayDateFormat: DateTimeFormatter = new DateTimeFormatterBuilder()
    .append(DateTimeFormat.forPattern("YYYY-MM-dd"))
    .appendLiteral(' ')
    .append(DateTimeFormat.forPattern("HH:mm:ssZ"))
    .toFormatter

  val rfcDateformat:           DateTimeFormatter = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ssZZ")
  val rfcDateformatWithMillis: DateTimeFormatter = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")

  /*
   * Display date must be used only for the user facing date in non serialized form
   * (for ex: in a web page).
   */
  def getDisplayDate(date: DateTime): String = {
    date.toString(displayDateFormat)
  }

  /*
   * Format a date for serialisation (json, database, etc). We use
   * ISO 8601 (rfc 3339) for that (without millis)
   */
  def serialize(datetime: DateTime): String = datetime.toString(ISODateTimeFormat.dateTimeNoMillis.withZoneUTC())

  def serializeZDT(datetime: ZonedDateTime): String = datetime.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)

  def parseDate(date: String): PureResult[DateTime] = {
    try {
      Right(ISODateTimeFormat.dateTimeNoMillis().parseDateTime(date))
    } catch {
      case NonFatal(ex) => Left(Inconsistency(s"String '${date}' can't be parsed as an ISO date/time: ${ex.getMessage}"))
    }
  }

  def parseDateOnly(date: String): PureResult[DateTime] = {
    try {
      Right(ISODateTimeFormat.date().parseDateTime(date))
    } catch {
      case NonFatal(ex) => Left(Inconsistency(s"String '${date}' can't be parsed as an ISO date: ${ex.getMessage}"))
    }
  }

  def parseDateZDT(date: String): PureResult[ZonedDateTime] = {
    try {
      Right(ZonedDateTime.parse(date, java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME))
    } catch {
      case NonFatal(ex) => Left(Inconsistency(s"String '${date}' can't be parsed as an ISO date/time: ${ex.getMessage}"))
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

  def getDisplayDateTimePicker(date: DateTime): String = {
    date.toString(dateFormatTimePicker)
  }
  private val periodFormatter = new PeriodFormatterBuilder()
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

  def formatPeriod(duration: Duration): String = {
    if (duration.getMillis < 1000) "less than 1 s"
    else periodFormatter.print(duration.toPeriod(ISOChronology.getInstanceUTC))
  }

  def getFormatedPeriod(start: DateTime, end: DateTime): String = {
    formatPeriod(new Duration(start, end))
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

}
