# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2020 Normation SAS

include ../../rust.makefile

DOC_EXAMPLES := $(wildcard rudderc/docs/examples/*/technique.yml)
DOC_EXAMPLES_CF := $(DOC_EXAMPLES:.yml=.cf)

install: build
	mkdir -p $(DESTDIR)/opt/rudder/bin
	# Install executable
	install -m 755 ../../target/release/rudderc $(DESTDIR)/opt/rudder/bin/rudderc

test-docs: $(DOC_EXAMPLES_CF)

test: libs
	cargo test --locked --package rudderc
	# Parse lib
	cargo run --quiet --bin rudderc -- --quiet lib -f json --stdout -l target/repos/ncf/tree/30_generic_methods/ -l target/repos/dsc/plugin/src/ncf/30_generic_methods/ >/dev/null

static: libs version
	cargo auditable build --release --locked --features embedded-lib

%.cf: %.yml libs
	cd $(shell dirname $@) && cargo run --quiet --bin rudderc -- --quiet build -l ../../../../target/repos/ncf/tree/30_generic_methods/

docs: libs
	cargo run --quiet --bin rudderc -- lib -l target/repos/ncf/tree/30_generic_methods/ -l target/repos/dsc/plugin/src/ncf/30_generic_methods/

libs:
	mkdir -p target/repos
	[ -d target/repos/ncf ] || git clone git@github.com:Normation/ncf.git target/repos/ncf
	cd target/repos/ncf && git checkout master && git pull origin master
	[ -d target/repos/dsc ] || git clone git@github.com:Normation/rudder-agent-windows.git target/repos/dsc || mkdir -p target/repos/dsc/plugin/ncf/30_generic_methods/
	cd target/repos/dsc && git checkout master && git pull origin master || true

agent-windows: libs
	curl -sS --output target/rudder-agent.msi https://publisher.normation.com/misc/windows/8.0.3/rudder-agent-8.0.3.2000000000.msi
	msiextract target/rudder-agent.msi -C target/agent-windows/
	# Sadly, we need to add .cf files to the agent to have everything in one place for now
	mkdir -p target/agent-windows/Rudder/share/initial-policy/ncf/cf/
	cp target/repos/ncf/tree/30_generic_methods/*.cf target/agent-windows/Rudder/share/initial-policy/ncf/cf/
	cp target/repos/dsc/plugin/src/ncf/30_generic_methods/*.cf target/agent-windows/Rudder/share/initial-policy/ncf/cf/
	# Simulate a real agent to get correct reporting
	echo "1d58a791-7278-4a2c-a10d-048c7b4be2a6" > target/agent-windows/Rudder/etc/uuid.hive

check: lint test test-docs

.PHONY: docs libs clean
