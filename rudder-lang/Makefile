.DEFAULT_GOAL := build
SHELL = /bin/bash

include ../rust.makefile

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

docs:
	mkdir -p target/docs
	asciidoctor --destination-dir=target/docs docs/index.adoc

.PHONY: docs