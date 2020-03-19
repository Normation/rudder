.DEFAULT_GOAL := build
SHELL = /bin/bash

include ../rust.makefile

DOC_EXAMPLES := $(wildcard docs/examples/*.rl)

apt-dependencies:
	apt update && apt install -y --no-install-recommends perl
	echo | cpan
	perl -MCPAN -e 'install Config::IniFiles'

yum-dependencies:
	yum install perl
	echo | cpan
	perl -MCPAN -e 'install Config::IniFiles'

build:
	perl tools/generate-lib
	cargo build --release

test-docs: $(DOC_EXAMPLES)

$(DOC_EXAMPLES):
	cargo run -- --config-file tools/rudderc-dev.conf --input $@ --output $@.cf
	rm $@.cf

docs:
	mkdir -p target/docs
	asciidoctor --destination-dir=target/docs docs/index.adoc

.PHONY: docs $(DOC_EXAMPLES)