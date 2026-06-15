# 401 — JSON with zio-json

zio-json is our **only** JSON library (see [`700`](700-dependencies-ecosystem.md)).
lift-json is removed — don't reintroduce it (source of truth: ADR
[`18879-main-json-library-zio-json`](../../../adr/webapp/18879-main-json-library-zio-json.md)).
Define codecs so callers need **no implicit import** — put them in the type's companion
(see [`001`](001-scala3-idioms.md)).

## Derive when you can, write a codec when you must

For a **plain case class** whose members all have codecs, prefer Scala 3 derivation:

```scala
final case class CampaignInfo(
    id:          CampaignId,
    name:        String,
    status:      CampaignStatus,
    schedule:    CampaignSchedule
) derives JsonCodec
```

Derivation finds member codecs from implicit scope, so each member type must itself
provide a codec in its companion — which is exactly the pattern below.

In practice, **most types define their codec manually** rather than via `derives` —
that's expected, not a smell. Wrapper/identifier types and anything parsed need
`mapOrFail`/`transformOrFail` (see [`201`](201-parse-dont-validate.md)) and *cannot* be
derived; types needing a discriminator, custom field names, or a specific wire format
also use explicit codecs. So: reach for `derives JsonCodec` on simple aggregates, and
write an explicit `implicit val`/`given` codec (in the companion) whenever the wire
format isn't a trivial 1:1 of the fields.

## Wrapper types: map a primitive codec

For value/opaque wrappers, build on a primitive codec — don't hand-write a parser:

```scala
object PluginId {
  implicit val decoder: JsonDecoder[PluginId] = JsonDecoder[String].mapOrFail(parse)        // parse: String => Either[String, PluginId]
  implicit val encoder: JsonEncoder[PluginId] = JsonEncoder[String].contramap(_.value)
}
```

When you have a `parse`/`serialize` pair, one line gives a round-trip codec:

```scala
implicit val codec: JsonCodec[CampaignId] = JsonCodec.string.transformOrFail(CampaignId.parse, _.serialize)
```

`mapOrFail`/`transformOrFail` keep us honest about **parse-don't-validate**
([`201`](201-parse-dont-validate.md)): a bad payload becomes a decode error.

## Sealed hierarchies: discriminators

For ADTs serialized as tagged objects, use a discriminator field:

```scala
@jsonDiscriminator("value")
sealed trait CampaignStatus { def value: CampaignStatusValue }
```

## Map keys & custom scalars

Use `JsonFieldEncoder`/`JsonFieldDecoder` for map keys, and `.contramap`/`.mapOrFail`
on string codecs for custom scalars (see the `AcceptationDateTime` codec in
`rudder-core/.../policies/ActiveTechnique.scala`, which also shows encoding `Instant`).

## Guidance

- **Put codecs in the companion object** of the type so no import is needed at use site.
- Prefer `derives JsonCodec`; drop to manual `mapOrFail`/`contramap` only for wrappers
  and special formats.
- `given`/`implicit val` both appear in the codebase; prefer `given`
  (new code must use `given`).
- Don't reach for circe/jackson/play-json — zio-json only.

## Enums {#enums}

We use **enumeratum** for enumerations (name/value, lookup-by-name, `values`) — we do
**not** use the native Scala 3 `enum` keyword. The pattern is a `sealed` base extending
`EnumEntry` + a companion extending `Enum[...]`, with each case giving its
**`entryName`** explicitly (`rudder-core/.../campaigns/DataTypes.scala`):

```scala
import enumeratum.*

sealed abstract class CampaignSortOrder(override val entryName: String) extends EnumEntry
object CampaignSortOrder extends Enum[CampaignSortOrder] {
  case object StartDate extends CampaignSortOrder("startDate")
  case object EndDate   extends CampaignSortOrder("endDate")

  // accept extra/legacy input spellings without changing the canonical entryName
  override def extraNamesToValuesMap: Map[String, CampaignSortOrder] =
    Map("start" -> StartDate, "end" -> EndDate)

  override def values: IndexedSeq[CampaignSortOrder] = findValues
}
```

**Always set `entryName` explicitly** (the `(override val entryName: String)` arg).
`entryName` is the *serialized* token, so pinning it decouples the wire/persisted form
from the Scala identifier: you can rename `case object StartDate`, move it, or refactor
the type freely without changing what users and stored files see. Relying on the default
(derived from the object name) would silently break that contract on any rename — see
[`404`](404-serialization-contracts.md). Use `extraNamesToValuesMap` to keep accepting
older input spellings.

For JSON, derive the codec from the enumeratum integration rather than hand-writing it:

```scala
import zio.json.enumeratum.*
implicit val codec: JsonCodec[CampaignStatusValue] = ... // via zio-json enumeratum support
```

For sealed hierarchies serialized as tagged objects, use `@jsonDiscriminator` (above).
