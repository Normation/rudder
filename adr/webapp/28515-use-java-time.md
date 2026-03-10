# Use Java date and time API

* Status: accepted
* Deciders: CAN, FAR, PIO, VHA, VME
* Date: 2026-03-10


## Context

The Scala and Java backend of the webapp have been using [Joda-Time](https://www.joda.org/joda-time/index.html) and its `DateTime` class for handling of dates and as common data type for representation of date and time.
But since Java SE 8 ([JSR-310](https://jcp.org/en/jsr/detail?id=310)), the Joda-Time library is a no longer viable project, and it is explicitly stated that users are asked to migrate away from it, in favor of the API in the standard Java library : `java.time`.

The Java date and time API is quite rich, there are several classes that could be used instead of the `DateTime` one from Joda-Time : `Instant`, `LocalDateTime`, `OffsetDateTime`, `ZonedDateTime`.
Since ADR [#28227](https://issues.rudder.io/issues/28227), we should represent date and times in UTC in most parts of the webapp, which is possible with all of those classes, but is always enforced with `Instant` (which is a timestamp that is naturally expressed in the UTC timezone). We also most often do not have the need to keep timezone or offset information, but need an exact moment for computation, which is satisfied by the `Instant` class. But the `Instant` is not suited for computations of days or months because it has no concepts of DST and calendar, so in this case `LocalDateTime` is more suited.

Some storage components of the webapp are low-level and have are some constraint on time representation :
  * in PostgreSQL, we always use the [timestamp with timezone](https://www.postgresql.org/docs/18/datatype-datetime.html) column type ; since we use the [doobie](https://typelevel.org/doobie/) library which has [some support for the Java API](https://typelevel.org/doobie/docs/15-Extensions-PostgreSQL.html#java-8-time-types-jsr310-), for instance the `Instant` class can be used for that,
  * in LDAP, the "generalized time" type defined in [RFC 4517](https://www.rfc-editor.org/rfc/rfc4517.html#section-3.3.13) is the low-level representation ; we use the [unboundid-ldapsdk](https://www.javadoc.io/doc/com.unboundid/unboundid-ldapsdk/3.1.1/com/unboundid/util/StaticUtils.html#encodeGeneralizedTime(java.util.Date)) library which also has support for some of the Java API, we already have a [dedicated class for `GeneralizedTime`](https://github.com/Normation/rudder/blob/6841542eb22c5cec1f09c8827061bdb5c18d7948/webapp/sources/scala-ldap/src/main/scala/com/normation/ldap/sdk/GeneralizedTime.scala#L38).


Since the aforementionned ADR, we should format dates into a [RFC 3339](https://www.rfc-editor.org/rfc/rfc3339#section-5.6)-comptatible format and with `Z` offset. The [DateTimeFormatter#ISO\_INSTANT](https://docs.oracle.com/javase/8/docs/api/java/time/format/DateTimeFormatter.html#ISO_INSTANT) and [DateTimeFormatter#ISO\_OFFSET\_DATE\_TIME](https://docs.oracle.com/javase/8/docs/api/java/time/format/DateTimeFormatter.html#ISO_OFFSET_DATE_TIME) from the Java API satisfy that need, especially when used with the `Instant` class for the UTC offset.

## Decision

* Mainly use the `Instant` class to represent date and time, but :
  * in case where computation in units of _days_ and above is needed, use `LocalDateTime` locally
  * in case where the timezone is needed, keep the timezone in the context (e.g. in another class field), in preference to using the `ZonedDateTime`
* In storage components : 
  * for LDAP, use the dedicated `GeneralizedTime`
  * for PostgreSQL, use the existing bindings : `import doobie.postgres.implicits.*`
* For serialisation, use `ISO_INSTANT` or `ISO_OFFSET_DATE_TIME` to comply with ADR [#28227](https://issues.rudder.io/issues/28227), by using the dedicated [`DateFormaterService`](https://github.com/Normation/rudder/blob/6841542eb22c5cec1f09c8827061bdb5c18d7948/webapp/sources/utils/src/main/scala/com/normation/utils/DateFormaterService.scala#L61)

## Consequences

* We will need to migrate usage of joda-time to be replaced with equivalent usage with Java API to follow this ADR, namely replacing most `org.joda.time.DateTime` with `java.time.Instant`
