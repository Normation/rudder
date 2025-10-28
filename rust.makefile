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
	# sccache is used if present
	sccache --show-stats || true

# https://matklad.github.io/2021/09/04/fast-rust-builds.html#ci-workflow
build: CARGO_INCREMENTAL=0
build: version
	cargo install --locked cargo-auditable@0.7.1
	cargo auditable build --features=${CARGO_FEATURES} --release --locked --jobs 2

dev-doc:
	cargo doc --document-private-items --open

lint: version
	mkdir -p target
	cargo fmt --all -- --check
	cargo clippy --message-format json --all-targets --examples --tests -- --deny warnings > target/cargo-clippy.json

clean:
	cargo clean
	rm -rf target

veryclean: clean
	rustup self uninstall
	rm -rf ~/.rustup ~/.cargo

stats:
	@ echo -n "TODOS: " && grep -r TODO src | wc -l
	@ tokei -s lines
