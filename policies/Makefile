# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2020 Normation SAS

include ../cargo.mk

check: lint
	cd rudderc && make check
	cd module-types/system-updates && cargo test --locked
	cd module-types/system-updates && cargo test --locked --features apt
	cd module-types/system-updates && cargo test --locked --features apt-compat
	cd module-types/augeas && cargo test --locked
	cd module-types/template && cargo test --locked
	cd module-types/commands && cargo test --locked
	cd module-types/inventory && cargo test --locked
