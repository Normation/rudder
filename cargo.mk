# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2019-2025 Normation SAS

.DEFAULT_GOAL := build
PATH := $(PATH):$(HOME)/.cargo/bin:$(PATH)

# Shallow wrapper around cargo for CI/CD builds.

# Not intended to replace cargo in dev workflow.

# Special effort for reproducible & auditable builds and SBOM generation.

# make release BIN=rudderc FEATURES=embedded-lib

# https://matklad.github.io/2021/09/04/fast-rust-builds.html#ci-workflow
# https://doc.rust-lang.org/nightly/cargo/guide/build-performance.html

CARGO_AUDITABLE_VER := 0.7.2
CARGO_CYCLONEDX_VER := 0.5.7
CARGO_NEXTEST_VER   := 0.9.115

# Specific CI behavior
ifeq ($(CI),1)
export RUSTFLAGS=--deny warnings
# faster cold builds
export CARGO_INCREMENTAL=0
# retry on network failures
export CARGO_NET_RETRY=10
export RUSTUP_MAX_RETRIES=10
export NEXTEST_RETRIES=3
# single thread to avoid test interference for now
export NEXTEST_TEST_THREADS=1
# limit jobs on CI
JOBS := 2
else
JOBS := $(shell nproc)
endif

ifdef TARGET
TARGET_OPT=--target=$(TARGET)
else
TARGET_OPT=
endif

ifdef FEATURES
FEATURES_OPT=--features=$(FEATURES)
else
FEATURES_OPT=
endif

# Use sccache if available
ifneq (, $(shell which sccache))
  SCCACHE := sccache
else
  SCCACHE :=
endif
export RUSTC_WRAPPER=$(SCCACHE)

rust-version:
	@echo "jobs=$(JOBS)"
	@cargo --version
	@rustc --version
	@cc --version | head -n1
	@echo "RUSTC_WRAPPER=$${RUSTC_WRAPPER}"
	@if [ "$$RUSTC_WRAPPER" = "sccache" ]; then sccache --show-stats; fi

cargo-auditable:
	cargo install --locked cargo-auditable@$(CARGO_AUDITABLE_VER)

cargo-cyclonedx:
	cargo install --locked cargo-cyclonedx@$(CARGO_CYCLONEDX_VER)

cargo-nextest:
	cargo install --locked cargo-nextest@$(CARGO_NEXTEST_VER)

cargo-release: rust-version cargo-auditable
ifdef BIN
	cargo auditable build --bin $(BIN) $(FEATURES_OPT) $(TARGET_OPT) --release --locked --jobs $(JOBS)
else
	@echo "Please specify BIN=binary_name"
	@exit 1
endif

cargo-sbom: rust-version cargo-cyclonedx
	@# Build all SBOMs and pick the right one, then cleanup.
	@# Currently cargo-cyclonedx cannot target a single binary.
	cargo cyclonedx --quiet --spec-version 1.5 --format json --describe binaries --target all --features=$(FEATURES)
	@find . -path "./target" -prune -o -name "$(BIN)_bin.cdx.json" -exec mv {} target/release/$(BIN).cdx.json \;
	@find . -path "./target" -prune -o -name "*.cdx.json" -exec rm {} \;
	gzip -f target/release/$(BIN).cdx.json
	@echo ""
	@echo "> Built target/release/$(BIN) and target/release/$(BIN).cdx.json.gz"

cargo-install-release: cargo-release
	install -m 755 target/release/$(BIN) $(DESTDIR)/bin/$(BIN)

cargo-test: rust-version cargo-nextest
ifdef PACKAGE
	cargo nextest run --package $(PACKAGE) $(FEATURES_OPT) --locked --no-tests pass --jobs $(JOBS)
else
	@echo "Please specify PACKAGE=crate_name"
	@exit 1
endif

dev-doc:
	cargo doc --document-private-items --open

lint:
	cargo fmt --all -- --check
	cargo clippy --all-targets --examples --tests --locked -- --deny warnings

clean:
	find . -name "*.cdx.*" -exec rm {} \;
	cargo clean
	rm -rf target

veryclean: clean
	rustup self uninstall
	rm -rf ~/.rustup ~/.cargo

# rustup does not support parallel installs
.NOTPARALLEL: cargo-release cargo-test cargo-sbom