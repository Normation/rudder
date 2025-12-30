#!/bin/sh

# used to setup Rust build containers

rustup component add clippy
rustup component add rustfmt

# Install pre-compiled sccache
SCCACHE_VER=0.10.0
wget --quiet https://github.com/mozilla/sccache/releases/download/v$SCCACHE_VER/sccache-v$SCCACHE_VER-x86_64-unknown-linux-musl.tar.gz
tar -xf sccache-v$SCCACHE_VER-x86_64-unknown-linux-musl.tar.gz
chmod +x sccache-v$SCCACHE_VER-x86_64-unknown-linux-musl/sccache
mv sccache-v$SCCACHE_VER-x86_64-unknown-linux-musl/sccache /usr/local/bin/

# Install pre-compiled cargo-deny
DENY_VER=0.18.9
wget --quiet https://github.com/EmbarkStudios/cargo-deny/releases/download/$DENY_VER/cargo-deny-$DENY_VER-x86_64-unknown-linux-musl.tar.gz
tar -xf cargo-deny-$DENY_VER-x86_64-unknown-linux-musl.tar.gz
mv cargo-deny-$DENY_VER-x86_64-unknown-linux-musl/cargo-deny /usr/local/bin/

# Build & check tools
cargo install --locked cargo-auditable@0.6.6
cargo install --locked cargo-cyclonedx@0.5.7
