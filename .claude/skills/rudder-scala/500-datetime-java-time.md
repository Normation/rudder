# 500 — Date & time: `java.time`

We are **migrating off Joda-Time** (`org.joda.time`), which is no longer maintained.
New and changed code uses the **`java.time`** standard library and its Scala-friendly
wrapper.

> Source of truth: ADR [`28515-use-java-time`](../../../adr/webapp/28515-use-java-time.md)
> and ADR [`27084-date-format-timezone`](../../../adr/webapp/27084-date-format-timezone.md).
> Read them when in doubt.

## Choosing the type (per ADR 28515)

- **Default to `Instant`** — a UTC timestamp, naturally offset-free. Use it for "a
  moment in time" almost everywhere (it dominates the codebase). It's also what our
  storage bindings expect.
- **`LocalDateTime`** only when you must compute in **days/months or larger** (calendar
  math, DST awareness) — `Instant` has no calendar concept.
- **Avoid `ZonedDateTime`/`OffsetDateTime`.** When a timezone genuinely matters, keep it
  as a **separate field/context** alongside an `Instant`, rather than carrying a zoned
  type around. (`ZonedDateTime` survives in some places like `PluginLicense`, but it's
  not the pattern to reach for.)
- Zones/offsets → `java.time.ZoneId` / `ZoneOffset` when needed.

## Don't introduce Joda

- **Don't** add new `org.joda.time.*` usages.
- When you touch code that uses Joda, prefer migrating the touched part to `java.time`
  if it's safe and contained.

## Conversion / formatting helpers

`com.normation.utils.DateFormaterService` (`utils/.../DateFormaterService.scala`) is the
central place for date handling and the Joda⇄java.time bridge used during migration:

```scala
import com.normation.utils.DateFormaterService.*

// joda -> java.time (transitional)
jodaDateTime.toJavaInstant
jodaDateTime.toZonedDateTime
jodaDateTime.toOffsetDateTime
// java.time -> joda (only where legacy APIs still require it)
instant.toJodaDateTime
offsetDateTime.toJodaDateTime
// display
DateFormaterService.getDisplayDate(zonedDateTime)
```

Use these extensions instead of re-deriving conversions inline.

## ZIO clock

For "now" inside an effect, get it from the ZIO clock rather than calling
`Instant.now()` directly, so time stays testable/effectful:

```scala
import com.normation.zio.currentOffsetDateTimeUTC   // UIO[OffsetDateTime]
```

(see `com.normation.zio` in `ZioCommons.scala`).

## Serialization & display: explicit timezone, UTC (per ADR 27084)

Every serialized date carries an **explicit timezone**, and the format is
**RFC 3339-compatible**:

- **REST API** — parse strictly RFC 3339 on input; output in **UTC with the `Z`
  offset**. Use the ISO formatters via `DateFormaterService` (built on
  `DateTimeFormatter.ISO_INSTANT` / `ISO_OFFSET_DATE_TIME`), e.g.
  `DateFormaterService.serializeZDT(...)` / the `Instant` serializers — don't hand-roll
  a format string and never emit a date without an offset.
- **Storage** — PostgreSQL uses `timestamp with timezone` via doobie
  (`import doobie.postgres.implicits.*`, which binds `Instant`); LDAP uses the
  dedicated `GeneralizedTime` (`scala-ldap`).
- **Web pages** — display with a shared timezone (user preference, else server TZ);
  objects with an explicit TZ (e.g. campaigns) may render in their own.

## JSON

Custom `Instant`/date codecs live with the types that use them (e.g. the `Instant`
codec inside the `AcceptationDateTime` `JsonCodec`) — see [`401`](401-json-zio-json.md).
Encode to the RFC 3339 / UTC form above.
