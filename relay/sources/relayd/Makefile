DESTDIR = $(CURDIR)/target
RUDDER_VERSION_TO_PACKAGE = "5.1.0-alpha1"

### Packaging

build-all: build-release man;

build-release:
	cargo build --release --bin rudder-relayd

man: target/man/rudder-relayd.1.gz;

target/man/%: man/%.adoc
	mkdir -p target/man
	a2x -D target/man --doctype manpage --format manpage $<

target/man/%.gz: target/man/%
	gzip -f $<

install: build-all
	# Directories
	mkdir -p $(DESTDIR)/opt/rudder/bin/
	mkdir -p $(DESTDIR)/opt/rudder/etc/
	mkdir -p $(DESTDIR)/opt/rudder/share/man/man1/
	mkdir -p $(DESTDIR)/usr/lib/systemd/system/
	# Install binary
	install -m 755 target/release/rudder-relayd $(DESTDIR)/opt/rudder/bin/rudder-relayd
	# Install service
	install -m 644 tools/rudder-relayd.service $(DESTDIR)/usr/lib/systemd/system/rudder-relayd.service
	# Install man pages
	install -m 644 target/man/rudder-relayd.1.gz $(DESTDIR)/opt/rudder/share/man/man1/
	# Install default configuration file
	install -m 644 tools/rudder-relayd.conf $(DESTDIR)/opt/rudder/etc/rudder-relayd.conf

### Build

# For CI check
check: lint test audit bench;

yum-dependencies:
	yum install pkgconfig postgresql-devel openssl-devel zlib-devel xz-devel asciidoc

apt-dependencies:
	apt install pkg-config libpq-dev libssl-dev zlib1g-dev lzma-dev asciidoc

build-env:
	curl https://sh.rustup.rs -sSf | sh
	rustup component add clippy
	cargo install cargo-update
	cargo install cargo-audit

build-env-update:
	rustup self update
	rustup update
	cargo install-update -a

lint:
	RUSTFLAGS="-D warnings" cargo clippy --all-targets --examples --tests -- -D clippy::all

clean:
	cargo clean
	rm -rf target

veryclean: clean
	rustup self uninstall
	rm -rf ~/.rustup ~/.cargo

### Dev

# fmt, build, test, audit, clean, bench, etc.
%:
	cargo $@

outdated:
	# only check on our dependencies
	cargo outdated --root-deps-only

deps-update: update outdated;

dev-env: build-env
	rustup component add rustfmt
	cargo install cargo-outdated
