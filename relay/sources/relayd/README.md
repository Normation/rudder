# relayd

## Install

### Dependencies

Runtime dependencies are:

* openssl
* libpq
* zlib
* lzma

To install build dependencies on Debian/Ubuntu:

```bash
sudo make apt-dependencies
```

To install build dependencies on RHEL/Fedora:

```bash
sudo make yum-dependencies
```

### Installation

To install:

```bash
make DESTDIR=/target/directory install
```

## Development environment

This project requires Rust 1.34 or later.
The best option to install the Rust compiler is to use
[rustup](https://www.rust-lang.org/tools/install).

Best IDEs for Rust development are *VSCode* or *IntelliJ IdeaC*
with their Rust plugin.

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

Schema for the database is in: `webapp/sources/rudder/rudder-core/src/main/resources/reportsSchema.sql`
