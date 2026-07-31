# 001 — Scala 3 idioms

How we write idiomatic Scala 3 so business code stays small and import-light.

## Logic lives in companion objects and extensions, not in the data

A business `case class` should be *dumb* (ideally zero methods). Put parsing,
serialization, conversion and helpers in the **companion object** or in `extension`
methods. This keeps the data type clean and aligns with Scala 3 practice.

This is about *business* objects; a technical type may hold the one canonical rendering
of itself — see [`400`](400-domain-case-classes.md#technical-types) for where the line
is.

Real example — `TenantAccess` (a tenant id + a permission) is a dumb 2-field case
class; its companion holds the `parse`, `serialize` and codec
(`rudder-core/.../tenants/Tenants.scala`):

```scala
final case class TenantAccess(id: TenantId, grant: TenantPermission)

object TenantAccess {
  def apply(id: TenantId): TenantAccess = TenantAccess(id, TenantPermission.ReadWrite)

  // "tenantId:permission", permission optional (absent => ReadWrite)
  def parse(s: String): Option[TenantAccess] = {
    val (rawId, optToken) = s.split(":", 2) match {
      case Array(id)       => (id, None)
      case Array(id, perm) => (id, Some(perm))
      case _               => (s, None)
    }
    for {
      id    <- TenantId.parse(rawId)
      grant <- TenantPermission.parseToken(optToken)
    } yield TenantAccess(id, grant)
  }

  extension (t: TenantAccess) {
    def serialize: String =
      if (t.grant == TenantPermission.ReadWrite) t.id.value else s"${t.id.value}:${t.grant.entryName}"
  }

  given JsonCodec[TenantAccess] =
    JsonCodec.string.transformOrFail(s => parse(s).toRight(s"Invalid tenant access: '$s'"), _.serialize)
}
```

Use a multi-field `case class` like this when the concept genuinely *is* several
values. When a concept is a **single** underlying value, prefer an `opaque type`
instead (see below).

## Minimize implicit imports

We want the **general case to need no `import` for implicits/givens**. Achieve that
by defining `given`/`implicit val` instances in the **companion object** of the type
they target — they're then found by implicit scope without an import. Reserve
explicit imports for genuinely cross-cutting helpers.

## Import syntax: no grouped `{a, b, c}`

Never group several names from one package on a single line with braces. Use **one
import per line**, and when you take **strictly more than two** names from the same
package, use a **star import** instead.

```scala
// NO — grouped braces
import com.normation.errors.{IOResult, PureResult, RudderError}

// yes — 1 or 2 names: one import per line
import com.normation.errors.IOResult
import com.normation.errors.PureResult

// yes — 3+ names from the same package: star import
import com.normation.errors.*
```

scalafmt (`rewrite.imports.expand`) already **expands grouped braces to one-per-line**,
so it enforces the "no `{a, b, c}`" half automatically. Choosing a **star import once you
have 3+** names is *our* convention on top — scalafmt won't create it for you, but it
leaves a `.*` import as-is. So: write the star yourself when it's 3 or more.

This is one case of a general split — some of our style is enforced by scalafmt and some
is not — and the rest of it (braces over significant indentation, `${value}`
interpolation, what `spotless:apply` will rewrite for you) is in
[`800`](800-build-and-formatting.md#style-what-the-formatter-decides-and-what-you-must-write).

## `extension` methods

Use `extension` to add behaviour to a type without polluting the type itself, e.g.
the `AcceptationDateTime` opaque type (`rudder-core/.../policies/ActiveTechnique.scala`):

```scala
opaque type AcceptationDateTime = Map[TechniqueVersion, Instant]
object AcceptationDateTime {
  extension (self: AcceptationDateTime) {
    def withNewVersions(vs: Map[TechniqueVersion, Instant]): AcceptationDateTime = self ++ vs
    def versions: Map[TechniqueVersion, Instant] = self
  }
  def empty: AcceptationDateTime = Map()
}
```

## Type-directed development: no stringly-typed code

This is
[principle 6 — name domain concepts as types, and propose the zero-cost form](../rudder-principles/SKILL.md#6-name-domain-concepts-as-types--and-propose-the-zero-cost-form)
in Scala. Concretely: a new concept gets a new type, with its `parse`/`serialize`/codec in
the companion ([`201`](201-parse-dont-validate.md)) — we do **not** thread bare
`String`/`Int`/`Boolean`/`Map[String, String]` around to stand for domain notions. If
you're about to write `def f(x: String, y: String)`, stop and name them.

**Technical data too**, not only the domain — instrumentation, cache keys, internal
messages. That is where the rule gets skipped, and the two triggers from principle 6
apply verbatim:

- a tuple passed through more than one function, or read as `_._1`/`_._3`, becomes a
  `final case class` with named fields;
- a discriminator a caller assembles by concatenation becomes a parameterised ADT case
  ([ADT shapes](#adt-shapes)).

```scala
// no: two positional strings threaded through four methods, and a tuple in the queue
def record(phase: String, detail: String, durationMs: Long): Unit
private val done = new ConcurrentLinkedQueue[(String, String, Long)]()

// yes: the model is named first, and the queue says what it holds
final case class BootStep(phase: BootPhase, detail: String)
final case class TimedStep(step: BootStep, duration: Duration)
def record(step: BootStep, duration: Duration): Unit
private val done = new ConcurrentLinkedQueue[TimedStep]()
```

Declare those types **above** the code that uses them: the reader meets the model, then
the procedure.

### Choosing the wrapper: `opaque type` > value class > raw

For a concept backed by a **single** underlying value, in order of preference:

1. **`opaque type`** — the preferred form for **new** wrappers, and **the zero-cost
   abstraction to propose** under principle 6: a true newtype with *no runtime
   allocation*, that still presents a typed API and keeps invariants true. Expose
   construction/behaviour via the companion + `extension` (as `AcceptationDateTime`
   above). Since it costs nothing at runtime, the hot-path objection
   ([principle 7](../rudder-principles/SKILL.md#7-weigh-hot-path-cost--while-planning-not-after))
   does not apply to it — say so when you propose it.
   ```scala
   opaque type TenantId = String
   object TenantId {
     def parse(s: String): Option[TenantId] = Option.when(s.nonEmpty && s.forall(_.isLetterOrDigit))(s)
     extension (t: TenantId) def value: String = t
   }
   ```
2. **Value class** — `case class X(value: T) extends AnyVal`. The older, still-common
   form (e.g. `PluginId`, `Licensee`, `SoftwareId`). Fine to leave in place and to
   match in existing files, but for new single-value wrappers prefer an `opaque type`
   — e.g. `PluginId` (just a `String` with a regex invariant) would today be better as
   an `opaque type`.
3. **Raw `String`/`Int`** — never, for a domain concept.

For a concept that is genuinely **two or more** values, use a `case class`
(e.g. `TenantAccess` above) — not an `opaque type` over a tuple.

## ADTs: choosing the shape of a closed set {#adt-shapes}

Sum types for closed sets are
[principle 1](../rudder-principles/SKILL.md#1-the-data-model-is-the-design) in Scala:
model them with `sealed trait` + `case object`/`case class`. Keep the cases dumb; put
logic in the companion (e.g. `PluginInstallStatus.from(...)` decides the status from
inputs in one place).

Three shapes are in use and the choice is not a matter of taste. Answer in order:

### 1. Does any case carry data?

Then: **`sealed trait Base(val name: String)`**, each case supplying its own name.
enumeratum is not an option here — `findValues` only finds `case object`s, so `values`
and lookup-by-name cannot exist once a case has parameters.

```scala
sealed trait BootPhase(val name: String)
object BootPhase {
  case object Git                    extends BootPhase("git-repositories")
  case class  Plugin(plugin: String) extends BootPhase("plugins:" + plugin)
}
```

The canonical name is a **constructor parameter** and the variable part is a **case
parameter** — never something a caller assembles. Write `BootPhase.Plugin(name)`, not
`"plugins:" + name` at the call site, and not a bare `String` phase. A case rendering
*its own* name this way is not "logic in the case": decisions and business rules still
belong in the companion.

If such a type is also serialized, put the matching `parse` in the companion and test
the round trip — you are hand-writing what enumeratum would otherwise have given you.

### 2. Is a value of the type ever (de)serialized?

JSON, database, LDAP, REST API, configuration file, event log — anything crossing the
process boundary, in either direction. Then **enumeratum is mandatory** (see
[`401`](401-json-zio-json.md#enums), [`404`](404-serialization-contracts.md)).

The `entryName` is a wire contract: it is written explicitly, reviewed, and stays stable
when the Scala case is renamed. A native `enum` takes its name from the case identifier,
so a rename silently changes what is stored on disk and answered to clients.

### 3. Otherwise: native `enum`

Purely internal, never leaves the JVM, no `values`/lookup needed — then the native
Scala 3 `enum` is fine, and is the shortest thing that works.

```scala
// the outcome of one internal decision, consumed by one `match`, stored nowhere
enum BootWatchdogVerdict {
  case Progressing
  case SameStep
  case Stalled
}
```

## Collections: prefer `Chunk`

Prefer zio's **`Chunk`** (and `NonEmptyChunk`) for sequences — it's a more
memory-compact, array-backed structure. `List` is historical and fine for small or
recursively-built data; `cats.data.NonEmptyList` is used where a non-empty list is
required (e.g. accumulated errors, see [`301`](301-error-model.md)). Match the
surrounding code when editing.

## `given`/`using` over `implicit`

Use Scala 3 `given`/`using` for **new** code — even when the surrounding class still
uses `implicit`. Don't mirror the old style for consistency's sake.

Moreover, when you touch a file, **rewrite the `implicit`s you see into `given`/`using`
whenever the change is strictly equivalent** (a plain `implicit val`/`implicit def`/
`using`-parameter with no behavioural difference). Leave `implicit` in place only when
the rewrite isn't a no-op — e.g. `implicit class` extension wrappers (use an
[`extension`](#extension-methods) instead, which is a different shape, not a mechanical
swap) or implicit conversions, where semantics or resolution could shift. Keep such
rewrites within the [up-merge](../rudder-principles/SKILL.md#working-in-a-long-lived-codebase)
budget: don't churn an entire legacy file just to convert implicits.

## Style reminders

- Don't add `copy` helpers; use [quicklens](403-quicklens-updates.md) for updates.
- Don't write companion methods that merely re-expose a field — let the case class
  field stand on its own.
- No `var` in new code (see the immutability carve-out in
  [`000`](000-coding-philosophy.md) for the legacy Lift / perf-local exceptions).
- **Parameterless `def`s keep their `()`.** Declare and call a no-argument *method* with
  empty parens — `def reload(): IOResult[Unit]` / `repo.reload()` — so it is visibly a
  method, not a `val`. Reserve no-parens access (`def value: String`) for the
  field-like, side-effect-free accessor case (e.g. on an `extension`/wrapper). The `()`
  signals "this is a computation/effect", and lets a `val` later replace a pure
  parameterless `def` without churning call sites.
