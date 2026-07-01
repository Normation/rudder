# 402 — Mapping with chimney

When converting between two representations of the same data (domain ⇆ API DTO,
domain ⇆ storage, wrapper ⇆ underlying), use **chimney** instead of hand-written
field-by-field copying. Less boilerplate, and the compiler checks completeness.

The main use is mapping a business object to/from a **DTO** so the serialized contract
can evolve independently of the domain model — see [`404`](404-serialization-contracts.md).

## Inline transformation

```scala
import io.scalaland.chimney.dsl.*

val dto: PluginDto = plugin.into[PluginDto]
  .withFieldComputed(_.statusLabel, _.status.toString)
  .transform
```

Use `transformInto[T]` for the trivial 1:1 case, `.into[T]....transform` when a few
fields need help (`withFieldConst`, `withFieldComputed`, `withFieldRenamed`).

## Reusable `Transformer` instances

Define a `Transformer` in the companion so it's found without imports and reused
(including by nested derivations and by [zio-json](401-json-zio-json.md) where useful):

```scala
object PluginId {
  implicit val transformer: Transformer[PluginId, String] = Transformer.derive[PluginId, String]
}
object MaxNodes {
  implicit val transformer: Transformer[MaxNodes, Option[Int]] = _.value
}
```
(`rudder-core/.../plugins/PluginData.scala`)

## Fallible transformations

When a mapping can fail, use chimney's partial transformers and bridge the result into
our effect types with the helper from `com.normation.errors`:

```scala
result.toIO            // chimney partial.Result[A] => IOResult[A]
result.toPureResult    // => PureResult[A]
```
(see [`302`](302-bridging-toio-runnow.md)). This keeps failures on the `RudderError`
channel ([`301`](301-error-model.md)).

## Guidance

- Reach for chimney whenever you'd otherwise write repetitive `X(a = src.a, b = src.b, …)`.
- Put shared `Transformer`s in companions; keep one-off mappings inline at the call site.
- Don't smuggle business logic into transformers — they map shapes, not enforce rules.
  Validation belongs in `parse` ([`201`](201-parse-dont-validate.md)).
- chimney is an approved dependency; don't add a second mapping library.
