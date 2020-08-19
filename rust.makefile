# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2019-2020 Normation SAS

.DEFAULT_GOAL := build
SHELL := /bin/bash
PATH := $(PATH):$(HOME)/.cargo/bin:$(PATH)
DEBUGOPT:=$(shell make -v --debug=n >/dev/null 2>&1 && echo --debug=n)

APT := apt update && apt install -y --no-install-recommends
YUM := yum install -y 

DESTDIR	:= $(CURDIR)/make_target
REDHATOS := $(wildcard /etc/redhat-release*)
DEBIANOS := $(wildcard /etc/debian_version*)

ifneq ($(DEBIANOS),)
PKG_INSTALLER := $(APT)
else ifneq ($(REDHATOS),)
PKG_INSTALLER := $(YUM)
endif

build-env:
	curl https://sh.rustup.rs -sSf | sh -s -- -y 
	rustup component add clippy
	cargo install cargo-update
	cargo install cargo-deny

build-env-update:
	rustup self update
	rustup update
	cargo install-update -a

version:
	cargo --version
	rustc --version

build: version
	# strip release binaries, cf. https://github.com/rust-lang/cargo/issues/3483#issuecomment-431209957
	# should be configurable in Cargo.toml in the future https://github.com/rust-lang/cargo/issues/3483#issuecomment-631584439
	RUSTFLAGS="--codegen link-arg=-Wl,--strip-all" cargo build --release

lint: version
	RUSTFLAGS="-D warnings" cargo check --all-targets --examples --tests
	cargo clippy --all-targets --examples --tests

check: lint
	cargo test
	cargo deny check

clean:
	cargo clean
	rm -rf target

veryclean: clean
	rustup self uninstall
	rm -rf ~/.rustup ~/.cargo

outdated:
	# only check on our dependencies
	cargo outdated --root-deps-only

deps-update: update outdated
	[ -d fuzz ] && cd fuzz && cargo update

dev-env: build-env
	rustup component add rustfmt
	cargo install cargo-outdated
	cargo install tokei

stats:
	@ echo -n "TODOS: " && grep -r TODO src | wc -l
	@ tokei

