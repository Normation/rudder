# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2020 Normation SAS

include ../rust.makefile

build:
	cd rudderc && make build

# The crates we build for Windows. The others depend on Unix-only libraries
# (openssl, sqlite, augeas, etc.) or APIs.
WINDOWS_PACKAGES := -p rudderc \
                    -p rudder_cli \
                    -p rudder_commons \
                    -p rudder_module_type \
                    -p rudder-module-inventory \
                    -p rudder-module-template

lint-windows:
	$(MAKE) lint-cross CROSS_TARGET=x86_64-pc-windows-gnu CROSS_PACKAGES="$(WINDOWS_PACKAGES)"

check:
	cd rudderc && make check
	cd module-types/system-updates && make check
	cd module-types/augeas && make check
	cd module-types/template && make check
	cd module-types/commands && make check
	cd module-types/inventory && make check
	make lint-windows

install:
	cd rudderc && make install
