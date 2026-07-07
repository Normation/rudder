# Rudder Rust — security-sensitive code

Rudder is a security sensitive project, we mainly deal with administrative access
on systems. We must take the security topics very seriously.

Read this before touching transport/TLS, signature or hash verification, anything that parses
input from a node or user, path handling, secrets, or privileged command execution. It states
the **principles** we hold to; the parenthetical file pointers are just where each one lives
today, not the point. A few intentional-looking "dangerous" calls are correct and a couple of
known gaps exist — don't undo the former or silently widen the latter. When in doubt, **fail
closed**.

## Trust model (why the rest of this matters)

Data flows `agent → relay → central server`, and relays proxy for downstream nodes. Key
consequences:

- **The agent and module types run privileged (root) on managed nodes.** A bug in a module
  type is a local root issue on every node.
- **Anything crossing a trust boundary is attacker-influenced:** node runlogs/reports, shared
  files and their metadata, IDs in URLs/paths, plugin packages, technique YAML, templates,
  config/augeas content. Treat all of it as untrusted and parse it at the boundary.
- Identity is established by **pinned per-node certificates by default** (standard CA/PKI
  validation is the opt-in alternative — see *Transport*). This pinning-first model explains
  most of the TLS/signature design below.

**Priorities: integrity ≫ availability.** When the two trade off, protect
correctness/integrity even at the cost of availability. Denial of service is generally **not**
our main concern — a module that refuses to act, an agent run that aborts, or an input we
reject is an acceptable outcome; **doing the wrong privileged thing is not.** This is exactly
why we *fail closed*: on any doubt, stop rather than proceed. (It also means an error/abort on
bad input, while not ideal, is far preferable to silently mis-handling it.)

Because the agent runs as root, three agent-side attack classes drive everything here:

1. **Remote takeover over the network** — auth bypass / RCE through a Rudder network service
   (policy update, `cf-serverd`, the future `agentd`). Countered by transport pinning, message
   signatures, and package signatures.
2. **Local privilege escalation** — an unprivileged local user hijacking the agent's root via
   file/dir permissions or TOCTOU. See *Local privilege escalation & TOCTOU*.
3. **Injection through server-supplied configuration data** — see *Privileged execution*.

## Authenticate positively — require success, not absence of failure

Every auth / verification decision must be gated on an **explicit, affirmative success
signal**. Reach "authenticated / verified / trusted" only after actively confirming it —
**never infer it from "no error was raised"**, an unset flag, a default, or a fallthrough.

- Default state is **denied**; only a positive check flips it to allowed. If a step is skipped,
  errors, panics, or a branch is unreachable, you must **stay denied** (fail closed).
- Prefer a **typed proof of success** you can only obtain by passing the check — match on an
  explicit `VerificationSuccess::ValidSignatureAndHash` (as `.rpkg` install does), or return a
  `Verified<T>` value — over `if !failed { proceed }`. This is the enums-over-booleans /
  parse-don't-validate idea applied to authz.
- Red patterns: an `Err` that's logged then execution continues anyway; a verify fn returning
  `()` while the caller assumes success; a boolean that defaults to `true`; an early
  `return Ok(())` that bypasses the check on some path.

## Transport: two certificate-validation modes

There are **two supported ways to validate the peer's certificate**, and code must handle both:

1. **Certificate pinning (the default).** Trust comes from an **exact pinned certificate per
   peer**, so the usual PKI reasoning is *replaced*, not weakened. Trust **only** the pinned
   cert (`tls_certs_only`); rebuild the client when a peer's cert rotates. Under pinning,
   **hostname matching is irrelevant** — matching the exact cert is stronger — so the
   `danger_accept_invalid_hostnames(true)` call is correct and **load-bearing**; don't "fix" or
   remove it.
2. **Standard CA/PKI validation.** Verify the cert against a trust store and hostname the normal
   way, for peers that present a CA-issued certificate. This is the opt-in alternative to
   pinning, not a relaxation of it — keep full verification (chain **and** hostname) on.

In both modes the client is locked down: HTTPS-only, modern minimum TLS version (TLS 1.3
today). Which mode applies is a configured decision — **pinning is the default**; don't silently
downgrade one to the other.

