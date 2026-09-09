# 600 — Security in depth

Security is a **design constraint integrated from the start** — in the API/arch design,
the implementation, and the system integration — not a layer bolted on at the end. When
designing or changing anything, ask where the trust boundary is and what enforces it.

## Principles

- **Defense in depth.** Don't rely on a single check. Enforce authorization/scoping at
  each layer that can: API, service, repository, storage query. A bug in one layer
  should not silently grant access.
- **Parse at the boundary.** Untrusted input becomes typed domain objects immediately
  (see [`201`](201-parse-dont-validate.md)); the domain never handles raw,
  unvalidated input. This is itself a security control.
- **Least privilege / explicit scope.** Operations that must be restricted carry their
  security context in the signature rather than reading ambient/global state — e.g.
  repository methods take `(implicit qc: QueryContext)` so the caller's scope is
  always present and enforced at query time
  (see `PropertiesRepository`, [`200`](200-persistence-repositories.md)). Splitting
  read/write repositories (`Ro*`/`Wo*`) lets you grant only the access a caller needs
  (see [`200`](200-persistence-repositories.md)).
- **Authorization is declared on every API endpoint** (`authz: List[AuthorizationType]`),
  enforced by the framework, not re-checked by hand in handlers (see
  [`103`](103-rest-api-and-endpoints.md)).

## Tenants / multi-tenancy

Some domain objects carry a `SecurityTag` (tenants) and there's a `HasSecurityTag`
type class to read/update it (see `HasSecurityTag[ActiveTechnique]` in
`rudder-core/.../policies/ActiveTechnique.scala`). When adding tenant-scoped data:

- model the scope on the object (`security: Option[SecurityTag]`),
- filter by scope in the repository/persistence layer (the right place to enforce —
  e.g. tenant-filtering proxy repositories), not only in the UI.

### Node-id sets: always resolve against QueryContext-filtered facts

Group `serverList`s (and anything derived from them: rule targets, campaign targets…)
are **not** tenant-filtered and can also contain deleted nodes. Any node-id set
returned to a user must therefore be **intersected with the facts visible in the
caller's `QueryContext`**:

- To resolve `RuleTarget`s into node ids, use `RuleTarget.getNodeIdsChunk` /
  `RoNodeGroupRepository.getNodeIdsChunk` / `FullNodeGroupCategory.getNodeIds`. They
  take a `NodeAndServerIds` (opaque pair of visible node ids + policy server ids,
  `domain.nodes`): get it from `nodeFactRepository.getNodeAndServerIds()(qc)` (cheap,
  policy servers are cached in the repository) or build it with
  `NodeAndServerIds.fromFacts(snapshot)` from a generation snapshot — the helper
  restricts its result to `.nodeIds`, so the qc does the tenant filtering.
  **Get it once per request, before any loop on rules**, and **don't hand-roll target
  resolution** (a `Set`-based duplicate of this helper existed until 9.1 and leaked
  group members across tenants because it skipped that intersection; a
  `Map[NodeId, Boolean]`-shaped API existed too and made every caller rebuild a full
  map per rule — that's why there is only one implementation now).
- A cache or repository populated under `QueryContext.systemQC` (e.g. score caches)
  serves data for **all** tenants: its *read* methods must take `(implicit qc:
  QueryContext)` and filter to qc-visible nodes before returning — in the
  repository, not in each API handler (see `AggregatedBenchmarkScoreRepository` in
  the security-benchmarks plugin).
- Symmetrically, **maintenance/write paths** (cleaning caches, computing the node set
  a policy applies to) must use `QueryContext.systemQC`, not the actor's qc — or a
  tenant-limited actor would silently drop data of nodes they can't see.

## Lift snippets: get `QueryContext` safely (ADR 28452)

A Lift snippet that needs the authenticated user / their tenants must obtain its
`QueryContext` from a **guaranteed-present** source — **not** by reading the global
`CurrentUser#queryContext`, which can spuriously return no user under ZIO and is a
security/auditability hole.

Extend **`SecureDispatchSnippet`**: it injects the `QueryContext` as a context function
and renders nothing (logging a warning) when no authenticated context exists — failing
closed.

```scala
trait SecureDispatchSnippet extends DispatchSnippet {
  def secureDispatch: QueryContext ?=> DispatchIt   // QueryContext guaranteed
  override def dispatch: DispatchIt =
    CurrentUser.queryContext.withQCOr(loggedInsecureDispatch)(secureDispatch)
}
```

Migrate snippets that need a `QueryContext` to this trait; don't reach for
`CurrentUser#queryContext` elsewhere.

## Path traversal: always enforce a root scope

Path traversal (a.k.a. directory traversal / "zip slip") is **ubiquitous and almost
always catastrophic** — a `../../etc/...` or an absolute path lets an attacker read or
overwrite files anywhere. Treat it as a default hazard: **any time you build a file path
from input that isn't fully under your control** (user input, API params, names from an
archive, git, LDAP, config…), pin a **root directory** and verify the resolved path
**cannot escape** it.

Don't hand-roll the check — use the helpers:

- `com.normation.utils.FileUtils.sanitizePath(baseFolder, subpath)` /
  `sanitizePath(baseFolder, path: List[String])` → `IOResult[File]`, fails if the
  resolved path escapes `baseFolder`. Use it whenever you turn an untrusted name into a
  file under a known root.
- `com.normation.rudder.git.ZipUtils.checkForZipSlip(entry)` → `IOResult[Unit]`, call it
  for **every** entry before extracting an archive.

Rules:

- Resolve and validate **before** opening/creating the file, and fail closed
  (`SecurityError`/error channel) on any escape — never "best-effort clean and continue".
- A bare `String`/`File` path from outside is untrusted input: parse it through a
  sanitizer at the boundary (this is parse-don't-validate for paths, see
  [`201`](201-parse-dont-validate.md)).
- Reject absolute paths and symlink escapes too, not just `..` — the helpers handle the
  resolution for you, so go through them.

## Security errors

Failures caused by a security concern use the `SecurityError` marker (see
[`301`](301-error-model.md)); they render as `SecurityError: …`. Use it so security
denials are distinguishable from ordinary inconsistencies — and **fail closed**: on
doubt, deny rather than allow.

## More security topics

- **Web & output safety** — XML parsing (`XmlSafe`/XXE), Lift `JsRaw`/XSS, CSRF, CSP,
  sessions: [`601`](601-web-and-output-security.md).
- **Authentication & authorization** — Spring auth vs Rudder authz, password/token
  crypto, RBAC/ACL `AuthorizationType`: [`602`](602-authentication-and-authorization.md).

## Checklist when changing security-relevant code

- Is untrusted input parsed into typed values before use (incl. XML via `XmlSafe`, paths
  via `sanitizePath`, see [`601`](601-web-and-output-security.md))?
- Is authorization checked at *every* layer that can enforce it, not just the UI
  (endpoint `authz`, `checkRights`, see [`602`](602-authentication-and-authorization.md))?
- Does data access carry and respect the caller's scope (`QueryContext`/tenants)?
- Is untrusted data escaped on output (no unjustified `JsRaw`)?
- Do denials fail closed and surface as `SecurityError`?
- Did you avoid logging secrets (passwords, hashes, tokens) / leaking internal detail in
  error `msg`s shown to users?
