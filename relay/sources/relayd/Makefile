DESTDIR = $(CURDIR)/target

build:
	cargo build

test:
	cargo test

audit:
	cargo audit

format:
	cargo fmt

lint:
	RUSTFLAGS="-D warnings" cargo clippy --all-targets --all-features --examples --tests -- -D clippy::all

install: clean lint audit
	cargo install --path=. --root=$(DESTDIR)

build-env:
	curl https://sh.rustup.rs -sSf | sh
	cargo install cargo-update
	cargo install cargo-audit

build-env-update:
	rustup self update
	rustup update
	cargo install-update -a

deps-update:
	cargo update
	cargo outdated -d 1

dev-env: build-env
	rustup component add rustfmt
	rustup component add clippy
	cargo install cargo-fix
	cargo install cargo-tree
	cargo install cargo-outdated

clean:
	cargo clean

veryclean: clean
	rm -rf ~/.rustup
	rm -rf ~/.cargo
