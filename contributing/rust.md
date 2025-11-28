# Rust dev environment

## General information

The compiler version used to build the projects are
set in a `rust-toolchain.toml` file in each project root, but
we strive to keep compiler versions in sync between projects.
We can rely on recent Rust compilers as we control the version
installed on our package builders.

We use dependencies from `crates.io` directly. Be careful to include `Cargo.lock` in repositories,
and to use `--locked` for build and test commands.

The Rust projects share a common base Makefile (`rust.makefile` at the repository root).

## Development environment

### Rust toolchain

The easiest way to install and manage
Rust toolchains locally is to use [rustup](https://rustup.rs).
It will install the latest stable toolchain by default, and download
additional toolchains when needed (based on the `rust-toolchain.toml` file).

### IDE/Editor

[Good IDEs for Rust development](https://areweideyet.com/) include JetBrains RustRover/CLion, Visual Studio Code or
any editor supporting LSP.

When using VS Code or another LSP-based editor integration, [rust-analyzer](https://github.com/rust-analyzer/rust-analyzer#language-server-quick-start)
should be used.

Our CI checks source formatting using the default `rustfmt` configuration, so the easiest way to apply it
is to configure your editor to format the files on save.

We also prevent `clippy` warning, so it can be useful to configure your editor to use it for linting
instead of `cargo check`.

=== Tools

Other useful tools:

```bash
rustup component add rustfmt # to format code, use with "cargo fmt"
rustup component add clippy  # various linters, use with "cargo clippy"
cargo install cargo-deny     # to list known vulnerabilities and check licenses in dependencies
cargo install cargo-outdated # to list outdated dependencies
cargo install cargo-update   # to update cargo-installed tools
cargo install cargo-tree     # to see the tree of dependencies
cargo install cargo-benchcmp # to compare benchmarks results
cargo install sccache        # to share compilation cache locally between projects
```
