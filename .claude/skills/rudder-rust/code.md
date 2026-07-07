# Rudder Rust — code conventions

The concrete, code-level conventions for Rust in this repo: how the code is written.
Project layout, the build/test/lint workflow, and Cargo/dependency mechanics are in
**[SKILL.md](SKILL.md)**; the *why* behind the style is in **[principles.md](principles.md)**;
security-sensitive surfaces are in **[security.md](security.md)**.

In general, write idiomatic code that follows all the well known best-practices.

## First thing on any new `.rs` file

Every source file starts with the SPDX header (this is enforced by convention across the
tree, ~all files have it):

```rust
// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS
```

Use the current year for new files; keep an existing year range when editing. Scripts,
`Makefile`s and TOML use the `#` comment form of the same two lines.

## Error handling

- **`anyhow` is the default** for binaries and most library code. Return
  `anyhow::Result<T>` / `Result<(), anyhow::Error>`.
- Add context at boundaries with `.context(...)` / `.with_context(|| ...)`; early-exit with
  `bail!(...)`. The codebase uses `bail!` + an explicit `if` rather than `ensure!` — keep
  that style for consistency.
- **`thiserror` only when callers need to match on variants.** The model is `relayd`'s
  `RudderError` enum (`relay/sources/relayd/src/error.rs`). Message style:
  `#[error("invalid run log: {0}")]` — lowercase, no trailing period, and **include the
  offending value**.
- **`unwrap`/`expect`:** free to use in tests (`clippy.toml` sets
  `allow-unwrap-in-tests = true`). In runtime paths prefer `?` + context; reserve `expect`
  for genuine invariants and give it a message that explains *why* it can't fail. `panic!`
  is rare and should stay that way.
- **Classify failures deliberately** — nominal outcome (encode in the type) vs error
  (`Result`) vs defect (panic/crash cleanly). See [principles.md](principles.md).

## Logging

- **`tracing`, never `log`.** Macros: `error!`/`warn!`/`info!`/`debug!`/`trace!`.
- Annotate notable async/entry functions with `#[instrument(...)]`; use `skip(...)` for
  large or non-`Debug` args and set an explicit `name=`/`level=`.
- `relayd` and `rudderc` compile with `max_level_trace`/`release_max_level_trace` so trace
  logs survive in release builds. Subscriber is `tracing-subscriber` with `env-filter`.
- **Never log secrets** (`secrecy::SecretString`) — see [security.md](security.md).

## Async (tokio)

`relayd` is a Tokio service; async tasks share one runtime, so never block it.

- **No blocking calls inside `async fn`.** `tokio::time::sleep(...).await`, not
  `std::thread::sleep`; async I/O, not the `std` blocking equivalents.
- **CPU-bound or unavoidably-blocking work → `tokio::task::spawn_blocking`** so the reactor
  keeps turning; don't `.await` a long synchronous computation inline.

## CLI parsing & output

- Binaries use **`clap` derive** (`#[derive(Parser)]`).
- **Module types use `gumdrop`** (`#[derive(Options)]`) via the framework, not clap — it's
  lighter. Don't pull clap into a module type.
- **CLI look-and-feel lives in `rudder_cli`** (`policies/rudder-cli`) and is **shared** so every
  Rudder CLI (`rudderc`, `rudder-package`, …) presents output the same way. Use it — its
  cargo/rustc-style helpers (`logs::ok_output(step, message)`, the colored log `init`, the
  `FileError` diagnostic renderer, the SIGPIPE-safe panic hook) — rather than hand-rolling
  `println!` + ad-hoc coloring. It already handles tty detection and `NO_COLOR`; new output
  belongs there, so the styling stays consistent across binaries.

## Module-type framework

Module types (`policies/module-types/*`) are standalone binaries the agent loads. They build
on the shared `rudder_module_type` crate (the `ModuleType0` trait + agent/CFEngine protocol
glue). The `main.rs` is a one-liner:

```rust
fn main() -> Result<(), anyhow::Error> {
    rudder_module_commands::entry()
}
```

Each ships a YAML metadata file included at compile time. Optional helpers are feature-gated
(`backup`, `diff`, `splay`). When adding one, mirror an existing module (`commands` is the
simplest) rather than wiring the protocol by hand.

Implement the `ModuleType0` trait; the method that matters is
`check_apply(&mut self, mode: PolicyMode, params) -> CheckApplyResult`:

- `validate(&params)` runs first for advanced parameter checks (types are already validated);
  `check_apply` assumes it passed. `init`/`terminate` are for set-up/clean-up (connect, spawn).
- **One `check_apply` does both check and apply** (by design — the two are usually nearly
  identical), so it must be **idempotent and convergent**: safe to re-run, a no-op once the
  system already matches.
- Return `Outcome::Success` when already compliant (**kept**, no change) or
  `Outcome::Repaired(msg)` when you actually changed the system; `Err(..)` is a real failure.
  Don't report `Repaired` when nothing changed — that distinction drives compliance reporting.

**`PolicyMode::Audit` vs `Enforce` is a hard invariant:**

- **`Enforce`** (default): check, and repair drift → `Repaired`.
- **`Audit`: never modify the system** — check and report only. Already compliant → `Success`;
  a change *would* be needed → return an **error** (drift is non-compliant), don't apply it.
  (E.g. `augeas` sets `SaveMode::Noop` in audit and `bail!`s when a write would be required.)
- Writing in audit mode is both a correctness **and a security** bug — audit must be
  side-effect-free. Thread `mode` through every path that could mutate state.

## Testing

- **Unit tests inline** in `#[cfg(test)] mod tests`; **integration tests** in the crate's
  `tests/` dir. Data-driven cases (e.g. `rudderc/tests/cases/`) are generated with
  `test-generator`.
- Runner is **`cargo nextest`**. CI pins `NEXTEST_TEST_THREADS=1`, so tests aren't relied on
  to be parallel-safe — but still write them independent (own `tempfile` dirs, no shared
  global state) so they pass locally in parallel too.
- Use **`pretty_assertions`** (dev-dep in most crates) for readable diffs; `proptest` for
  property tests, `rstest` for parameterized cases, `tempfile` for filesystem work.
- **Fuzzing** (`cargo-fuzz`) targets live under `<crate>/fuzz/` (`relayd` runinfo, template
  render). Add fuzz coverage when parsing untrusted input.
- `criterion` benches (`harness = false`) where perf matters (`relayd`).
- **Never silently ignore a failing test.** A red test means either the code is broken or the
  test is — fix one of them. Don't `#[ignore]`, comment out, or `--skip` a failure to get a
  green run, even when it looks environmental (flaky, timing, "works on my machine", missing
  tool). Investigate the real cause; if a test genuinely can't run in some environment, gate
  it explicitly (`#[cfg(...)]` / a documented feature) with a comment saying why — never leave
  a failure quietly swept under the rug.
