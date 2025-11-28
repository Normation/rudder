# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2019-2025 Normation SAS

.DEFAULT_GOAL := build
PATH := $(PATH):$(HOME)/.cargo/bin:$(PATH)

# Only requires rustup installed.
# Try to stay a thin layer, delegating to cargo as much as possible.

# Not designed for dev workflow, but for CI/CD and release builds.
# Special effort for auditable builds and SBOM generation.

# https://matklad.github.io/2021/09/04/fast-rust-builds.html#ci-workflow

CARGO_AUDITABLE_VER := 0.7.2
CARGO_CYCLONEDX_VER := 0.5.7

#export RUSTFLAGS="-D warnings"

ifeq ($(CI),1)
export CARGO_INCREMENTAL=0
export CARGO_NET_RETRY=10
export RUSTUP_MAX_RETRIES=10
endif

# Use sccache if available
ifneq (, $(shell which sccache))
  SCCACHE := sccache
else
  SCCACHE :=
endif
export RUSTC_WRAPPER=$(SCCACHE)

# set jobs to number of cores or 2 on jenkins
ifneq ($(JENKINS_HOME),)
  JOBS := 2
else
  JOBS := $(shell nproc)
endif

rust-version:
	@echo "jobs=$(JOBS)"
	@cargo --version
	@rustc --version
	@rustc --version
	@cc --version | head -n1
	@echo "RUSTC_WRAPPER=$${RUSTC_WRAPPER}"
	@if [ "$$RUSTC_WRAPPER" = "sccache" ]; then sccache --show-stats; fi

cargo-auditable:
	cargo install --locked cargo-auditable@$(CARGO_AUDITABLE_VER)

cargo-cyclonedx:
	cargo install --locked cargo-cyclonedx@$(CARGO_CYCLONEDX_VER)

rudder-relayd: target/release/rudder-relayd
rudder-package: target/release/rudder-package
rudderc: target/release/rudderc

target/release/%: rust-version cargo-auditable
	cargo auditable build --bin $* --features=${CARGO_FEATURES} --release --locked --jobs $(JOBS)

dev-doc:
	cargo doc --document-private-items --open

lint:
	cargo fmt --all -- --check
	cargo clippy --all-targets --examples --tests -- --deny warnings

# FIXME features??
sbom: rust-version cargo-cyclonedx
	cargo cyclonedx --quiet --format json --describe binaries --target all
	rm -rf target/sbom
	mkdir -p target/sbom
	find . -path "./target" -prune -o -name "*.cdx.json" -exec mv {} target/sbom/ \;

clean:
	find . -name "*.cdx.*" - -exec rm {} \;
	cargo clean
	rm -rf target

veryclean: clean
	rustup self uninstall
	rm -rf ~/.rustup ~/.cargo

# Let cargo handles its dependencies
.PHONY: target/release/%