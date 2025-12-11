package com.normation.utils

import com.normation.utils.DateFormaterService.toJavaInstant
import org.joda.time.DateTimeZone
import org.joda.time.format.ISODateTimeFormat
import zio.test.*
import zio.test.Assertion.*

import java.time.format.DateTimeFormatter
import java.time.temporal.{ChronoField, ChronoUnit}
import java.time.{Instant, ZoneId, ZoneOffset}

object DateFormaterServiceSpec extends ZIOSpecDefault {
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
        assert(DateFormaterService.rfcDateformat.parseDateTime(input.toString).toJavaInstant)(
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
        assert(DateFormaterService.rfcDateformat.parseDateTime(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(input)).toJavaInstant)(
          equalTo(input.toInstant)
        )
      }
    }
  ) @@ TestAspect.shrinks(0)
}
