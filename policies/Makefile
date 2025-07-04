# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2020 Normation SAS

include ../rust.makefile

build:
	cd rudderc && make build

check:
	cd rudderc && make check
	cd module-types/system-updates && make check
	cd module-types/augeas && make check
	cd module-types/template && make check
	cd module-types/inventory && make check

install:
	cd rudderc && make install
