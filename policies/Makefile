# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2020 Normation SAS

include ../rust.makefile

DOC_EXAMPLES := $(wildcard rudderc/docs/examples/*.yml)
DOC_EXAMPLES_CF := $(DOC_EXAMPLES:.yml=.cf)

install: build
	mkdir -p $(DESTDIR)/opt/rudder/bin
	# Install executable
	install -m 755 target/release/rudderc $(DESTDIR)/opt/rudder/bin/rudderc

test-docs: $(DOC_EXAMPLES_CF)

test: libs
	cargo test --locked
	# Parse lib
	cargo run --bin rudderc --quiet -- -o /tmp/test.cf rudderc/tests/cases/general/ntp/technique.yml -l target/repos/ncf/tree/30_generic_methods/ -l target/repos/dsc/plugin/src/ncf/30_generic_methods/

%.cf: %.yml libs
	cargo run --bin rudderc --quiet -- --quiet -l target/repos/ncf/tree/30_generic_methods/ --output $@ --target cf $<

docs: test-docs
	mkdir -p target/docs
	cp -r rudderc/docs/images target/docs/
	asciidoctor -a stylesheet=assets/css/rudder.css --doctype=book --destination-dir=target/docs rudderc/docs/index.adoc

libs-docs: libs
	mkdir -p target/docs
	cargo run --bin rudderc -- --library target/repos/ncf/tree/30_generic_methods/ \
           --library target/repos/dsc/plugin/src/ncf/30_generic_methods/ --modules --output target/methods.md
	pandoc --standalone --metadata=mainfont:sans --metadata=title:"Rudder methods docs" \
	       --from commonmark_x --to html5 --toc --toc-depth=2 --output target/docs/methods.html target/methods.md

libs:
	mkdir -p target/repos
	[ -d target/repos/ncf ] || git clone git@github.com:Normation/ncf.git target/repos/ncf
	cd target/repos/ncf && git checkout master && git pull origin master
	[ -d target/repos/dsc ] || git clone git@github.com:Normation/rudder-agent-windows.git target/repos/dsc || mkdir -p target/repos/dsc/plugin/ncf/30_generic_methods/
	cd target/repos/dsc && git checkout master && git pull origin master || true

check: lint test test-docs

.PHONY: docs libs clean
