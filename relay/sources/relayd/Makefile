#####################################################################################
# Copyright 2019 Normation SAS
#####################################################################################
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, Version 3.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.
#
#####################################################################################

# Dev tools, for build see ../Makefile

yum-dependencies:
	yum install pkgconfig postgresql-devel openssl-devel zlib-devel asciidoc

apt-dependencies:
	apt install --no-install-recommends pkg-config libpq-dev libssl-dev zlib1g-dev asciidoc

build-env:
	curl https://sh.rustup.rs -sSf | sh
	rustup component add clippy
	cargo install cargo-update
	cargo install cargo-deny

build-env-update:
	rustup self update
	rustup update
	cargo install-update -a

clean:
	cargo clean
	rm -rf target

veryclean: clean
	rustup self uninstall
	rm -rf ~/.rustup ~/.cargo

# fmt, build, test, audit, clean, bench, etc.
%:
	cargo $@

outdated:
	# only check on our dependencies
	cargo outdated --root-deps-only

deps-update: update outdated
	cd fuzz && cargo update

dev-env: build-env
	rustup component add rustfmt
	cargo install cargo-outdated
