package com.normation.utils

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit
import org.joda.time.DateTimeZone
import org.joda.time.format.ISODateTimeFormat
import org.joda.time as joda
import zio.test.*
import zio.test.Assertion.*

object DateFormaterServiceSpec extends ZIOSpecDefault {

  val legacyGitTagFormat = new org.joda.time.format.DateTimeFormatterBuilder()
    .appendYear(4, 4)
    .appendLiteral('-')
    .appendFixedDecimal(org.joda.time.DateTimeFieldType.monthOfYear(), 2)
    .appendLiteral('-')
    .appendFixedDecimal(org.joda.time.DateTimeFieldType.dayOfMonth(), 2)
    .appendLiteral('_')
    .appendFixedDecimal(org.joda.time.DateTimeFieldType.hourOfDay(), 2)
    .appendLiteral('-')
    .appendFixedDecimal(org.joda.time.DateTimeFieldType.minuteOfHour(), 2)
    .appendLiteral('-')
    .appendFixedDecimal(org.joda.time.DateTimeFieldType.secondOfMinute(), 2)
    .appendLiteral('.')
    .appendFractionOfSecond(3, 9)
    .toFormatter()
    .withZoneUTC()

  val nearEnoughInstant = Gen.instant(
    min = Instant.EPOCH,
    max = Instant.parse("9999-01-01T00:00:00Z")
  )

  def spec = suite("DateFormaterService")(
    test("basicDateTimeFormatter should behave like jodatime ISODateTimeFormat.basicDateTime()") {
      check(Gen.instant(min = Instant.EPOCH, max = Instant.parse("9999-01-01T00:00:00Z"))) { input =>
        assert(DateFormaterService.formatAsBasicDateTime(input))(
          equalTo(org.joda.time.DateTime(input.toEpochMilli, DateTimeZone.UTC).toString(ISODateTimeFormat.basicDateTime()))
        )
      }
    },
    test("parseInstant should parse any instant") {
      check(Gen.instant(min = Instant.EPOCH, max = Instant.parse("9999-01-01T00:00:00Z"))) { input =>
        assert(DateFormaterService.parseInstant(input.toString))(
          isRight(equalTo(input))
        )
      }
    },
    test("parseInstant should parse any offsetdatetime") {
      check(
        Gen.instant(
          min = Instant.EPOCH,
          max = Instant.parse("9999-01-01T00:00:00Z")
        ),
        Gen.zoneOffset
      ) { (input, offset) =>
        assert(DateFormaterService.parseInstant(input.atOffset(offset).toString))(
          isRight(equalTo(input.atOffset(offset).toInstant))
        )
      }
    },
    test("serializeZDT should parse have the same output as Instant.toString") {
      check(
        Gen
          .zonedDateTime(
            min = Instant.EPOCH.atZone(ZoneId.of("Z")),
            max = Instant.parse("9999-01-01T00:00:00Z").atZone(ZoneId.of("Z"))
          )
          .map(_.withNano(1)) // Instant and ISO_OFFSET_DATE_TIME do not agree about removing trailing 0 to nanoseconds
      ) { input =>
        assert(DateFormaterService.serializeZDT(input))(
          equalTo(input.toInstant.toString)
        )
      }
    },
    test("serializeInstant should parse have the same output as Instant.toString") {
      check(
        Gen
          .instant(
            min = Instant.EPOCH,
            max = Instant.parse("9999-01-01T00:00:00Z")
          )
          .map(_.`with`(ChronoField.NANO_OF_SECOND, 1))
      ) { input =>
        assert(DateFormaterService.serializeInstant(input))(
          equalTo(input.toString)
        )
      }
    },
    test("rfcDateformat should parse any instant without milliseconds") {
      check(
        Gen
          .instant(
            min = Instant.EPOCH,
            max = Instant.parse("9999-01-01T00:00:00Z")
          )
          .map(_.truncatedTo(ChronoUnit.SECONDS))
      ) { input =>
        assert(OffsetDateTime.parse(input.toString, DateFormaterService.javatimeRfcDateformat).toInstant)(
          equalTo(input)
        )
      }
    },
    test("rfcDateformat should parse any offsetdatetime without milliseconds") {
      check(
        Gen
          .offsetDateTime(
            min = Instant.EPOCH.atOffset(ZoneOffset.UTC),
            max = Instant.parse("9999-01-01T00:00:00Z").atOffset(ZoneOffset.UTC)
          )
          .map(_.truncatedTo(ChronoUnit.SECONDS))
      ) { input =>
        assert(
          OffsetDateTime.parse(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(input), DateFormaterService.javatimeRfcDateformat)
        )(
          equalTo(input)
        )
      }
    },
    test("joda Duration and java.time Duration should toString the same thing") {
      check(
        nearEnoughInstant.map(_.`with`(ChronoField.MILLI_OF_SECOND, 123)), // avoid differences due to reduced milliseconds
        nearEnoughInstant.map(_.`with`(ChronoField.MILLI_OF_SECOND, 258))
      ) { (start, end) =>
        val jodaValue     = new joda.Duration(end.toEpochMilli - start.toEpochMilli).toPeriod.toString
        val javatimeValue =
          java.time.Duration.between(start.truncatedTo(ChronoUnit.MILLIS), end.truncatedTo(ChronoUnit.MILLIS)).toString
        assert(jodaValue)(equalTo(javatimeValue))
      }
    },
    suite("gitTagFormat")(
      test("gitTagFormat should format dates the same way legacy format did") {
        check(nearEnoughInstant) { input =>
          val expected = legacyGitTagFormat.print(new org.joda.time.DateTime(input.toEpochMilli))
          assert(DateFormaterService.formatAsGitTag(input))(equalTo(expected))
        }
      },
      test("gitTagFormat should parse dates generated by legacy format") {
        check(nearEnoughInstant.map(_.truncatedTo(ChronoUnit.MILLIS))) { input =>
          val asString = legacyGitTagFormat.print(new org.joda.time.DateTime(input.toEpochMilli))
          assert(DateFormaterService.parseAsGitTag(asString))(equalTo(input))
        }
      }
    )
  ) @@ TestAspect.shrinks(0)
}