A **third, fully-unverified path** (`no_verify()` / `danger_accept_invalid_certs`) exists **only**
for deprecated legacy compatibility — it is neither of the two real modes; never route new
functionality through it.

(Today: `relayd/src/http_client.rs`.)

## Message authentication: verify against the known pinned cert

Signed messages (agent runlogs are S/MIME/PKCS7-signed) are verified against the **server-side
known cert** for that node, not any embedded chain. The verify flags reflect the pinning model:
ignore certs carried in the message (`NOINTERN`) and skip chain building (`NOVERIFY`, empty
trust store) — there is no meaningful CA chain. "No chain verification" here is **by design**.
Don't accept weaker signature hashes to make something verify. (Today: `relayd/src/input.rs`.)

## Hashing: strong algorithms only

In general accept **only SHA-256 / SHA-512**. MD5 and SHA-1 are intentionally absent — don't reintroduce
them. Reject unknown algorithm names at parse time rather than defaulting.

## Verify signature *and* hash on anything installed

Downloaded artifacts (plugin `.rpkg` packages) are verified for **both** signature and hash
before use — detached OpenPGP (pure-Rust sequoia, with a `gpgv` fallback). Keep verification
**on the critical path** whenever you add an install/update route; a new code path that installs
without verifying is a supply-chain hole. (Known gap: a *local* install may bypass the check —
confirm before treating local artifacts as trusted.) (Today: `rudder-package/src/signature/`.)

## Path traversal: allowlist + reject dot-segments

Any time untrusted input becomes a **path segment** (HTTP params, archive entry names, IDs from
git/LDAP/config), treat traversal as the default hazard:

- **Allowlist the charset *and* reject dot-segments explicitly.** A charset regex that permits
  `.` still lets `.`/`..` through — the explicit `!= "."` / `!= ".."` check is what actually
  stops traversal. Don't rely on a regex alone.
- Pin a known **root** and verify the resolved path can't escape it. Reject **absolute paths
  and symlink escapes**, not just `..`. Validate **before** opening/creating, and don't
  `canonicalize()`-then-trust.

(Reference implementation: `SharedFile::new`, `relayd/src/data/shared_file.rs`.)

## Local privilege escalation & TOCTOU (agent/module FS access)

The agent runs as root, so every file or directory it opens, walks, or executes is a
privilege-escalation surface: if an unprivileged local user can influence a path — plant a
symlink, win a create/replace race, or own a directory the agent traverses — they redirect the
agent's root-level action onto a target of their choosing (classic TOCTOU). Module FS access is
deliberately **naïve today**; hardening it is a known, in-progress goal, and the bar is *match
CFEngine on Linux, keep exploring Windows*. When writing code that touches the filesystem as
root:

