package com.normation.ldap.sdk

import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatterBuilder
import java.time.format.SignStyle
import java.time.temporal.ChronoField.DAY_OF_MONTH
import java.time.temporal.ChronoField.HOUR_OF_DAY
import java.time.temporal.ChronoField.MINUTE_OF_HOUR
import java.time.temporal.ChronoField.MONTH_OF_YEAR
import java.time.temporal.ChronoField.NANO_OF_SECOND
import java.time.temporal.ChronoField.SECOND_OF_MINUTE
import java.time.temporal.ChronoField.YEAR
import zio.test.*
import zio.test.Assertion.*

object GeneralizedTimeSpec extends ZIOSpecDefault {

  def toGeneralizedTimeString(instant: Instant) = {
    val date = new DateTimeFormatterBuilder()
      .appendValue(YEAR, 4, 10, SignStyle.NEVER)
      .appendValue(MONTH_OF_YEAR, 2)
      .appendValue(DAY_OF_MONTH, 2)
      .toFormatter()

    val time = new DateTimeFormatterBuilder()
      .appendValue(HOUR_OF_DAY, 2)
      .appendValue(MINUTE_OF_HOUR, 2)
      .optionalStart
      .appendValue(SECOND_OF_MINUTE, 2)
      .optionalStart
      .appendFraction(NANO_OF_SECOND, 0, 3, true)
      .appendOffsetId()
      .toFormatter()

    val dateTime = new DateTimeFormatterBuilder().parseCaseInsensitive
      .append(date)
      .append(time)
      .toFormatter()

    dateTime.format(instant.atOffset(ZoneOffset.UTC))
  }

  def spec = suite("GeneralizedTime")(
    test("migrated version should output the same values as ldap lib code") {
      check(Gen.instant(min = Instant.EPOCH, max = Instant.parse("9999-12-31T23:59:59.99Z"))) { input =>
        assert(toGeneralizedTimeString(input))(equalTo(GeneralizedTime(input).toString))
      }
    }
  ) @@ TestAspect.shrinks(0)
}
