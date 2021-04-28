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

RUSTUP_VER := "1.23.1"

ifneq ($(DEBIANOS),)
PKG_INSTALLER := $(APT)
else ifneq ($(REDHATOS),)
PKG_INSTALLER := $(YUM)
endif

rustup:
	curl -o rustup-init "https://repository.rudder.io/build-dependencies/rustup/${RUSTUP_VER}/rustup-init-x86_64"
	chmod +x rustup-init
	./rustup-init -y
	rm -f rustup-init

# Setup build tools and update them
# This target must stay idempotent and fast
setup:
	rustup --version || make -f rust.makefile rustup
	# In case we just installed it
	. "$(HOME)/.cargo/env"
	# Our global set of versions, each project has a rust-toolchain file
	cargo +1.37.0 --version || rustup install "1.37.0"
	cargo +1.42.0 --version || rustup install "1.42.0"
	cargo +1.47.0 --version || rustup install "1.47.0"
	cargo +1.51.0 --version || rustup install "1.51.0"
	# cargo tools
	cargo install-update --version || cargo install cargo-update
	cargo deny --version || cargo install cargo-deny
	sccache --version || cargo install sccache

update: setup
	rustup update
	cargo install-update --all

version:
	cargo --version
	rustc --version
	@echo "RUSTC_WRAPPER=$${RUSTC_WRAPPER}"
	sccache --show-stats

build: version
	# strip release binaries, cf. https://github.com/rust-lang/cargo/issues/3483#issuecomment-431209957
	# should be configurable in Cargo.toml in the future https://github.com/rust-lang/rust/issues/72110
	RUSTFLAGS="--codegen link-arg=-Wl,--strip-all" cargo build --release

lint: version
	# to be sure clippy is actually run
	touch src/lib.rs
	mkdir -p target
	cargo clippy --message-format json --all-targets --examples --tests -- --deny warnings > target/cargo-clippy.json

check: lint
	cargo test

check-vulns:
	cargo deny check

veryclean: clean
	rustup self uninstall
	rm -rf ~/.rustup ~/.cargo

outdated:
	# only check on our dependencies
	cargo outdated --root-deps-only

dev-env: build-env
	rustup component add rustfmt
	cargo install cargo-outdated
	cargo install tokei

stats:
	@ echo -n "TODOS: " && grep -r TODO src | wc -l
	@ tokei

.PHONY: rustup setup
