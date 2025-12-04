package com.normation.utils

import java.time.Instant
import org.joda.time.DateTimeZone
import org.joda.time.format.ISODateTimeFormat
import zio.test.*
import zio.test.Assertion.*

object DateFormaterServiceSpec extends ZIOSpecDefault {
  def spec = suite("DateFormaterService")(
    test("basicDateTimeFormatter should behave like jodatime ISODateTimeFormat.basicDateTime()") {
      check(Gen.instant(min = Instant.EPOCH, max = Instant.parse("9999-01-01T00:00:00Z"))) { input =>
        assert(DateFormaterService.formatAsBasicDateTime(input))(
          equalTo(org.joda.time.DateTime(input.toEpochMilli, DateTimeZone.UTC).toString(ISODateTimeFormat.basicDateTime()))
        )
      }
    }
  )
}
