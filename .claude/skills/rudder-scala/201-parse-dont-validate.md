# 201 — Parse, don't validate: the Scala mechanics

> The rule itself is
> [principle 2 — parse at the edge; the core is pure structure](../rudder-principles/SKILL.md#2-parse-at-the-edge-the-core-is-pure-structure).
> Read it there. This file is **how we express it in Scala**.

Any value crossing an I/O boundary (HTTP API, CLI args, config files, git content, LDAP,
DB) is parsed into a typed domain object at that boundary. The domain then works only with
values *correct by construction* — we never pass an unchecked `String` inward and re-check
it later.

## The pattern

A `parse` function turns raw input into either a typed value or an error message, and
lives in the **companion** of the target type:

```scala
case class PluginId(value: String) extends AnyVal
object PluginId {
  private val pluginIdRegex = """^(\p{Alnum}[\p{Alnum}-_]*)$""".r
  def parse(s: String): Either[String, PluginId] = s match {
    case pluginIdRegex(_) => Right(PluginId(s))
    case _                => Left(s"Invalid plugin ID: '$s'. ...")
  }
}
```

For values that round-trip (parse ⇆ serialize), pair `parse` with a `serialize`:

```scala
case class CampaignId(value: String, rev: Revision = GitVersion.DEFAULT_REV) {
  def serialize: String = ...
}
object CampaignId {
  def parse(s: String): Either[String, CampaignId] = GitVersion.parseUidRev(s).map { case (id, rev) => CampaignId(id, rev) }
  implicit val codec: JsonCodec[CampaignId] = JsonCodec.string.transformOrFail(CampaignId.parse, _.serialize)
}
```

## Scala consequences

- **Make illegal states unrepresentable.** Prefer a wrapper type with a private/guarded
  constructor + `parse` over a bare primitive that "should" be valid. For a single-value
  concept, an `opaque type` is the preferred (zero-cost) wrapper — see
  [`001`](001-scala3-idioms.md) and
  [principle 6](../rudder-principles/SKILL.md#6-name-domain-concepts-as-types--and-propose-the-zero-cost-form).
- **Parsing returns errors, it doesn't throw.** Use `Either[String, A]` (string = message)
  or `PureResult[A]`/`IOResult[A]` (`RudderError`) — see [`301`](301-error-model.md).
  zio-json codecs use `mapOrFail`/`transformOrFail`, so a bad payload becomes a decode
  error rather than a half-built object ([`401`](401-json-zio-json.md)).
- **Parse once, at the edge.** Deep in the domain, assume validity — don't re-validate. A
  defensive re-check inside a domain function is a signal that parsing happened too late
  (see [principle 3](../rudder-principles/SKILL.md#3-fix-the-root-cause-not-the-visible-symptom)).
- **Errors are accumulated** when parsing collections of inputs, so the user sees all
  problems at once (`Accumulated`, see [`301`](301-error-model.md)).
- **`null` is part of this** — handled at the edge, visibly, and distinguished from a
  genuine domain optional: see [`000`](000-coding-philosophy.md).
