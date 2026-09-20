# 400 — Domain case classes

Business objects are **plain, dumb `case class`es** — ideally with *no* methods. All
logic (parsing, serialization, conversion, derived views) goes in the **companion
object** or in `extension` methods (see [`001`](001-scala3-idioms.md)).

## Business object or technical type? {#technical-types}

That rule is about **business objects**: the entities and value objects of the domain.
It is not a blanket ban on methods.

A **technical type** — instrumentation, a cache key, an internal protocol message, a
piece of internal state; a type that exists to make *our* code readable rather than to
model the business — may carry the **single canonical rendering of itself** as a `def`
on the type. That is the point of putting it there: there is then exactly one spelling
of it, instead of the same `s"..."` repeated at every call site.

```scala
final case class BootStep(phase: BootPhase, detail: String) {
  def display: String = s"[${phase.name}] '${detail}'"
}
```
(`rudder-web/.../bootstrap/liftweb/BootProgress.scala`; the same idea puts the canonical
name of an ADT case on the case, see [`001`](001-scala3-idioms.md#adt-shapes))

**"Canonical" is the test: exactly one.** As soon as there are two renderings — a short
and a long form, a log form and a JSON form — none of them is canonical: they go to the
companion or an `extension`, and anything serialized follows
[`401`](401-json-zio-json.md) / [`404`](404-serialization-contracts.md).

What never goes on the type, business or technical: I/O, repository calls, effects, and
business rules.

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

Don't pass bare `String`/`Int` for domain concepts
([principle 6](../rudder-principles/SKILL.md#6-name-domain-concepts-as-types--and-propose-the-zero-cost-form)).
Wrap them:

```scala
final case class Licensee(value: String)   extends AnyVal   // existing common form
opaque type NodeName = String                               // modern newtype, zero overhead
```

See [`001`](001-scala3-idioms.md) for the value-class vs opaque-type choice — for **new**
single-value wrappers the `opaque type` is preferred, and being zero-cost it is the form to
propose even on a hot path.

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

Which shape — enumeratum, a `sealed trait Base(val name: String)` with parameterised
cases, or a native `enum` — is decided by the oracle in
[`001`](001-scala3-idioms.md#adt-shapes). Short version: **enumeratum as soon as the
value is (de)serialized**. Keep the cases dumb either way.

## Where the logic goes

| Concern | Where |
|---|---|
| Build/validate from raw input | `def parse` in companion ([`201`](201-parse-dont-validate.md)) |
| JSON in/out | codecs in companion ([`401`](401-json-zio-json.md)) |
| Map to another representation | chimney `Transformer` ([`402`](402-chimney-transformers.md)) |
| Produce a modified copy | quicklens ([`403`](403-quicklens-updates.md)) |
| Derived/display helpers | `def` in companion or `extension`, kept minimal — except the *one* canonical rendering of a technical type ([above](#technical-types)) |

## Don't

- Don't put I/O, repository calls, or effectful logic on the case class.
- Don't add convenience methods that just re-expose fields.
- Don't accept `String` where a parsed domain type belongs.
