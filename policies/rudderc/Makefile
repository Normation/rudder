# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2020 Normation SAS

include ../../cargo.mk

DOC_EXAMPLES := $(wildcard rudderc/docs/examples/*/technique.yml)
DOC_EXAMPLES_CF := $(DOC_EXAMPLES:.yml=.cf)
export PACKAGE := rudderc

build:
	$(MAKE) cargo-release BIN=rudderc

install: build
	mkdir -p $(DESTDIR)/opt/rudder/bin
	# Install executable
	install -m 755 ../../target/release/rudderc $(DESTDIR)/opt/rudder/bin/rudderc

test-docs: $(DOC_EXAMPLES_CF)

test: libs
	$(MAKE) cargo-test PACKAGE=rudderc
	# Parse lib
	cargo run --quiet --bin rudderc -- --quiet lib -f json --stdout -l target/repos/rudder/policies/lib/tree/30_generic_methods -l target/repos/dsc/plugin/src/ncf/30_generic_methods/ >/dev/null

test-powershell:
	pwsh -command 'Get-ChildItem -Recurse -Path tests/cases/general -Include "*.ps1" | ? { $$_.FullName -inotmatch "target" } | %{ echo $$_.Directory.FullName; Invoke-ScriptAnalyzer -Settings ./PSScriptAnalyzerSettings.psd1 -Path $$_.Directory.FullName -EnableExit -ReportSummary }'

static: libs
	$(MAKE) cargo-release BIN=rudderc FEATURES="embedded-lib"

%.cf: %.yml libs
	cd $(shell dirname $@) && cargo run --quiet --bin rudderc -- --quiet build -l ../../../../target/repos/rudder/policies/lib/tree/30_generic_methods/

docs: libs
	cargo run --quiet --bin rudderc -- lib -l target/repos/rudder/policies/lib/tree/30_generic_methods/ -l target/repos/dsc/plugin/src/ncf/30_generic_methods/

libs:
	mkdir -p target/repos
	[ -d target/repos/rudder ] || git clone --branch master --single-branch --depth 1 git@github.com:Normation/rudder.git target/repos/rudder
	[ -d target/repos/dsc ] || git clone --branch master --single-branch --depth 1 git@github.com:Normation/rudder-agent-windows.git target/repos/dsc || mkdir -p target/repos/dsc/plugin/ncf/30_generic_methods/

agent-windows: libs
	curl -sS --output target/rudder-agent.msi https://publisher.normation.com/misc/windows/8.1/latest
	msiextract target/rudder-agent.msi -C target/agent-windows/
	# Sadly, we need to add .cf files to the agent to have everything in one place for now
	mkdir -p target/agent-windows/Rudder/share/initial-policy/ncf/cf/
	cp target/repos/rudder/policies/lib/tree/30_generic_methods/*.cf target/agent-windows/Rudder/share/initial-policy/ncf/cf/
	cp target/repos/dsc/plugin/src/ncf/30_generic_methods/*.cf target/agent-windows/Rudder/share/initial-policy/ncf/cf/
	# Simulate a real agent to get correct reporting
	echo "1d58a791-7278-4a2c-a10d-048c7b4be2a6" > target/agent-windows/Rudder/etc/uuid.hive

check: lint test test-docs test-powershell

.PHONY: docs libs clean
