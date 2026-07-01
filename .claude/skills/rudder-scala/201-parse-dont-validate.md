# 201 — Parse, don't validate

Any value crossing an I/O boundary (HTTP API, CLI args, config files, git content,
LDAP, DB) is **parsed into a typed domain object at the boundary**. The domain then
works only with values that are *correct by construction* — we never pass an unchecked
`String` inward and re-check it later.

## The pattern

A `parse` function turns raw input into either a typed value or an error message, and
lives in the companion of the target type:

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

## Consequences

- **Make illegal states unrepresentable.** Prefer a wrapper type with a private/
  guarded constructor + `parse` over a bare primitive that "should" be valid.
- **Parsing returns errors, it doesn't throw.** Use `Either[String, A]` (string =
  message) or `PureResult[A]`/`IOResult[A]` (`RudderError`) — see
  [`301`](301-error-model.md). zio-json codecs use `mapOrFail`/`transformOrFail` so a
  bad payload becomes a decode error, not a half-built object.
- **Parse once, at the edge.** Deep in the domain, assume validity — don't re-validate.
- **Errors are accumulated** when parsing collections of inputs, so the user sees all
  problems at once (`Accumulated`, see [`301`](301-error-model.md)).
