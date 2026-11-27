# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 Normation SAS

# Before the include: cargo.mk derives PACKAGE_OPT from it and checks BIN when included
export PACKAGE := rudder-mcp
BIN := rudder-mcp

include ../cargo.mk

.DEFAULT_GOAL := archive

VERSION := $(shell sed -n 's/^version = "\(.*\)"/\1/p' Cargo.toml)
# "unknown" when not built from a git checkout
COMMIT := $(shell git rev-parse HEAD 2>/dev/null || echo unknown)
# The binary is built for the OS it is built on, e.g. sles16, or sles15sp6 with the service pack
# (VERSION_ID 15.6): it runs on later SPs, not on earlier ones
OS ?= $(shell . /etc/os-release && case "$${ID}$${VERSION_ID}" in \
         (sles15.*) echo "$${ID}15sp$${VERSION_ID\#*.}" ;; \
         (*) echo "$${ID}$${VERSION_ID%%.*}" ;; \
       esac)
ARCHIVE := rudder-mcp-$(VERSION)-$(OS)

build: cargo-release

# target/rudder-mcp-<version>-<os>.tar.xz, installed with its INSTALL.sh
archive: build
	rm -rf target/$(ARCHIVE)
	mkdir -p target/$(ARCHIVE)
	cp ../target/release/rudder-mcp config/main.conf config/logging.conf systemd/rudder-mcp.service \
	   apache/rudder-apache-mcp.conf README.md packaging/INSTALL.sh packaging/UNINSTALL.sh target/$(ARCHIVE)/
	printf 'version: %s\ncommit: %s\n' "$(VERSION)" "$(COMMIT)" > target/$(ARCHIVE)/VERSION
	tar cJf target/$(ARCHIVE).tar.xz -C target $(ARCHIVE)

check: lint
	$(MAKE) cargo-test

.PHONY: build archive check


