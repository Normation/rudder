---
name: rudder-rust
description: Rudder's Rust conventions, build/test/lint workflow, and project-specific idiosyncrasies. Use when writing, reviewing, refactoring, security-reviewing, or adding crates/dependencies to any Rust code under policies/ or relay/sources/ — the agent, agentd, rudderc, rudder-cli, module-types/* (augeas, commands, directory, inventory, system-updates, template), relayd, rudder-package, and shared libs (rudder_commons, rudder_module_type, lib). For security-sensitive work (TLS/cert pinning, signature/hash verification, untrusted-input parsers, path traversal, secrets, privileged execution, unsafe) also read the bundled security.md reference.
---

# Rudder Rust practices

## Repository layout and components

All Rust lives in one Cargo workspace (root `Cargo.toml`), split across two subsystems:
`policies/` (agent, `rudderc`, CLI, module types, shared libs) and `relay/sources/`
(`relayd`, `rudder-package`). Toolchain is pinned in `rust-toolchain.toml`;
`rustup` picks it up automatically. The workspace also targets `x86_64-pc-windows-gnu`,
so keep code cross-platform unless it's behind a clearly OS-gated path.

**Module types are cross-platform first-class citizens.** They ship on managed nodes across
**Linux and Windows** and on both **x86_64, aarch64 and armv7 (ARM)** — none of these is a
second-tier port. Write module-type code (and the shared crates they pull in) to build and
run on all of them: gate OS-specific behaviour explicitly with `#[cfg(...)]` rather than
assuming Unix, and don't bake in arch assumptions (pointer width, endianness, x86-only
intrinsics). If you can't test a target locally, at least `cargo check --target ...` it.

Concrete Windows pitfalls: build paths with `Path`/`PathBuf`, never hardcoded `/` separators
or `/`-rooted absolute paths; don't assume Unix permission/ownership bits, a `root` user,
`/tmp`, `/proc`, or `\n` line endings; shelling out differs (`cmd`/PowerShell vs `sh`) and env
vars are case-insensitive. Put anything genuinely OS-specific behind `#[cfg(unix)]` /
`#[cfg(windows)]` with a **real implementation on each side** — not a Unix path plus a
`todo!()` stub.

This file is the entry point: repository layout, the build/test/lint workflow, and
Cargo/workspace/dependency mechanics. Three companions are **required reading**:

- **[code.md](code.md)** — code-level conventions (file headers, error handling, logging,
  async, CLI, module-type framework, testing). Read before writing or changing Rust.
- **[principles.md](principles.md)** — the design mindset (data-first, parse-don't-validate,
  error classification, serialization contracts). Read before any non-trivial design; it's
  what "idiomatic in this codebase" means.
- **[security.md](security.md)** — read before any security-sensitive work or review: TLS,
  signature/hash verification, untrusted-input parsers, path handling, secrets,
  privileged/`root` execution, `unsafe`. Don't fall back on general instinct over the
  documented model.

## Build, test, lint (dev workflow)

Use `cargo` directly. The `Makefile`/`cargo.mk` are **CI wrappers** (auditable builds, SBOM,
forced single-threaded tests) — not for day-to-day work.

```bash
cargo build --bin rudderc                       # build one binary
cargo nextest run --package rudderc             # run a crate's tests (nextest is the runner)
cargo test --package relayd --doc               # doctests (nextest doesn't run those)
cargo fmt --all                                 # format (default rustfmt, no rustfmt.toml)
cargo clippy --all-targets --examples --tests   # lint
```

Before pushing, mirror what CI gates on (`make check` per subsystem ≈ `lint` then tests):

```bash
cargo fmt --all -- --check
cargo clippy --all-targets --examples --tests --locked -- --deny warnings
```

**CI builds with `RUSTFLAGS=--deny warnings`** — any warning is a hard failure. Keep the tree
warning-clean locally; don't leave `dead_code`/unused-import warnings for CI to catch.
Clippy runs at default lint level (no `pedantic`/`unwrap_used` enabled), so the bar is
"default clippy, zero warnings."

`Cargo.lock` is committed and must stay in sync; CI uses `--locked`. Some crates have
feature-gated test matrices (e.g. `lib` runs with `test-unix`/`test-windows`,
`rudder-module-system-updates` with `apt`/`apt-compat`) — check the crate's `Makefile`
before assuming `cargo nextest run -p <crate>` exercises everything.

## Workspace & Cargo.toml conventions

- **Inherit package metadata**, don't repeat it. New crates use:
  ```toml
  [package]
  name = "..."
  version = "0.0.0-dev"        # internal crates are unversioned
  description = "..."
  authors.workspace = true
  edition.workspace = true     # = 2024
  homepage.workspace = true
  repository.workspace = true
  license.workspace = true
  ```
- When adding a crate to the workspace, always add the tests to the relevant `Makefile`
  so that it is checked by the CI.
- **No `[workspace.dependencies]`.** Each crate declares and pins its own versions
  independently. Match the version string the neighboring crates already use rather than
  inventing a new one.
- **Crate naming is currently inconsistent**, so follow the local pattern: directories
  are hyphenated (`rudder-module-type/`), but package names mix styles —
  shared libs use underscores (`rudder_commons`, `rudder_cli`, `rudder_module_type`), while
  binaries/modules use hyphens (`rudder-relayd`, `rudder-module-augeas`, `rudder-package`).
  When in doubt, copy the closest sibling.
- **Trim default features and list what you need:** `default-features = false` with an
  explicit `features = [...]` is the house style (see `relayd`'s deps). TLS goes through
  OpenSSL/native-tls deliberately — don't swap in rustls without reason.
- Internal deps are path deps: `rudder_commons = { path = "../rudder-commons" }`.

## Dependencies & supply chain (cargo-deny)

`cargo deny check` runs in CI against `deny.toml`. Adding deps means respecting it:

- **New license** not in the `allow` list → the build fails until you add it (with
  justification). Don't add copyleft-incompatible licenses; the project is GPL-3.0-or-later.
- **Git dependency** → must be whitelisted in `deny.toml` `allow-git` (and ideally pinned by
  `rev`). Crates from the `Normation` GitHub org are pre-allowed.
- **Security advisories** are denied by default; the only exceptions are the explicitly
  listed `RUSTSEC-*` ignores, each with a comment saying why it's acceptable. Don't add an
  ignore without that rationale.
- Keep deps lean: unused dependencies are caught by `cargo-machete`. If a dep is needed but
  looks unused (e.g. pulled in for a macro), add it to
  `[package.metadata.cargo-machete] ignored = [...]` as `rudderc` does for `regex`.

## Security-sensitive code

Transport/TLS, signature/hash verification, untrusted-input parsers, path handling, secrets,
privileged execution, and `unsafe` have their own reference — **[security.md](security.md)**.
Read it before touching any of them; it also carries the reviewer red-flag checklist.
