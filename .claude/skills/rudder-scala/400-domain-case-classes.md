# 400 — Domain case classes

Business objects are **plain, dumb `case class`es** — ideally with *no* methods. All
logic (parsing, serialization, conversion, derived views) goes in the **companion
object** or in `extension` methods (see [`001`](001-scala3-idioms.md)).

## Shape

```scala
final case class Plugin(
    id:            PluginId,
    name:          String,
    description:   String,
    version:       Option[String],
    status:        PluginInstallStatus,
    pluginVersion: Version,
    errors:        List[PluginError],
    license:       Option[PluginLicense]
)
```
(`rudder-core/.../plugins/PluginData.scala`)

- `final case class` for domain entities/value objects.
- Use precise field types, not primitives: `PluginId` not `String`, `Version` not
  `String`, `Option[...]` for genuinely optional data.
- Prefer `final case class` and immutability everywhere; no `var`.

## Wrap identifiers and scalars

Don't pass bare `String`/`Int` for domain concepts. Wrap them:

```scala
final case class Licensee(value: String)   extends AnyVal   // existing common form
opaque type NodeName = String                               // modern newtype, zero overhead
```

This makes signatures self-documenting and prevents mixing up a `RuleId` with a
`DirectiveId`. See [`001`](001-scala3-idioms.md) for value-class vs opaque-type choice.

## ADTs for closed sets

```scala
sealed trait PluginInstallStatus
object PluginInstallStatus {
  case object Enabled     extends PluginInstallStatus
  case object Disabled    extends PluginInstallStatus
  case object Uninstalled extends PluginInstallStatus

  // decision logic lives in the companion, not on the cases
  def from(pluginType: PluginType, installed: Boolean, enabled: Boolean, reason: StatusDisabledReason): PluginInstallStatus = ...
}
```

When you need name/value, lookup, or `values`, use **enumeratum** (the project
standard — *not* the native Scala 3 `enum` keyword); see
[`401`](401-json-zio-json.md#enums). Keep the cases dumb.

## Where the logic goes

| Concern | Where |
|---|---|
| Build/validate from raw input | `def parse` in companion ([`201`](201-parse-dont-validate.md)) |
| JSON in/out | codecs in companion ([`401`](401-json-zio-json.md)) |
| Map to another representation | chimney `Transformer` ([`402`](402-chimney-transformers.md)) |
| Produce a modified copy | quicklens ([`403`](403-quicklens-updates.md)) |
| Derived/display helpers | `def` in companion or `extension`, kept minimal |

## Don't

- Don't put I/O, repository calls, or effectful logic on the case class.
- Don't add convenience methods that just re-expose fields.
- Don't accept `String` where a parsed domain type belongs.
