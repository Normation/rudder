# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2020 Normation SAS

include ../rust.makefile

DOC_EXAMPLES := $(wildcard docs/examples/*.yml)
DOC_EXAMPLES_CF := $(DOC_EXAMPLES:.yml=.cf)

install: build
	mkdir -p $(DESTDIR)/opt/rudder/bin
	# Install executable
	install -m 755 target/release/rudderc $(DESTDIR)/opt/rudder/bin/rudderc

test-docs: $(DOC_EXAMPLES_CF)

test:
	cargo test --locked

%.cf: %.yml
	cargo run --quiet -- --quiet --output $@ --target cf $<

docs: test-docs
	mkdir -p target/docs
	cp -r docs/images target/docs/
	asciidoctor -a stylesheet=assets/css/rudder.css --doctype=book --destination-dir=target/docs docs/index.adoc

check: lint test test-docs

.PHONY: docs libs clean
