# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 Normation SAS

# Before the include: cargo.mk derives PACKAGE_OPT from it and checks BIN when included
export PACKAGE := rudder-mcp
BIN := rudder-mcp

include ../cargo.mk

.DEFAULT_GOAL := archive

VERSION := $(shell sed -n 's/^version = "\(.*\)"/\1/p' Cargo.toml)
COMMIT := $(shell git rev-parse HEAD)
# The binary is built for the OS it is built on, e.g. sles16
OS ?= $(shell . /etc/os-release && echo "$${ID}$${VERSION_ID%%.*}")
ARCHIVE := rudder-mcp-$(VERSION)-$(OS)

build: cargo-release

# target/rudder-mcp-<version>-<os>.tar.xz, installed with its INSTALL.sh
archive: build
	rm -rf target/$(ARCHIVE)
	mkdir -p target/$(ARCHIVE)
	cp ../target/release/rudder-mcp config/main.conf config/logging.conf systemd/rudder-mcp.service \
	   apache/rudder-apache-mcp.conf packaging/README.md packaging/INSTALL.sh target/$(ARCHIVE)/
	printf 'version: %s\ncommit: %s\n' "$(VERSION)" "$(COMMIT)" > target/$(ARCHIVE)/VERSION
	tar cJf target/$(ARCHIVE).tar.xz -C target $(ARCHIVE)

check: lint
	$(MAKE) cargo-test

.PHONY: build archive check