- **Check ownership/permissions at open time, and open safely** — don't assume a path resolved
  a moment ago still points where you think (the job of CFEngine's `safe_open`/`CheckLinkSecurity`).
- **Don't follow untrusted symlinks:** use no-follow semantics (`O_NOFOLLOW`-style) and verify
  each component, rather than a single `canonicalize()`-then-trust.
- Prefer **confinement** (operate under a pinned root dir; e.g. augeas's optional `root`) over
  acting on live system paths.
- The agent is also exposed through **local inputs its dependencies parse** (e.g. a CVE in a
  parsing lib) — keep those deps current (see Supply chain) and fuzz parsers over local data.

## Decompression: cap output, guard zip-slip

Inflating attacker-controlled `.gz`/`.zip` into an unbounded buffer (`read_to_end`) is
**decompression-bomb** exposure — bound the inflated size. If you extract archive entries that
**write** files, you must also guard **zip-slip**: validate/strip entry paths and reject `..`
(reading a single known entry avoids this; writing many does not). (Today: `relayd/src/input.rs`.)

## SQL: parameterize, never build query strings

Use the parameterized query DSL (diesel: `insert_into`/`.filter`/`.execute`). **Never `format!`
user data into SQL.** (Today: `relayd/src/output/database.rs`).

## Secrets: keep them wrapped

Type passwords/tokens as `SecretString`/`Secret<_>` (via `secrecy`) and keep them wrapped
through the call chain:

- Call `.expose_secret()` **only** at the point of use (DB connect string, HTTP basic-auth).
- **Never** log, `Debug`-print, or put a secret in an error message — `secrecy`'s `Debug`
  redacts, so don't defeat it by exposing first.
- Source defaults from config (`serde_inline_default`), not literals scattered in code.

## Privileged execution / command injection

The agent runs as root, so anything it executes is an injection sink.

- When shelling out, use `Command::new(prog).arg(x)` with **separate args** — never build a
  `sh -c "...{input}..."` string. Validate/escape any value from technique parameters or node
  data before it reaches a command, path, or config mutation. Prefer confinement (e.g. augeas's
  `root`) over acting on the live system.
- **Server-supplied config data is a trust boundary, even though the agent mostly treats it as
  trusted.** The agent will run whatever the server sends — so any value that becomes a command,
  package name, username, or path is a sink. Much of that data **originates externally** (node
  properties synced from an API, an inventory-reported login, a package name) and its route from
  server input to agent action is hard to trace. There's no hard risk line yet; the working
  rule is: per module, know which inputs reach a privileged operation and keep them
  **parameterised/validated, not interpolated** (e.g. usernames, package names).

## Untrusted-input parsers & fuzzing

Parsers (nom, serde over node/user data) are the highest-value attack surface. **When you add
or change a parser over untrusted bytes, add/extend a `cargo-fuzz` target** and make malformed
input return an error, not panic — a reachable `panic!`/`unwrap` usually means input that
wasn't actually validated (an integrity risk, not just a crash). (Harnesses: `relayd/fuzz`,
`module-types/template/fuzz`.)

## `unsafe`

New `unsafe` is exceptional — almost all of ours is FFI. There's no `forbid(unsafe_code)`, but
when it's unavoidable: confine it to the smallest scope, write a `// SAFETY:` comment stating
the invariant being upheld, and expose a safe wrapper.

## Supply chain

`cargo deny check` gates advisories/licenses/sources/bans (workflow in the main skill).
Security-specific: a `RUSTSEC-*` advisory may only be added to the `ignore` list **with a
comment explaining why the risk is acceptable**. Prefer **upgrading out** of an advisory over
ignoring it.

## Reviewer red flags (quick scan)

- An auth/verify decision treated as success because no error was returned (absence of failure),
  rather than gated on an explicit positive result.
- `danger_accept_invalid_certs` / `no_verify` reached from a non-legacy code path.
- Weakening message/signature verification (accepting weaker hashes, loosening the verify flags).
- New MD5/SHA-1 usage.
- Comparing a secret / MAC / token / hash with `==` instead of a **constant-time** compare
  (`subtle`/`ring`) — timing side channel.
- A token / key / nonce / salt / session id from a **non-cryptographic RNG** (`rand::random`,
  a seedable `StdRng`) instead of an OS CSPRNG (`getrandom` / `OsRng`).
- An install/update path that doesn't verify signature *and* hash.
- Untrusted input used as a path segment without the allowlist + dot-segment check.
- A path opened/executed as root without ownership/symlink (no-follow) checks — winnable TOCTOU.
- A server/property-derived value (username, package name, path) interpolated into a root
  command or FS mutation instead of passed as a validated, separate argument.
- `format!` into SQL or into a filesystem path.
- `expose_secret()` flowing into a log/`Debug`/error string.
- Unbounded buffering (`read_to_end`) on attacker-controlled compressed/streamed input; archive
  extraction that writes files without a zip-slip guard.
- A new parser over untrusted bytes with no fuzz target and `unwrap()`/`panic!` on bad input.
- `unwrap`/`expect`/`panic!`/`unreachable!`, or a slice `[a..b]` / index `[i]`, on a value
  derived from untrusted or runtime input (panic = defect/crash — use `?` and `.get()`).
- Arithmetic that can overflow, or an `as` cast that truncates/sign-flips (`u64 as usize`,
  `usize as u32`), on a length/size/offset from untrusted input — use checked/saturating ops
  and `TryFrom`.
- A security-relevant `Result` dropped with `let _ = …` or an ignored `#[must_use]` (a verify,
  write, `flush`, or permission-set call whose failure is silently discarded).
- A Rust `panic!` allowed to unwind across an `extern "C"` FFI boundary (augeas/apt/WUA
  bindings) — that's UB; catch it (`catch_unwind`) or abort at the boundary.
- New `unsafe` without a `// SAFETY:` comment.
