# protocol-v2-poc

## Development environment

This project requires Rust 1.32 or later (for Rust 2018 support
and system allocator).
The best option to install the Rust compiler is to use
[rustup](https://www.rust-lang.org/tools/install).

Best IDEs for Rust development are *VSCode* or *IntelliJ IdeaC*
with their Rust plugin.

You may want to install `rls` for *VSCode* integration 
(IdeaC has its own embedded tools):

```bash
rustup component add rls rust-analysis rust-src
cargo install racer
```

Useful tools include:

```bash
rustup component add rustfmt # to format code, use with "cargo fmt"
rustup component add clippy  # higher-level linters, use with "cargo clippy"
cargo install cargo-audit    # to list known vulnerabilities in dependencies
cargo install cargo-fix      # to automatically fix code
cargo install cargo-outdated # to list outdated dependencies
cargo install cargo-update   # to update cargo-installed tools
cargo install cargo-tree     # to see the tree of dependencies
cargo install cargo-benchcmp # to compare benchmarks results
```

## Development database

Schema for the database is: `webapp/sources/rudder/rudder-core/src/main/resources/reportsSchema.sql`
