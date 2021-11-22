# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2019-2020 Normation SAS

.DEFAULT_GOAL := build
SHELL := /bin/bash
PATH := $(PATH):$(HOME)/.cargo/bin:$(PATH)
DEBUGOPT:=$(shell make -v --debug=n >/dev/null 2>&1 && echo --debug=n)

DESTDIR	:= $(CURDIR)/make_target

version:
	cargo --version
	rustc --version
	@echo "RUSTC_WRAPPER=$${RUSTC_WRAPPER}"
	sccache --show-stats || true

build: version
	# strip release binaries, cf. https://github.com/rust-lang/cargo/issues/3483#issuecomment-431209957
	# should be configurable in Cargo.toml in the future https://github.com/rust-lang/rust/issues/72110
	RUSTFLAGS="--codegen link-arg=-Wl,--strip-all" cargo build --release

lint: version
	# to be sure clippy is actually run
	touch src/lib.rs
	mkdir -p target
	cargo clippy --message-format json --all-targets --examples --tests > target/cargo-clippy.json

check: lint
	cargo test

clean:
	cargo clean
	rm -rf target

veryclean: clean
	rustup self uninstall
	rm -rf ~/.rustup ~/.cargo

stats:
	@ echo -n "TODOS: " && grep -r TODO src | wc -l
	@ tokei
